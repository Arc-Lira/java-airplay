package wtf.nanoka.airplay.app;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Lets a second launch surface the receiver that is already running instead of
 * starting a competing mDNS advertisement. A file is used because it needs no
 * extra port and survives antivirus or firewall rules that block loopback IPC.
 */
@Slf4j
public final class InstanceActivation {

    static final String REQUEST_FILE_NAME = "activate.request";

    private InstanceActivation() {
    }

    /** Asks the running receiver to show its window. Returns false if it cannot be reached. */
    public static boolean request(Path directory) {
        try {
            Files.createDirectories(directory);
            Path request = directory.resolve(REQUEST_FILE_NAME);
            Files.writeString(request, Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
        } catch (IOException e) {
            log.warn("Unable to ask the running receiver to show its window: {}", e.getMessage());
            return false;
        }
    }

    /** Watches for {@link #request(Path)} and runs the callback on the polling thread. */
    public static Thread startWatcher(ReceiverInstanceLock lock, Runnable onActivate) {
        return Thread.ofPlatform()
                .name("java-airplay-instance-activation")
                .daemon(true)
                .start(() -> {
                    Path request = lock.directory().resolve(REQUEST_FILE_NAME);
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(500);
                            if (consumeRequest(lock, request)) {
                                onActivate.run();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (IOException e) {
                            log.debug("Unable to consume an activation request: {}", e.getMessage());
                        } catch (RuntimeException e) {
                            log.warn("Unable to activate the receiver window: {}", e.getMessage());
                        }
                    }
                });
    }

    private static boolean consumeRequest(ReceiverInstanceLock lock, Path request) throws IOException {
        // Coordinate with release() so a restart's old watcher cannot consume
        // requests after the replacement takes over.
        synchronized (lock) {
            var startedAt = lock.acquisitionStartedAt();
            if (startedAt == null) {
                return false;
            }
            try {
                // Leave stale markers alone. Deleting one could remove a fresh
                // request written between checking its time and deleting it.
                if (Files.getLastModifiedTime(request).compareTo(startedAt) < 0) {
                    return false;
                }
                return Files.deleteIfExists(request);
            } catch (NoSuchFileException ignored) {
                return false;
            }
        }
    }
}

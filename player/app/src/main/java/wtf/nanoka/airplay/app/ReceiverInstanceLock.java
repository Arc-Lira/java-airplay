package wtf.nanoka.airplay.app;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.OptionalLong;

/**
 * Prevents two receiver processes from advertising the same AirPlay service.
 * mDNS cannot merge duplicate instance names: every extra process renames its
 * service to "(2)", "(3)", and leaves SRV records that point at dead ports.
 */
@Slf4j
public final class ReceiverInstanceLock implements AutoCloseable {

    static final String LOCK_FILE_NAME = "instance.lock";
    static final String OWNER_FILE_NAME = "instance.pid";

    private final Path directory;
    private FileChannel channel;
    private FileLock lock;
    private FileTime acquisitionStartedAt;

    public ReceiverInstanceLock(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public ReceiverInstanceLock() {
        this(defaultDirectory());
    }

    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".java-airplay");
    }

    Path directory() {
        return directory;
    }

    synchronized FileTime acquisitionStartedAt() {
        return isHeld() ? acquisitionStartedAt : null;
    }

    /** Returns false when another receiver instance already holds the lock. */
    public synchronized boolean acquire() throws IOException {
        if (isHeld()) {
            return true;
        }
        Files.createDirectories(directory);
        FileChannel opened = FileChannel.open(directory.resolve(LOCK_FILE_NAME),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        // A competing process can write a request as soon as tryLock succeeds.
        FileTime startedAt = FileTime.fromMillis(System.currentTimeMillis());
        FileLock acquired;
        try {
            acquired = opened.tryLock();
        } catch (OverlappingFileLockException e) {
            acquired = null;
        } catch (IOException | RuntimeException e) {
            try {
                opened.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        if (acquired == null) {
            opened.close();
            return false;
        }
        channel = opened;
        lock = acquired;
        acquisitionStartedAt = startedAt;
        writeOwnerPid(ProcessHandle.current().pid());
        return true;
    }

    public synchronized boolean isHeld() {
        return lock != null && lock.isValid();
    }

    public synchronized void release() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException e) {
                log.debug("Unable to release the receiver instance lock: {}", e.getMessage());
            }
            lock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                log.debug("Unable to close the receiver lock file: {}", e.getMessage());
            }
            channel = null;
        }
    }

    /** PID written by the process that currently holds the lock, when available. */
    public OptionalLong ownerPid() {
        Path ownerFile = directory.resolve(OWNER_FILE_NAME);
        try {
            String value = Files.readString(ownerFile, StandardCharsets.UTF_8).trim();
            long pid = Long.parseLong(value);
            return pid > 0 ? OptionalLong.of(pid) : OptionalLong.empty();
        } catch (IOException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private void writeOwnerPid(long pid) {
        try {
            Files.writeString(directory.resolve(OWNER_FILE_NAME), Long.toString(pid),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.debug("Unable to record the receiver instance PID: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        release();
    }
}

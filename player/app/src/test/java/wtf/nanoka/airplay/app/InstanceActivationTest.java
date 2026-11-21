package wtf.nanoka.airplay.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceActivationTest {

    @TempDir
    Path directory;

    @Test
    void watcherSurfacesTheRunningReceiverWhenALaunchIsRequested() throws Exception {
        CountDownLatch activated = new CountDownLatch(1);
        try (var lock = new ReceiverInstanceLock(directory)) {
            assertTrue(lock.acquire());
            // The second launch can write its request before the service is ready.
            assertTrue(InstanceActivation.request(directory));
            Thread watcher = InstanceActivation.startWatcher(lock, activated::countDown);
            try {
                assertTrue(activated.await(5, TimeUnit.SECONDS), "the startup request must be delivered");
                assertFalse(Files.exists(directory.resolve(InstanceActivation.REQUEST_FILE_NAME)),
                        "the request is consumed once");
            } finally {
                watcher.interrupt();
            }
        }
    }

    @Test
    void ignoresStaleRequestsThatPredateTheRunningInstance() throws Exception {
        Path request = Files.writeString(directory.resolve(InstanceActivation.REQUEST_FILE_NAME), "999");
        Files.setLastModifiedTime(request, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        CountDownLatch activated = new CountDownLatch(1);
        try (var lock = new ReceiverInstanceLock(directory)) {
            assertTrue(lock.acquire());
            Thread watcher = InstanceActivation.startWatcher(lock, activated::countDown);
            try {
                assertFalse(activated.await(1200, TimeUnit.MILLISECONDS),
                        "a request left over from a crashed launch must not steal focus");
            } finally {
                watcher.interrupt();
            }
        }
    }
}

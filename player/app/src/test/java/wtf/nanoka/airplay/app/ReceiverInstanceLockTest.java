package wtf.nanoka.airplay.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverInstanceLockTest {

    @TempDir
    Path directory;

    @Test
    void rejectsASecondReceiverUsingTheSameProfileDirectory() throws Exception {
        try (var first = new ReceiverInstanceLock(directory);
             var second = new ReceiverInstanceLock(directory)) {
            assertTrue(first.acquire());
            assertTrue(first.isHeld());
            assertFalse(second.acquire(), "a second receiver must not advertise the same AirPlay service");
            assertFalse(second.isHeld());
            assertEquals(ProcessHandle.current().pid(), first.ownerPid().orElseThrow());
        }
    }

    @Test
    void releasesTheLockSoARestartReplacementCanTakeOver() throws Exception {
        var original = new ReceiverInstanceLock(directory);
        assertTrue(original.acquire());
        original.release();

        try (var replacement = new ReceiverInstanceLock(directory)) {
            assertTrue(replacement.acquire(), "the replacement process must acquire the released lock");
        }
    }

    @Test
    void reacquiresTheLockWhenARestartIsRolledBack() throws Exception {
        var lock = new ReceiverInstanceLock(directory);
        assertTrue(lock.acquire());
        lock.release();
        assertTrue(lock.acquire());
        assertTrue(lock.isHeld());
    }

    @Test
    void createsTheProfileDirectoryOnFirstUse() throws Exception {
        Path nested = directory.resolve("profile").resolve(".java-airplay");
        try (var lock = new ReceiverInstanceLock(nested)) {
            assertTrue(lock.acquire());
            assertTrue(Files.isRegularFile(nested.resolve(ReceiverInstanceLock.LOCK_FILE_NAME)));
        }
    }

    @Test
    void reportsNoOwnerWhenThePidFileIsMissingOrInvalid() throws Exception {
        try (var lock = new ReceiverInstanceLock(directory)) {
            assertTrue(lock.ownerPid().isEmpty());
            Files.writeString(directory.resolve(ReceiverInstanceLock.OWNER_FILE_NAME), "not-a-pid");
            assertTrue(lock.ownerPid().isEmpty());
        }
    }
}

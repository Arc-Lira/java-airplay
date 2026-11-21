package wtf.nanoka.airplay.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerAppStartupTest {

    @TempDir
    Path directory;

    @Test
    void honorsValidationFromSystemPropertiesEvenWhenTheInstanceLockIsHeld() throws Exception {
        var environment = new StandardEnvironment() {
            @Override
            public Map<String, Object> getSystemProperties() {
                return Map.of("java-airplay.validation", "true");
            }
        };
        try (var running = new ReceiverInstanceLock(directory);
             var next = new ReceiverInstanceLock(directory)) {
            assertTrue(running.acquire());
            assertTrue(PlayerApp.canStart(environment, next));
            assertFalse(next.isHeld());

            environment.getPropertySources().addFirst(
                    new SimpleCommandLinePropertySource("--java-airplay.validation=false"));
            assertFalse(PlayerApp.canStart(environment, next));
        }
    }

    @Test
    void refusesToStartAReceiverIfTheLockFileCannotBeOpened() throws Exception {
        Files.createDirectory(directory.resolve(ReceiverInstanceLock.LOCK_FILE_NAME));
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new SimpleCommandLinePropertySource("--java-airplay.validation=false"));
        try (var instance = new ReceiverInstanceLock(directory)) {
            assertThrows(IOException.class, () -> PlayerApp.canStart(environment, instance));
            assertFalse(instance.isHeld());
        }
    }
}

package wtf.nanoka.airplay.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.app.ReceiverInstanceLock;
import wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingsControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsEveryEditableSettingAndPreservesUnknownProperties() throws Exception {
        Path settingsFile = temporaryDirectory.resolve("application.properties");
        Files.writeString(settingsFile, "logging.level.wtf.nanoka=DEBUG\n", StandardCharsets.UTF_8);
        var controller = controller(null, settingsFile);

        var result = controller.save(validSettings());

        assertTrue(result.success(), result.message());
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals("Living Room", properties.getProperty("airplay.serverName"));
        assertEquals("true", properties.getProperty("airplay.hevc"));
        assertEquals("auto", properties.getProperty("airplay.networkInterface"));
        assertEquals("quality", properties.getProperty("player.gstreamer.renderMode"));
        assertEquals("true", properties.getProperty("player.gstreamer.aggressiveFrameDropping"));
        assertEquals("DEBUG", properties.getProperty("logging.level.wtf.nanoka"));
    }

    @Test
    void rejectsInvalidValuesWithoutCreatingAFile() {
        Path settingsFile = temporaryDirectory.resolve("invalid.properties");
        ReceiverSettings valid = validSettings();
        ReceiverSettings invalid = new ReceiverSettings(
                valid.serverName(), "12", valid.height(), valid.fps(), valid.identityFile(),
                valid.audioJitterPackets(), valid.requirePairing(), valid.hevcEnabled(),
                valid.networkInterface(),
                valid.playerImplementation(), valid.trayEnabled(), valid.swingEnabled(),
                valid.videoDecoder(), valid.gpuAdapter(), valid.videoQueueDepth(),
                valid.aggressiveFrameDropping(), valid.renderMode());

        var result = controller(null, settingsFile).save(invalid);

        assertFalse(result.success());
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void reconstructsRestartCommandWithoutDependingOnProcessArguments() throws Exception {
        try (var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("applicationArguments",
                    new DefaultApplicationArguments("--airplay.serverName=Restarted Receiver"));
            context.refresh();
            var controller = controller(context, temporaryDirectory.resolve("application.properties"));

            var command = controller.currentRestartCommand();

            assertFalse(command.isEmpty());
            assertTrue(command.stream().noneMatch(argument -> argument.contains("-agentlib:jdwp")));
            assertTrue(command.contains("--airplay.serverName=Restarted Receiver"));
            assertTrue(command.contains("-jar") || command.contains(PlayerApp.class.getName()));
        }
    }

    @Test
    void readsTheFailureEvenAfterTheReplacementHasExited() throws Exception {
        Path marker = temporaryDirectory.resolve("restart.ready");
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process replacement = new ProcessBuilder(java, "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String diagnostic = "Bonjour registration failed on WLAN";
            RestartReadiness.signalFailure(marker, "attempt", replacement.pid(), diagnostic);
            assertTrue(replacement.waitFor(10, TimeUnit.SECONDS));

            var result = UserSettingsController.waitForReady(replacement, marker, "attempt", 1, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(result.message().contains(diagnostic), result.message());

            var stale = UserSettingsController.waitForReady(replacement, marker, "earlier-attempt", 1, TimeUnit.SECONDS);
            assertFalse(stale.success());
            assertFalse(stale.message().contains(diagnostic), stale.message());
        } finally {
            replacement.destroyForcibly();
        }
    }

    @Test
    void restartRollbackOnlyRestoresTheServerWhileHoldingTheInstanceLock() throws Exception {
        Path profile = temporaryDirectory.resolve("profile");
        try (var original = new ReceiverInstanceLock(profile);
             var competing = new ReceiverInstanceLock(profile)) {
            var controller = new UserSettingsController(null,
                    temporaryDirectory.resolve("application.properties"), original);
            assertTrue(original.acquire());
            original.release();
            assertTrue(competing.acquire());

            AtomicInteger starts = new AtomicInteger();
            AirPlayConfig config = new AirPlayConfig();
            config.setIdentityFile(temporaryDirectory.resolve("identity.key").toString());
            AirPlayServer server = new AirPlayServer(config, null) {
                @Override
                public synchronized void start() {
                    assertTrue(original.isHeld(), "a restored server must own the instance lock");
                    starts.incrementAndGet();
                }
            };

            var blocked = controller.restoreAfterFailedRestart(server, true, "replacement did not become ready");
            assertFalse(blocked.success());
            assertTrue(blocked.message().contains("another process holds the instance lock"));
            assertEquals(0, starts.get());

            competing.release();
            var restored = controller.restoreAfterFailedRestart(server, true, "replacement did not become ready");
            assertFalse(restored.success(), "the replacement still failed to start");
            assertTrue(restored.message().contains("The previous receiver was restored"));
            assertEquals(1, starts.get());
        }
    }

    private UserSettingsController controller(GenericApplicationContext context, Path settingsFile) {
        return new UserSettingsController(context, settingsFile,
                new ReceiverInstanceLock(temporaryDirectory.resolve("profile")));
    }

    private ReceiverSettings validSettings() {
        return new ReceiverSettings(
                "Living Room", "3840", "2160", "60",
                temporaryDirectory.resolve("identity.key").toString(), 4,
                true, true, "auto", "gstreamer", true, true,
                "d3d12h264dec", "0", 3, true, "quality");
    }
}

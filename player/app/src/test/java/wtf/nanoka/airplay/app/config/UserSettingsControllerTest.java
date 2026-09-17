package wtf.nanoka.airplay.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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
        var controller = new UserSettingsController(null, settingsFile);

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

        var result = new UserSettingsController(null, settingsFile).save(invalid);

        assertFalse(result.success());
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void reconstructsRestartCommandWithoutDependingOnProcessArguments() throws Exception {
        try (var context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("applicationArguments",
                    new DefaultApplicationArguments("--airplay.serverName=Restarted Receiver"));
            context.refresh();
            var controller = new UserSettingsController(context,
                    temporaryDirectory.resolve("application.properties"));

            var command = controller.currentRestartCommand();

            assertFalse(command.isEmpty());
            assertTrue(command.stream().noneMatch(argument -> argument.contains("-agentlib:jdwp")));
            assertTrue(command.contains("--airplay.serverName=Restarted Receiver"));
            assertTrue(command.contains("-jar") || command.contains(PlayerApp.class.getName()));
        }
    }

    private ReceiverSettings validSettings() {
        return new ReceiverSettings(
                "Living Room", "3840", "2160", "60",
                temporaryDirectory.resolve("identity.key").toString(), 4,
                true, true, "auto", "gstreamer", true, true,
                "d3d12h264dec", "0", 3, true, "quality");
    }
}

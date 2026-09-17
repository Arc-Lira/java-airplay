package wtf.nanoka.airplay.player.gstreamer.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionPlayerWindowVisualTest {

    @Test
    void rendersDarkVideoViewSnapshot() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Visual snapshot requires a desktop");
        Preferences preferences = Preferences.userNodeForPackage(ThemeManager.class);
        String previousTheme = preferences.get("theme-mode", null);
        String previousLanguage = preferences.get("ui-language", null);
        preferences.put("theme-mode", "DARK");
        preferences.put("ui-language", "ENGLISH");

        try {
            var settings = new ReceiverSettings(
                    "Visual QA Receiver", "1920", "1080", "60", "identity.pem", 32,
                    true, true, "auto", "gstreamer", true, true, "auto", "auto", 2, false, "balanced");
            var controller = new SettingsController() {
                @Override
                public Path settingsFile() {
                    return Path.of("visual-qa.properties").toAbsolutePath();
                }

                @Override
                public Result save(ReceiverSettings settings) {
                    return Result.success("Saved");
                }

                @Override
                public Result restart() {
                    return Result.success("Restarted");
                }
            };
            try (var window = new VisionPlayerWindow(new VisionPlayerWindow.Config(
                    "Visual QA Receiver", 1920, 1080, 60, true, false, settings, controller))) {
                window.showVideo(new VideoStreamInfo(
                        "visual", 1920, 1080, 60, VideoStreamInfo.Codec.H264));
                SwingUtilities.invokeAndWait(() -> { });
                JFrame frame = Arrays.stream(Window.getWindows())
                        .filter(JFrame.class::isInstance)
                        .map(JFrame.class::cast)
                        .filter(candidate -> candidate.getTitle().equals("Java AirPlay - Visual QA Receiver"))
                        .findFirst()
                        .orElseThrow();
                var videoImage = new BufferedImage(1180, 760, BufferedImage.TYPE_INT_ARGB);
                SwingUtilities.invokeAndWait(() -> {
                    frame.setExtendedState(JFrame.NORMAL);
                    frame.setSize(1180, 760);
                    frame.validate();
                    render(frame, videoImage);
                });
                Path testClasses = Path.of(VisionPlayerWindowVisualTest.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                Path visualReports = testClasses.getParent().getParent().getParent()
                        .resolve(Path.of("reports", "visual"));
                writeSnapshot(videoImage, visualReports.resolve("java-airplay-window.png"));

                Method selectSection = VisionPlayerWindow.class.getDeclaredMethod("selectSection", String.class);
                selectSection.setAccessible(true);
                Field settingsStackField = VisionPlayerWindow.class.getDeclaredField("settingsStack");
                settingsStackField.setAccessible(true);
                JPanel settingsStack = (JPanel) settingsStackField.get(window);
                var settingsImage = new BufferedImage(1180, 760, BufferedImage.TYPE_INT_ARGB);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        selectSection.invoke(window, "settings");
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(error);
                    }
                    frame.validate();
                    JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(
                            JScrollPane.class, settingsStack);
                    scrollPane.getVerticalScrollBar().setValue(650);
                    frame.validate();
                    render(frame, settingsImage);
                });
                writeSnapshot(settingsImage, visualReports.resolve("java-airplay-settings.png"));
            }
            SwingUtilities.invokeAndWait(() -> { });
        } finally {
            restorePreference(preferences, "theme-mode", previousTheme);
            restorePreference(preferences, "ui-language", previousLanguage);
        }
    }

    private static void restorePreference(Preferences preferences, String key, String value) {
        if (value == null) {
            preferences.remove(key);
        } else {
            preferences.put(key, value);
        }
    }

    private static void render(JFrame frame, BufferedImage image) {
        var graphics = image.createGraphics();
        try {
            frame.getRootPane().printAll(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private static void writeSnapshot(BufferedImage image, Path snapshot) throws Exception {
        Files.createDirectories(snapshot.getParent());
        assertTrue(ImageIO.write(image, "png", snapshot.toFile()));
        assertTrue(Files.size(snapshot) > 0);
    }
}

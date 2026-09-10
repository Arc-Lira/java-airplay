package wtf.nanoka.airplay.player.gstreamer.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

final class ThemeManager implements AutoCloseable {

    private static final String THEME_PREFERENCE = "theme-mode";
    private static final String WINDOWS_THEME_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    private static final ThemePalette DARK_PALETTE = new ThemePalette(
            true,
            new Color(0x151719), new Color(0x242526), new Color(0x302C28),
            new Color(255, 255, 255, 12),
            new Color(34, 38, 40, 220), new Color(30, 34, 36, 242), new Color(66, 73, 76, 118),
            new Color(95, 104, 108, 112), new Color(135, 145, 148, 126),
            new Color(246, 248, 248), new Color(22, 25, 26),
            new Color(0xF5F7F7), new Color(0xB9C1C3), new Color(0x7F8A8D),
            new Color(0x0A84FF), new Color(0x32D74B), new Color(0xFF9F0A), new Color(0xFF453A),
            new Color(255, 255, 255, 28), new Color(0x090A0B), new Color(0, 0, 0, 105));

    private static final ThemePalette LIGHT_PALETTE = new ThemePalette(
            false,
            new Color(0xF3F5F4), new Color(0xE4E7E6), new Color(0xD8D0C7),
            new Color(32, 42, 44, 12),
            new Color(255, 255, 255, 190), new Color(250, 251, 250, 232), new Color(255, 255, 255, 126),
            new Color(255, 255, 255, 150), new Color(255, 255, 255, 215),
            new Color(255, 255, 255), new Color(0x202426),
            new Color(0x202426), new Color(0x596164), new Color(0x7B8588),
            new Color(0x007AFF), new Color(0x34C759), new Color(0xFF9500), new Color(0xFF3B30),
            new Color(32, 42, 44, 24), new Color(0x111314), new Color(36, 40, 42, 46));

    private final Preferences preferences = Preferences.userNodeForPackage(ThemeManager.class);
    private final List<Runnable> listeners = new ArrayList<>();
    private final AtomicBoolean systemCheckRunning = new AtomicBoolean();
    private final Timer systemThemeTimer;

    private ThemeMode mode;
    private boolean dark;

    ThemeManager() {
        mode = ThemeMode.fromPreference(preferences.get(THEME_PREFERENCE, ThemeMode.SYSTEM.name()));
        boolean allowExternalCommands = !SwingUtilities.isEventDispatchThread();
        dark = mode == ThemeMode.SYSTEM ? detectSystemDarkTheme(allowExternalCommands) : mode == ThemeMode.DARK;
        applyLookAndFeel();

        systemThemeTimer = new Timer(3_000, event -> refreshSystemThemeAsync());
        systemThemeTimer.setInitialDelay(3_000);
        systemThemeTimer.start();
        if (mode == ThemeMode.SYSTEM && !allowExternalCommands) {
            refreshSystemThemeAsync();
        }
    }

    ThemeMode mode() {
        return mode;
    }

    ThemePalette palette() {
        return dark ? DARK_PALETTE : LIGHT_PALETTE;
    }

    void setMode(ThemeMode mode) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setMode(mode));
            return;
        }
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        preferences.put(THEME_PREFERENCE, mode.name());
        if (mode == ThemeMode.SYSTEM) {
            notifyListeners();
            refreshSystemThemeAsync();
            return;
        }
        boolean nextDark = mode == ThemeMode.DARK;
        if (nextDark != dark) {
            dark = nextDark;
            applyLookAndFeel();
        } else {
            notifyListeners();
        }
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void refreshSystemThemeAsync() {
        if (mode != ThemeMode.SYSTEM || !systemCheckRunning.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("system-theme-detector").start(() -> {
            boolean detectedDark = detectSystemDarkTheme(true);
            SwingUtilities.invokeLater(() -> {
                systemCheckRunning.set(false);
                if (mode == ThemeMode.SYSTEM && dark != detectedDark) {
                    dark = detectedDark;
                    applyLookAndFeel();
                }
            });
        });
    }

    private void applyLookAndFeel() {
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 10);
        UIManager.put("Component.focusWidth", 1);

        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            window.repaint();
        }
        notifyListeners();
    }

    private void notifyListeners() {
        List.copyOf(listeners).forEach(Runnable::run);
    }

    private static boolean detectSystemDarkTheme(boolean allowExternalCommands) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            Object desktopProperty = Toolkit.getDefaultToolkit().getDesktopProperty("win.darkMode.on");
            if (desktopProperty instanceof Boolean darkMode) {
                return darkMode;
            }
            try {
                return Advapi32Util.registryGetIntValue(
                        WinReg.HKEY_CURRENT_USER, WINDOWS_THEME_KEY, "AppsUseLightTheme") == 0;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (os.contains("mac")) {
            String appearance = System.getProperty("apple.awt.application.appearance", "")
                    .toLowerCase(Locale.ROOT);
            if (appearance.contains("dark")) {
                return true;
            }
            return allowExternalCommands
                    && runAppearanceCommand("defaults", "read", "-g", "AppleInterfaceStyle").contains("dark");
        }

        String gtkTheme = System.getenv("GTK_THEME");
        if (gtkTheme != null && gtkTheme.toLowerCase(Locale.ROOT).contains("dark")) {
            return true;
        }
        if (!allowExternalCommands) {
            return false;
        }
        return runAppearanceCommand("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                .contains("dark");
    }

    private static String runAppearanceCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String value = reader.readLine();
                return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    @Override
    public void close() {
        systemThemeTimer.stop();
    }

    enum ThemeMode {
        SYSTEM("System", "theme.system"),
        LIGHT("Light", "theme.light"),
        DARK("Dark", "theme.dark");

        private final String label;
        private final String labelKey;

        ThemeMode(String label, String labelKey) {
            this.label = label;
            this.labelKey = labelKey;
        }

        String label() {
            return label;
        }

        /** Key into the UI message bundle; use with an I18n instance for localized labels. */
        String labelKey() {
            return labelKey;
        }

        static ThemeMode fromPreference(String value) {
            try {
                return ThemeMode.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                return SYSTEM;
            }
        }
    }

    record ThemePalette(
            boolean dark,
            Color backgroundStart,
            Color backgroundMiddle,
            Color backgroundEnd,
            Color ambientLine,
            Color glass,
            Color glassStrong,
            Color glassSoft,
            Color control,
            Color controlHover,
            Color selected,
            Color selectedText,
            Color textPrimary,
            Color textSecondary,
            Color textTertiary,
            Color accent,
            Color success,
            Color warning,
            Color danger,
            Color divider,
            Color videoSurface,
            Color shadow) {
    }
}

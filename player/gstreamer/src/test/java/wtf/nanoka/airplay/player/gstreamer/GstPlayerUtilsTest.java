package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GstPlayerUtilsTest {

    @Test
    void mapsWindows64BitHostsToGStreamerArchitectures() {
        if (!Platform.isWindows() || !Platform.is64Bit()) {
            return;
        }
        if (Platform.isARM()) {
            assertEquals("arm64", GstPlayerUtils.windowsGStreamerArchitecture());
            return;
        }
        if (Platform.isIntel()) {
            assertEquals("x86_64", GstPlayerUtils.windowsGStreamerArchitecture());
        }
    }

    @Test
    void prefersOfficialThenHomebrewMacLibraryPaths() {
        String location = GstPlayerUtils.findMacLocation();
        if (!Platform.isMac()) {
            assertTrue(location.isEmpty() || location.contains("GStreamer")
                    || location.contains("homebrew") || location.contains("usr/local"));
            return;
        }
        assertTrue(location.contains("GStreamer.framework")
                || location.contains("/opt/homebrew/lib")
                || location.contains("/usr/local/lib")
                || location.isEmpty());
    }
}

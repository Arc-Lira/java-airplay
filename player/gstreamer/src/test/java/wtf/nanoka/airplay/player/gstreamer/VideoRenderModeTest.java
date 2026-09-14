package wtf.nanoka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoRenderModeTest {

    @Test
    void balancedWaitsOnTheClockWithoutDroppingTheNextDecodedFrame() {
        assertTrue(VideoRenderMode.BALANCED.clockSyncProperties().contains("sync=true"));
        assertTrue(VideoRenderMode.BALANCED.clockSyncProperties().contains("sync-to-first=true"));
        assertTrue(VideoRenderMode.BALANCED.clockSyncProperties().contains("qos=true"));
        assertTrue(VideoRenderMode.BALANCED.queueProperties().contains("leaky=no"));
        assertTrue(VideoRenderMode.BALANCED.queueProperties().contains("max-size-buffers=6"));
        assertFalse(VideoRenderMode.BALANCED.queueProperties().contains("leaky=downstream"));
        assertTrue(VideoRenderMode.QUALITY.clockSyncProperties().contains("sync=true"));
        assertTrue(VideoRenderMode.QUALITY.clockSyncProperties().contains("qos=false"));
        assertTrue(VideoRenderMode.QUALITY.queueProperties().contains("leaky=no"));
    }

    @Test
    void lowLatencyModeExplicitlyDisablesClockSynchronization() {
        assertTrue(VideoRenderMode.LOW_LATENCY.clockSyncProperties().contains("sync=false"));
        assertTrue(VideoRenderMode.LOW_LATENCY.clockSyncProperties().contains("qos=false"));
        assertTrue(VideoRenderMode.LOW_LATENCY.queueProperties().contains("leaky=downstream"));
        assertThrows(IllegalArgumentException.class, () -> VideoRenderMode.fromProperty("invalid"));
    }
}

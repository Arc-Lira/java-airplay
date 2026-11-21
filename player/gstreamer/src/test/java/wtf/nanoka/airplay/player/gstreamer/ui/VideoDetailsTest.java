package wtf.nanoka.airplay.player.gstreamer.ui;

import org.junit.jupiter.api.Test;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoDetailsTest {

    @Test
    void distinguishesUnknownValuesFromMeasuredHighResolutionStream() throws Exception {
        Preferences preferences = Preferences.userRoot().node("video-details-test-" + System.nanoTime());
        I18n i18n = new I18n(preferences);
        try {
            for (I18n.Language language : I18n.Language.values()) {
                i18n.setLanguage(language);
                String pending = VideoDetails.format(new VideoStreamInfo("test"), i18n);
                assertTrue(pending.contains(i18n.tr("video.resolution.pending")));
                assertTrue(pending.contains(i18n.tr("video.fps.pending")));
                assertFalse(pending.contains("1080"));
                assertFalse(pending.contains("60 FPS"));
                String measured = VideoDetails.format(new VideoStreamInfo(
                        "test", 1290, 2796, 59.9, VideoStreamInfo.Codec.HEVC), i18n);
                assertTrue(measured.contains("1290 x 2796"));
                assertTrue(measured.contains("60 FPS"));
                assertTrue(measured.contains("HEVC"));
            }
        } finally {
            i18n.close();
            preferences.removeNode();
        }
    }
}

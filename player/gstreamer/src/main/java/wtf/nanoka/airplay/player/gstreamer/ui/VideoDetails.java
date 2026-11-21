package wtf.nanoka.airplay.player.gstreamer.ui;

import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.Locale;

final class VideoDetails {

    private VideoDetails() {
    }

    static String format(VideoStreamInfo stream, I18n i18n) {
        String resolution = stream.getWidth() > 0 && stream.getHeight() > 0
                ? stream.getWidth() + " x " + stream.getHeight()
                : i18n.tr("video.resolution.pending");
        String fps = stream.getFps() > 0
                ? String.format(Locale.ROOT, "%.0f FPS", stream.getFps())
                : i18n.tr("video.fps.pending");
        String codec = switch (stream.getCodec()) {
            case H264 -> "H.264";
            case HEVC -> "HEVC";
            case UNKNOWN -> i18n.tr("receiver.details.detecting");
        };
        return i18n.tr("video.stream") + " " + resolution + "  |  " + fps + "  |  " + codec;
    }
}

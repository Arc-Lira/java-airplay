package wtf.nanoka.airplay.server;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public interface AirPlayConsumer {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    default void onVideo(byte[] bytes, long timestamp) {
        onVideo(bytes);
    }

    default void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
    }

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    default void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        onAudio(bytes);
    }

    void onAudioSrcDisconnect();

    /** Brings the receiver window to the front when the user launches it again. */
    default void showWindow() {
    }

    // HLS stuff, youtube
    default void onMediaPlaylist(String playlistUri) {
    }

    default void onMediaPlaylistRemove() {
    }

    default void onMediaPlaylistPause() {
    }

    default void onMediaPlaylistResume() {
    }

    default PlaybackInfo playbackInfo() {
        return new PlaybackInfo(0, 0);
    }

    record PlaybackInfo(double duration, double position) {
    }
}

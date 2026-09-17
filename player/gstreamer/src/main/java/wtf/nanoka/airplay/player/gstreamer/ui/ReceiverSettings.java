package wtf.nanoka.airplay.player.gstreamer.ui;

import java.util.Objects;

public record ReceiverSettings(
        String serverName,
        String width,
        String height,
        String fps,
        String identityFile,
        int audioJitterPackets,
        boolean requirePairing,
        boolean hevcEnabled,
        String networkInterface,
        String playerImplementation,
        boolean trayEnabled,
        boolean swingEnabled,
        String videoDecoder,
        String gpuAdapter,
        int videoQueueDepth,
        boolean aggressiveFrameDropping,
        String renderMode) {

    public ReceiverSettings {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(width, "width");
        Objects.requireNonNull(height, "height");
        Objects.requireNonNull(fps, "fps");
        Objects.requireNonNull(identityFile, "identityFile");
        Objects.requireNonNull(networkInterface, "networkInterface");
        Objects.requireNonNull(playerImplementation, "playerImplementation");
        Objects.requireNonNull(videoDecoder, "videoDecoder");
        Objects.requireNonNull(gpuAdapter, "gpuAdapter");
        Objects.requireNonNull(renderMode, "renderMode");
    }
}

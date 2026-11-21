package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import org.freedesktop.gstreamer.message.Message;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.player.gstreamer.ui.VisionPlayerWindow;

import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class GstPlayerSwing extends GstPlayer {

    private final VisionPlayerWindow window;
    private volatile long videoWindowHandle;
    private volatile VideoOverlay videoOverlay;

    public GstPlayerSwing(int fps,
                          int videoQueueDepth,
                          String videoDecoder,
                          String gpuAdapter,
                          String renderMode,
                          boolean hevcEnabled,
                          boolean aggressiveFrameDropping,
                          WindowOptions options) {
        super(fps, videoQueueDepth,
                GstPlayerDefault.createPipelineDescription(
                        VideoStreamInfo.Codec.H264, videoDecoder, gpuAdapter, renderMode),
                hevcEnabled ? GstPlayerDefault.createPipelineDescription(
                        VideoStreamInfo.Codec.HEVC, videoDecoder, gpuAdapter, renderMode) : null,
                aggressiveFrameDropping);
        window = new VisionPlayerWindow(new VisionPlayerWindow.Config(
                options.receiverName(),
                options.advertisedWidth(),
                options.advertisedHeight(),
                fps,
                options.pairingRequired(),
                options.closeToTray(),
                options.settings(),
                options.settingsController(),
                this::rebindVideoSurface));
        videoWindowHandle = videoWindowHandle();
        if (videoWindowHandle == 0) {
            throw new IllegalStateException("The integrated video surface has no native window handle");
        }
        h264Pipeline.getBus().setSyncHandler(this::handleSyncMessage);
        if (hevcPipeline != null) {
            hevcPipeline.getBus().setSyncHandler(this::handleSyncMessage);
        }
    }

    @Override
    protected Pipeline createH264Pipeline() {
        return (Pipeline) Gst.parseLaunch(GstPlayerDefault.createPipelineDescription(
                "auto", "auto", VideoRenderMode.BALANCED.propertyValue()));
    }

    @Override
    protected Pipeline createHevcPipeline() {
        return (Pipeline) Gst.parseLaunch(GstPlayerDefault.createPipelineDescription(
                VideoStreamInfo.Codec.HEVC, "auto", "auto", VideoRenderMode.BALANCED.propertyValue()));
    }

    private BusSyncReply handleSyncMessage(Message message) {
        if (!VideoOverlay.isPrepareWindowHandleMessage(message)) {
            return BusSyncReply.PASS;
        }
        if (!(message.getSource() instanceof Element sink)) {
            log.warn("GStreamer requested a video window handle from a non-element source");
            return BusSyncReply.PASS;
        }
        try {
            VideoOverlay overlay = VideoOverlay.wrap(sink);
            overlay.setWindowHandle(videoWindowHandle);
            videoOverlay = overlay;
            return BusSyncReply.DROP;
        } catch (RuntimeException error) {
            log.warn("Unable to attach the GStreamer video sink to the application window: {}",
                    error.getMessage());
            return BusSyncReply.DROP;
        }
    }

    /**
     * Rebinds the active sink after AWT recreates the Canvas peer in its new
     * top-level window. Keeping the decoder running avoids waiting for a new
     * H.264/HEVC random-access frame after every move.
     */
    private void rebindVideoSurface() {
        long handle = videoWindowHandle();
        if (handle == 0) {
            log.warn("Unable to rebind the video pipeline because the video surface has no native handle");
            return;
        }
        videoWindowHandle = handle;
        VideoOverlay overlay = videoOverlay;
        if (overlay == null) {
            return;
        }
        try {
            overlay.setWindowHandle(handle);
            overlay.expose();
            log.info("Rebound the video pipeline to the moved video surface");
        } catch (RuntimeException error) {
            log.warn("Unable to rebind the active video sink; restarting the pipeline: {}", error.getMessage());
            if (restartActiveVideoPipeline()) {
                log.info("Restarted the video pipeline for the moved video surface");
            }
        }
    }

    private long videoWindowHandle() {
        for (int attempt = 0; attempt < 20; attempt++) {
            AtomicLong handle = new AtomicLong();
            Runnable readHandle = () -> {
                Canvas canvas = window.videoCanvas();
                if (canvas.isDisplayable()) {
                    handle.set(Native.getComponentID(canvas));
                }
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) {
                    readHandle.run();
                } else {
                    SwingUtilities.invokeAndWait(readHandle);
                }
            } catch (Exception error) {
                log.warn("Unable to read the native video surface handle: {}", error.getMessage());
                return 0;
            }
            if (handle.get() != 0) {
                return handle.get();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    @Override
    protected void onVideoPlaybackError(String message) {
        window.showVideoError(message);
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        window.showVideoFormatDetected(videoStreamInfo);
        super.onVideoFormatDetected(videoStreamInfo);
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        window.showVideo(videoStreamInfo);
        super.onVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideoSrcDisconnect() {
        super.onVideoSrcDisconnect();
        window.showIdle();
    }

    public void showWindow() {
        window.showWindow();
    }

    public void showDetachedVideo() {
        window.showDetachedVideo();
    }

    public void toggleVideoFullscreen() {
        window.toggleVideoFullscreen();
    }

    public void toggleLanguage() {
        window.toggleLanguage();
    }

    public String languageLabel() {
        return window.languageLabel();
    }

    public String localized(String key) {
        return window.localized(key);
    }

    public void addLanguageChangeListener(Runnable listener) {
        window.addLanguageChangeListener(listener);
    }

    public void setCloseToTray(boolean closeToTray) {
        window.setCloseToTray(closeToTray);
    }

    @Override
    public void close() {
        h264Pipeline.getBus().clearSyncHandler();
        if (hevcPipeline != null) {
            hevcPipeline.getBus().clearSyncHandler();
        }
        super.close();
        window.close();
    }

    public record WindowOptions(
            String receiverName,
            int advertisedWidth,
            int advertisedHeight,
            boolean pairingRequired,
            boolean closeToTray,
            wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings settings,
            wtf.nanoka.airplay.player.gstreamer.ui.SettingsController settingsController) {
    }
}

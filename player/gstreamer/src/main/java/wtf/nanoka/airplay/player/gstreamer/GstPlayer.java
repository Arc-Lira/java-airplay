package wtf.nanoka.airplay.player.gstreamer;

import wtf.nanoka.airplay.lib.AudioStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GLib;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingDeque;

@Slf4j
public abstract class GstPlayer implements AirPlayConsumer, AutoCloseable {

    private static final long VIDEO_BACKPRESSURE_WARNING_NANOS = TimeUnit.SECONDS.toNanos(5);

    static {
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG", "3", true);
        Gst.init(Version.of(1, 10), "BasicPipeline");
    }

    protected final Pipeline h264Pipeline;
    protected final Pipeline hevcPipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;

    private final AppSrc h264Src;
    private final AppSrc hevcSrc;
    private final AppSrc alacSrc;
    private final AppSrc aacEldSrc;

    private Pipeline hlsPipeline;

    private AudioStreamInfo.CompressionType audioCompressionType;
    private final LinkedBlockingDeque<TimedMedia> videoQueue;
    private final LinkedBlockingDeque<TimedMedia> audioQueue = new LinkedBlockingDeque<>(32);
    private final Object hlsLock = new Object();
    private final int videoQueueDepth;
    private final boolean aggressiveFrameDropping;
    private final long configuredVideoFrameDurationNanos;
    private volatile int audioSampleRate = 44100;
    private volatile int audioSamplesPerFrame = 480;
    private long firstAudioTimestamp = Long.MIN_VALUE;
    private volatile Pipeline activeVideoPipeline;
    private volatile AppSrc activeVideoSrc;
    private volatile VideoStreamInfo.Codec activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
    private volatile VideoStreamInfo detectedVideoFormat;
    // Only accessed under videoTransitionLock. Restore the auto choice on disconnect.
    private final Map<Pipeline, String> softwareFallbacks = new HashMap<>();
    private byte[] videoParameterSets;
    private boolean awaitingVideoKeyframe;
    private volatile long videoGeneration;
    protected final Object videoTransitionLock = new Object();
    private int videoPushesInFlight;
    private int audioPushesInFlight;
    private volatile long lastVideoBackpressureWarningNanos;
    private volatile boolean closed;
    private final Thread videoFeeder;
    private final Thread audioFeeder;

    public GstPlayer() {
        this(60, 3, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth) {
        this(fps, videoQueueDepth, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription) {
        this(fps, videoQueueDepth, h264PipelineDescription, null);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription,
                        String hevcPipelineDescription) {
        this(fps, videoQueueDepth, h264PipelineDescription, hevcPipelineDescription, false);
    }

    protected GstPlayer(int fps, int videoQueueDepth, String h264PipelineDescription,
                        String hevcPipelineDescription, boolean aggressiveFrameDropping) {
        this.videoQueueDepth = Math.max(1, videoQueueDepth);
        this.aggressiveFrameDropping = aggressiveFrameDropping;
        videoQueue = new LinkedBlockingDeque<>(this.videoQueueDepth);
        configuredVideoFrameDurationNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(1, fps);
        h264Pipeline = h264PipelineDescription == null
                ? createH264Pipeline()
                : (Pipeline) Gst.parseLaunch(h264PipelineDescription);
        h264Src = (AppSrc) h264Pipeline.getElementByName("h264-src");
        configureVideoSource(h264Src,
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au");

        hevcPipeline = hevcPipelineDescription == null
                ? null
                : (Pipeline) Gst.parseLaunch(hevcPipelineDescription);
        hevcSrc = hevcPipeline == null ? null : (AppSrc) hevcPipeline.getElementByName("hevc-src");
        if (hevcSrc != null) {
            configureVideoSource(hevcSrc,
                    "video/x-h265,stream-format=(string)byte-stream,alignment=(string)au");
        }
        monitorVideoPipeline(h264Pipeline, VideoStreamInfo.Codec.H264);
        if (hevcPipeline != null) {
            monitorVideoPipeline(hevcPipeline, VideoStreamInfo.Codec.HEVC);
        }

        alacPipeline = (Pipeline) Gst.parseLaunch("appsrc name=alac-src ! avdec_alac ! audioconvert "
                + "! audioresample ! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false");

        alacSrc = (AppSrc) alacPipeline.getElementByName("alac-src");
        alacSrc.setStreamType(AppSrc.StreamType.STREAM);
        alacSrc.set("is-live", true);
        alacSrc.set("format", Format.TIME);
        alacSrc.set("emit-signals", false);
        alacSrc.set("block", true);
        alacSrc.set("max-buffers", 32L);
        alacSrc.setMaxBytes(512 * 1024);

        aacEldPipeline = (Pipeline) Gst.parseLaunch("appsrc name=aac-eld-src ! avdec_aac ! audioconvert "
                + "! audioresample ! clocksync sync=true sync-to-first=true ! autoaudiosink sync=false");

        aacEldSrc = (AppSrc) aacEldPipeline.getElementByName("aac-eld-src");
        aacEldSrc.setStreamType(AppSrc.StreamType.STREAM);
        aacEldSrc.set("is-live", true);
        aacEldSrc.set("format", Format.TIME);
        aacEldSrc.set("emit-signals", false);
        aacEldSrc.set("block", true);
        aacEldSrc.set("max-buffers", 32L);
        aacEldSrc.setMaxBytes(512 * 1024);

        videoFeeder = Thread.ofPlatform().name("gstreamer-video-feeder").daemon(true).start(this::feedVideo);
        audioFeeder = Thread.ofPlatform().name("gstreamer-audio-feeder").daemon(true).start(this::feedAudio);
        if (aggressiveFrameDropping) {
            log.warn("Experimental aggressive encoded-frame dropping is enabled; predicted frames may corrupt");
        }
    }

    protected abstract Pipeline createH264Pipeline();

    protected abstract Pipeline createHevcPipeline();

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        if (videoStreamInfo.getCodec() != VideoStreamInfo.Codec.UNKNOWN) {
            activateVideoPipeline(videoStreamInfo.getCodec());
        } else {
            deactivateVideoPipeline();
        }
    }

    @Override
    public void onVideoFormatDetected(VideoStreamInfo videoStreamInfo) {
        if (videoStreamInfo.getCodec() != VideoStreamInfo.Codec.UNKNOWN) {
            synchronized (videoTransitionLock) {
                VideoStreamInfo previousFormat = detectedVideoFormat;
                detectedVideoFormat = videoStreamInfo;
                Pipeline pipeline = videoStreamInfo.getCodec() == VideoStreamInfo.Codec.H264
                        ? h264Pipeline : hevcPipeline;
                boolean sizeChanged = previousFormat == null
                        || previousFormat.getCodec() != videoStreamInfo.getCodec()
                        || previousFormat.getWidth() != videoStreamInfo.getWidth()
                        || previousFormat.getHeight() != videoStreamInfo.getHeight();
                if (!closed && pipeline != null && sizeChanged
                        && GstDecoderSupport.needsSoftwareDecoder(pipeline, videoStreamInfo)) {
                    Pipeline previous;
                    synchronized (this) {
                        previous = invalidateVideoPipeline();
                        videoParameterSets = null;
                    }
                    StateChangeReturn stopped = stopAndDrainVideoPipeline(previous);
                    if (previous != pipeline) {
                        stopped = pipeline.stop();
                    }
                    if (stopped == StateChangeReturn.FAILURE) {
                        throw new IllegalStateException("Unable to stop the video pipeline before decoder replacement");
                    }
                    softwareFallbacks.put(pipeline, GstDecoderSupport.useSoftwareDecoder(pipeline, videoStreamInfo));
                }
                activateVideoPipeline(videoStreamInfo.getCodec());
            }
        }
    }

    private void monitorVideoPipeline(Pipeline pipeline, VideoStreamInfo.Codec codec) {
        pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {
            if (closed || activeVideoPipeline != pipeline) {
                return;
            }
            String details = "GStreamer " + codec + " error from " + source.getName()
                    + " (" + code + "): " + message;
            log.error("{}; sender format: {}. Try the software decoder (avdec_h264, GPU auto) "
                    + "or a lower display profile, then reconnect.", details, detectedVideoFormat);
            onVideoPlaybackError(details);
        });
        pipeline.getBus().connect((Bus.WARNING) (source, code, message) ->
                log.warn("GStreamer {} warning from {} ({}): {}", codec, source.getName(), code, message));
    }

    protected void onVideoPlaybackError(String message) {
    }

    @Override
    public void onVideo(byte[] bytes) {
        onVideo(bytes, -1);
    }

    @Override
    public void onVideo(byte[] bytes, long timestamp) {
        TimedMedia media;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (activeVideoSrc == null) {
                log.debug("Ignoring a video access unit before codec detection");
                return;
            }
            byte[] detectedParameterSets = leadingParameterSets(bytes, activeVideoCodec);
            if (detectedParameterSets != null) {
                videoParameterSets = detectedParameterSets;
            }
            byte[] accessUnit = bytes;
            if (awaitingVideoKeyframe) {
                if (!containsRandomAccessUnit(bytes, activeVideoCodec)) {
                    return;
                }
                if (detectedParameterSets == null && videoParameterSets != null) {
                    accessUnit = concatenate(videoParameterSets, bytes);
                }
                awaitingVideoKeyframe = false;
            }
            media = new TimedMedia(accessUnit, timestamp, activeVideoSrc, videoGeneration);
        }
        try {
            if (aggressiveFrameDropping) {
                offerLatest(videoQueue, media);
            } else {
                // Preserve every encoded reference frame. TCP backpressure is safer than corrupting the decoder DPB.
                if (!videoQueue.offerLast(media, 500, TimeUnit.MILLISECONDS)) {
                    logVideoBackpressure();
                    videoQueue.putLast(media);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            if (closed || media.generation() != videoGeneration || media.source() != activeVideoSrc) {
                videoQueue.remove(media);
            }
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        deactivateVideoPipeline();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        this.audioCompressionType = audioStreamInfo.getCompressionType();
        audioSampleRate = audioStreamInfo.getSampleRate() > 0 ? audioStreamInfo.getSampleRate() : 44100;
        audioSamplesPerFrame = audioStreamInfo.getSamplesPerFrame() > 0 ? audioStreamInfo.getSamplesPerFrame() : 480;
        audioQueue.clear();
        firstAudioTimestamp = Long.MIN_VALUE;
        configureAudioCaps();
        switch (audioCompressionType) {
            case ALAC -> alacPipeline.play();
            case AAC_ELD -> aacEldPipeline.play();
            default -> log.warn("Audio codec {} is advertised but not playable by this receiver", audioCompressionType);
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, -1, 0);
    }

    @Override
    public void onAudio(byte[] bytes, long timestamp, int sequenceNumber) {
        offerLatest(audioQueue, new TimedMedia(bytes, timestamp));
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioQueue.clear();
        alacPipeline.stop();
        aacEldPipeline.stop();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        Pipeline next = (Pipeline) Gst.parseLaunch("playbin3");
        next.set("uri", playlistUri);

        synchronized (hlsLock) {
            if (closed) {
                stopAndClose(next);
                return;
            }
            Pipeline previous = hlsPipeline;
            hlsPipeline = next;
            if (previous != null) {
                stopAndClose(previous);
            }
            try {
                next.play();
            } catch (RuntimeException | Error failure) {
                if (hlsPipeline == next) {
                    hlsPipeline = null;
                }
                stopAndClose(next);
                throw failure;
            }
        }
    }

    @Override
    public void onMediaPlaylistRemove() {
        synchronized (hlsLock) {
            if (hlsPipeline != null) {
                stopAndClose(hlsPipeline);
                hlsPipeline = null;
            }
        }
    }

    @Override
    public void onMediaPlaylistPause() {
        synchronized (hlsLock) {
            if (hlsPipeline != null && hlsPipeline.isPlaying()) {
                hlsPipeline.pause();
            }
        }
    }

    @Override
    public void onMediaPlaylistResume() {
        synchronized (hlsLock) {
            if (hlsPipeline != null && !hlsPipeline.isPlaying()) {
                hlsPipeline.play();
            }
        }
    }

    @Override
    public PlaybackInfo playbackInfo() {
        synchronized (hlsLock) {
            if (hlsPipeline != null) {
                return new PlaybackInfo(
                        hlsPipeline.queryDuration(TimeUnit.SECONDS),
                        hlsPipeline.queryPosition(TimeUnit.SECONDS));
            }
        }
        return AirPlayConsumer.super.playbackInfo();
    }

    private void configureAudioCaps() {
        String alacCodecData = String.format("00000024616c616300000000000001600010280a0e0200ff000000000000000000%08x",
                audioSampleRate);
        alacSrc.setCaps(Caps.fromString("audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)"
                + audioSampleRate + ",stream-format=raw,codec_data=(buffer)" + alacCodecData));
        aacEldSrc.setCaps(Caps.fromString("audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)"
                + audioSampleRate + ",stream-format=raw,codec_data=(buffer)f8e85000"));
    }

    private void feedVideo() {
        long feederGeneration = Long.MIN_VALUE;
        long firstTimestamp = Long.MIN_VALUE;
        long syntheticPresentationTime = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TimedMedia media = videoQueue.takeFirst();
                if (media.generation() != videoGeneration) {
                    continue;
                }
                if (media.generation() != feederGeneration) {
                    feederGeneration = media.generation();
                    firstTimestamp = Long.MIN_VALUE;
                    syntheticPresentationTime = 0;
                }
                if (firstTimestamp == Long.MIN_VALUE && media.timestamp() >= 0) {
                    firstTimestamp = media.timestamp();
                }
                long presentationTime = media.timestamp() >= 0 && firstTimestamp != Long.MIN_VALUE
                        ? Math.max(0, fixedPointDeltaNanos(firstTimestamp, media.timestamp()))
                        : syntheticPresentationTime;
                syntheticPresentationTime = presentationTime + configuredVideoFrameDurationNanos;
                pushVideoBufferIfCurrent(media, presentationTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Unable to feed GStreamer video: {}", e.getMessage());
            }
        }
    }

    private void feedAudio() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TimedMedia media = audioQueue.takeFirst();
                if (firstAudioTimestamp == Long.MIN_VALUE && media.timestamp() >= 0) {
                    firstAudioTimestamp = media.timestamp();
                }
                long presentationTime = media.timestamp() >= 0 && firstAudioTimestamp != Long.MIN_VALUE
                        ? unsignedRtpDelta(firstAudioTimestamp, media.timestamp()) * TimeUnit.SECONDS.toNanos(1) / audioSampleRate
                        : 0;
                long duration = (long) audioSamplesPerFrame * TimeUnit.SECONDS.toNanos(1) / audioSampleRate;
                AppSrc source = audioCompressionType == AudioStreamInfo.CompressionType.ALAC ? alacSrc : aacEldSrc;
                pushAudioBufferIfOpen(source, media.bytes(), presentationTime, duration);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Unable to feed GStreamer audio: {}", e.getMessage());
            }
        }
    }

    private void pushBuffer(AppSrc source, byte[] bytes, long presentationTime, long duration, String streamName) {
        Buffer buffer = new Buffer(bytes.length);
        var mapped = buffer.map(true);
        try {
            mapped.put(bytes);
        } finally {
            buffer.unmap();
        }
        buffer.setPresentationTimestamp(presentationTime);
        buffer.setDuration(duration);
        FlowReturn result = source.pushBuffer(buffer);
        if (result != FlowReturn.OK && result != FlowReturn.FLUSHING) {
            log.warn("GStreamer rejected {} buffer: {}", streamName, result);
        }
    }

    private void pushVideoBufferIfCurrent(TimedMedia media, long presentationTime) {
        if (!beginVideoPush(media)) {
            return;
        }
        try {
            pushBuffer(media.source(), media.bytes(), presentationTime,
                    configuredVideoFrameDurationNanos, "video");
        } finally {
            endVideoPush();
        }
    }

    private synchronized boolean beginVideoPush(TimedMedia media) {
        if (closed || media.generation() != videoGeneration || media.source() != activeVideoSrc) {
            return false;
        }
        videoPushesInFlight++;
        return true;
    }

    private synchronized void endVideoPush() {
        videoPushesInFlight--;
        notifyAll();
    }

    private <T> void offerLatest(LinkedBlockingDeque<T> queue, T value) {
        while (!queue.offerLast(value)) {
            queue.pollFirst();
        }
    }

    private void logVideoBackpressure() {
        long now = System.nanoTime();
        if (now - lastVideoBackpressureWarningNanos >= VIDEO_BACKPRESSURE_WARNING_NANOS) {
            lastVideoBackpressureWarningNanos = now;
            log.warn("GStreamer video input is backpressured; preserving encoded frames and waiting for capacity");
        }
    }

    private void configureVideoSource(AppSrc source, String caps) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        source.setCaps(Caps.fromString(caps));
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
        source.set("block", !aggressiveFrameDropping);
        source.set("max-buffers", (long) videoQueueDepth);
        source.set("leaky-type", aggressiveFrameDropping ? 2 : 0);
        source.setMaxBytes(4 * 1024 * 1024);
    }

    private void activateVideoPipeline(VideoStreamInfo.Codec codec) {
        Pipeline pipeline;
        AppSrc source;
        if (codec == VideoStreamInfo.Codec.H264) {
            pipeline = h264Pipeline;
            source = h264Src;
        } else if (codec == VideoStreamInfo.Codec.HEVC && hevcPipeline != null && hevcSrc != null) {
            pipeline = hevcPipeline;
            source = hevcSrc;
        } else if (codec == VideoStreamInfo.Codec.HEVC) {
            throw new IllegalStateException("The sender selected HEVC, but HEVC reception is disabled");
        } else {
            throw new IllegalArgumentException("Unsupported video codec: " + codec);
        }
        synchronized (videoTransitionLock) {
            Pipeline previous;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (activeVideoPipeline == pipeline) {
                    return;
                }
                previous = invalidateVideoPipeline();
            }
            stopAndDrainVideoPipeline(previous);
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (activeVideoCodec != codec) {
                    videoParameterSets = null;
                }
                awaitingVideoKeyframe = true;
                videoGeneration++;
                videoQueue.clear();
                activeVideoPipeline = pipeline;
                activeVideoSrc = source;
                activeVideoCodec = codec;
            }
            try {
                pipeline.play();
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                if (activeVideoPipeline == pipeline) {
                        invalidateVideoPipeline();
                        activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                        awaitingVideoKeyframe = false;
                    }
                }
                pipeline.stop();
                throw failure;
            }
        }
        log.info("Started GStreamer {} video pipeline", codec);
    }

    private void deactivateVideoPipeline() {
        synchronized (videoTransitionLock) {
            Pipeline pipeline;
            synchronized (this) {
                pipeline = invalidateVideoPipeline();
                activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                detectedVideoFormat = null;
                videoParameterSets = null;
                awaitingVideoKeyframe = false;
            }
            StateChangeReturn stopped = stopAndDrainVideoPipeline(pipeline);
            if (!closed) {
                softwareFallbacks.entrySet().removeIf(entry -> {
                    Pipeline fallback = entry.getKey();
                    try {
                        StateChangeReturn state = fallback == pipeline ? stopped : fallback.stop();
                        if (state == StateChangeReturn.FAILURE) {
                            throw new IllegalStateException("Unable to stop the video pipeline");
                        }
                        GstDecoderSupport.restoreHardwareDecoder(fallback, entry.getValue());
                        return true;
                    } catch (RuntimeException error) {
                        log.warn("Unable to restore {} for the next video session", entry.getValue(), error);
                        return false;
                    }
                });
            }
        }
    }

    private void pushAudioBufferIfOpen(AppSrc source, byte[] bytes, long presentationTime, long duration) {
        synchronized (this) {
            if (closed) {
                return;
            }
            audioPushesInFlight++;
        }
        try {
            pushBuffer(source, bytes, presentationTime, duration, "audio");
        } finally {
            synchronized (this) {
                audioPushesInFlight--;
                notifyAll();
            }
        }
    }

    /**
     * Rebinds the currently selected video pipeline after its native sink
     * surface has moved to another window.
     */
    protected boolean restartActiveVideoPipeline() {
        synchronized (videoTransitionLock) {
            Pipeline previous;
            VideoStreamInfo.Codec codec;
            synchronized (this) {
                if (closed || activeVideoPipeline == null
                        || activeVideoCodec == VideoStreamInfo.Codec.UNKNOWN) {
                    return false;
                }
                codec = activeVideoCodec;
                previous = invalidateVideoPipeline();
            }
            stopAndDrainVideoPipeline(previous);
            activateVideoPipeline(codec);
            return true;
        }
    }

    protected Pipeline invalidateVideoPipeline() {
        Pipeline pipeline = activeVideoPipeline;
        activeVideoPipeline = null;
        activeVideoSrc = null;
        videoGeneration++;
        videoQueue.clear();
        return pipeline;
    }

    protected StateChangeReturn stopAndDrainVideoPipeline(Pipeline pipeline) {
        StateChangeReturn stopped = pipeline == null ? StateChangeReturn.SUCCESS : pipeline.stop();
        boolean interrupted = false;
        synchronized (this) {
            while (videoPushesInFlight > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return stopped;
    }

    private void stopAndClose(Element element) {
        element.stop();
        element.close();
    }

    private boolean containsRandomAccessUnit(byte[] accessUnit, VideoStreamInfo.Codec codec) {
        int offset = 0;
        while (offset + 5 <= accessUnit.length && hasStartCode(accessUnit, offset)) {
            int nalStart = offset + 4;
            int next = findStartCode(accessUnit, nalStart + 1);
            int nalType = codec == VideoStreamInfo.Codec.HEVC
                    ? (accessUnit[nalStart] >> 1) & 0x3f
                    : accessUnit[nalStart] & 0x1f;
            if (codec == VideoStreamInfo.Codec.H264 && nalType == 5) {
                return true;
            }
            if (codec == VideoStreamInfo.Codec.HEVC && nalType >= 16 && nalType <= 21) {
                return true;
            }
            offset = next >= 0 ? next : accessUnit.length;
        }
        return false;
    }

    private byte[] leadingParameterSets(byte[] accessUnit, VideoStreamInfo.Codec codec) {
        int offset = 0;
        int parameterSetsEnd = 0;
        while (offset + 5 <= accessUnit.length && hasStartCode(accessUnit, offset)) {
            int nalStart = offset + 4;
            int next = findStartCode(accessUnit, nalStart + 1);
            int nalEnd = next >= 0 ? next : accessUnit.length;
            int nalType = codec == VideoStreamInfo.Codec.HEVC
                    ? (accessUnit[nalStart] >> 1) & 0x3f
                    : accessUnit[nalStart] & 0x1f;
            boolean parameterSet = codec == VideoStreamInfo.Codec.HEVC
                    ? nalType >= 32 && nalType <= 34
                    : nalType == 7 || nalType == 8;
            if (!parameterSet) {
                break;
            }
            parameterSetsEnd = nalEnd;
            offset = nalEnd;
        }
        return parameterSetsEnd == 0 ? null : Arrays.copyOf(accessUnit, parameterSetsEnd);
    }

    private boolean hasStartCode(byte[] bytes, int offset) {
        return bytes.length - offset >= 4
                && bytes[offset] == 0 && bytes[offset + 1] == 0
                && bytes[offset + 2] == 0 && bytes[offset + 3] == 1;
    }

    private int findStartCode(byte[] bytes, int offset) {
        for (int index = offset; index <= bytes.length - 4; index++) {
            if (hasStartCode(bytes, index)) {
                return index;
            }
        }
        return -1;
    }

    private byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private long fixedPointDeltaNanos(long first, long current) {
        long delta = current - first;
        long seconds = delta >> 32;
        long fraction = delta & 0xffff_ffffL;
        return seconds * TimeUnit.SECONDS.toNanos(1) + ((fraction * TimeUnit.SECONDS.toNanos(1)) >>> 32);
    }

    private long unsignedRtpDelta(long first, long current) {
        return (current - first) & 0xffff_ffffL;
    }

    private record TimedMedia(byte[] bytes, long timestamp, AppSrc source, long generation) {

        private TimedMedia(byte[] bytes, long timestamp) {
            this(bytes, timestamp, null, 0);
        }
    }

    @Override
    public void close() {
        synchronized (videoTransitionLock) {
            Pipeline pipeline;
            synchronized (this) {
                closed = true;
                pipeline = invalidateVideoPipeline();
                activeVideoCodec = VideoStreamInfo.Codec.UNKNOWN;
                videoParameterSets = null;
            }
            stopAndDrainVideoPipeline(pipeline);
        }
        videoFeeder.interrupt();
        audioFeeder.interrupt();
        videoQueue.clear();
        audioQueue.clear();
        h264Pipeline.stop();
        if (hevcPipeline != null) {
            hevcPipeline.stop();
        }
        alacPipeline.stop();
        aacEldPipeline.stop();
        synchronized (hlsLock) {
            if (hlsPipeline != null) {
                stopAndClose(hlsPipeline);
                hlsPipeline = null;
            }
        }
        joinFeeder(videoFeeder);
        joinFeeder(audioFeeder);
        waitForAudioPushes();
        h264Pipeline.close();
        if (hevcPipeline != null) {
            hevcPipeline.close();
        }
        alacPipeline.close();
        aacEldPipeline.close();
    }

    private void waitForAudioPushes() {
        boolean interrupted = false;
        synchronized (this) {
            while (audioPushesInFlight > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinFeeder(Thread feeder) {
        try {
            feeder.join(TimeUnit.SECONDS.toMillis(3));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (feeder.isAlive()) {
            log.warn("GStreamer feeder thread did not stop within the shutdown timeout: {}", feeder.getName());
        }
    }
}

package wtf.nanoka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.TestAbortedException;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GstHighResolutionPipelineTest {

    @BeforeAll
    static void initialize() {
        try {
            GstPlayerUtils.configurePaths();
            Gst.init(Version.of(1, 10), "HighResolutionTest");
        } catch (Throwable error) {
            Assumptions.assumeTrue(false, "Native GStreamer is unavailable: " + error.getMessage());
        }
    }

    @Test
    void checksBothDimensionsAgainstHardwareCaps() {
        try (var caps = Caps.fromString("video/x-h264,width=(int)[1,4096],height=(int)[1,2304]")) {
            assertTrue(GstDecoderSupport.supportsResolution(caps, format(2796, 1290, VideoStreamInfo.Codec.H264)));
            assertFalse(GstDecoderSupport.supportsResolution(caps, format(1290, 2796, VideoStreamInfo.Codec.H264)));
            assertTrue(GstDecoderSupport.supportsResolution(caps, format(2732, 2048, VideoStreamInfo.Codec.H264)));
            assertFalse(GstDecoderSupport.supportsResolution(caps, format(2048, 2732, VideoStreamInfo.Codec.H264)));
            assertFalse(GstDecoderSupport.supportsResolution(caps, format(2796, 1290, VideoStreamInfo.Codec.HEVC)));
        }
    }

    @Test
    void detectsUnsupportedSizeOnTheAutomaticallySelectedD3dDecoder() throws Exception {
        var codec = VideoStreamInfo.Codec.H264;
        requirePlaybackPlugins(codec);
        String auto = GstPlayerDefault.createPipelineDescription("auto", "auto");
        try (var player = new TestPlayer(codec, withTestSink(auto))) {
            Pipeline pipeline = player.h264Pipeline;
            String hardware = pipeline.getElementByName("auto-video-decoder").getFactory().getName();
            Assumptions.assumeTrue(hardware.startsWith("d3d11") || hardware.startsWith("d3d12"),
                    "No automatic D3D decoder is available");
            VideoStreamInfo supportedFormat = format(1920, 1080, codec);
            assertFalse(GstDecoderSupport.needsSoftwareDecoder(pipeline, supportedFormat));
            player.onVideoFormatDetected(supportedFormat);

            // Force the caps rejection on any supported adapter while playing.
            player.onVideoFormatDetected(format(100_000, 100_000, codec));
            assertEquals("avdec_h264", pipeline.getElementByName("video-decoder").getFactory().getName());
            player.onVideoFormatDetected(supportedFormat);
            assertNull(pipeline.getElementByName("auto-video-decoder"), "keep software for the current session");
            sendVideo(player, 320, 240, codec);

            player.onVideoSrcDisconnect();
            player.onVideoFormatDetected(supportedFormat);
            assertEquals(hardware, pipeline.getElementByName("auto-video-decoder").getFactory().getName());
            assertNull(pipeline.getElementByName("video-decoder"));
        }
    }

    @Test
    void skipsWhenAnOptionalFactoryIsMissing() {
        assertThrows(TestAbortedException.class, () -> requireFactories("java-airplay-nonexistent-test-element"));
    }

    @ParameterizedTest
    @CsvSource({"2796,1290,H264", "1290,2796,H264", "2796,1290,HEVC", "1290,2796,HEVC", "3840,2160,HEVC",
            "2732,2048,H264", "2048,2732,H264", "2732,2048,HEVC", "2048,2732,HEVC", "2048,1536,H264"})
    void decodesHighResolutionAccessUnitsWithoutDownscaling(int width, int height,
                                                          VideoStreamInfo.Codec codec) throws Exception {
        requirePlaybackPlugins(codec);
        try (var player = new TestPlayer(codec)) {
            sendVideo(player, width, height, codec);
        }
    }

    @ParameterizedTest
    @EnumSource(value = VideoStreamInfo.Codec.class, names = {"H264", "HEVC"})
    void handlesIpadRotationWithoutReconnecting(VideoStreamInfo.Codec codec) throws Exception {
        // 4:3 rotation handling, kept at a moderate size: three consecutive 2732x2048
        // HEVC encodes in one JVM were observed to crash the native test process.
        requirePlaybackPlugins(codec);
        try (var player = new TestPlayer(codec)) {
            sendVideo(player, 1024, 768, codec);
            sendVideo(player, 768, 1024, codec);
            sendVideo(player, 1024, 768, codec);
        }
    }

    @Test
    void replacesDecoderWhileKeepingSourceAndOutput() {
        requireFactories("appsrc", "h264parse", "identity", "avdec_h264", "queue", "fakesink");
        try (var pipeline = (Pipeline) Gst.parseLaunch("appsrc name=h264-src ! h264parse name=video-parser "
                + "! identity name=auto-video-decoder ! queue name=video-output ! fakesink")) {
            Element source = pipeline.getElementByName("h264-src");
            Element output = pipeline.getElementByName("video-output");
            GstDecoderSupport.useSoftwareDecoder(pipeline, format(1290, 2796, VideoStreamInfo.Codec.H264));
            assertEquals("avdec_h264", pipeline.getElementByName("video-decoder").getFactory().getName());
            assertEquals(source, pipeline.getElementByName("h264-src"));
            assertEquals(output, pipeline.getElementByName("video-output"));
            assertTrue(pipeline.getElementByName("video-decoder").getStaticPad("src").isLinked());
        }
    }

    @Test
    void firstKeyframeDecodesWithParameterSetsFromAnEarlierDiscardedAccessUnit() throws Exception {
        var codec = VideoStreamInfo.Codec.H264;
        requirePlaybackPlugins(codec);
        try (var player = new TestPlayer(codec);
             var sender = (Pipeline) Gst.parseLaunch("videotestsrc num-buffers=1 ! "
                     + "video/x-raw,format=I420,width=320,height=240 ! "
                     + "x264enc tune=zerolatency byte-stream=true ! h264parse ! "
                     + "video/x-h264,stream-format=byte-stream,alignment=au ! appsink name=encoded sync=false")) {
            AppSink sink = (AppSink) sender.getElementByName("encoded");
            var encoded = new AtomicReference<byte[]>();
            var sampleReady = new CountDownLatch(1);
            sink.set("emit-signals", true);
            sink.connect((AppSink.NEW_SAMPLE) source -> {
                try (var sample = source.pullSample()) {
                    encoded.set(readBuffer(sample.getBuffer()));
                } finally {
                    sampleReady.countDown();
                }
                return FlowReturn.OK;
            });
            sender.play();
            try {
                assertTrue(sampleReady.await(5, TimeUnit.SECONDS), "encoder did not produce a keyframe");
                byte[] accessUnit = encoded.get();
                assertNotNull(accessUnit);
                byte[] parameterSets = selectH264Nals(accessUnit, 7, 8);
                byte[] keyframe = selectH264Nals(accessUnit, 5);
                assertTrue(parameterSets.length > 0);
                assertTrue(keyframe.length > 0);
                player.expectResolution(320, 240);
                player.onVideoFormatDetected(format(320, 240, codec));

                // The new configuration arrives with a predicted picture that the decoder must discard.
                byte[] preceding = Arrays.copyOf(parameterSets, parameterSets.length + 6);
                System.arraycopy(new byte[]{0, 0, 0, 1, 0x41, 1}, 0, preceding, parameterSets.length, 6);
                player.onVideo(preceding);
                player.onVideo(keyframe);

                assertTrue(player.decoded.await(5, TimeUnit.SECONDS), "no frame decoded: " + player.error);
                assertEquals("320x240", player.dimensions.get());
                assertNull(player.error.get());
            } finally {
                sender.stop();
            }
        }
    }

    private static byte[] selectH264Nals(byte[] bytes, int... types) {
        var selected = new ByteArrayOutputStream();
        int start = h264StartCode(bytes, 0);
        while (start >= 0) {
            int nal = start + (bytes[start + 2] == 1 ? 3 : 4);
            if (nal >= bytes.length) {
                break;
            }
            int next = h264StartCode(bytes, nal + 1);
            int type = bytes[nal] & 0x1f;
            if (Arrays.stream(types).anyMatch(candidate -> candidate == type)) {
                selected.writeBytes(new byte[]{0, 0, 0, 1});
                selected.write(bytes, nal, (next < 0 ? bytes.length : next) - nal);
            }
            start = next;
        }
        return selected.toByteArray();
    }

    private static int h264StartCode(byte[] bytes, int from) {
        for (int index = from; index + 2 < bytes.length; index++) {
            if (bytes[index] == 0 && bytes[index + 1] == 0 && (bytes[index + 2] == 1
                    || (index + 3 < bytes.length && bytes[index + 2] == 0 && bytes[index + 3] == 1))) {
                return index;
            }
        }
        return -1;
    }

    private static void requirePlaybackPlugins(VideoStreamInfo.Codec codec) {
        requireFactories("appsrc", "appsink", "videotestsrc", "fakesink", "queue",
                "avdec_alac", "avdec_aac", "audioconvert", "audioresample", "clocksync", "autoaudiosink");
        if (codec == VideoStreamInfo.Codec.H264) {
            requireFactories("x264enc", "h264parse", "avdec_h264");
        } else {
            requireFactories("x265enc", "h265parse", "avdec_h265");
        }
    }

    private static void requireFactories(String... names) {
        for (String name : names) {
            try {
                ElementFactory.find(name);
            } catch (IllegalArgumentException missingFactory) {
                Assumptions.assumeTrue(false, name + " is unavailable");
            }
        }
    }

    private void sendVideo(TestPlayer player, int width, int height, VideoStreamInfo.Codec codec) throws Exception {
        String token = codec == VideoStreamInfo.Codec.HEVC ? "h265" : "h264";
        String encoder = codec == VideoStreamInfo.Codec.HEVC ? "x265enc" : "x264enc";
        // Keep the native encoder from allocating a worker pool for every CPU
        // for these short streams, particularly when running the whole suite.
        String encoderThreads = codec == VideoStreamInfo.Codec.HEVC
                ? " option-string=pools=none:frame-threads=1" : " threads=2";
        player.expectResolution(width, height);
        try (var sender = (Pipeline) Gst.parseLaunch("videotestsrc num-buffers=8 pattern=ball ! "
                + "video/x-raw,format=I420,width=" + width + ",height=" + height + ",framerate=60/1 ! "
                + encoder + " tune=zerolatency speed-preset=ultrafast" + encoderThreads
                + " ! " + token + "parse ! video/x-"
                + token + ",stream-format=byte-stream,alignment=au ! "
                + "appsink name=encoded sync=false emit-signals=false max-buffers=8")) {
            var error = new AtomicReference<String>();
            CountDownLatch encoded = new CountDownLatch(1);
            sender.getBus().connect((Bus.ERROR) (source, code, message) -> {
                error.set(message);
                encoded.countDown();
            });
            sender.getBus().connect((Bus.EOS) source -> encoded.countDown());

            player.onVideoFormatDetected(format(width, height, codec));
            sender.play();

            // Pull from a separate thread. player.onVideo() may block on its bounded
            // queue; blocking inside a GStreamer streaming thread prevents the pipeline
            // from leaving PLAYING and makes stop() hang forever.
            Thread consumer = Thread.ofPlatform().daemon(true).name("test-encoder-pump").start(() -> {
                AppSink sink = (AppSink) sender.getElementByName("encoded");
                while (!Thread.currentThread().isInterrupted()) {
                    try (var sample = sink.pullSample()) {
                        if (sample == null) {
                            return;
                        }
                        player.onVideo(readBuffer(sample.getBuffer()));
                    } catch (RuntimeException e) {
                        return;
                    }
                }
            });
            try {
                assertTrue(player.decoded.await(20, TimeUnit.SECONDS), "No decoded frame: " + player.error);
                assertEquals(width + "x" + height, player.dimensions.get());
                assertTrue(encoded.await(20, TimeUnit.SECONDS), "Sender did not finish");
                assertNull(error.get());
                assertNull(player.error.get());
            } finally {
                consumer.interrupt();
                sender.stop();
            }
        }
    }

    private static byte[] readBuffer(Buffer buffer) {
        var mapped = buffer.map(false);
        try {
            byte[] bytes = new byte[mapped.remaining()];
            mapped.get(bytes);
            return bytes;
        } finally {
            buffer.unmap();
        }
    }

    private static String withTestSink(String pipeline) {
        return pipeline.substring(0, pipeline.indexOf(" ! clocksync "))
                + " ! fakesink name=decoded sync=false async=false";
    }

    private static VideoStreamInfo format(int width, int height, VideoStreamInfo.Codec codec) {
        return new VideoStreamInfo("high-resolution", width, height, 60, codec);
    }

    private static final class TestPlayer extends GstPlayer {
        private volatile CountDownLatch decoded = new CountDownLatch(1);
        private volatile String expectedDimensions;
        private final AtomicReference<String> dimensions = new AtomicReference<>();
        private final AtomicReference<String> error = new AtomicReference<>();

        private TestPlayer(VideoStreamInfo.Codec codec) {
            this(codec, withTestSink(GstPlayerDefault.createPipelineDescription(codec, "avdec_h264", "auto", "low-latency"))
                    .replace(" name=video-decoder ", " name=video-decoder max-threads=2 "));
        }

        private TestPlayer(VideoStreamInfo.Codec codec, String description) {
            super(60, 8, codec == VideoStreamInfo.Codec.H264 ? description : "appsrc name=h264-src ! fakesink",
                    codec == VideoStreamInfo.Codec.HEVC ? description : null);
            Pipeline pipeline = codec == VideoStreamInfo.Codec.HEVC ? hevcPipeline : h264Pipeline;
            Pad sink = pipeline.getElementByName("decoded").getStaticPad("sink");
            sink.addProbe(PadProbeType.BUFFER, (pad, probe) -> {
                try (Caps caps = pad.getCurrentCaps()) {
                    if (caps == null || caps.size() == 0) {
                        return PadProbeReturn.OK;
                    }
                    Structure structure = caps.getStructure(0);
                    Integer width = structure.getInteger("width");
                    Integer height = structure.getInteger("height");
                    if (width != null && height != null) {
                        String size = width + "x" + height;
                        dimensions.set(size);
                        if (size.equals(expectedDimensions)) {
                            decoded.countDown();
                        }
                    }
                } catch (RuntimeException ignored) {
                    // The pipeline may be flushing during teardown.
                }
                return PadProbeReturn.OK;
            });
        }

        private void expectResolution(int width, int height) {
            decoded = new CountDownLatch(1);
            expectedDimensions = width + "x" + height;
        }

        @Override
        protected void onVideoPlaybackError(String message) {
            error.set(message);
            decoded.countDown();
        }

        @Override
        protected Pipeline createH264Pipeline() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Pipeline createHevcPipeline() {
            throw new UnsupportedOperationException();
        }
    }
}

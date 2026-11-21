package wtf.nanoka.airplay.player.gstreamer;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Pipeline;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

/** Checks the actual stream, which is not necessarily the advertised display size. */
@Slf4j
final class GstDecoderSupport {

    private GstDecoderSupport() {
    }

    static boolean needsSoftwareDecoder(Pipeline pipeline, VideoStreamInfo format) {
        if (format.getWidth() <= 0 || format.getHeight() <= 0) {
            return false;
        }
        Element decoder = pipeline.getElementByName("auto-video-decoder");
        if (decoder == null) {
            return false;
        }
        String factory = decoder.getFactory().getName();
        // D3D sinks also accept system-memory video, so a software decoder can retain
        // the same native window. CUDA/Vulkan paths need their own memory converters.
        if (!factory.startsWith("d3d11") && !factory.startsWith("d3d12")) {
            return false;
        }
        try (var pad = decoder.getStaticPad("sink");
             var template = pad.getTemplate();
             var caps = template.getCaps()) {
            return !supportsResolution(caps, format);
        }
    }

    static boolean supportsResolution(Caps supported, VideoStreamInfo format) {
        String mediaType = format.getCodec() == VideoStreamInfo.Codec.HEVC ? "video/x-h265" : "video/x-h264";
        try (var requested = Caps.fromString(mediaType + ",width=(int)" + format.getWidth()
                + ",height=(int)" + format.getHeight());
             var intersection = supported.intersect(requested)) {
            return !intersection.isEmpty();
        }
    }

    /** Called only with the pipeline stopped and all Java pushes drained. */
    static String useSoftwareDecoder(Pipeline pipeline, VideoStreamInfo format) {
        Element previous = pipeline.getElementByName("auto-video-decoder");
        String previousFactory = previous.getFactory().getName();
        String software = format.getCodec() == VideoStreamInfo.Codec.HEVC ? "avdec_h265" : "avdec_h264";
        replaceDecoder(pipeline, previous, software, "video-decoder");
        log.warn("{} does not support the sender's {} {}x{} resolution; using {} without resizing the video",
                previousFactory, format.getCodec(), format.getWidth(), format.getHeight(), software);
        return previousFactory;
    }

    static void restoreHardwareDecoder(Pipeline pipeline, String factory) {
        replaceDecoder(pipeline, pipeline.getElementByName("video-decoder"), factory, "auto-video-decoder");
    }

    private static void replaceDecoder(Pipeline pipeline, Element previous, String factory, String name) {
        Element parser = pipeline.getElementByName("video-parser");
        Element output = pipeline.getElementByName("video-output");
        Element replacement = ElementFactory.make(factory, name);
        if (!pipeline.add(replacement)) {
            replacement.close();
            throw new IllegalStateException("Unable to add video decoder " + factory);
        }
        Element.unlinkMany(parser, previous, output);
        if (!Element.linkMany(parser, replacement, output)) {
            Element.unlinkMany(parser, replacement, output);
            pipeline.remove(replacement);
            replacement.close();
            Element.linkMany(parser, previous, output);
            throw new IllegalStateException("Unable to link video decoder " + factory);
        }
        pipeline.remove(previous);
        previous.close();
    }
}

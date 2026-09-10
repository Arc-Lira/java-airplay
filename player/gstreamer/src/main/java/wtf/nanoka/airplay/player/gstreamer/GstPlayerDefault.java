package wtf.nanoka.airplay.player.gstreamer;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import wtf.nanoka.airplay.lib.VideoStreamInfo;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class GstPlayerDefault extends GstPlayer {

    private static final Pattern DEVICE_DECODER =
            Pattern.compile("d3d(11|12)h26[45]device(\\d+)dec", Pattern.CASE_INSENSITIVE);

    public GstPlayerDefault() {
        this(60, 3, "auto", "auto", VideoRenderMode.BALANCED.propertyValue());
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder) {
        this(fps, videoQueueDepth, videoDecoder, "auto", VideoRenderMode.BALANCED.propertyValue());
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder, String gpuAdapter) {
        this(fps, videoQueueDepth, videoDecoder, gpuAdapter, VideoRenderMode.BALANCED.propertyValue());
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder, String gpuAdapter,
                            String renderMode) {
        this(fps, videoQueueDepth, videoDecoder, gpuAdapter, renderMode, false);
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder, String gpuAdapter,
                            String renderMode, boolean hevcEnabled) {
        this(fps, videoQueueDepth, videoDecoder, gpuAdapter, renderMode, hevcEnabled, false);
    }

    public GstPlayerDefault(int fps, int videoQueueDepth, String videoDecoder, String gpuAdapter,
                            String renderMode, boolean hevcEnabled, boolean aggressiveFrameDropping) {
        super(fps, videoQueueDepth,
                createPipelineDescription(VideoStreamInfo.Codec.H264, videoDecoder, gpuAdapter, renderMode),
                hevcEnabled
                        ? createPipelineDescription(VideoStreamInfo.Codec.HEVC, videoDecoder, gpuAdapter, renderMode)
                        : null,
                aggressiveFrameDropping);
    }

    @Override
    protected Pipeline createH264Pipeline() {
        return (Pipeline) Gst.parseLaunch(createPipelineDescription(
                "auto", "auto", VideoRenderMode.BALANCED.propertyValue()));
    }

    @Override
    protected Pipeline createHevcPipeline() {
        return (Pipeline) Gst.parseLaunch(createPipelineDescription(
                VideoStreamInfo.Codec.HEVC, "auto", "auto", VideoRenderMode.BALANCED.propertyValue()));
    }

    static String createPipelineDescription(String configuredDecoder, String configuredAdapter) {
        return createPipelineDescription(
                configuredDecoder, configuredAdapter, VideoRenderMode.BALANCED.propertyValue());
    }

    static String createPipelineDescription(String configuredDecoder, String configuredAdapter,
                                            String configuredRenderMode) {
        return createPipelineDescription(
                VideoStreamInfo.Codec.H264, configuredDecoder, configuredAdapter, configuredRenderMode);
    }

    static String createPipelineDescription(VideoStreamInfo.Codec codec, String configuredDecoder,
                                            String configuredAdapter, String configuredRenderMode) {
        if (codec != VideoStreamInfo.Codec.H264 && codec != VideoStreamInfo.Codec.HEVC) {
            throw new IllegalArgumentException("A concrete video codec is required for the GStreamer pipeline");
        }
        DecoderSelection selection = selectDecoder(codec, configuredDecoder, configuredAdapter);
        VideoRenderMode renderMode = VideoRenderMode.fromProperty(configuredRenderMode);
        String sink = selectSink(selection);
        String codecToken = codecToken(codec);
        String sourceName = codec == VideoStreamInfo.Codec.H264 ? "h264-src" : "hevc-src";
        return "appsrc name=" + sourceName + " ! " + codecToken + "parse config-interval=-1 "
                + "! " + selection.decoder() + " "
                + "! queue " + renderMode.queueProperties()
                + " ! clocksync " + renderMode.clockSyncProperties() + " ! " + sink;
    }

    public static void validateConfiguration(String videoDecoder, String gpuAdapter,
                                             String renderMode, boolean hevcEnabled) {
        validatePipeline(VideoStreamInfo.Codec.H264, videoDecoder, gpuAdapter, renderMode);
        if (hevcEnabled) {
            validatePipeline(VideoStreamInfo.Codec.HEVC, videoDecoder, gpuAdapter, renderMode);
        }
    }

    public static List<String> availableGpuAdapterIndices() {
        List<String> indices = new ArrayList<>();
        indices.add("auto");
        availableGpuAdapters().stream()
                .map(adapter -> Integer.toString(adapter.index()))
                .forEach(indices::add);
        return List.copyOf(indices);
    }

    public static List<GpuAdapter> availableGpuAdapters() {
        return GpuAdapterScanner.scan().stream()
                .filter(adapter -> elementExists(d3dDecoderForAdapter("d3d12", adapter.index()))
                        || elementExists(d3dDecoderForAdapter("d3d11", adapter.index())))
                .toList();
    }

    private static void validatePipeline(VideoStreamInfo.Codec codec, String videoDecoder,
                                         String gpuAdapter, String renderMode) {
        String description = createPipelineDescription(codec, videoDecoder, gpuAdapter, renderMode);
        try (Pipeline ignored = (Pipeline) Gst.parseLaunch(description)) {
            // Parsing also validates element properties that factory lookup alone cannot check.
        }
    }

    private static DecoderSelection selectDecoder(VideoStreamInfo.Codec codec, String configuredDecoder,
                                                  String configuredAdapter) {
        String decoder = decoderForCodec(normalize(configuredDecoder, "auto"), codec);
        String codecToken = codecToken(codec);
        String adapterValue = normalize(configuredAdapter, "auto");
        Integer requestedIndex = parseAdapterIndex(adapterValue);
        List<GpuAdapter> adapters = GpuAdapterScanner.scan();
        logAdapters(adapters);

        if (!"auto".equalsIgnoreCase(decoder)) {
            D3dDecoder requestedD3d = parseD3dDecoder(decoder, codec);
            if (requestedD3d != null) {
                return selectD3dDecoder(requestedD3d, requestedIndex, adapters, codec);
            }
            if (requestedIndex != null) {
                throw new IllegalArgumentException("player.gstreamer.gpuAdapter is only supported with "
                        + "D3D12 or D3D11 decoders; selected decoder is " + decoder);
            }
            requireElement(decoder, "video decoder");
            log.info("Using configured GStreamer {} decoder: {}", codec, decoder);
            return new DecoderSelection(decoder, null);
        }

        if (requestedIndex != null) {
            GpuAdapter adapter = requireAdapter(adapters, requestedIndex);
            for (String api : new String[]{"d3d12", "d3d11"}) {
                String candidate = d3dDecoderForAdapter(api, adapter.index(), codec);
                if (elementExists(candidate)) {
                    log.info("Selected {} for configured DXGI adapter {}", candidate, adapter.description());
                    return new DecoderSelection(candidate, adapter);
                }
            }
            throw new IllegalArgumentException("DXGI adapter " + requestedIndex
                    + " does not expose a GStreamer D3D12 or D3D11 " + codec + " decoder");
        }

        GpuAdapter d3d12Adapter = bestCapableAdapter(adapters, "d3d12", codec);
        if (d3d12Adapter != null) {
            String candidate = d3dDecoderForAdapter("d3d12", d3d12Adapter.index(), codec);
            log.info("Automatically selected {} on {}", candidate, d3d12Adapter.description());
            return new DecoderSelection(candidate, d3d12Adapter);
        }
        String baseD3d12Decoder = "d3d12" + codecToken + "dec";
        if (adapters.isEmpty() && elementExists(baseD3d12Decoder)) {
            log.info("Automatically selected {}; DXGI enumeration was unavailable", baseD3d12Decoder);
            return new DecoderSelection(baseD3d12Decoder, null);
        }
        String nvDecoder = "nv" + codecToken + "dec";
        if (elementExists(nvDecoder) && elementExists("cudadownload")) {
            log.info("Automatically selected GStreamer {} decoder: {}", codec, nvDecoder);
            return new DecoderSelection(nvDecoder, null);
        }

        GpuAdapter d3d11Adapter = bestCapableAdapter(adapters, "d3d11", codec);
        if (d3d11Adapter != null) {
            String candidate = d3dDecoderForAdapter("d3d11", d3d11Adapter.index(), codec);
            log.info("Automatically selected {} on {}", candidate, d3d11Adapter.description());
            return new DecoderSelection(candidate, d3d11Adapter);
        }
        String baseD3d11Decoder = "d3d11" + codecToken + "dec";
        if (adapters.isEmpty() && elementExists(baseD3d11Decoder)) {
            log.info("Automatically selected {}; DXGI enumeration was unavailable", baseD3d11Decoder);
            return new DecoderSelection(baseD3d11Decoder, null);
        }

        for (String candidate : new String[]{
                "vtdec_hw", "va" + codecToken + "dec",
                "v4l2sl" + codecToken + "dec", "v4l2" + codecToken + "dec",
                "vulkan" + codecToken + "dec"}) {
            if (decoderAvailable(candidate)) {
                log.info("Automatically selected GStreamer {} decoder: {}", codec, candidate);
                return new DecoderSelection(candidate, null);
            }
        }
        String softwareDecoder = "avdec_" + codecToken;
        requireElement(softwareDecoder, "software video decoder");
        log.info("No supported hardware {} decoder found; using {}", codec, softwareDecoder);
        return new DecoderSelection(softwareDecoder, null);
    }

    private static DecoderSelection selectD3dDecoder(D3dDecoder requested,
                                                       Integer configuredIndex,
                                                       List<GpuAdapter> adapters,
                                                       VideoStreamInfo.Codec codec) {
        Integer requestedIndex = requested.adapterIndex();
        if (requestedIndex != null && configuredIndex != null && !requestedIndex.equals(configuredIndex)) {
            throw new IllegalArgumentException("Decoder " + requested.configuredName() + " targets DXGI adapter "
                    + requestedIndex + " but player.gstreamer.gpuAdapter=" + configuredIndex);
        }
        Integer selectedIndex = configuredIndex != null ? configuredIndex : requestedIndex;
        if (selectedIndex != null) {
            GpuAdapter adapter = requireAdapter(adapters, selectedIndex);
            String candidate = d3dDecoderForAdapter(requested.api(), adapter.index(), codec);
            requireElement(candidate, requested.api().toUpperCase() + " " + codec
                    + " decoder for DXGI adapter " + selectedIndex);
            log.info("Using {} on configured DXGI adapter {}", candidate, adapter.description());
            return new DecoderSelection(candidate, adapter);
        }

        GpuAdapter adapter = bestCapableAdapter(adapters, requested.api(), codec);
        if (adapter != null) {
            String candidate = d3dDecoderForAdapter(requested.api(), adapter.index(), codec);
            log.info("Automatically selected {} on {}", candidate, adapter.description());
            return new DecoderSelection(candidate, adapter);
        }
        String baseDecoder = requested.api() + codecToken(codec) + "dec";
        requireElement(baseDecoder, requested.api().toUpperCase() + " " + codec + " decoder");
        log.info("Using configured GStreamer {} decoder: {}", codec, baseDecoder);
        return new DecoderSelection(baseDecoder, null);
    }

    private static String selectSink(DecoderSelection selection) {
        String decoder = selection.decoder();
        if (isD3dDecoder(decoder, "d3d12")) {
            return requireD3dSink("d3d12videosink", decoder, selection.adapter());
        }
        if (isD3dDecoder(decoder, "d3d11")) {
            return requireD3dSink("d3d11videosink", decoder, selection.adapter());
        }
        if (decoder.startsWith("nvh26") && decoder.endsWith("dec")) {
            requireElement("cudadownload", "NVDEC download element");
            return "cudadownload ! autovideosink sync=false";
        }
        if (decoder.startsWith("vulkanh26") && decoder.endsWith("dec")) {
            requireElement("vulkancolorconvert", "Vulkan color conversion element");
            requireElement("vulkansink", "Vulkan video sink");
            return "vulkancolorconvert ! vulkansink sync=false";
        }
        return "autovideosink sync=false";
    }

    private static String requireD3dSink(String sink, String decoder, GpuAdapter adapter) {
        requireElement(sink, "native video sink for " + decoder);
        String adapterProperty = adapter == null ? "" : " adapter=" + adapter.index();
        String closedWindowProperty = "d3d12videosink".equals(sink) ? " error-on-closed=false" : "";
        return sink + adapterProperty + closedWindowProperty + " sync=false";
    }

    static String d3dDecoderForAdapter(String api, int adapterIndex) {
        return d3dDecoderForAdapter(api, adapterIndex, VideoStreamInfo.Codec.H264);
    }

    static String d3dDecoderForAdapter(String api, int adapterIndex, VideoStreamInfo.Codec codec) {
        if (adapterIndex < 0) {
            throw new IllegalArgumentException("DXGI adapter index must not be negative");
        }
        return adapterIndex == 0
                ? api + codecToken(codec) + "dec"
                : api + codecToken(codec) + "device" + adapterIndex + "dec";
    }

    private static GpuAdapter bestCapableAdapter(List<GpuAdapter> adapters, String api,
                                                 VideoStreamInfo.Codec codec) {
        return adapters.stream()
                .filter(GpuAdapter::isHardware)
                .filter(adapter -> elementExists(d3dDecoderForAdapter(api, adapter.index(), codec)))
                .max(Comparator.comparingLong(GpuAdapter::dedicatedVideoMemory)
                        .thenComparingInt(adapter -> -adapter.index()))
                .orElse(null);
    }

    private static GpuAdapter requireAdapter(List<GpuAdapter> adapters, int index) {
        return adapters.stream()
                .filter(adapter -> adapter.index() == index)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DXGI GPU adapter " + index
                        + " was not found. Available adapters: " + availableAdapters(adapters)));
    }

    private static Integer parseAdapterIndex(String configuredAdapter) {
        if ("auto".equalsIgnoreCase(configuredAdapter)) {
            return null;
        }
        try {
            int index = Integer.parseInt(configuredAdapter);
            if (index < 0) {
                throw new NumberFormatException();
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("GPU adapter must be auto or a non-negative DXGI index", e);
        }
    }

    private static D3dDecoder parseD3dDecoder(String decoder, VideoStreamInfo.Codec codec) {
        String normalized = decoder.toLowerCase();
        String codecToken = codecToken(codec);
        if (("d3d12" + codecToken + "dec").equals(normalized)) {
            return new D3dDecoder("d3d12", null, decoder);
        }
        if (("d3d11" + codecToken + "dec").equals(normalized)) {
            return new D3dDecoder("d3d11", null, decoder);
        }
        Matcher matcher = DEVICE_DECODER.matcher(normalized);
        if (matcher.matches()) {
            return new D3dDecoder("d3d" + matcher.group(1), Integer.parseInt(matcher.group(2)), decoder);
        }
        return null;
    }

    private static boolean isD3dDecoder(String decoder, String api) {
        return (decoder.startsWith(api + "h264") || decoder.startsWith(api + "h265"))
                && decoder.endsWith("dec");
    }

    private static boolean decoderAvailable(String decoder) {
        if (!elementExists(decoder)) {
            return false;
        }
        if (decoder.startsWith("vulkanh26") && decoder.endsWith("dec")) {
            return elementExists("vulkancolorconvert") && elementExists("vulkansink");
        }
        return true;
    }

    private static boolean elementExists(String element) {
        try {
            return ElementFactory.find(element) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireElement(String element, String purpose) {
        if (!elementExists(element)) {
            throw new IllegalArgumentException("GStreamer " + purpose + " is not installed: " + element);
        }
    }

    private static void logAdapters(List<GpuAdapter> adapters) {
        if (!adapters.isEmpty()) {
            log.info("Detected DXGI GPU adapters: {}",
                    adapters.stream().map(GpuAdapter::description).collect(Collectors.joining(", ")));
        }
    }

    private static String availableAdapters(List<GpuAdapter> adapters) {
        if (adapters.isEmpty()) {
            return "none (DXGI enumeration unavailable)";
        }
        return adapters.stream().map(adapter -> Integer.toString(adapter.index())).collect(Collectors.joining(", "));
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String decoderForCodec(String configuredDecoder, VideoStreamInfo.Codec codec) {
        if (codec == VideoStreamInfo.Codec.H264 || "auto".equalsIgnoreCase(configuredDecoder)
                || "vtdec_hw".equalsIgnoreCase(configuredDecoder)) {
            return configuredDecoder;
        }
        String normalized = configuredDecoder.toLowerCase();
        if (normalized.contains("h264")) {
            return normalized.replace("h264", "h265");
        }
        if (normalized.contains("h265")) {
            return normalized;
        }
        throw new IllegalArgumentException("Cannot derive an HEVC decoder from configured H.264 decoder: "
                + configuredDecoder);
    }

    private static String codecToken(VideoStreamInfo.Codec codec) {
        return codec == VideoStreamInfo.Codec.HEVC ? "h265" : "h264";
    }

    private record DecoderSelection(String decoder, GpuAdapter adapter) {
    }

    private record D3dDecoder(String api, Integer adapterIndex, String configuredName) {
    }
}

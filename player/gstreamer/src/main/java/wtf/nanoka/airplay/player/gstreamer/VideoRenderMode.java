package wtf.nanoka.airplay.player.gstreamer;

import java.util.Locale;

public enum VideoRenderMode {
    BALANCED("balanced", "Balanced", "renderMode.balanced", 6, "no", true, true),
    QUALITY("quality", "Quality", "renderMode.quality", 8, "no", true, false),
    LOW_LATENCY("low-latency", "Low latency", "renderMode.lowLatency", 1, "downstream", false, false);

    private final String propertyValue;
    private final String label;
    private final String labelKey;
    private final int decodedQueueDepth;
    private final String queueLeakMode;
    private final boolean synchronizedPresentation;
    private final boolean qos;

    VideoRenderMode(String propertyValue, String label, String labelKey, int decodedQueueDepth,
                    String queueLeakMode, boolean synchronizedPresentation, boolean qos) {
        this.propertyValue = propertyValue;
        this.label = label;
        this.labelKey = labelKey;
        this.decodedQueueDepth = decodedQueueDepth;
        this.queueLeakMode = queueLeakMode;
        this.synchronizedPresentation = synchronizedPresentation;
        this.qos = qos;
    }

    public String propertyValue() {
        return propertyValue;
    }

    public String label() {
        return label;
    }

    /** Key into the UI message bundle; use with an I18n instance for localized labels. */
    public String labelKey() {
        return labelKey;
    }

    String queueProperties() {
        return "max-size-buffers=" + decodedQueueDepth
                + " max-size-bytes=0 max-size-time=0 leaky=" + queueLeakMode;
    }

    String clockSyncProperties() {
        return "sync=" + synchronizedPresentation + " qos=" + qos
                + " sync-to-first=" + synchronizedPresentation;
    }

    public static VideoRenderMode fromProperty(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (VideoRenderMode mode : values()) {
            if (mode.propertyValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Video render mode must be balanced, quality, or low-latency");
    }
}

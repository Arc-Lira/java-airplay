package wtf.nanoka.airplay.server;

import lombok.Data;
import wtf.nanoka.airplay.lib.NetworkInterfaceSelector;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

@Data
public class AirPlayConfig {
    private String serverName = "Java AirPlay";
    private String width = "1920";
    private String height = "1080";
    private String fps = "60";
    private String identityFile = Path.of(System.getProperty("user.home"), ".java-airplay", "identity.key").toString();
    private int audioJitterPackets = 3;
    private boolean requirePairing = true;
    private boolean hevc = false;
    private String networkInterface = "auto";

    public void validate() {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("airplay.serverName must not be blank");
        }
        if (serverName.getBytes(StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("airplay.serverName must not exceed 63 UTF-8 bytes");
        }
        resolve(width, "airplay.width", 320, 7680, 1920);
        resolve(height, "airplay.height", 240, 4320, 1080);
        resolve(fps, "airplay.fps", 1, 120, 60);
        if (identityFile == null || identityFile.isBlank()) {
            throw new IllegalArgumentException("airplay.identityFile must not be blank");
        }
        if (audioJitterPackets < 1 || audioJitterPackets > 64) {
            throw new IllegalArgumentException("airplay.audioJitterPackets must be between 1 and 64");
        }
        NetworkInterfaceSelector.requireConfigured(networkInterface);
    }

    public int getResolvedWidth() {
        return resolve(width, "airplay.width", 320, 7680, 1920);
    }

    public int getResolvedHeight() {
        return resolve(height, "airplay.height", 240, 4320, 1080);
    }

    public int getResolvedFps() {
        return resolve(fps, "airplay.fps", 1, 120, 60);
    }

    private int resolve(String configuredValue, String property, int minimum, int maximum, int fallback) {
        if (configuredValue == null || configuredValue.isBlank()) {
            throw new IllegalArgumentException(property + " must be 'auto' or an integer");
        }
        if ("auto".equalsIgnoreCase(configuredValue.trim())) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(configuredValue.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be 'auto' or an integer", e);
        }
    }

}

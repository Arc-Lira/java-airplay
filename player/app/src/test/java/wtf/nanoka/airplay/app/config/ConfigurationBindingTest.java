package wtf.nanoka.airplay.app.config;

import wtf.nanoka.airplay.server.AirPlayConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationBindingTest {

    @Test
    void bindsEveryAirPlayAndPlayerPropertyFromPackagedConfiguration() throws Exception {
        Properties properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        properties.forEach((key, value) -> values.put(key.toString(), value));
        Binder binder = new Binder(new MapConfigurationPropertySource(values));

        AirPlayConfig airPlay = binder.bind("airplay", Bindable.of(AirPlayConfig.class))
                .orElseThrow(() -> new IllegalStateException("AirPlay properties were not bound"));
        PlayerProperties player = binder.bind("player", Bindable.of(PlayerProperties.class))
                .orElseThrow(() -> new IllegalStateException("Player properties were not bound"));

        assertEquals("Java AirPlay", airPlay.getServerName());
        assertEquals("1920", airPlay.getWidth());
        assertEquals("1080", airPlay.getHeight());
        assertEquals("60", airPlay.getFps());
        assertEquals(1920, airPlay.getResolvedWidth());
        assertEquals(1080, airPlay.getResolvedHeight());
        assertEquals(60, airPlay.getResolvedFps());
        assertTrue(airPlay.getIdentityFile().contains("identity.key"));
        assertEquals(3, airPlay.getAudioJitterPackets());
        assertTrue(airPlay.isRequirePairing());
        assertEquals(false, airPlay.isHevc());
        assertEquals("auto", airPlay.getNetworkInterface());

        assertEquals("gstreamer", player.getImplementation());
        assertTrue(player.getTray().isEnabled());
        assertTrue(player.getGstreamer().isSwing());
        assertEquals("auto", player.getGstreamer().getVideoDecoder());
        assertEquals("auto", player.getGstreamer().getGpuAdapter());
        assertEquals(2, player.getGstreamer().getVideoQueueDepth());
        assertEquals(false, player.getGstreamer().isAggressiveFrameDropping());
        assertEquals("balanced", player.getGstreamer().getRenderMode());

        airPlay.validate();
        player.validate();
    }

    @Test
    void acceptsAutoDisplayCapabilities() {
        var airPlay = new AirPlayConfig();
        airPlay.setWidth("auto");
        airPlay.setHeight("auto");
        airPlay.setFps("auto");

        airPlay.validate();

        assertTrue(airPlay.getResolvedWidth() >= 320);
        assertTrue(airPlay.getResolvedHeight() >= 240);
        assertTrue(airPlay.getResolvedFps() >= 1);
    }

    @Test
    void acceptsAnyNonNegativeGpuAdapterIndex() {
        var player = new PlayerProperties();
        player.getGstreamer().setGpuAdapter("4");

        player.validate();
    }

    @Test
    void rejectsInvalidGpuAdapterIndex() {
        var player = new PlayerProperties();
        player.getGstreamer().setGpuAdapter("-1");
        assertThrows(IllegalArgumentException.class, player::validate);

        player.getGstreamer().setGpuAdapter("not-a-number");
        assertThrows(IllegalArgumentException.class, player::validate);
    }

    @Test
    void rejectsBlankNetworkInterface() {
        var airPlay = new AirPlayConfig();
        airPlay.setNetworkInterface(" ");
        assertThrows(IllegalArgumentException.class, airPlay::validate);
    }

    @Test
    void rejectsReceiverNamesThatCannotFitInOneDnsLabel() {
        var airPlay = new AirPlayConfig();
        airPlay.setServerName("x".repeat(64));

        assertThrows(IllegalArgumentException.class, airPlay::validate);
    }
}

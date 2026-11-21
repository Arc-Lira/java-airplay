package wtf.nanoka.airplay.app.config;

import wtf.nanoka.airplay.app.ReceiverInstanceLock;
import wtf.nanoka.airplay.app.menu.SystemTrayMenu;
import wtf.nanoka.airplay.player.ffmpeg.FFmpegPlayer;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerDefault;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerSwing;
import wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings;
import wtf.nanoka.airplay.player.gstreamer.ui.SettingsController;
import wtf.nanoka.airplay.player.h264dump.H264Dump;
import wtf.nanoka.airplay.player.vlc.VlcPlayer;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayConsumer;
import wtf.nanoka.airplay.server.AirPlayServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Supplier;

@Configuration
@EnableConfigurationProperties(PlayerProperties.class)
public class PlayerConfig {

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "gstreamer")
    public AirPlayConsumer gstreamer(AirPlayConfig airPlayConfig,
                                     PlayerProperties playerProperties,
                                     SettingsController settingsController) {
        playerProperties.validate();
        var gstreamer = playerProperties.getGstreamer();
        return gstreamer.isSwing() ? new GstPlayerSwing(
                airPlayConfig.getResolvedFps(),
                gstreamer.getVideoQueueDepth(),
                gstreamer.getVideoDecoder(),
                gstreamer.getGpuAdapter(),
                gstreamer.getRenderMode(),
                airPlayConfig.isHevc(),
                gstreamer.isAggressiveFrameDropping(),
                new GstPlayerSwing.WindowOptions(
                        airPlayConfig.getServerName(),
                        airPlayConfig.getResolvedWidth(),
                        airPlayConfig.getResolvedHeight(),
                        airPlayConfig.isRequirePairing(),
                        playerProperties.getTray().isEnabled(),
                        receiverSettings(airPlayConfig, playerProperties),
                        settingsController))
                : new GstPlayerDefault(airPlayConfig.getResolvedFps(), gstreamer.getVideoQueueDepth(),
                gstreamer.getVideoDecoder(), gstreamer.getGpuAdapter(), gstreamer.getRenderMode(),
                airPlayConfig.isHevc(), gstreamer.isAggressiveFrameDropping());
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "h264-dump", matchIfMissing = true)
    public AirPlayConsumer h264dump(AirPlayConfig airPlayConfig) throws Exception {
        requireH264Only(airPlayConfig, "h264-dump");
        return new H264Dump();
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "vlc")
    public AirPlayConsumer vlc(AirPlayConfig airPlayConfig) {
        requireH264Only(airPlayConfig, "VLC");
        return new VlcPlayer();
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "ffmpeg")
    public AirPlayConsumer ffmpeg(AirPlayConfig airPlayConfig) {
        requireH264Only(airPlayConfig, "FFmpeg");
        return new FFmpegPlayer();
    }

    @Bean
    @ConfigurationProperties(prefix = "airplay")
    public AirPlayConfig airPlayConfig() {
        return new AirPlayConfig();
    }

    @Bean
    public SettingsController settingsController(ApplicationContext context, ReceiverInstanceLock instanceLock) {
        return new UserSettingsController(context, instanceLock);
    }

    @Bean
    @ConditionalOnProperty(value = "player.tray.enabled", havingValue = "true", matchIfMissing = true)
    public SystemTrayMenu systemTrayMenu(ApplicationContext context, AirPlayConsumer airPlayConsumer) {
        GstPlayerSwing swing = airPlayConsumer instanceof GstPlayerSwing player ? player : null;
        Runnable showWindow = swing != null ? swing::showWindow : null;
        Runnable showDetachedVideo = swing != null ? swing::showDetachedVideo : null;
        Runnable toggleVideoFullscreen = swing != null
                ? swing::toggleVideoFullscreen : null;
        Runnable toggleLanguage = swing != null ? swing::toggleLanguage : null;
        Supplier<SystemTrayMenu.Labels> labels = swing != null ? () -> new SystemTrayMenu.Labels(
                swing.localized("tray.open"),
                swing.localized("tray.videoWindow"),
                swing.localized("tray.videoWindow.fullscreen"),
                swing.localized("tray.language"),
                swing.languageLabel(),
                swing.localized("tray.quit"),
                "Java AirPlay") : null;
        Runnable trayReady = swing != null
                ? () -> swing.setCloseToTray(true)
                : null;
        Runnable trayUnavailable = swing != null
                ? () -> swing.setCloseToTray(false)
                : null;
        SystemTrayMenu tray = new SystemTrayMenu(context, showWindow, showDetachedVideo, toggleVideoFullscreen,
                toggleLanguage, labels, trayReady, trayUnavailable);
        if (swing != null) {
            swing.addLanguageChangeListener(tray::updateLabels);
        }
        return tray;
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig,
                                       AirPlayConsumer airPlayConsumer) {
        return new AirPlayServer(airPlayConfig, airPlayConsumer);
    }

    private ReceiverSettings receiverSettings(AirPlayConfig airPlayConfig, PlayerProperties playerProperties) {
        var gstreamer = playerProperties.getGstreamer();
        return new ReceiverSettings(
                airPlayConfig.getServerName(),
                airPlayConfig.getWidth(),
                airPlayConfig.getHeight(),
                airPlayConfig.getFps(),
                airPlayConfig.getIdentityFile(),
                airPlayConfig.getAudioJitterPackets(),
                airPlayConfig.isRequirePairing(),
                airPlayConfig.isHevc(),
                airPlayConfig.getNetworkInterface(),
                playerProperties.getImplementation(),
                playerProperties.getTray().isEnabled(),
                gstreamer.isSwing(),
                gstreamer.getVideoDecoder(),
                gstreamer.getGpuAdapter(),
                gstreamer.getVideoQueueDepth(),
                gstreamer.isAggressiveFrameDropping(),
                gstreamer.getRenderMode());
    }

    private void requireH264Only(AirPlayConfig airPlayConfig, String player) {
        if (airPlayConfig.isHevc()) {
            throw new IllegalArgumentException(player + " player does not support AirPlay HEVC reception");
        }
    }
}

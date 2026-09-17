package wtf.nanoka.airplay.server;

import wtf.nanoka.airplay.lib.AirPlayBonjour;
import wtf.nanoka.airplay.lib.AirPlayIdentity;
import wtf.nanoka.airplay.server.internal.ControlServer;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

@Slf4j
public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final ControlServer controlServer;
    private boolean started;

    public AirPlayServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer) {
        try {
            airPlayConfig.validate();
            log.info("AirPlay display capability: {}x{} @ {} fps (configured: {}x{} @ {})",
                    airPlayConfig.getResolvedWidth(), airPlayConfig.getResolvedHeight(), airPlayConfig.getResolvedFps(),
                    airPlayConfig.getWidth(), airPlayConfig.getHeight(), airPlayConfig.getFps());
            var identity = AirPlayIdentity.loadOrCreate(Path.of(airPlayConfig.getIdentityFile()));
            airPlayBonjour = new AirPlayBonjour(
                    airPlayConfig.getServerName(), identity, airPlayConfig.isHevc(),
                    airPlayConfig.getNetworkInterface());
            controlServer = new ControlServer(airPlayConfig, airPlayConsumer, identity);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize persistent AirPlay identity", e);
        }
    }

    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        try {
            controlServer.start();
            airPlayBonjour.start(controlServer.getPort());
            started = true;
        } catch (Exception e) {
            try {
                stopComponents();
            } catch (RuntimeException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    public synchronized void stop() {
        started = false;
        stopComponents();
    }

    private void stopComponents() {
        RuntimeException failure = null;
        try {
            airPlayBonjour.stop();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            controlServer.stop();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

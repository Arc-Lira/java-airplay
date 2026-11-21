package wtf.nanoka.airplay.app;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import wtf.nanoka.airplay.app.config.RestartReadiness;
import wtf.nanoka.airplay.app.config.UserSettingsController;
import wtf.nanoka.airplay.app.config.UserSettingsPropertySource;
import wtf.nanoka.airplay.lib.AirPlayBonjour.RegistrationException;
import wtf.nanoka.airplay.player.gstreamer.ui.I18n;
import wtf.nanoka.airplay.server.AirPlayServer;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.AWTError;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;

@Slf4j
@RequiredArgsConstructor
@SpringBootApplication
public class PlayerApp implements ApplicationRunner {

    private static final String VALIDATION_PROPERTY = "java-airplay.validation";

    private final AirPlayServer airPlayServer;
    private final Environment environment;
    private final ReceiverInstanceLock instanceLock;
    private Thread activationWatcher;

    public static void main(String[] args) {
        ReceiverInstanceLock lock = new ReceiverInstanceLock();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(PlayerApp.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .initializers(new UserSettingsPropertySource(), applicationContext -> {
                    try {
                        if (!canStart(applicationContext.getEnvironment(), lock)) {
                            InstanceActivation.request(lock.directory());
                            System.exit(0);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Unable to acquire the receiver instance lock", e);
                    }
                    applicationContext.getBeanFactory().registerSingleton("receiverInstanceLock", lock);
                })
                .run(args);
        if (isValidationRun(context.getEnvironment())) {
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }

    /**
     * Only one receiver may serve a profile directory. A second instance would
     * advertise the same AirPlay name, jmDNS would rename it to
     * "Java AirPlay (2)", and the phone would keep SRV records pointing at a
     * port that the first instance no longer serves.
     */
    static boolean canStart(Environment environment, ReceiverInstanceLock instanceLock) throws IOException {
        if (isValidationRun(environment) || instanceLock.acquire()) {
            return true;
        }
        String owner = instanceLock.ownerPid().stream()
                .mapToObj(pid -> " (PID " + pid + ")")
                .findFirst()
                .orElse("");
        log.warn("Refusing to start a second receiver instance{}; only one process may advertise "
                + "AirPlay on this computer.", owner);
        return false;
    }

    private static boolean isValidationRun(Environment environment) {
        return environment.getProperty(VALIDATION_PROPERTY, Boolean.class, false);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (isValidationRun(environment)) {
            return;
        }
        try {
            airPlayServer.start();
        } catch (RegistrationException failure) {
            reportBonjourFailure(failure);
            throw failure;
        }
        activationWatcher = InstanceActivation.startWatcher(instanceLock, airPlayServer::showWindow);
    }

    private void reportBonjourFailure(RegistrationException failure) {
        try {
            var i18n = new I18n();
            String message = i18n.tr("startup.bonjour.help")
                    .formatted(UserSettingsPropertySource.settingsFile(environment)) + "\n\n" + failure.getMessage();
            log.error("{}", message);
            if (signalRestart(message) || GraphicsEnvironment.isHeadless()) {
                return;
            }
            boolean trayEnabled = environment.getProperty("player.tray.enabled", Boolean.class, true);
            boolean swingPlayer = "gstreamer".equalsIgnoreCase(environment.getProperty("player.implementation"))
                    && environment.getProperty("player.gstreamer.swing", Boolean.class, true);
            if (trayEnabled || swingPlayer) {
                Runnable dialog = () -> {
                    var details = new JTextArea(message, 12, 60);
                    details.setEditable(false);
                    details.setLineWrap(true);
                    details.setWrapStyleWord(true);
                    details.setCaretPosition(0);
                    JOptionPane.showMessageDialog(null, new JScrollPane(details),
                            i18n.tr("startup.bonjour.title"), JOptionPane.ERROR_MESSAGE);
                };
                if (SwingUtilities.isEventDispatchThread()) {
                    dialog.run();
                } else {
                    SwingUtilities.invokeAndWait(dialog);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(e);
        } catch (Exception | AWTError e) {
            failure.addSuppressed(e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applicationReady() throws IOException {
        if (!isValidationRun(environment)) {
            signalRestart(null);
        }
    }

    private boolean signalRestart(String failureMessage) throws IOException {
        String readyFile = System.getenv(UserSettingsController.RESTART_READY_FILE_ENV);
        String token = System.getenv(UserSettingsController.RESTART_READY_TOKEN_ENV);
        if (readyFile == null || readyFile.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        Path path = Path.of(readyFile);
        long pid = ProcessHandle.current().pid();
        if (failureMessage == null) {
            RestartReadiness.signal(path, token, pid);
        } else {
            RestartReadiness.signalFailure(path, token, pid, failureMessage);
        }
        return true;
    }

    @PreDestroy
    private void preDestroy() {
        if (activationWatcher != null) {
            activationWatcher.interrupt();
        }
        airPlayServer.stop();
        instanceLock.release();
    }
}

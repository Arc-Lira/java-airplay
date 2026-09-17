package wtf.nanoka.airplay.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.player.gstreamer.ui.ReceiverSettings;
import wtf.nanoka.airplay.player.gstreamer.ui.SettingsController;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerDefault;
import wtf.nanoka.airplay.server.AirPlayConfig;
import wtf.nanoka.airplay.server.AirPlayServer;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

@Slf4j
public final class UserSettingsController implements SettingsController {

    public static final String RESTART_READY_FILE_ENV = "JAVA_AIRPLAY_RESTART_READY_FILE";
    public static final String RESTART_READY_TOKEN_ENV = "JAVA_AIRPLAY_RESTART_READY_TOKEN";

    private final ApplicationContext applicationContext;
    private final Path settingsFile;
    private final boolean validateNativeRuntime;
    private final AtomicBoolean restarting = new AtomicBoolean();
    private final AtomicBoolean restartUnlockDeferred = new AtomicBoolean();

    UserSettingsController(ApplicationContext applicationContext) {
        this(applicationContext,
                UserSettingsPropertySource.settingsFile(applicationContext.getEnvironment()), true);
    }

    UserSettingsController(ApplicationContext applicationContext, Path settingsFile) {
        this(applicationContext, settingsFile, false);
    }

    private UserSettingsController(ApplicationContext applicationContext, Path settingsFile,
                                   boolean validateNativeRuntime) {
        this.applicationContext = applicationContext;
        this.settingsFile = settingsFile.toAbsolutePath().normalize();
        this.validateNativeRuntime = validateNativeRuntime;
    }

    @Override
    public Path settingsFile() {
        return settingsFile;
    }

    @Override
    public Result save(ReceiverSettings settings) {
        try {
            validate(settings);
            Properties properties = new Properties();
            if (Files.isRegularFile(settingsFile)) {
                try (var reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
            putSettings(properties, settings);
            writeAtomically(properties);
            log.info("Saved receiver settings to {}", settingsFile);
            return Result.success("Saved. Restart required.");
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        } catch (IOException e) {
            log.warn("Unable to save receiver settings to {}: {}", settingsFile, e.getMessage());
            return Result.failure("Could not save settings: " + e.getMessage());
        } catch (LinkageError e) {
            log.warn("Unable to validate native receiver settings: {}", e.getMessage());
            return Result.failure("Could not validate native settings: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Unable to validate receiver settings: {}", e.getMessage());
            return Result.failure("Could not validate settings: " + e.getMessage());
        }
    }

    @Override
    public Result restart() {
        if (!restarting.compareAndSet(false, true)) {
            return Result.failure("A restart is already in progress.");
        }
        restartUnlockDeferred.set(false);
        final List<String> restartCommand;
        try {
            restartCommand = currentRestartCommand();
        } catch (RuntimeException | IOException e) {
            restarting.set(false);
            return Result.failure("Settings were saved, but the restart command could not be created: "
                    + e.getMessage());
        }
        AirPlayServer airPlayServer = null;
        boolean serverStopAttempted = false;
        Path readyFile = null;
        Process replacement = null;
        String readyToken = null;
        try {
            Result validation = validateRestart(restartCommand);
            if (!validation.success()) {
                if (!restartUnlockDeferred.get()) {
                    restarting.set(false);
                }
                return validation;
            }
            airPlayServer = applicationContext.getBean(AirPlayServer.class);
            serverStopAttempted = true;
            airPlayServer.stop();

            readyFile = Files.createTempFile("java-airplay-restart-", ".ready");
            Files.deleteIfExists(readyFile);
            readyToken = UUID.randomUUID().toString();
            ProcessBuilder processBuilder = processBuilder(restartCommand);
            processBuilder.environment().remove(RESTART_READY_FILE_ENV);
            processBuilder.environment().remove(RESTART_READY_TOKEN_ENV);
            processBuilder.environment().put(RESTART_READY_FILE_ENV, readyFile.toString());
            processBuilder.environment().put(RESTART_READY_TOKEN_ENV, readyToken);
            replacement = processBuilder.start();
            if (!waitForReady(replacement, readyFile, readyToken, 30, TimeUnit.SECONDS)) {
                if (!terminateAndWait(replacement)) {
                    deferRestartUnlock(replacement);
                    return Result.failure("Settings were saved, but the replacement process could not be terminated. "
                            + "The previous receiver was not restored to avoid a port conflict.");
                }
                return restoreAfterFailedRestart(airPlayServer, serverStopAttempted,
                        "the replacement process did not become ready");
            }
        } catch (Exception e) {
            boolean replacementTerminated = replacement == null || terminateAndWait(replacement);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Unable to restart Java AirPlay: {}", e.getMessage());
            if (!replacementTerminated) {
                deferRestartUnlock(replacement);
                return Result.failure("Settings were saved, but restart failed and the replacement process "
                        + "could not be terminated. The previous receiver was not restored.");
            }
            return restoreAfterFailedRestart(airPlayServer, serverStopAttempted,
                    "restart failed: " + e.getMessage());
        } finally {
            if (readyFile != null) {
                try {
                    Files.deleteIfExists(readyFile);
                } catch (IOException ignored) {
                    log.debug("Unable to remove restart readiness file: {}", readyFile);
                }
            }
        }

        Thread.ofPlatform().name("java-airplay-shutdown").start(() -> {
            SpringApplication.exit(applicationContext, () -> 0);
            System.exit(0);
        });
        return Result.success("Restarting...");
    }

    private Result validateRestart(List<String> restartCommand) throws IOException {
        List<String> validationCommand = withoutApplicationProperties(restartCommand,
                "java-airplay.validation",
                "player.gstreamer.swing", "player.tray.enabled");
        validationCommand.add("--java-airplay.validation=true");
        validationCommand.add("--player.gstreamer.swing=false");
        validationCommand.add("--player.tray.enabled=false");
        ProcessBuilder processBuilder = processBuilder(validationCommand);
        processBuilder.environment().remove(RESTART_READY_FILE_ENV);
        processBuilder.environment().remove(RESTART_READY_TOKEN_ENV);
        Process validation = null;
        try {
            validation = processBuilder.start();
            if (!validation.waitFor(20, TimeUnit.SECONDS)) {
                boolean terminated = terminateAndWait(validation);
                if (!terminated) {
                    deferRestartUnlock(validation);
                }
                return Result.failure(terminated
                        ? "Settings were saved, but restart validation timed out."
                        : "Settings were saved, but restart validation timed out and could not be terminated.");
            }
            if (validation.exitValue() != 0) {
                return Result.failure("Settings were saved, but the new configuration failed startup validation.");
            }
            return Result.success("Validated");
        } catch (InterruptedException e) {
            boolean terminated = validation == null || terminateAndWait(validation);
            if (!terminated) {
                deferRestartUnlock(validation);
            }
            Thread.currentThread().interrupt();
            return Result.failure(terminated
                    ? "Settings were saved, but restart validation was interrupted."
                    : "Settings were saved, but interrupted restart validation could not be terminated.");
        }
    }

    private void deferRestartUnlock(Process process) {
        restartUnlockDeferred.set(true);
        process.onExit().whenComplete((exited, error) -> {
            restartUnlockDeferred.set(false);
            restarting.set(false);
        });
    }

    private boolean waitForReady(Process process, Path readyFile, String token, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        long readySince = Long.MIN_VALUE;
        while (System.nanoTime() < deadline) {
            if (RestartReadiness.matches(readyFile, token, process.pid()) && process.isAlive()) {
                if (readySince == Long.MIN_VALUE) {
                    readySince = System.nanoTime();
                } else if (System.nanoTime() - readySince >= TimeUnit.MILLISECONDS.toNanos(500)) {
                    return true;
                }
            }
            if (!process.isAlive()) {
                return false;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean terminateAndWait(Process process) {
        if (!process.isAlive()) {
            return true;
        }
        boolean interrupted = false;
        process.destroy();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try {
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !process.isAlive();
    }

    private Result restoreAfterFailedRestart(AirPlayServer airPlayServer, boolean serverStopAttempted,
                                             String reason) {
        String suffix = "";
        if (serverStopAttempted && airPlayServer != null) {
            try {
                airPlayServer.start();
                suffix = " The previous receiver was restored.";
            } catch (Exception restoreError) {
                log.error("Unable to restore the AirPlay server after restart failure", restoreError);
                suffix = " The previous receiver could not be restored.";
            }
        }
        restarting.set(false);
        return Result.failure("Settings were saved, but " + reason + "." + suffix);
    }

    private List<String> withoutApplicationProperties(List<String> command, String... properties) {
        List<String> filtered = new ArrayList<>(command.size());
        outer:
        for (String argument : command) {
            for (String property : properties) {
                if (argument.startsWith("--" + property + "=")) {
                    continue outer;
                }
            }
            filtered.add(argument);
        }
        return filtered;
    }

    private ProcessBuilder processBuilder(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of(System.getProperty("user.dir")).toFile());
        processBuilder.inheritIO();
        return processBuilder;
    }

    List<String> currentRestartCommand() throws IOException {
        String executable = ProcessHandle.current().info().command()
                .filter(value -> !value.isBlank())
                .orElseGet(this::defaultJavaExecutable);
        List<String> command = new ArrayList<>();
        command.add(executable);
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> !isDebugAgentArgument(argument))
                .forEach(command::add);

        String classPath = System.getProperty("java.class.path", "").trim();
        if (classPath.isEmpty()) {
            throw new IllegalStateException("the current Java classpath is unavailable");
        }
        Path executableJar = executableJar(classPath);
        if (executableJar != null) {
            command.add("-jar");
            command.add(executableJar.toString());
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(PlayerApp.class.getName());
        }
        String[] sourceArguments = applicationContext.getBean(ApplicationArguments.class).getSourceArgs();
        command.addAll(List.of(sourceArguments));
        return List.copyOf(command);
    }

    private Path executableJar(String classPath) throws IOException {
        String[] entries = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        if (entries.length != 1 || entries[0].isBlank()) {
            return null;
        }
        final Path candidate;
        try {
            candidate = Path.of(entries[0]).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (!Files.isRegularFile(candidate) || !candidate.getFileName().toString().endsWith(".jar")) {
            return null;
        }
        try (JarFile jar = new JarFile(candidate.toFile())) {
            if (jar.getManifest() == null
                    || jar.getManifest().getMainAttributes().getValue("Main-Class") == null) {
                return null;
            }
        }
        return candidate;
    }

    private String defaultJavaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("windows");
        Path executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("Java executable was not found at " + executable);
        }
        return executable.toString();
    }

    private boolean isDebugAgentArgument(String argument) {
        String normalized = argument.toLowerCase();
        return normalized.startsWith("-agentlib:jdwp")
                || normalized.contains("idea_rt.jar")
                || normalized.contains("debugger-agent")
                || normalized.startsWith("@");
    }

    private void validate(ReceiverSettings settings) {
        var airPlay = new AirPlayConfig();
        airPlay.setServerName(settings.serverName().trim());
        airPlay.setWidth(settings.width().trim());
        airPlay.setHeight(settings.height().trim());
        airPlay.setFps(settings.fps().trim());
        airPlay.setIdentityFile(settings.identityFile().trim());
        airPlay.setAudioJitterPackets(settings.audioJitterPackets());
        airPlay.setRequirePairing(settings.requirePairing());
        airPlay.setHevc(settings.hevcEnabled());
        airPlay.setNetworkInterface(settings.networkInterface().trim());
        airPlay.validate();
        validateIdentityPath(airPlay.getIdentityFile());

        var player = new PlayerProperties();
        player.setImplementation(settings.playerImplementation().trim());
        player.getTray().setEnabled(settings.trayEnabled());
        player.getGstreamer().setSwing(settings.swingEnabled());
        player.getGstreamer().setVideoDecoder(settings.videoDecoder().trim());
        player.getGstreamer().setGpuAdapter(settings.gpuAdapter().trim());
        player.getGstreamer().setVideoQueueDepth(settings.videoQueueDepth());
        player.getGstreamer().setAggressiveFrameDropping(settings.aggressiveFrameDropping());
        player.getGstreamer().setRenderMode(settings.renderMode().trim());
        player.validate();

        if (settings.hevcEnabled() && !"gstreamer".equalsIgnoreCase(settings.playerImplementation().trim())) {
            throw new IllegalArgumentException("HEVC reception requires the GStreamer player");
        }
        if (validateNativeRuntime && "gstreamer".equalsIgnoreCase(settings.playerImplementation().trim())) {
            GstPlayerDefault.validateConfiguration(
                    settings.videoDecoder(), settings.gpuAdapter(), settings.renderMode(), settings.hevcEnabled());
        }
    }

    private void validateIdentityPath(String configuredPath) {
        final Path identityPath;
        try {
            identityPath = Path.of(configuredPath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("airplay.identityFile is not a valid path", e);
        }
        try {
            if (Files.exists(identityPath)) {
                if (!Files.isRegularFile(identityPath) || !Files.isReadable(identityPath)
                        || Files.size(identityPath) != 32) {
                    throw new IllegalArgumentException(
                            "airplay.identityFile must be a readable 32-byte identity file");
                }
                return;
            }
            Path parent = identityPath.getParent();
            while (parent != null && !Files.exists(parent)) {
                parent = parent.getParent();
            }
            if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
                throw new IllegalArgumentException("airplay.identityFile parent directory is not writable");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to inspect airplay.identityFile: " + e.getMessage(), e);
        }
    }

    private void putSettings(Properties properties, ReceiverSettings settings) {
        properties.setProperty("airplay.serverName", settings.serverName().trim());
        properties.setProperty("airplay.width", settings.width().trim());
        properties.setProperty("airplay.height", settings.height().trim());
        properties.setProperty("airplay.fps", settings.fps().trim());
        properties.setProperty("airplay.identityFile", settings.identityFile().trim());
        properties.setProperty("airplay.audioJitterPackets", Integer.toString(settings.audioJitterPackets()));
        properties.setProperty("airplay.requirePairing", Boolean.toString(settings.requirePairing()));
        properties.setProperty("airplay.hevc", Boolean.toString(settings.hevcEnabled()));
        properties.setProperty("airplay.networkInterface", settings.networkInterface().trim());
        properties.setProperty("player.implementation", settings.playerImplementation().trim());
        properties.setProperty("player.tray.enabled", Boolean.toString(settings.trayEnabled()));
        properties.setProperty("player.gstreamer.swing", Boolean.toString(settings.swingEnabled()));
        properties.setProperty("player.gstreamer.videoDecoder", settings.videoDecoder().trim());
        properties.setProperty("player.gstreamer.gpuAdapter", settings.gpuAdapter().trim());
        properties.setProperty("player.gstreamer.videoQueueDepth", Integer.toString(settings.videoQueueDepth()));
        properties.setProperty("player.gstreamer.aggressiveFrameDropping",
                Boolean.toString(settings.aggressiveFrameDropping()));
        properties.setProperty("player.gstreamer.renderMode", settings.renderMode().trim());
    }

    private void writeAtomically(Properties properties) throws IOException {
        Path directory = settingsFile.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "application", ".properties.tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "Java AirPlay user settings");
            }
            try {
                Files.move(temporary, settingsFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

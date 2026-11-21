package wtf.nanoka.airplay.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Slf4j
public final class UserSettingsPropertySource
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String PROPERTY_SOURCE_NAME = "javaAirPlayUserSettings";
    private static final String SETTINGS_FILE_PROPERTY = "java-airplay.settings-file";

    public static Path settingsFile() {
        return settingsFile(null);
    }

    public static Path settingsFile(Environment environment) {
        String configured = environment == null
                ? System.getProperty(SETTINGS_FILE_PROPERTY)
                : environment.getProperty(SETTINGS_FILE_PROPERTY);
        Path path = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".java-airplay", "application.properties")
                : Path.of(configured);
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException("java-airplay.settings-file must name a file, not a filesystem root");
        }
        return normalized;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Path settingsFile = settingsFile(applicationContext.getEnvironment());
        if (!Files.isRegularFile(settingsFile)) {
            return;
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Java AirPlay user settings: " + settingsFile, e);
        }

        var propertySources = applicationContext.getEnvironment().getPropertySources();
        propertySources.remove(PROPERTY_SOURCE_NAME);
        var source = new PropertiesPropertySource(PROPERTY_SOURCE_NAME, properties);
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else if (propertySources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, source);
        } else if (propertySources.contains(SimpleCommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(SimpleCommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME, source);
        } else {
            propertySources.addFirst(source);
        }
        log.info("Loaded desktop settings from {}", settingsFile);
    }
}

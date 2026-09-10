/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2021 Neil C Smith - Codelerity Ltd.
 *
 * Copying and distribution of this file, with or without modification,
 * are permitted in any medium without royalty provided the copyright
 * notice and this notice are preserved. This file is offered as-is,
 * without any warranty.
 *
 */
package wtf.nanoka.airplay.player.gstreamer;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Utility methods for use in examples.
 */
class GstPlayerUtils {

    private GstPlayerUtils() {
    }

    /**
     * Configures paths to the GStreamer libraries. On Windows queries various
     * GStreamer environment variables, and then sets up the PATH environment
     * variable. On macOS, adds the location to jna.library.path (macOS binaries
     * link to each other). On both, the gstreamer.path system property can be
     * used to override. On Linux, assumes GStreamer is in the path already.
     */
    static void configurePaths() {
        if (Platform.isWindows()) {
            String gstPath = System.getProperty("gstreamer.path", findWindowsLocation());
            if (!gstPath.isEmpty()) {
                String systemPath = System.getenv("PATH");
                if (systemPath == null || systemPath.trim().isEmpty()) {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath);
                } else {
                    Kernel32.INSTANCE.SetEnvironmentVariable("PATH", gstPath
                            + File.pathSeparator + systemPath);
                }
            }
        } else if (Platform.isMac()) {
            prependJnaLibraryPath(System.getProperty("gstreamer.path", findMacLocation()));
        } else {
            prependJnaLibraryPath(System.getProperty("gstreamer.path", ""));
        }
    }

    private static void prependJnaLibraryPath(String gstPath) {
        if (gstPath == null || gstPath.isBlank()) {
            return;
        }
        String jnaPath = System.getProperty("jna.library.path", "").trim();
        if (jnaPath.isEmpty()) {
            System.setProperty("jna.library.path", gstPath);
        } else {
            System.setProperty("jna.library.path", jnaPath + File.pathSeparator + gstPath);
        }
    }

    /**
     * Query over a stream of possible environment variables for GStreamer
     * location, filtering on the first non-null result, and adding \bin\ to the
     * value.
     *
     * @return location or empty string
     */
    static String findWindowsLocation() {
        String architecture = windowsGStreamerArchitecture();
        if (architecture == null) {
            return "";
        }
        String environmentSuffix = architecture.toUpperCase();
        Stream<String> configuredLocations = Stream.of(
                        "GSTREAMER_1_0_ROOT_MSVC_" + environmentSuffix,
                        "GSTREAMER_1_0_ROOT_" + environmentSuffix)
                .map(System::getenv)
                .filter(p -> p != null && !p.isBlank());
        if ("X86_64".equals(environmentSuffix)) {
            configuredLocations = Stream.concat(configuredLocations,
                    Stream.of("GSTREAMER_1_0_ROOT_MINGW_X86_64").map(System::getenv)
                            .filter(p -> p != null && !p.isBlank()));
        }
        String installDirectory = "msvc_" + architecture;
        Stream<String> standardLocations = Stream.of(
                        System.getenv("LOCALAPPDATA") == null ? null
                                : Path.of(System.getenv("LOCALAPPDATA"), "Programs", "gstreamer", "1.0",
                                installDirectory).toString(),
                        System.getenv("ProgramFiles") == null ? null
                                : Path.of(System.getenv("ProgramFiles"), "gstreamer", "1.0",
                                installDirectory).toString())
                .filter(p -> p != null && !p.isBlank());
        return Stream.concat(configuredLocations, standardLocations)
                .map(Path::of)
                .filter(Files::isDirectory)
                .map(path -> path.resolve("bin").toString() + File.separator)
                .findFirst().orElse("");
    }

    static String findMacLocation() {
        return Stream.of(
                        "/Library/Frameworks/GStreamer.framework/Libraries/",
                        "/opt/homebrew/lib",
                        "/usr/local/lib")
                .filter(path -> Files.isDirectory(Path.of(path)))
                .findFirst()
                .orElse("");
    }

    static String windowsGStreamerArchitecture() {
        if (!Platform.is64Bit()) {
            return null;
        }
        if (Platform.isARM()) {
            return "arm64";
        }
        if (Platform.isIntel()) {
            return "x86_64";
        }
        return null;
    }
}

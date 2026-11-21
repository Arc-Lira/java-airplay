package wtf.nanoka.airplay.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class RestartReadiness {

    private RestartReadiness() {
    }

    public static void signal(Path readyPath, String token, long processId) throws IOException {
        writeMarker(readyPath, processId, "READY " + token + " " + processId + "\n");
    }

    public static void signalFailure(Path readyPath, String token, long processId, String diagnostic) throws IOException {
        String reason = diagnostic == null || diagnostic.isBlank() ? "Startup failed." : diagnostic.strip();
        writeMarker(readyPath, processId, "FAILED " + token + " " + processId + "\n" + reason + "\n");
    }

    private static void writeMarker(Path readyPath, long processId, String marker) throws IOException {
        Path normalized = readyPath.toAbsolutePath().normalize();
        Path temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp-" + processId);
        try {
            Files.writeString(temporary, marker, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record Status(boolean ready, String failure) {
        static final Status READY = new Status(true, null);
        static final Status PENDING = new Status(false, null);
    }

    static Status read(Path readyPath, String token, long processId) {
        try {
            String marker = Files.readString(readyPath, StandardCharsets.UTF_8).strip();
            String identity = token + " " + processId;
            if (marker.equals("READY " + identity)) {
                return Status.READY;
            }
            String failureHeader = "FAILED " + identity + "\n";
            if (marker.startsWith(failureHeader)) {
                return new Status(false, marker.substring(failureHeader.length()));
            }
        } catch (IOException e) {
            // No complete marker from this process yet.
        }
        return Status.PENDING;
    }
}

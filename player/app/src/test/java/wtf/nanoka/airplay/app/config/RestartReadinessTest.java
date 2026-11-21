package wtf.nanoka.airplay.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestartReadinessTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyTheCompleteExpectedTokenAndProcess() throws Exception {
        Path marker = temporaryDirectory.resolve("restart.ready");

        RestartReadiness.signal(marker, "expected-token", 1234);

        assertEquals("READY expected-token 1234\n", Files.readString(marker, StandardCharsets.US_ASCII));
        assertEquals(RestartReadiness.Status.READY, RestartReadiness.read(marker, "expected-token", 1234));
        assertEquals(RestartReadiness.Status.PENDING, RestartReadiness.read(marker, "wrong-token", 1234));
        assertEquals(RestartReadiness.Status.PENDING, RestartReadiness.read(marker, "expected-token", 4321));

        Files.writeString(marker, "READY expected-token", StandardCharsets.US_ASCII);
        assertEquals(RestartReadiness.Status.PENDING, RestartReadiness.read(marker, "expected-token", 1234));
    }

    @Test
    void readsTheDiagnosticOnlyForTheMatchingFailedReplacement() throws Exception {
        Path marker = temporaryDirectory.resolve("restart.ready");
        String diagnostic = "Bonjour registration failed on 以太网\nNo eligible IPv4 address.";
        RestartReadiness.signalFailure(marker, "token-1", 12345, diagnostic);

        assertEquals(new RestartReadiness.Status(false, diagnostic), RestartReadiness.read(marker, "token-1", 12345));
        assertEquals(RestartReadiness.Status.PENDING, RestartReadiness.read(marker, "token", 12345));
        assertEquals(RestartReadiness.Status.PENDING, RestartReadiness.read(marker, "token-1", 1234));
    }
}

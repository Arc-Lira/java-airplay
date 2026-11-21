package wtf.nanoka.airplay.lib;

import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AirPlayBonjourTest {

    private static final NetworkInterfaceSelector.Adapter WIFI =
            new NetworkInterfaceSelector.Adapter("wlan0", "Intel Wi-Fi", "192.168.1.12", false, false, true);
    private static final NetworkInterfaceSelector.Adapter ETHERNET =
            new NetworkInterfaceSelector.Adapter("eth0", "Realtek Ethernet", "192.168.1.20", false, false, true);
    private static final NetworkInterfaceSelector.Adapter SECONDARY_WIFI_ADDRESS =
            new NetworkInterfaceSelector.Adapter(WIFI.name(), WIFI.displayName(), "192.168.1.11", false, false, true);
    private static final NetworkInterfaceSelector.Adapter VIRTUAL =
            new NetworkInterfaceSelector.Adapter("eth1", "vEthernet", "172.29.0.1", true, false, true);

    @Test
    void autoTriesTheNextAdapterAfterABindFailureAndKeepsOneRegistration() throws Exception {
        var resolution = NetworkInterfaceSelector.resolve("auto", List.of(VIRTUAL, ETHERNET, WIFI), WIFI.ipv4());
        var bonjour = new AirPlayBonjour("Receiver", AirPlayIdentity.random(), false, "auto");
        var attempts = new ArrayList<NetworkInterfaceSelector.Adapter>();
        var closes = new AtomicInteger();
        AirPlayBonjour.AdapterRegistration open = (adapter, port) -> {
            attempts.add(adapter);
            assertEquals(55005, port);
            if (adapter.equals(WIFI)) {
                throw new BindException("Cannot assign requested address: bind");
            }
            return closes::incrementAndGet;
        };

        try {
            bonjour.start(55005, resolution, open);
            bonjour.start(55005, resolution, open);
            assertEquals(List.of(WIFI, ETHERNET), attempts);
            assertEquals(0, closes.get());
        } finally {
            bonjour.stop();
        }
        bonjour.stop();
        assertEquals(1, closes.get());
    }

    @Test
    void retriesASecondAddressOnTheSameAdapterWithoutTryingAnotherNetwork() throws Exception {
        var resolution = NetworkInterfaceSelector.resolve(WIFI.name(),
                List.of(WIFI, SECONDARY_WIFI_ADDRESS, ETHERNET), ETHERNET.ipv4());
        var bonjour = new AirPlayBonjour("Receiver", AirPlayIdentity.random(), false, WIFI.name());
        var attempts = new ArrayList<NetworkInterfaceSelector.Adapter>();

        try {
            bonjour.start(55005, resolution, (adapter, port) -> {
                attempts.add(adapter);
                if (adapter.equals(WIFI)) {
                    throw new BindException("Cannot assign requested address: bind");
                }
                return () -> {};
            });
            assertEquals(List.of(WIFI, SECONDARY_WIFI_ADDRESS), attempts);
        } finally {
            bonjour.stop();
        }
    }

    @Test
    void showsEachFailedAddressAndPreservesItsBindErrorWhenNoAdapterWorks() {
        var resolution = NetworkInterfaceSelector.resolve("auto", List.of(ETHERNET, WIFI), WIFI.ipv4());
        var bonjour = new AirPlayBonjour("Receiver", AirPlayIdentity.random(), false, "auto");
        var wifiError = new BindException("Cannot assign requested address: bind");
        var ethernetError = new BindException("Address already in use: bind");

        var failure = assertThrows(AirPlayBonjour.RegistrationException.class,
                () -> bonjour.start(55005, resolution,
                        (adapter, port) -> { throw adapter.equals(WIFI) ? wifiError : ethernetError; }));

        assertTrue(failure.getMessage().contains("UDP 5353"));
        assertTrue(failure.getMessage().contains(WIFI.description() + ": " + wifiError));
        assertTrue(failure.getMessage().contains(ETHERNET.description() + ": " + ethernetError));
        assertEquals(2, failure.getSuppressed().length);
        assertSame(wifiError, failure.getSuppressed()[0].getCause());
        assertSame(ethernetError, failure.getSuppressed()[1].getCause());
    }

    @Test
    void reportsWhenThereIsNoActiveIpv4Adapter() {
        var resolution = NetworkInterfaceSelector.resolve("auto", List.of(), null);
        var bonjour = new AirPlayBonjour("Receiver", AirPlayIdentity.random(), false, "auto");
        var failure = assertThrows(AirPlayBonjour.RegistrationException.class,
                () -> bonjour.start(55005, resolution, (adapter, port) -> fail("no adapter should be tried")));
        assertTrue(failure.getMessage().contains("no active network adapter with an IPv4 address"));
    }

    @Test
    void aManuallySelectedAdapterDoesNotFailOverToAnotherNetwork() {
        var resolution = NetworkInterfaceSelector.resolve(WIFI.name(), List.of(ETHERNET, WIFI), ETHERNET.ipv4());
        var bonjour = new AirPlayBonjour("Receiver", AirPlayIdentity.random(), false, WIFI.name());
        var attempts = new ArrayList<NetworkInterfaceSelector.Adapter>();
        assertThrows(AirPlayBonjour.RegistrationException.class, () -> bonjour.start(55005, resolution, (adapter, port) -> {
            attempts.add(adapter);
            throw new BindException("Cannot assign requested address: bind");
        }));
        assertEquals(List.of(WIFI), attempts);
    }
}

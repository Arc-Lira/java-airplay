package wtf.nanoka.airplay.lib;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkInterfaceSelectorTest {

    private static final NetworkInterfaceSelector.Adapter WIFI =
            new NetworkInterfaceSelector.Adapter("wlan0", "Intel Wi-Fi 6 AX201", "192.168.0.100", false, false, true);
    private static final NetworkInterfaceSelector.Adapter ETHERNET =
            new NetworkInterfaceSelector.Adapter("eth0", "Realtek PCIe GbE", "192.168.0.50", false, false, true);
    private static final NetworkInterfaceSelector.Adapter WSL =
            new NetworkInterfaceSelector.Adapter("eth1", "vEthernet (WSL)", "172.29.176.1", true, false, true);
    private static final NetworkInterfaceSelector.Adapter APIPA =
            new NetworkInterfaceSelector.Adapter("eth2", "Ethernet 2", "169.254.12.34", false, true, false);

    @Test
    void autoPrefersPhysicalLanOverHyperVSwitch() {
        var resolution = NetworkInterfaceSelector.resolve("auto", List.of(WSL, WIFI), null);

        assertEquals(WIFI, resolution.chosen());
        assertTrue(resolution.automatic());
        assertFalse(resolution.usedVirtualFallback());
        assertTrue(resolution.warning().isEmpty());
    }

    @Test
    void autoPrefersTheDefaultRouteWhenTwoLanAdaptersExist() {
        var resolution = NetworkInterfaceSelector.resolve(
                "auto", List.of(WIFI, ETHERNET), ETHERNET.ipv4());

        assertEquals(ETHERNET, resolution.chosen());
        assertTrue(resolution.warning().orElseThrow().contains("Multiple LAN interfaces"));
        assertTrue(resolution.warning().orElseThrow().contains(ETHERNET.description()));
    }

    @Test
    void autoSkipsApipaAddresses() {
        var resolution = NetworkInterfaceSelector.resolve("auto", List.of(APIPA, WIFI), null);

        assertEquals(WIFI, resolution.chosen());
    }

    @Test
    void autoFallsBackToVirtualAdapterWhenNothingElseExists() {
        var resolution = NetworkInterfaceSelector.resolve("auto", List.of(WSL), null);

        assertEquals(WSL, resolution.chosen());
        assertTrue(resolution.usedVirtualFallback());
        assertTrue(resolution.warning().orElseThrow().contains("virtual adapter"));
    }

    @Test
    void matchesConfiguredDisplayNameIpv4OrJavaName() {
        List<NetworkInterfaceSelector.Adapter> adapters = List.of(WIFI, WSL);

        assertEquals(WIFI, NetworkInterfaceSelector.resolve(WIFI.displayName(), adapters, null).chosen());
        assertEquals(WIFI, NetworkInterfaceSelector.resolve(WIFI.ipv4(), adapters, null).chosen());
        assertEquals(WSL, NetworkInterfaceSelector.resolve("eth1", adapters, null).chosen());
        assertFalse(NetworkInterfaceSelector.resolve(WSL.displayName(), adapters, null).automatic());
    }

    @Test
    void missingConfiguredAdapterFallsBackToAuto() {
        var resolution = NetworkInterfaceSelector.resolve("USB Ethernet", List.of(WIFI, WSL), null);

        assertEquals(WIFI, resolution.chosen());
        assertTrue(resolution.configuredMissing());
        assertTrue(resolution.warning().orElseThrow().contains("was not found"));
    }

    @Test
    void treatsVethernetNamesAsVirtual() {
        assertTrue(NetworkInterfaceSelector.isVirtualName("vEthernet (Default Switch)"));
        assertTrue(NetworkInterfaceSelector.isVirtualName("vEthernet (WSL)"));
        assertFalse(NetworkInterfaceSelector.isVirtualName("Intel(R) Ethernet Connection"));
        assertFalse(NetworkInterfaceSelector.isVirtualName("Wi-Fi"));
    }

    @Test
    void rejectsBlankConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> NetworkInterfaceSelector.requireConfigured("  "));
        assertEquals("auto", NetworkInterfaceSelector.normalize(null));
    }
}

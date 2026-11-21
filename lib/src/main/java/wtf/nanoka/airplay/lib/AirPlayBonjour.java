package wtf.nanoka.airplay.lib;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers airplay/airtunes service mdns
 */
@Slf4j
@RequiredArgsConstructor
public class AirPlayBonjour {

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";

    private final String serverName;
    private final AirPlayIdentity identity;
    private final boolean hevcEnabled;
    private final String networkInterface;

    private Closeable registration;

    public synchronized void start(int airTunesPort) throws RegistrationException {
        if (registration == null) {
            start(airTunesPort, NetworkInterfaceSelector.resolve(networkInterface), this::registerOn);
        }
    }

    synchronized void start(int airTunesPort, NetworkInterfaceSelector.Resolution resolution,
                            AdapterRegistration open) throws RegistrationException {
        if (registration != null) {
            return;
        }
        resolution.warning().ifPresent(log::warn);
        if (resolution.candidates().isEmpty()) {
            throw new RegistrationException("Bonjour could not start: no active network adapter with an IPv4 address was found.",
                    List.of());
        }

        List<IOException> failures = new ArrayList<>();
        for (var adapter : resolution.candidates()) {
            try {
                registration = open.register(adapter, airTunesPort);
            } catch (IOException | SecurityException e) {
                String detail = adapter.description() + ": " + e;
                log.warn("Unable to register AirPlay services on {}", detail);
                failures.add(new IOException(detail, e));
                continue;
            }

            if (!failures.isEmpty()) {
                if (adapter.virtual()) {
                    log.warn("Bonjour registered on virtual adapter {} after {} failed attempt(s). "
                                    + "iPhone may be unable to reach this adapter.",
                            adapter.description(), failures.size());
                } else {
                    log.info("Bonjour selected {} after {} failed registration attempt(s)",
                            adapter.description(), failures.size());
                }
            }
            return;
        }
        int mdnsPort = Integer.getInteger("net.mdns.port", 5353);
        throw new RegistrationException("Bonjour could not register AirPlay on any selected network adapter "
                + "(mDNS UDP " + mdnsPort + ").", failures);
    }

    private Closeable registerOn(NetworkInterfaceSelector.Adapter adapter, int airTunesPort) throws IOException {
        String mac = identity.getDeviceId();
        String airTunesServerName = mac.replaceAll(":", "") + "@" + serverName;
        ServiceInfo airPlay = ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                serverName, airTunesPort, 0, 0, airPlayMDNSProps(mac));
        ServiceInfo airTunes = ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps());
        JmDNS jmDNS = JmDNS.create(InetAddress.getByName(adapter.ipv4()));
        boolean registered = false;
        try {
            jmDNS.registerService(airPlay);
            jmDNS.registerService(airTunes);
            log.info("{} service is registered on {} ({})", serverName + AIRPLAY_SERVICE_TYPE,
                    adapter.description(), airTunesPort);
            log.info("{} service is registered on {} ({})", airTunesServerName + AIRTUNES_SERVICE_TYPE,
                    adapter.description(), airTunesPort);
            registered = true;
            return () -> close(jmDNS);
        } finally {
            if (!registered) {
                close(jmDNS);
            }
        }
    }

    public synchronized void stop() {
        if (registration == null) {
            return;
        }
        try {
            registration.close();
        } catch (IOException e) {
            log.debug("Unable to close Bonjour registration: {}", e.getMessage(), e);
        } finally {
            registration = null;
        }
    }

    private void close(JmDNS jmDNS) {
        try {
            jmDNS.unregisterAllServices();
        } catch (RuntimeException e) {
            log.debug("Unable to unregister mDNS services: {}", e.getMessage());
        } finally {
            try {
                jmDNS.close();
            } catch (IOException | RuntimeException e) {
                log.debug("Unable to close mDNS service: {}", e.getMessage());
            }
        }
    }

    @FunctionalInterface
    interface AdapterRegistration {
        Closeable register(NetworkInterfaceSelector.Adapter adapter, int port) throws IOException;
    }

    public static final class RegistrationException extends IOException {
        private RegistrationException(String summary, List<IOException> failures) {
            super(summary + (failures.isEmpty() ? "" : "\n"
                    + String.join("\n", failures.stream().map(IOException::getMessage).toList())));
            failures.forEach(this::addSuppressed);
        }
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        airPlayMDNSProps.put("features", AirPlayFeatures.txtValue(hevcEnabled));
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x44");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV3,2C");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", identity.getPublicKeyHex());
        //airPlayMDNSProps.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        airPlayMDNSProps.put("rmodel", "PC1.0");
        airPlayMDNSProps.put("rrv", "1.01");
        airPlayMDNSProps.put("rsv", "1.00");
        airPlayMDNSProps.put("pcversion", "1715");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        //airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", AirPlayFeatures.txtValue(hevcEnabled));
        airTunesMDNSProps.put("am", "AppleTV3,2C");
        airTunesMDNSProps.put("md", "0,1,2");
        //airTunesMDNSProps.put("rhd", "5.6.0.0");
        //airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("sm", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x44");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", identity.getPublicKeyHex());
        return airTunesMDNSProps;
    }

}

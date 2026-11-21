package wtf.nanoka.airplay.lib;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class NetworkInterfaceSelector {

    public static final String AUTO = "auto";

    private static final Pattern VIRTUAL_NAME = Pattern.compile(
            "vethernet|hyper-v|\\bwsl\\b|virtualbox|vmware|vboxnet|docker|npcap|tap-windows|\\btap\\b|\\btun\\b"
                    + "|bluetooth|pseudo-interface|vpn|wireguard|zerotier|hamachi|default switch"
                    + "|virtual adapter|virtual network|loopback",
            Pattern.CASE_INSENSITIVE);

    private NetworkInterfaceSelector() {
    }

    public record Adapter(
            String name,
            String displayName,
            String ipv4,
            boolean virtual,
            boolean apipa,
            boolean rfc1918) {

        public String id() {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return name;
        }

        public String description() {
            String shown = displayName == null || displayName.isBlank() ? name : displayName;
            return "[" + name + "] " + shown + " (" + ipv4 + ")";
        }
    }

    public record Resolution(
            Adapter chosen,
            List<Adapter> all,
            boolean automatic,
            boolean configuredMissing,
            boolean usedVirtualFallback,
            List<Adapter> candidates) {

        public Resolution(Adapter chosen, List<Adapter> all, boolean automatic,
                          boolean configuredMissing, boolean usedVirtualFallback) {
            this(chosen, all, automatic, configuredMissing, usedVirtualFallback,
                    chosen == null ? List.of() : List.of(chosen));
        }

        public Optional<String> warning() {
            if (chosen == null) {
                return Optional.empty();
            }
            if (configuredMissing) {
                return Optional.of("Configured AirPlay interface was not found; trying automatic selection starting with "
                        + chosen.description());
            }
            if (usedVirtualFallback) {
                return Optional.of("No physical LAN adapter was found; trying virtual adapter "
                        + chosen.description()
                        + ". iPhone may fail to connect. Choose a LAN adapter in Settings.");
            }
            if (!automatic) {
                return Optional.empty();
            }
            List<Adapter> lan = lanCandidates(all);
            if (lan.size() < 2) {
                return Optional.empty();
            }
            String listed = String.join(", ", lan.stream().map(Adapter::description).toList());
            return Optional.of("Multiple LAN interfaces can advertise AirPlay (" + listed
                    + "). Trying " + chosen.description() + " first"
                    + " (airplay.networkInterface=auto). Pick one in Settings if iPhone cannot find this receiver.");
        }
    }

    public static List<Adapter> listAll() {
        return uniqueByInterface(enumerateLive());
    }

    public static Resolution resolve(String configured) {
        return resolve(configured, enumerateLive(), detectDefaultRouteIpv4());
    }

    static Resolution resolve(String configured, List<Adapter> adapters, String defaultRouteIpv4) {
        List<Adapter> unique = uniqueByInterface(adapters);
        boolean automatic = configured == null || configured.isBlank()
                || AUTO.equalsIgnoreCase(configured.trim());
        String requested = automatic ? "" : configured.trim();
        Adapter exactAddress = adapters.stream().filter(adapter -> requested.equals(adapter.ipv4()))
                .findFirst().orElse(null);
        Adapter matched = exactAddress != null ? exactAddress : matchInterface(requested, unique);
        boolean configuredMissing = !automatic && matched == null;
        Comparator<Adapter> preference = preferenceOrder(defaultRouteIpv4);
        Adapter chosen = matched != null ? matched : unique.stream().max(preference).orElse(null);
        List<Adapter> candidates = exactAddress != null ? List.of(exactAddress) : adapters.stream()
                .distinct()
                .filter(adapter -> matched == null || adapter.name().equals(matched.name()))
                .sorted(Comparator.comparing((Adapter adapter) -> adapter.equals(chosen))
                        .thenComparing(preference).reversed())
                .toList();
        boolean usedVirtualFallback = (automatic || configuredMissing)
                && chosen != null && chosen.virtual();
        return new Resolution(chosen, unique, automatic || configuredMissing, configuredMissing,
                usedVirtualFallback, candidates);
    }

    static List<Adapter> uniqueByInterface(List<Adapter> adapters) {
        Map<String, Adapter> unique = new LinkedHashMap<>();
        for (Adapter adapter : adapters) {
            unique.merge(adapter.name(), adapter, NetworkInterfaceSelector::preferAddress);
        }
        List<Adapter> ordered = new ArrayList<>(unique.values());
        ordered.sort(stabilityOrder());
        return List.copyOf(ordered);
    }

    private static Adapter matchInterface(String needle, List<Adapter> adapters) {
        if (needle.isEmpty()) {
            return null;
        }
        for (Adapter adapter : adapters) {
            if (needle.equalsIgnoreCase(adapter.name())
                    || needle.equalsIgnoreCase(adapter.displayName())) {
                return adapter;
            }
        }
        return null;
    }

    private static Adapter preferAddress(Adapter left, Adapter right) {
        Comparator<Adapter> order = preferenceOrder(null);
        int compared = order.compare(left, right);
        if (compared >= 0) {
            return left;
        }
        return right;
    }

    private static Comparator<Adapter> preferenceOrder(String defaultRouteIpv4) {
        return Comparator
                .comparingInt((Adapter adapter) -> score(adapter, defaultRouteIpv4))
                .thenComparing(stabilityOrder());
    }

    private static Comparator<Adapter> stabilityOrder() {
        return Comparator
                .comparing((Adapter adapter) -> adapter.displayName() == null ? "" : adapter.displayName(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Adapter::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Adapter::ipv4);
    }

    static int score(Adapter adapter, String defaultRouteIpv4) {
        if (adapter.apipa()) {
            return -10_000;
        }
        int score = 0;
        if (!adapter.virtual()) {
            score += 1_000;
        }
        if (adapter.rfc1918()) {
            score += 100;
        }
        if (defaultRouteIpv4 != null && defaultRouteIpv4.equals(adapter.ipv4())) {
            score += 50;
        }
        return score;
    }

    static List<Adapter> lanCandidates(List<Adapter> adapters) {
        return adapters.stream()
                .filter(adapter -> !adapter.virtual() && !adapter.apipa() && adapter.rfc1918())
                .toList();
    }

    static List<Adapter> enumerateLive() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(NetworkInterfaceSelector::usableInterface)
                    .flatMap(NetworkInterfaceSelector::adaptersOn)
                    .toList();
        } catch (SocketException e) {
            return List.of();
        }
    }

    static String detectDefaultRouteIpv4() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isAnyLocalAddress() && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean isVirtualName(String value) {
        return value != null && VIRTUAL_NAME.matcher(value).find();
    }

    private static boolean usableInterface(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp() && !networkInterface.isLoopback() && !networkInterface.isPointToPoint();
        } catch (SocketException e) {
            return false;
        }
    }

    private static Stream<Adapter> adaptersOn(NetworkInterface networkInterface) {
        boolean virtual = isVirtual(networkInterface);
        String displayName = Optional.ofNullable(networkInterface.getDisplayName()).orElse("");
        return networkInterface.inetAddresses()
                .filter(Inet4Address.class::isInstance)
                .map(Inet4Address.class::cast)
                .map(address -> new Adapter(
                        networkInterface.getName(),
                        displayName,
                        address.getHostAddress(),
                        virtual,
                        isApipa(address),
                        isRfc1918(address)));
    }

    private static boolean isVirtual(NetworkInterface networkInterface) {
        return networkInterface.isVirtual()
                || isVirtualName(networkInterface.getName())
                || isVirtualName(networkInterface.getDisplayName());
    }

    private static boolean isApipa(Inet4Address address) {
        byte[] octets = address.getAddress();
        return (octets[0] & 0xff) == 169 && (octets[1] & 0xff) == 254;
    }

    private static boolean isRfc1918(Inet4Address address) {
        byte[] octets = address.getAddress();
        int first = octets[0] & 0xff;
        int second = octets[1] & 0xff;
        return first == 10
                || (first == 192 && second == 168)
                || (first == 172 && second >= 16 && second <= 31);
    }

    public static String normalize(String configured) {
        if (configured == null || configured.isBlank()) {
            return AUTO;
        }
        return configured.trim();
    }

    public static void requireConfigured(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("airplay.networkInterface must be auto or a network interface name");
        }
    }
}

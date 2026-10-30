package libclash;

import android.util.Log;

import org.asterisk.meow.Libmeow;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drop-in replacement for the mihomo gomobile AAR used by META.
 *
 * <p>Data plane: libmeow (meow-rs) with the VpnService fd. Control plane:
 * profile YAML is exposed as a mihomo-shaped /proxies snapshot; traffic
 * counters come from libmeow; delay tests / providers / logs are degraded
 * stubs until the meow REST bridge lands.
 */
public final class Libclash {
    private static final String TAG = "libclash-shim";

    private static volatile String home = "";
    private static volatile String configPath;
    private static volatile String ageSecretKey = "";

    private static final Map<Integer, String> overrides = new ConcurrentHashMap<>();
    private static final Map<String, String> selections = new ConcurrentHashMap<>();
    private static final Map<Long, HttpURLConnection> fetches = new ConcurrentHashMap<>();
    private static final Map<Long, Thread> fetchThreads = new ConcurrentHashMap<>();

    private Libclash() {
    }

    public static void touch() {
    }

    public static void init(String homePath, String version, int sdk, ContentResolver resolver) {
        home = homePath;
        File homeDir = new File(homePath);
        homeDir.mkdirs();
        seedGeodata(homeDir);
        Libmeow.nativeInit();
        Libmeow.nativeSetHomeDir(homePath);
    }

    public static String coreVersion() {
        return "meow-rs 0.28.0 / libmeow 0.1.0";
    }

    public static void load(Completion completion, String path) {
        String resolved = resolveConfig(path);
        if (resolved == null) {
            completion.complete("config not found: " + path);
            return;
        }
        configPath = normalizeForMeow(new File(resolved));
        completion.complete("");
    }

    public static void loadFromBytes(Completion completion, String path, byte[] content) {
        try {
            File target = new File(path);
            if (target.isDirectory() || target.getName().indexOf('.') < 0) {
                target = new File(target, "config.yaml");
            }
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(content);
            }
            configPath = normalizeForMeow(target);
            completion.complete("");
        } catch (Throwable t) {
            completion.complete(t.toString());
        }
    }

    public static void startTun(int fd, String stack, String gateway, String portal,
                                String dns, TunInterface tun) throws Exception {
        startTunInternal(fd, tun);
    }

    public static void startTunWithCongestionController(int fd, String stack, String gateway,
                                                        String portal, String dns,
                                                        String congestionController,
                                                        TunInterface tun) throws Exception {
        startTunInternal(fd, tun);
    }

    public static void startTunContext(TunInterface tun) {
        // ROOT modes still use the mihomo CLI; nothing to start in-process.
    }

    public static void stopTun() {
        Libmeow.nativeStop();
    }

    public static void stopTunContext() {
    }

    public static void reset() {
        Libmeow.nativeStop();
        configPath = null;
        lastTrafficNanos = 0L;
        lastTrafficUp = 0L;
        lastTrafficDown = 0L;
        lastTrafficRateUp = 0L;
        lastTrafficRateDown = 0L;
    }

    private static void startTunInternal(int fd, TunInterface tun) throws Exception {
        if (configPath == null) {
            throw new IllegalStateException("no configuration loaded");
        }
        Libmeow.nativeSetProtectorCallback(tun);
        Libmeow.nativeSetOwnerCallback(tun);
        // The embedder detaches the ParcelFileDescriptor before this call, so
        // the fd's ownership transfers to meow's netstack for the session.
        int rc = Libmeow.nativeStart(configPath, fd, 1500);
        if (rc != 0) {
            throw new IllegalStateException(Libmeow.nativeLastError());
        }
    }

    private static long lastTrafficUp;
    private static long lastTrafficDown;
    private static long lastTrafficNanos;
    private static long lastTrafficRateUp;
    private static long lastTrafficRateDown;

    /**
     * mihomo's /traffic reports per-second rates, while meow only exposes
     * cumulative counters. Derive the rate from the delta between calls; the
     * caller polls roughly once per second.
     */
    public static synchronized Traffic queryTrafficNow() {
        long up = Libmeow.nativeUploadTotal();
        long down = Libmeow.nativeDownloadTotal();
        long now = System.nanoTime();
        if (lastTrafficNanos == 0L || up < lastTrafficUp || down < lastTrafficDown) {
            lastTrafficUp = up;
            lastTrafficDown = down;
            lastTrafficNanos = now;
            lastTrafficRateUp = 0L;
            lastTrafficRateDown = 0L;
            return new Traffic(0L, 0L);
        }
        double elapsed = (now - lastTrafficNanos) / 1_000_000_000.0;
        if (elapsed < 0.2) {
            return new Traffic(lastTrafficRateUp, lastTrafficRateDown);
        }
        lastTrafficRateUp = Math.max(0L, (long) ((up - lastTrafficUp) / elapsed));
        lastTrafficRateDown = Math.max(0L, (long) ((down - lastTrafficDown) / elapsed));
        lastTrafficUp = up;
        lastTrafficDown = down;
        lastTrafficNanos = now;
        return new Traffic(lastTrafficRateUp, lastTrafficRateDown);
    }

    public static Traffic queryTrafficTotal() {
        return new Traffic(Libmeow.nativeUploadTotal(), Libmeow.nativeDownloadTotal());
    }

    public static long queryMemory() {
        return Libmeow.nativeMemoryUsage();
    }

    public static String queryTunnelState() {
        return "{\"mode\":\"rule\"}";
    }

    public static int queryConnectionCount() {
        return Libmeow.nativeConnectionCount();
    }

    public static String queryConnections() {
        String result = Libmeow.nativeConnectionsJson();
        return result == null
                ? "{\"connections\":[],\"uploadTotal\":0,\"downloadTotal\":0}"
                : result;
    }

    public static boolean closeConnection(String id) {
        return Libmeow.nativeCloseConnection(id);
    }

    public static void closeAllConnections() {
        Libmeow.nativeCloseAllConnections();
    }

    public static String queryProxies() {
        return proxiesJson();
    }

    public static String queryProxyDelay(String name, String url, int timeoutMillis,
                                         String expectedStatus) {
        String result = Libmeow.nativeProxyDelay(name, url, timeoutMillis, expectedStatus);
        Log.d(TAG, "delay " + name + ": " + result);
        return result == null ? "{\"error\":\"delay probe failed\"}" : result;
    }

    public static String queryProviderProxyDelay(String providerName, String name, String url,
                                                 int timeoutMillis, String expectedStatus) {
        return "{\"error\":\"provider delay test is not wired to the meow bridge yet\"}";
    }

    public static String queryGroupDelay(String name, String url, int timeoutMillis,
                                         String expectedStatus) {
        List<String> members = groupMembers(name);
        if (members.isEmpty()) {
            return "{}";
        }
        JSONObject result = new JSONObject();
        int workers = Math.min(8, members.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, workers));
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String member : members) {
                futures.add(pool.submit(() ->
                        Libmeow.nativeProxyDelay(member, url, timeoutMillis, expectedStatus)));
            }
            long waitMillis = Math.max(1000L, timeoutMillis + 2000L);
            for (int i = 0; i < members.size(); i++) {
                try {
                    String json = futures.get(i).get(waitMillis, TimeUnit.MILLISECONDS);
                    Log.d(TAG, "group delay " + members.get(i) + ": " + json);
                    if (json == null) {
                        continue;
                    }
                    JSONObject parsed = new JSONObject(json);
                    int delay = parsed.optInt("delay", -1);
                    if (delay >= 0) {
                        result.put(members.get(i), delay);
                    }
                } catch (Throwable error) {
                    Log.d(TAG, "group delay " + members.get(i) + " failed: " + error);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return result.toString();
    }

    public static boolean patchSelector(String selector, String name) {
        selections.put(selector, name);
        return true;
    }

    public static String queryAutoSwitch() {
        return "{}";
    }

    public static void patchAutoSwitch(String settingsJson) {
    }

    public static String queryProvider(String type, String name) {
        try {
            JSONObject root = new JSONObject();
            root.put("name", name);
            root.put("type", "Proxy");
            root.put("proxies", new JSONArray());
            return root.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static String queryProviders() {
        return "[]";
    }

    public static void updateProvider(Completion completion, String type, String name) {
        completion.complete("");
    }

    public static String readOverride(int slot) {
        return overrides.getOrDefault(slot, "{}");
    }

    public static void writeOverride(int slot, String json) {
        overrides.put(slot, json);
    }

    public static void clearOverride(int slot) {
        overrides.remove(slot);
    }

    public static LogSubscription subscribeLogcat(LogcatInterface callback) {
        return new LogSubscription();
    }

    public static void setAgeSecretKey(String key) {
        ageSecretKey = key == null ? "" : key;
    }

    public static String decryptAge(String content, String secretKey) throws Exception {
        if (secretKey == null || secretKey.trim().isEmpty()) {
            return content;
        }
        throw new Exception("age-encrypted subscriptions are not supported by libmeow");
    }

    public static boolean veritySecretKeys(String secretKey) {
        return false;
    }

    public static boolean supportsSubscriptionHwid() {
        // META sends FetchOptions.hwid; the shim forwards it as x-hwid.
        return true;
    }

    public static void notifyDnsChanged(String payload) {
    }

    public static void notifyInstalledAppsChanged(String payload) {
    }

    public static void fetchAndValid(FetchCallback callback, long taskId, String path,
                                     String url, String optionsJson) throws Exception {
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                callback.report("{\"action\":\"FetchConfiguration\",\"args\":[],\"progress\":0,\"max\":0}");
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(30_000);
                connection.setReadTimeout(60_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", fetchOption(optionsJson, "userAgent", "clash.meta"));
                String hwid = fetchOption(optionsJson, "hwid", "");
                if (!hwid.isEmpty()) {
                    connection.setRequestProperty("x-hwid", hwid);
                }
                fetches.put(taskId, connection);
                File target = new File(path);
                if (target.isDirectory()) {
                    // META passes a per-save processing directory
                    // (cache/mihomo-profile-processing-*); the prepared
                    // profile goes to config.yaml inside it.
                    target = new File(target, "config.yaml");
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (InputStream in = connection.getInputStream();
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                callback.complete("");
            } catch (Throwable t) {
                callback.complete(t.toString());
            } finally {
                fetches.remove(taskId);
                fetchThreads.remove(taskId);
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "libclash-fetch-" + taskId);
        fetchThreads.put(taskId, thread);
        thread.start();
    }

    public static void cancelFetch(long taskId) {
        HttpURLConnection connection = fetches.remove(taskId);
        if (connection != null) {
            connection.disconnect();
        }
        Thread thread = fetchThreads.remove(taskId);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static String fetchOption(String optionsJson, String key, String fallback) {
        try {
            String value = new JSONObject(optionsJson).optString(key, "");
            if (!value.isEmpty()) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /**
     * META emits mihomo's `system` nameserver sentinel, which meow parses as
     * a host named "system" and rejects. Replace it recursively and make sure
     * a numeric bootstrap server exists.
     *
     * <p>Writes a sibling config.meow.yaml so META's own file stays intact.
     */
    private static String normalizeForMeow(File source) {
        try (FileInputStream in = new FileInputStream(source)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?>)) {
                return source.getAbsolutePath();
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> root = (Map<Object, Object>) loaded;
            replaceSystem(root);
            Object dnsRaw = root.get("dns");
            if (dnsRaw instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> dns = (Map<Object, Object>) dnsRaw;
                Object defaults = dns.get("default-nameserver");
                if (!(defaults instanceof List<?>) || ((List<?>) defaults).isEmpty()) {
                    List<Object> list = new ArrayList<>();
                    list.add("223.5.5.5");
                    dns.put("default-nameserver", list);
                }
            }
            File target = new File(source.getParentFile(), "config.meow.yaml");
            try (FileOutputStream out = new FileOutputStream(target)) {
                new Yaml().dump(root, new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
            }
            return target.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "config normalize failed, using original: " + t);
            return source.getAbsolutePath();
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceSystem(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                Object child = entry.getValue();
                if ("system".equals(child)) {
                    entry.setValue("223.5.5.5");
                } else {
                    replaceSystem(child);
                }
            }
        } else if (value instanceof List<?>) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                Object child = list.get(i);
                if ("system".equals(child)) {
                    list.set(i, "223.5.5.5");
                } else {
                    replaceSystem(child);
                }
            }
        }
    }

    /** Copy META's resource names to the names meow's discovery expects. */
    private static void seedGeodata(File homeDir) {
        copyIfMissing(new File(homeDir, "GeoSite.dat"), new File(homeDir, "geosite.dat"));
        copyIfMissing(new File(homeDir, "geoip.metadb"), new File(homeDir, "Country.mmdb"));
    }

    private static void copyIfMissing(File source, File target) {
        if (target.isFile() || !source.isFile()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "copy " + source.getName() + " failed: " + t);
        }
    }

    private static String resolveConfig(String path) {
        File file = new File(path);
        if (file.isDirectory()) {
            File nested = new File(file, "config.yaml");
            return nested.isFile() ? nested.getAbsolutePath() : null;
        }
        return file.isFile() ? file.getAbsolutePath() : null;
    }

    /** Flatten a proxy group (or GLOBAL) to leaf proxy names from the profile. */
    private static List<String> groupMembers(String name) {
        Map<?, ?> root = loadYaml();
        if (root == null) {
            return Collections.singletonList(name);
        }
        Map<String, Map<?, ?>> groups = new java.util.LinkedHashMap<>();
        Object rawGroups = root.get("proxy-groups");
        if (rawGroups instanceof List<?>) {
            for (Object item : (List<?>) rawGroups) {
                if (item instanceof Map<?, ?>) {
                    Map<?, ?> map = (Map<?, ?>) item;
                    String groupName = String.valueOf(map.get("name"));
                    if (!groupName.isEmpty() && !"null".equals(groupName)) {
                        groups.put(groupName, map);
                    }
                }
            }
        }
        if ("GLOBAL".equals(name)) {
            List<String> all = new ArrayList<>();
            Object rawProxies = root.get("proxies");
            if (rawProxies instanceof List<?>) {
                for (Object item : (List<?>) rawProxies) {
                    if (item instanceof Map<?, ?>) {
                        String proxyName = String.valueOf(((Map<?, ?>) item).get("name"));
                        if (!proxyName.isEmpty() && !"null".equals(proxyName)) {
                            all.add(proxyName);
                        }
                    }
                }
            }
            all.addAll(groups.keySet());
            return flattenMembers(all, groups, new HashSet<>());
        }
        Map<?, ?> group = groups.get(name);
        if (group == null) {
            return Collections.singletonList(name);
        }
        Object members = group.get("proxies");
        if (!(members instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (Object member : (List<?>) members) {
            names.add(String.valueOf(member));
        }
        return flattenMembers(names, groups, new HashSet<>());
    }

    private static List<String> flattenMembers(List<String> names,
                                               Map<String, Map<?, ?>> groups,
                                               Set<String> seen) {
        List<String> out = new ArrayList<>();
        for (String member : names) {
            if (!seen.add(member)) {
                continue;
            }
            Map<?, ?> nested = groups.get(member);
            if (nested == null) {
                out.add(member);
                continue;
            }
            Object members = nested.get("proxies");
            if (!(members instanceof List<?>)) {
                continue;
            }
            List<String> childNames = new ArrayList<>();
            for (Object child : (List<?>) members) {
                childNames.add(String.valueOf(child));
            }
            out.addAll(flattenMembers(childNames, groups, seen));
        }
        return out;
    }

    private static String proxiesJson() {
        JSONObject result = new JSONObject();
        JSONObject proxies = new JSONObject();
        Set<String> names = new LinkedHashSet<>();
        boolean hasGroup = false;
        try {
            Map<?, ?> root = loadYaml();
            if (root != null) {
                Object rawProxies = root.get("proxies");
                if (rawProxies instanceof List<?>) {
                    for (Object item : (List<?>) rawProxies) {
                        if (!(item instanceof Map<?, ?>)) {
                            continue;
                        }
                        Map<?, ?> map = (Map<?, ?>) item;
                        String name = String.valueOf(map.get("name"));
                        if (name.isEmpty() || "null".equals(name)) {
                            continue;
                        }
                        JSONObject node = new JSONObject();
                        node.put("name", name);
                        node.put("type", str(map, "type", "Proxy"));
                        node.put("udp", Boolean.TRUE.equals(map.get("udp")));
                        node.put("history", new JSONArray());
                        proxies.put(name, node);
                        names.add(name);
                    }
                }
                Object rawGroups = root.get("proxy-groups");
                if (rawGroups instanceof List<?>) {
                    for (Object item : (List<?>) rawGroups) {
                        if (!(item instanceof Map<?, ?>)) {
                            continue;
                        }
                        Map<?, ?> map = (Map<?, ?>) item;
                        String name = String.valueOf(map.get("name"));
                        if (name.isEmpty() || "null".equals(name)) {
                            continue;
                        }
                        JSONArray all = new JSONArray();
                        Object members = map.get("proxies");
                        if (members instanceof List<?>) {
                            for (Object member : (List<?>) members) {
                                all.put(String.valueOf(member));
                            }
                        }
                        JSONObject group = new JSONObject();
                        group.put("name", name);
                        group.put("type", str(map, "type", "Selector"));
                        group.put("all", all);
                        group.put("now", selections.getOrDefault(name,
                                all.length() > 0 ? all.optString(0) : "DIRECT"));
                        group.put("udp", true);
                        group.put("history", new JSONArray());
                        proxies.put(name, group);
                        names.add(name);
                        hasGroup = true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "profile parse failed: " + t);
        }

        try {
            if (!proxies.has("DIRECT")) {
                proxies.put("DIRECT", node("DIRECT", "Direct", true));
            }
            if (!proxies.has("REJECT")) {
                proxies.put("REJECT", node("REJECT", "Reject", false));
            }
            names.add("DIRECT");
            if (!hasGroup) {
                JSONArray members = new JSONArray();
                for (String name : names) {
                    members.put(name);
                }
                JSONObject fallback = new JSONObject();
                fallback.put("name", "Proxy");
                fallback.put("type", "Selector");
                fallback.put("all", members);
                fallback.put("now", "DIRECT");
                fallback.put("udp", true);
                fallback.put("history", new JSONArray());
                proxies.put("Proxy", fallback);
                names.add("Proxy");
            }
            JSONArray all = new JSONArray();
            for (String name : names) {
                if (!"GLOBAL".equals(name)) {
                    all.put(name);
                }
            }
            JSONObject global = new JSONObject();
            global.put("name", "GLOBAL");
            global.put("type", "Selector");
            global.put("all", all);
            global.put("now", selections.getOrDefault("GLOBAL",
                    all.length() > 0 ? all.optString(0) : "DIRECT"));
            global.put("udp", true);
            global.put("history", new JSONArray());
            proxies.put("GLOBAL", global);
            result.put("proxies", proxies);
        } catch (Throwable t) {
            Log.w(TAG, "proxy snapshot build failed: " + t);
        }
        return result.toString();
    }

    private static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static JSONObject node(String name, String type, boolean udp) throws Exception {
        JSONObject node = new JSONObject();
        node.put("name", name);
        node.put("type", type);
        node.put("udp", udp);
        node.put("history", new JSONArray());
        return node;
    }

    private static Map<?, ?> loadYaml() {
        String path = configPath;
        if (path == null) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(path)) {
            return new Yaml().load(in);
        } catch (Throwable t) {
            Log.w(TAG, "yaml load failed: " + t);
            return null;
        }
    }
}

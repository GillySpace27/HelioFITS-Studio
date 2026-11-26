package org.helioviewer.jhv.timelines.band;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The IMAP (NASA SPDF) timeline catalog, built from resources/data/imap-timelines.json without any network
 * (not waiting for the HAPI servers). Band base URLs are {@code imap:<product>#<series>}; the session
 * serializer and BandType.equals key on them, so a saved session restores without this catalog being merged.
 */
public final class ImapCatalog {

    public static final String SCHEME = "imap:";

    record Product(String id, String kind, String title, String shortName, String dir, String instrument,
                   String level, String descriptor, int maxSpanDays, long bytesPerDay, String file) {
        boolean isCoho() {
            return "coho".equals(kind);
        }
    }

    record Series(String product, String var, String label, String unit, double min, double max, String scale,
                  String group, boolean heavy) {
        String baseUrl() {
            return SCHEME + product + '#' + var;
        }
    }

    private record Config(String catalogName, String attribution, Map<String, Product> products,
                          List<Series> series, Map<String, List<String>> predefined, List<String> groupTitles) {}

    private static final Config CONFIG = load();
    private static final Map<String, Series> BY_URL = index(CONFIG);
    private static final AtomicReference<Consumer<String>> STATUS = new AtomicReference<>(s -> {});
    /** Acknowledgement attribute of the last CDF read per product (filled by ImapSource). */
    private static final ConcurrentHashMap<String, String> ACKNOWLEDGEMENTS = new ConcurrentHashMap<>();

    private ImapCatalog() {}

    private static Config load() {
        try (InputStream is = FileUtils.getResource("/data/imap-timelines.json")) {
            return parse(JSONUtils.get(is));
        } catch (IOException | RuntimeException e) { // Checks keep the file readable; never a crash here
            Log.warn("IMAP catalog could not be read", e);
            return new Config("IMAP (NASA SPDF)", "", Map.of(), List.of(), Map.of(), List.of());
        }
    }

    static Config parse(JSONObject jo) {
        LinkedHashMap<String, Product> products = new LinkedHashMap<>();
        JSONObject jp = jo.getJSONObject("products");
        for (String id : jp.keySet()) {
            JSONObject p = jp.getJSONObject(id);
            products.put(id, new Product(id, p.getString("kind"), p.getString("title"), p.optString("short", "IMAP"),
                    p.getString("dir"), p.optString("instrument", ""), p.optString("level", ""),
                    p.optString("descriptor", ""), p.optInt("maxSpanDays", 0), p.optLong("bytesPerDay", 0),
                    p.optString("file", "")));
        }
        List<Series> series = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        JSONArray groups = jo.getJSONArray("groups");
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.getJSONObject(i);
            String title = g.getString("title");
            titles.add(title);
            JSONArray bands = g.getJSONArray("bands");
            for (int j = 0; j < bands.length(); j++) {
                JSONObject b = bands.getJSONObject(j);
                JSONArray r = b.getJSONArray("range");
                series.add(new Series(b.getString("product"), b.getString("var"), b.getString("label"),
                        b.getString("unit"), r.getDouble(0), r.getDouble(1), b.optString("scale", "linear"), title,
                        g.optBoolean("heavy", false)));
            }
        }
        LinkedHashMap<String, List<String>> predefined = new LinkedHashMap<>();
        JSONObject pre = jo.optJSONObject("predefined");
        if (pre != null) {
            for (String name : pre.keySet()) {
                List<String> urls = new ArrayList<>();
                JSONArray ja = pre.getJSONArray(name);
                for (int i = 0; i < ja.length(); i++)
                    urls.add(ja.getString(i));
                predefined.put(name, List.copyOf(urls));
            }
        }
        return new Config(jo.optString("catalog", "IMAP (NASA SPDF)"), jo.optString("attribution", ""),
                Map.copyOf(products), List.copyOf(series), Map.copyOf(predefined), List.copyOf(titles));
    }

    private static Map<String, Series> index(Config c) {
        LinkedHashMap<String, Series> m = new LinkedHashMap<>();
        c.series.forEach(s -> m.put(s.baseUrl(), s));
        return m;
    }

    // ---- lookups used by ImapSource, Band and BandDownloads

    static boolean handles(String baseUrl) {
        return baseUrl != null && baseUrl.startsWith(SCHEME);
    }

    static Series series(String baseUrl) {
        return BY_URL.get(baseUrl);
    }

    static Product product(String id) {
        return CONFIG.products.get(id);
    }

    static String productOf(String baseUrl) {
        int hash = baseUrl.indexOf('#');
        return hash < 0 ? baseUrl.substring(SCHEME.length()) : baseUrl.substring(SCHEME.length(), hash);
    }

    static String seriesOf(String baseUrl) {
        int hash = baseUrl.indexOf('#');
        return hash < 0 ? "" : baseUrl.substring(hash + 1);
    }

    static void acknowledgement(String product, String text) {
        if (text != null && !text.isBlank())
            ACKNOWLEDGEMENTS.put(product, text);
    }

    private static BandType bandType(Series s) {
        Product p = CONFIG.products.get(s.product);
        String label = p.shortName + ' ' + s.label;
        JSONObject jo = new JSONObject().
                put("baseUrl", s.baseUrl()).
                put("unitLabel", s.unit).
                put("name", label).
                put("label", label).
                put("range", new JSONArray().put(s.min).put(s.max)).
                put("scale", s.scale).
                put("groups", new JSONArray().put(s.group));
        return new BandType(jo);
    }

    // ---- public API for the UI

    /** The IMAP catalog on its own, ready to show without any server. */
    public static BandReaderHapi.CatalogData catalogData() {
        return merge(new BandReaderHapi.CatalogData(Map.of(), Map.of()));
    }

    /** The given HAPI catalog data plus the IMAP tree (appended after the servers) and the IMAP predefined stack. */
    public static BandReaderHapi.CatalogData merge(BandReaderHapi.CatalogData hapi) {
        LinkedHashMap<String, BandDataset[]> datasets = new LinkedHashMap<>(hapi.datasets());
        List<BandDataset> leaves = new ArrayList<>();
        LinkedHashMap<String, BandType> types = new LinkedHashMap<>();
        for (Series s : CONFIG.series) {
            BandType t = bandType(s);
            types.put(s.baseUrl(), t);
            leaves.add(new BandDataset(s.label, List.of(t)));
        }
        if (!leaves.isEmpty())
            datasets.put(CONFIG.catalogName, leaves.toArray(BandDataset[]::new));

        LinkedHashMap<String, List<BandType>> predefined = new LinkedHashMap<>(hapi.predefinedGroups());
        CONFIG.predefined.forEach((name, urls) -> {
            List<BandType> list = new ArrayList<>(predefined.getOrDefault(name, List.of()));
            for (String url : urls) {
                BandType t = types.get(url);
                if (t != null)
                    list.add(t);
            }
            predefined.put(name, List.copyOf(list));
        });
        return new BandReaderHapi.CatalogData(java.util.Collections.unmodifiableMap(datasets),
                java.util.Collections.unmodifiableMap(predefined));
    }

    public static boolean isImap(BandType type) {
        return handles(type.getBaseUrl());
    }

    /** True for the MAG groups: about 3.7 MB per day, 1-minute means only, capped visible span. */
    public static boolean isHeavy(BandType type) {
        Series s = BY_URL.get(type.getBaseUrl());
        return s != null && s.heavy;
    }

    /** The note to show next to a heavy group. */
    public static String heavyNote() {
        return "IMAP MAG files are about 3.7 MB per day (read from the SPDF listing); only 1-minute means are shown, "
                + "and at most 14 days at a time.";
    }

    /** The footer for a selected IMAP band, or null for any other band. */
    public static String attribution(BandType type) {
        if (!isImap(type))
            return null;
        String text = CONFIG.attribution;
        String ack = ACKNOWLEDGEMENTS.get(productOf(type.getBaseUrl()));
        return ack == null ? text : text + ' ' + ack;
    }

    /** Where the UI wants "zoom in to load" style messages (called on the EDT). */
    public static void setStatusSink(Consumer<String> sink) {
        STATUS.set(sink == null ? s -> {} : sink);
    }

    static void status(String message) {
        STATUS.get().accept(message);
    }

}

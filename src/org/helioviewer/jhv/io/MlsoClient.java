package org.helioviewer.jhv.io;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * Searches and loads Mauna Loa Solar Observatory data (KCor, UCoMP) through the HAO MLSO API, v1.
 *
 * <p>Everything here comes from the client's documentation, mlso-api-client 1.0.0
 * (mlso-api-client.readthedocs.io/en/v1.0.0, pages endpoints.html, usage.html and
 * mlso-api-client.html): the base URL http://api.mlso.ucar.edu/v1, the endpoints
 * /instruments, /instruments/{id}, /instruments/{id}/products, .../products/{id}/files and
 * /authenticate, the filters start-date, end-date, every and wave-region, and the keys of each
 * reply. Listing is open; a download needs the session cookie that /authenticate?username=
 * sets for an email registered with HAO, so the shared HTTP client carries {@link #COOKIES}.
 */
public final class MlsoClient {

    static final String HOST = "api.mlso.ucar.edu";
    static final String BASE_URL = "http://" + HOST + "/v1";
    public static final String REGISTER_URL = "https://registration.hao.ucar.edu";
    public static final String USERNAME_KEY = "mlso.username";
    /** The wave regions the files endpoint accepts for UCoMP, per its documentation. */
    public static final String[] UCOMP_WAVE_REGIONS = {"637", "706", "789", "1074", "1079"};

    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public record Instrument(String id, String name, String dates) {
        @Override
        public String toString() {
            return name.isEmpty() ? id : name;
        }
    }

    public record Product(String id, String title, String description) {
        @Override
        public String toString() {
            return title.isEmpty() ? id : id + ": " + title;
        }
    }

    public record DataItem(String filename, URI uri, long milli, long size) {
        @Override
        public String toString() {
            return TimeUtils.format(milli) + "  " + filename;
        }
    }

    public interface Receiver {
        String API_UNREACHABLE = "Could not reach the MLSO API. Check your connection and try again.";

        default void setMlsoResponseInstruments(List<Instrument> list) {}

        default void setMlsoResponseProducts(List<Product> list) {}

        default void setMlsoResponseItems(List<DataItem> list) {}

        default void setMlsoResponseFailed(String reason) {}
    }

    private static Task.FailureHandler reportTo(Receiver receiver) {
        return (logContext, t) -> {
            Log.error(logContext, t);
            receiver.setMlsoResponseFailed(Receiver.API_UNREACHABLE);
        };
    }

    public static void submitGetInstruments(@Nonnull Receiver receiver) {
        Task.submitBackground("mlso", MlsoClient::queryInstruments, receiver::setMlsoResponseInstruments, reportTo(receiver));
    }

    public static void submitGetProducts(@Nonnull Receiver receiver, @Nonnull String instrument) {
        Task.submitBackground("mlso", () -> parseProducts(getJson(BASE_URL + "/instruments/" + enc(instrument) + "/products")),
                receiver::setMlsoResponseProducts, reportTo(receiver));
    }

    public static void submitSearch(@Nonnull Receiver receiver, @Nonnull String instrument, @Nonnull String product,
                                    long start, long end, long cadence, String waveRegion) {
        String url = filesUrl(instrument, product, start, end, cadence, waveRegion);
        Task.submitBackground("mlso", () -> parseFiles(getJson(url)), receiver::setMlsoResponseItems, reportTo(receiver));
    }

    /**
     * Sign in with the registered email, then load the files as one layer. Signing in first every
     * time is cheap and means a session cookie that the server has expired never fails a load.
     */
    public static void submitLoad(@Nonnull Receiver receiver, @Nonnull List<DataItem> items, @Nonnull String username) {
        List<URI> uris = items.stream().map(DataItem::uri).toList();
        Task.submitBackground("mlso", () -> authenticate(username), failure -> {
            if (failure == null)
                Commands.loadImage(uris);
            else
                receiver.setMlsoResponseFailed(failure);
        }, reportTo(receiver));
    }

    /** @return null once signed in, otherwise the reason to show the user */
    static String authenticate(String username) throws Exception {
        URI uri = new URI(BASE_URL + "/authenticate?username=" + enc(username));
        try (NetClient nc = NetClient.of(uri, true, NetClient.NetCache.BYPASS)) {
            if (nc.isSuccessful())
                return null;
            String message = message(nc.getSource().readUtf8());
            return "MLSO did not accept " + username + (message.isEmpty() ? "" : ": " + message) +
                    ". Register the address at " + REGISTER_URL + " first.";
        }
    }

    private static String message(String body) {
        try {
            return new JSONObject(body).optString("message", "").trim();
        } catch (org.json.JSONException e) { // an HTML error page, or nothing at all
            return "";
        }
    }

    /** The files query, with the time range and the cadence turned into the API's own filters. */
    static String filesUrl(String instrument, String product, long start, long end, long cadence, String waveRegion) {
        StringBuilder sb = new StringBuilder(BASE_URL).append("/instruments/").append(enc(instrument))
                .append("/products/").append(enc(product)).append("/files")
                .append("?start-date=").append(enc(TimeUtils.format(QUERY_TIME, start)))
                .append("&end-date=").append(enc(TimeUtils.format(QUERY_TIME, end)));
        String every = every(cadence);
        if (every != null)
            sb.append("&every=").append(every);
        if (waveRegion != null && !waveRegion.isBlank())
            sb.append("&wave-region=").append(enc(waveRegion.trim()));
        return sb.toString();
    }

    /**
     * The API's every filter takes a count and a unit (2hours, 1day). The largest unit that
     * divides the cadence exactly, so nothing is rounded; null for native cadence.
     */
    static String every(long cadenceMilli) {
        long sec = cadenceMilli / 1000;
        if (sec <= 0)
            return null;
        if (sec % 86400 == 0)
            return plural(sec / 86400, "day");
        if (sec % 3600 == 0)
            return plural(sec / 3600, "hour");
        if (sec % 60 == 0)
            return plural(sec / 60, "minute");
        return plural(sec, "second");
    }

    private static String plural(long n, String unit) {
        return n + unit + (n == 1 ? "" : "s");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Object getJson(String url) throws Exception {
        try (NetClient nc = NetClient.of(new URI(url), false, NetClient.NetCache.NETWORK)) {
            return new JSONTokener(nc.getSource().readUtf8()).nextValue();
        }
    }

    private static List<Instrument> queryInstruments() throws Exception {
        List<Instrument> result = new ArrayList<>();
        for (String id : parseIds(getJson(BASE_URL + "/instruments"))) {
            JSONObject jo;
            try {
                jo = getJson(BASE_URL + "/instruments/" + enc(id)) instanceof JSONObject o ? o : new JSONObject();
            } catch (java.io.IOException | org.json.JSONException e) { // an instrument without details is still searchable
                Log.warn(e);
                jo = new JSONObject();
            }
            result.add(parseInstrument(id, jo));
        }
        return result;
    }

    /** The documentation shows the instrument list as a list of ids; accept it bare or wrapped. */
    static List<String> parseIds(Object json) {
        JSONArray array = json instanceof JSONArray a ? a : null;
        if (json instanceof JSONObject o)
            for (String key : o.keySet())
                if (o.opt(key) instanceof JSONArray a) {
                    array = a;
                    break;
                }
        List<String> ids = new ArrayList<>();
        if (array != null)
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, "").trim();
                if (!id.isEmpty())
                    ids.add(id);
            }
        return ids;
    }

    static Instrument parseInstrument(String id, JSONObject jo) {
        JSONObject dates = jo.optJSONObject("dates");
        String range = dates == null ? "" : dates.optString("start-date", "") + " to " + dates.optString("end-date", "");
        return new Instrument(id, jo.optString("name", ""), range);
    }

    static List<Product> parseProducts(Object json) {
        List<Product> result = new ArrayList<>();
        JSONArray products = json instanceof JSONObject o ? o.optJSONArray("products") : null;
        if (products != null)
            for (int i = 0; i < products.length(); i++) {
                JSONObject p = products.optJSONObject(i);
                if (p != null && !p.optString("id", "").isEmpty())
                    result.add(new Product(p.getString("id"), p.optString("title", ""), p.optString("description", "")));
            }
        return result;
    }

    static List<DataItem> parseFiles(Object json) {
        List<DataItem> result = new ArrayList<>();
        JSONArray files = json instanceof JSONObject o ? o.optJSONArray("files") : null;
        if (files != null)
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null)
                    continue;
                try {
                    result.add(new DataItem(f.getString("filename"), new URI(f.getString("url")),
                            TimeUtils.parse(f.getString("date-obs")), f.optLong("filesize", 0)));
                } catch (org.json.JSONException | java.net.URISyntaxException | java.time.format.DateTimeParseException e) { // one malformed row does not cost the rest
                    Log.warn("MLSO: skipping file entry " + f + ": " + e);
                }
            }
        result.sort((a, b) -> Long.compare(a.milli, b.milli));
        return result;
    }

    /**
     * Keeps the MLSO session cookie in memory for the life of the process, and only for the API
     * host, so no other archive ever sees it. Nothing is written to disk.
     */
    public static final CookieJar COOKIES = new CookieJar() {
        private final List<Cookie> cookies = new CopyOnWriteArrayList<>();

        @Override
        public void saveFromResponse(@Nonnull HttpUrl url, @Nonnull List<Cookie> list) {
            if (!HOST.equals(url.host()))
                return;
            for (Cookie c : list) {
                cookies.removeIf(old -> old.name().equals(c.name()) && old.domain().equals(c.domain()) && old.path().equals(c.path()));
                cookies.add(c);
            }
        }

        @Nonnull
        @Override
        public List<Cookie> loadForRequest(@Nonnull HttpUrl url) {
            if (!HOST.equals(url.host()))
                return List.of();
            long now = System.currentTimeMillis();
            cookies.removeIf(c -> c.expiresAt() < now);
            return cookies.stream().filter(c -> c.matches(url)).toList();
        }
    };

    private MlsoClient() {}
}

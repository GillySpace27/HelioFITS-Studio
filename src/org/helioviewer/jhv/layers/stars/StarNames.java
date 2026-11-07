package org.helioviewer.jhv.layers.stars;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;

/**
 * Star names for the cursor identifier, keyed by Hipparcos number: the IAU Catalog of Star Names
 * (IAU-CSN) of the IAU Working Group on Star Names, fetched once from the WGSN table and kept on
 * disk beside the Hipparcos catalogue, as the catalogue itself is (StarCatalog).
 *
 * <p>Each row gives the proper name ("Sirius") and, for most, a designation: the Bayer letter and
 * constellation ("α CMa"), or a variable-star name where that is the designation ("V2500 Oph").
 * The table holds about 450 named stars, so a star without a name here is named by its Hipparcos
 * number. The file says IAU products are released under Creative Commons Attribution; the credit
 * line is {@link #CREDIT}.
 *
 * <p>The table is fixed width. The text columns are cut at the positions of their headings; the
 * Hipparcos number is the fourth field before the date, counted from the right, because the
 * designation column holds spaces ("HR 897") and the numeric columns are right aligned.
 */
public final class StarNames {

    public static final String CREDIT = "Star names: IAU Catalog of Star Names (IAU WGSN), CC BY.";
    static final String URL = "https://www.pas.rochester.edu/~emamajek/WGSN/IAU-CSN.txt";
    private static final String FILE_NAME = "iau-csn.txt";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d\\d-\\d\\d");

    /** One named star: its proper name, and its Bayer (or variable-star) designation or null. */
    public record Entry(String name, @Nullable String designation) {}

    private final Map<Integer, Entry> byHip;

    private StarNames(Map<Integer, Entry> _byHip) {
        byHip = _byHip;
    }

    @Nullable
    public Entry get(int hip) {
        return byHip.get(hip);
    }

    public int size() {
        return byHip.size();
    }

    public static Path cacheFile() {
        return Directories.CATALOGS.getFile().toPath().resolve(FILE_NAME);
    }

    /** The cached copy, or null when there is none yet or it cannot be read. */
    @Nullable
    public static StarNames loadCached() {
        Path file = cacheFile();
        if (!Files.isRegularFile(file))
            return null;
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Could not read the star names at " + file, e);
            return null;
        }
    }

    /** Fetches the table, writes the cache atomically, and returns it. Blocking; call off the GUI thread. */
    public static StarNames download() throws IOException, InterruptedException {
        String body;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(TIMEOUT)
                    .header("User-Agent", AppInfo.userAgent).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                throw new IOException("HTTP " + response.statusCode() + " from the IAU star names table");
            body = response.body();
        }
        StarNames names = parse(body);
        if (names.size() == 0)
            throw new IOException("The IAU star names table had no rows with a Hipparcos number");
        Path file = cacheFile();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".part");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Log.info("Star names: " + names.size() + " written to " + file);
        return names;
    }

    /** Parses the IAU-CSN text; rows without a Hipparcos number are left out. */
    public static StarNames parse(String text) throws IOException {
        String[] lines = text.split("\r?\n");
        String header = null;
        for (String l : lines)
            if (l.startsWith("#Name/ASCII")) {
                header = l;
                break;
            }
        if (header == null)
            throw new IOException("Not the IAU star names table");
        int diacritics = header.indexOf("Name/Diacritics");
        int id1 = header.indexOf("ID");
        int id2 = header.indexOf("ID", id1 + 2);
        int con = header.indexOf("Con");
        if (diacritics < 0 || id1 < 0 || id2 < 0 || con < 0)
            throw new IOException("The IAU star names table has columns this reader does not know");

        Map<Integer, Entry> out = new HashMap<>();
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("#") || l.startsWith("$") || l.length() < con + 3)
                continue;
            String[] t = l.trim().split("\\s+");
            int date = -1;
            for (int i = t.length - 1; i >= 0; i--)
                if (DATE.matcher(t[i]).matches()) {
                    date = i;
                    break;
                }
            if (date < 4)
                continue;
            int hip;
            try {
                hip = Integer.parseInt(t[date - 4]);
            } catch (NumberFormatException e) {
                continue; // "_": no Hipparcos number
            }
            String name = l.substring(0, diacritics).strip();
            String letter = l.substring(id2, con).strip();
            String constellation = l.substring(con, Math.min(l.length(), con + 3)).strip();
            String designation = letter.isEmpty() || letter.equals("_") || constellation.equals("_") ? null : letter + " " + constellation;
            if (!name.isEmpty())
                out.put(hip, new Entry(name, designation));
        }
        return new StarNames(out);
    }
}

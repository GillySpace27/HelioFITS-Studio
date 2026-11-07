package org.helioviewer.jhv.layers.stars;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;

/**
 * The background stars: the Hipparcos catalogue down to V = 9, fetched once from ESA and kept on
 * disk, never shipped with the application.
 *
 * <p><b>Why Hipparcos and not Gaia.</b> Gaia DR3 does not contain the brightest stars: a search of
 * the archive on 2026-11-07 found no Sirius, Vega, Betelgeuse, Regulus, Spica, Antares or Aldebaran
 * (the starfield spike, project file heliad/starfield/spike/results.md). A sky without them is not
 * the sky anyone recognises. Hipparcos has all of them, with B-V colours, and V &lt; 9 is 83,037
 * stars (counted on the archive the same day).
 *
 * <p><b>Why download and not bundle.</b> ESA distributes Hipparcos under CC BY-NC 3.0 IGO. Fetching
 * at first use redistributes nothing; Gilly chose this on 2026-11-07. The credit line is
 * {@link #CREDIT}.
 *
 * <p><b>Why the asynchronous TAP endpoint.</b> The synchronous one refused every request for
 * several minutes during the spike ("maximum number of synchronous queued jobs (150) reached");
 * the asynchronous one worked throughout. Anonymous asynchronous jobs stopped at exactly 50,000
 * rows, so the sky is fetched in declination bands well under that.
 */
public final class StarCatalog {

    public static final String CREDIT = "Stars: Hipparcos catalogue. Credit: ESA (CC BY-NC 3.0 IGO).";
    public static final String LICENCE_URL = "https://www.cosmos.esa.int/web/hipparcos/catalogues";
    public static final double MAX_MAGNITUDE = 9;

    private static final String TAP = "https://gea.esac.esa.int/tap-server/tap/async";
    private static final String FILE_NAME = "hipparcos-v9.csv";
    private static final String HEADER = "hip,ra,de,pmra,pmde,vmag,b_v";
    private static final int[] DEC_BANDS = {-90, -60, -30, 0, 30, 60, 90};
    private static final int ROW_CAP = 50000; // seen in the spike; a band this full may be truncated
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final long POLL_MS = 1500;
    private static final int MAX_POLLS = 200;

    /** Hipparcos main catalogue positions are for epoch J1991.25 (ICRS). */
    public static final double EPOCH_YEAR = 1991.25;

    // ICRS degrees at EPOCH_YEAR, proper motion in mas/yr (pmra includes cos dec), V, B-V (NaN when absent)
    public final double[] ra;
    public final double[] dec;
    public final double[] pmra;
    public final double[] pmdec;
    public final double[] vmag;
    public final double[] bv;

    private StarCatalog(double[] _ra, double[] _dec, double[] _pmra, double[] _pmdec, double[] _vmag, double[] _bv) {
        ra = _ra;
        dec = _dec;
        pmra = _pmra;
        pmdec = _pmdec;
        vmag = _vmag;
        bv = _bv;
    }

    private StarCatalog(int n) {
        this(new double[n], new double[n], new double[n], new double[n], new double[n], new double[n]);
    }

    public int size() {
        return ra.length;
    }

    public static Path cacheFile() {
        return Directories.CATALOGS.getFile().toPath().resolve(FILE_NAME);
    }

    /** The cached copy, or null when there is none yet or it cannot be read. */
    @Nullable
    public static StarCatalog loadCached() {
        Path file = cacheFile();
        if (!Files.isRegularFile(file))
            return null;
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Could not read the star catalogue at " + file, e);
            return null;
        }
    }

    /** Fetches the catalogue from ESA, writes the cache atomically, and returns it. Blocking; call off the GUI thread. */
    public static StarCatalog download() throws IOException, InterruptedException {
        StringBuilder all = new StringBuilder(HEADER).append('\n');
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (int i = 0; i + 1 < DEC_BANDS.length; i++) {
                String body = runJob(client, bandQuery(DEC_BANDS[i], DEC_BANDS[i + 1], i + 2 == DEC_BANDS.length));
                int rows = appendRows(body, all);
                if (rows >= ROW_CAP)
                    throw new IOException("The ESA archive returned " + rows + " rows for one band, which may be truncated");
            }
        }
        StarCatalog catalog = parse(all.toString());
        Path file = cacheFile();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".part");
        Files.writeString(tmp, all, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Log.info("Star catalogue: " + catalog.size() + " stars written to " + file);
        return catalog;
    }

    static String bandQuery(int decFrom, int decTo, boolean lastBand) {
        return "SELECT hip,ra,de,pmra,pmde,vmag,b_v FROM public.hipparcos WHERE vmag < " + (int) MAX_MAGNITUDE
                + " AND de >= " + decFrom + " AND de " + (lastBand ? "<= " : "< ") + decTo;
    }

    private static String runJob(HttpClient client, String adql) throws IOException, InterruptedException {
        String form = "REQUEST=doQuery&LANG=ADQL&FORMAT=csv&PHASE=RUN&QUERY=" + URLEncoder.encode(adql, StandardCharsets.UTF_8);
        HttpRequest create = HttpRequest.newBuilder(URI.create(TAP)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", AppInfo.userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> created = client.send(create, HttpResponse.BodyHandlers.ofString());
        String job = created.headers().firstValue("Location").orElseThrow(
                () -> new IOException("The ESA archive did not start a query (HTTP " + created.statusCode() + ')'));

        for (int i = 0; i < MAX_POLLS; i++) {
            String phase = get(client, job + "/phase").strip();
            switch (phase) {
                case "COMPLETED" -> {
                    return get(client, job + "/results/result");
                }
                case "ERROR", "ABORTED" -> throw new IOException("The ESA archive query ended in " + phase);
                default -> Thread.sleep(POLL_MS);
            }
        }
        throw new IOException("The ESA archive query did not finish in time");
    }

    private static String get(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", AppInfo.userAgent).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IOException("HTTP " + response.statusCode() + " from the ESA archive");
        return response.body();
    }

    /** Appends the data rows of one CSV result (its header line dropped) and returns how many there were. */
    static int appendRows(String csv, StringBuilder out) throws IOException {
        int rows = 0;
        try (BufferedReader r = new BufferedReader(new StringReader(csv))) {
            String header = r.readLine();
            if (header == null || !header.strip().equals(HEADER))
                throw new IOException("Unexpected columns from the ESA archive: " + header);
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isBlank())
                    continue;
                out.append(line).append('\n');
                rows++;
            }
        }
        return rows;
    }

    /** Parses the cached CSV. Rows without a position or a magnitude are skipped (a few Hipparcos entries have none). */
    static StarCatalog parse(String csv) throws IOException {
        String[] lines = csv.split("\n");
        if (lines.length == 0 || !lines[0].strip().equals(HEADER))
            throw new IOException("Not a star catalogue file");
        StarCatalog c = new StarCatalog(lines.length - 1);
        int n = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split(",", -1);
            if (f.length < 7)
                continue;
            double ra = num(f[1]), de = num(f[2]), v = num(f[5]);
            if (Double.isNaN(ra) || Double.isNaN(de) || Double.isNaN(v))
                continue;
            c.ra[n] = ra;
            c.dec[n] = de;
            c.pmra[n] = zeroIfNaN(num(f[3]));
            c.pmdec[n] = zeroIfNaN(num(f[4]));
            c.vmag[n] = v;
            c.bv[n] = num(f[6]);
            n++;
        }
        return n == c.size() ? c : c.trim(n);
    }

    private StarCatalog trim(int n) {
        return new StarCatalog(Arrays.copyOf(ra, n), Arrays.copyOf(dec, n), Arrays.copyOf(pmra, n),
                Arrays.copyOf(pmdec, n), Arrays.copyOf(vmag, n), Arrays.copyOf(bv, n));
    }

    private static double num(String s) {
        s = s.strip();
        if (s.isEmpty())
            return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double zeroIfNaN(double d) {
        return Double.isNaN(d) ? 0 : d;
    }

}

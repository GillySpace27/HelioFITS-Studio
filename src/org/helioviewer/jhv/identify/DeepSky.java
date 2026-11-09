package org.helioviewer.jhv.identify;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.layers.stars.StarSky;
import org.helioviewer.jhv.math.Vec3;

import spice.basic.SpiceErrorException;

/**
 * Star clusters, galaxies and nebulae under the pointer: the optional deep-sky datasets of the
 * Identify panel. They are fixed points on the celestial sphere, so they are placed in each
 * observer's sky the way the stars are (StarSky.raysAt) and found with the same sight line.
 *
 * <p>The objects are OpenNGC (NGC and IC, with the Messier numbers and common names), fetched once
 * from the project's repository and kept on disk beside the star catalogue, like the star tables.
 * Nothing here is typed in from memory: every position, size and magnitude is a row of that file.
 * A row is listed when it has a Messier number or a common name, or is brighter than the panel's
 * magnitude limit. The size is the major axis, so a large galaxy is matched and outlined at its
 * real extent; a row with no size is a point.
 */
public final class DeepSky implements Drawn.Source {

    public static final String CREDIT = "Deep-sky objects: OpenNGC (Mattia Verga et al.), CC BY-SA 4.0.";
    static final String URL = "https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv";
    private static final String FILE_NAME = "openngc-ngc.csv";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** The most objects named under one pointer (a cluster inside a galaxy's disc): more would be a wall of text (judgement). */
    private static final int MAX_HITS = 3;

    /** One catalogue row, positions in degrees (ICRS), size as a radius in degrees, magnitude NaN when not given. */
    record Obj(String name, String id, String details, Kind kind, double raDeg, double decDeg, double radiusDeg, double mag, boolean named) {}

    private static final DeepSky SOURCE = new DeepSky();
    private static final AtomicReference<List<Obj>> catalog = new AtomicReference<>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicReference<Rays> cache = new AtomicReference<>();

    private record Rays(long milli, double lon, double lat, int hash, List<Obj> objects, double[] rays) {}

    public static DeepSky source() {
        return SOURCE;
    }

    @Override
    public List<Drawn.Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
        if (!anyOn())
            return List.of();
        Drawn.Pointer p = Drawn.pointer(vp, mv, x, y);
        Rays r = p == null ? null : rays(p.observer());
        if (r == null)
            return List.of();
        double tol = tolerancePixels * p.perPixel();
        List<Drawn.Hit> out = new ArrayList<>();
        List<Obj> objects = r.objects();
        for (int i = 0; i < objects.size(); i++) {
            Obj o = objects.get(i);
            Vec3 ray = new Vec3(r.rays()[3 * i], r.rays()[3 * i + 1], r.rays()[3 * i + 2]);
            double reach = Math.toRadians(o.radiusDeg()) + tol;
            if (Vec3.dot(ray, p.dir()) < Math.cos(Math.min(reach, Math.PI))) // cheap cull before the atan2
                continue;
            double sep = Identifier.angle(ray, p.dir());
            if (sep <= reach)
                out.add(new Drawn.Hit(o.name(), o.details(), sep / p.perPixel(), Double.NaN, o.kind(), mark(o, ray)));
        }
        out.sort(Comparator.comparingDouble(Drawn.Hit::pixels));
        return out.size() > MAX_HITS ? new ArrayList<>(out.subList(0, MAX_HITS)) : out;
    }

    @Override
    public List<Mark> marks(Viewport vp, MapView mv, Identifier.Target first) {
        if (!anyOn())
            return List.of();
        Rays r = rays(first.observer());
        if (r == null)
            return List.of();
        List<Mark> out = new ArrayList<>();
        for (int i = 0; i < r.objects().size(); i++) {
            Obj o = r.objects().get(i);
            if (!o.named())
                continue; // a ring on every faint galaxy would hide the image; only the ones with a name or a number
            out.add(mark(o, new Vec3(r.rays()[3 * i], r.rays()[3 * i + 1], r.rays()[3 * i + 2])));
        }
        return out;
    }

    private static Mark mark(Obj o, Vec3 ray) {
        return new Mark("dso:" + o.id(), o.kind(), null, observer -> ray, Math.toRadians(o.radiusDeg()));
    }

    private static boolean anyOn() {
        return IdentifySettings.dataClusters() || IdentifySettings.dataGalaxies() || IdentifySettings.dataNebulae();
    }

    private static boolean wanted(Obj o) {
        boolean kind = switch (o.kind()) {
            case CLUSTER -> IdentifySettings.dataClusters();
            case GALAXY -> IdentifySettings.dataGalaxies();
            case DIFFUSE -> IdentifySettings.dataNebulae();
            default -> false;
        };
        return kind && (o.named() || o.mag() <= IdentifySettings.deepSkyMaxMagnitude());
    }

    /** The wanted objects' rays for one observer, kept while the pointer moves over one frame. */
    @Nullable
    private static Rays rays(Position obs) {
        load();
        List<Obj> all = catalog.get();
        if (all == null)
            return null;
        int hash = (IdentifySettings.dataClusters() ? 1 : 0) | (IdentifySettings.dataGalaxies() ? 2 : 0) | (IdentifySettings.dataNebulae() ? 4 : 0)
                | (int) Math.round(IdentifySettings.deepSkyMaxMagnitude() * 10) << 3;
        Rays r = cache.get();
        if (r != null && r.milli() == obs.time.milli && r.lon() == obs.lon && r.lat() == obs.lat && r.hash() == hash)
            return r;
        List<Obj> use = new ArrayList<>();
        for (Obj o : all)
            if (wanted(o))
                use.add(o);
        double[] ra = new double[use.size()], dec = new double[use.size()], rays = new double[3 * use.size()];
        for (int i = 0; i < ra.length; i++) {
            ra[i] = use.get(i).raDeg();
            dec[i] = use.get(i).decDeg();
        }
        try {
            StarSky.raysAt(obs, ra, dec, rays);
        } catch (SpiceErrorException e) { // a time outside the kernels: nothing to place
            Log.warn("Deep-sky directions unavailable at " + obs.time, e);
            return null;
        }
        r = new Rays(obs.time.milli, obs.lon, obs.lat, hash, use, rays);
        cache.set(r);
        return r;
    }

    public static Path cacheFile() {
        return Directories.CATALOGS.getFile().toPath().resolve(FILE_NAME);
    }

    /** Loads the table once, off the GUI thread: the disk copy first, the archive otherwise. */
    private static void load() {
        if (!started.compareAndSet(false, true))
            return;
        Thread t = new Thread(() -> {
            try {
                List<Obj> c = loadCached();
                catalog.set(c != null ? c : download());
            } catch (IOException e) {
                Log.warn("Identify deep sky: the OpenNGC table could not be fetched", e);
                started.set(false); // try again the next time a switch is touched
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "identify-deep-sky");
        t.setDaemon(true);
        t.start();
    }

    @Nullable
    private static List<Obj> loadCached() {
        Path file = cacheFile();
        if (!Files.isRegularFile(file))
            return null;
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.warn("Could not read the deep-sky table at " + file, e);
            return null;
        }
    }

    private static List<Obj> download() throws IOException, InterruptedException {
        String body;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(TIMEOUT)
                    .header("User-Agent", AppInfo.userAgent).GET().build();
            HttpResponse<String> response = org.helioviewer.jhv.activity.NetActivity.send(client, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                throw new IOException("HTTP " + response.statusCode() + " from the OpenNGC table");
            body = response.body();
        }
        List<Obj> objects = parse(body);
        if (objects.isEmpty())
            throw new IOException("The OpenNGC table had no usable rows");
        Path file = cacheFile();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".part");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        Log.info("Deep sky: " + objects.size() + " objects written to " + file);
        return objects;
    }

    /** For checks: use this list instead of loading. */
    static void use(@Nullable List<Obj> objects) {
        started.set(true);
        catalog.set(objects);
        cache.set(null);
    }

    // OpenNGC types: the kind each maps to; everything else (stars, duplicates, non-existent, groups) is left out.
    @Nullable
    static Kind kindOf(String type) {
        return switch (type) {
            case "OCl", "GCl" -> Kind.CLUSTER;
            case "G" -> Kind.GALAXY;
            case "PN", "Neb", "HII", "RfN", "EmN", "SNR", "Cl+N" -> Kind.DIFFUSE;
            default -> null;
        };
    }

    private static String typeWord(String type) {
        return switch (type) {
            case "OCl" -> "open cluster";
            case "GCl" -> "globular cluster";
            case "Cl+N" -> "cluster with nebula";
            case "G" -> "galaxy";
            case "PN" -> "planetary nebula";
            case "Neb" -> "nebula";
            case "HII" -> "HII region";
            case "RfN" -> "reflection nebula";
            case "EmN" -> "emission nebula";
            case "SNR" -> "supernova remnant";
            default -> type;
        };
    }

    /** "NGC0224" to "NGC 224", "IC0001" to "IC 1". */
    static String catalogId(String name) {
        int i = 0;
        while (i < name.length() && !Character.isDigit(name.charAt(i)))
            i++;
        if (i == 0 || i == name.length())
            return name;
        String digits = name.substring(i).replaceFirst("^0+(?=\\d)", "");
        return name.substring(0, i) + " " + digits;
    }

    /** "HH:MM:SS.ss" or "+DD:MM:SS.s" to degrees; hours are scaled by 15. */
    static double sexagesimal(String s, boolean hours) {
        String[] p = s.trim().split(":");
        double sign = p[0].startsWith("-") ? -1 : 1;
        double v = Math.abs(Double.parseDouble(p[0])) + Double.parseDouble(p[1]) / 60 + Double.parseDouble(p[2]) / 3600;
        return sign * v * (hours ? 15 : 1);
    }

    /** Parses the OpenNGC table (semicolon separated, a header row of column names). */
    static List<Obj> parse(String text) throws IOException {
        String[] lines = text.split("\r?\n");
        if (lines.length < 2 || !lines[0].startsWith("Name;Type;RA;Dec"))
            throw new IOException("Not the OpenNGC table");
        String[] head = lines[0].split(";", -1);
        int name = col(head, "Name"), type = col(head, "Type"), ra = col(head, "RA"), dec = col(head, "Dec"),
                maj = col(head, "MajAx"), bmag = col(head, "B-Mag"), vmag = col(head, "V-Mag"), m = col(head, "M"), common = col(head, "Common names");
        List<Obj> out = new ArrayList<>();
        for (int n = 1; n < lines.length; n++) {
            String[] f = lines[n].split(";", -1);
            if (f.length <= common)
                continue;
            Kind kind = kindOf(f[type]);
            if (kind == null || f[ra].isBlank() || f[dec].isBlank())
                continue;
            try {
                String id = catalogId(f[name]);
                String messier = f[m].isBlank() ? null : "M " + Integer.parseInt(f[m].trim());
                String common1 = f[common].isBlank() ? null : f[common].split(",")[0].trim();
                double mag = !f[vmag].isBlank() ? Double.parseDouble(f[vmag]) : !f[bmag].isBlank() ? Double.parseDouble(f[bmag]) : Double.NaN;
                double radius = f[maj].isBlank() ? 0 : Double.parseDouble(f[maj]) / 2 / 60; // major axis in arcmin to a radius in degrees
                String label = common1 != null ? common1 : messier != null ? messier : id;
                String ids = messier != null && !id.equals(messier) ? messier + " = " + id : id;
                String details = typeWord(f[type]) + ", " + ids + (Double.isNaN(mag) ? "" : String.format(", mag %.1f", mag));
                out.add(new Obj(label, id, details, kind, sexagesimal(f[ra], true), sexagesimal(f[dec], false), radius,
                        Double.isNaN(mag) ? 99 : mag, messier != null || common1 != null));
            } catch (RuntimeException e) { // a malformed row is skipped, not the whole table
                Log.warn("Deep sky: skipped row '" + lines[n] + "'", e);
            }
        }
        return out;
    }

    private static int col(String[] head, String name) throws IOException {
        for (int i = 0; i < head.length; i++)
            if (head[i].equals(name))
                return i;
        throw new IOException("The OpenNGC table has no " + name + " column");
    }

    private DeepSky() {}
}

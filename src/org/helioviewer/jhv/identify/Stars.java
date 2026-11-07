package org.helioviewer.jhv.identify;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.stars.StarCatalog;
import org.helioviewer.jhv.layers.stars.StarNames;
import org.helioviewer.jhv.layers.stars.StarSky;
import org.helioviewer.jhv.math.Vec3;

import spice.basic.SpiceErrorException;

/**
 * Stars under the pointer, when View &gt; Identify Stars is on: the Hipparcos catalogue the
 * Stars overlay draws (StarCatalog), at the same directions (StarSky.rays), named by the IAU
 * proper name, the Bayer designation, or the Hipparcos number, as Settings &gt; Identify chooses.
 *
 * <p>Both tables are loaded once, in the background, the first time the switch is on: from the
 * disk cache if there is one, else from the archive, as the Stars overlay does. Until they arrive
 * no star is named. Stars are at infinity, so the direction from any observer is the same; the
 * first target's observer only fixes the frame the probe and the rays share.
 */
public final class Stars {

    private static final AtomicReference<StarCatalog> catalog = new AtomicReference<>();
    private static final AtomicReference<StarNames> names = new AtomicReference<>();
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicReference<Rays> cache = new AtomicReference<>();

    /** The rays for one observer and magnitude limit, kept while the pointer moves over one frame. */
    private record Rays(long milli, double lon, double lat, double magLimit, int count, double[] rays, int[] index) {}

    /** A star under the pointer: its row, how far in pixels, and the label the settings ask for. */
    public record Star(int row, double pixels, String label, String details) {}

    /** The nearest star within the tolerance of canvas point (x, y), or null. */
    @Nullable
    public static Star at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
        if (!IdentifySettings.stars())
            return null;
        load();
        StarCatalog cat = catalog.get();
        if (cat == null)
            return null;
        Identifier.Probe probe = Probes.at(mv, vp, x, y), probe1 = Probes.at(mv, vp, x + 1, y);
        if (probe == null || probe1 == null)
            return null;
        Position obs = IdentifyHover.targets(vp, mv).getFirst().observer();
        if (mv.isOrthographic() && Drawn.orbited(mv.viewRotation(), obs.toQuat()))
            return null; // the orbited view names only what it draws, and the sky behind is no observer's
        Vec3 dir = probe.direction(obs), dir1 = probe1.direction(obs);
        if (dir == null || dir1 == null)
            return null;
        double perPixel = Identifier.angle(dir, dir1);
        if (!(perPixel > 0))
            return null;
        Rays r = rays(obs, cat, IdentifySettings.starMaxMagnitude());
        if (r == null)
            return null;
        int best = nearest(r.rays(), r.count(), dir, Math.cos(tolerancePixels * perPixel));
        if (best < 0)
            return null;
        int row = r.index()[best];
        Vec3 ray = new Vec3(r.rays()[3 * best], r.rays()[3 * best + 1], r.rays()[3 * best + 2]);
        return new Star(row, Identifier.angle(dir, ray) / perPixel, label(cat, names.get(), row,
                IdentifySettings.starNamesIau(), IdentifySettings.starNamesBayer()), details(cat, row));
    }

    /** The index of the ray closest to {@code dir} whose cosine with it is at least {@code minCos}, or -1. */
    static int nearest(double[] rays, int count, Vec3 dir, double minCos) {
        int best = -1;
        double bestCos = minCos;
        for (int i = 0; i < count; i++) {
            double c = rays[3 * i] * dir.x + rays[3 * i + 1] * dir.y + rays[3 * i + 2] * dir.z;
            if (c >= bestCos) {
                bestCos = c;
                best = i;
            }
        }
        return best;
    }

    /** "Sirius", "α CMa" or "HIP 32349", by the switches and what the tables hold; "V 7.12" as the last resort. */
    static String label(StarCatalog cat, @Nullable StarNames table, int row, boolean iau, boolean bayer) {
        int hip = cat.hip[row];
        StarNames.Entry e = table == null || hip <= 0 ? null : table.get(hip);
        if (e != null && iau)
            return e.name();
        if (e != null && bayer && e.designation() != null)
            return e.designation();
        return hip > 0 ? "HIP " + hip : String.format("star V %.2f", cat.vmag[row]);
    }

    static String details(StarCatalog cat, int row) {
        return String.format("  star, V %.2f%s", cat.vmag[row], cat.hip[row] > 0 ? ", HIP " + cat.hip[row] : "");
    }

    @Nullable
    private static Rays rays(Position obs, StarCatalog cat, double magLimit) {
        Rays r = cache.get();
        if (r != null && r.milli() == obs.time.milli && r.lon() == obs.lon && r.lat() == obs.lat && r.magLimit() == magLimit)
            return r;
        double[] rays = new double[3 * cat.size()];
        int[] index = new int[cat.size()];
        try {
            int n = StarSky.rays(obs, cat, magLimit, rays, index);
            r = new Rays(obs.time.milli, obs.lon, obs.lat, magLimit, n, rays, index);
        } catch (SpiceErrorException e) { // SPICE refuses a time outside its kernels: no stars then
            Log.warn("Star directions unavailable at " + obs.time, e);
            return null;
        }
        cache.set(r);
        return r;
    }

    /** Loads the catalogue and the names once, off the GUI thread: the disk cache first, the archive otherwise. */
    private static void load() {
        if (!started.compareAndSet(false, true))
            return;
        Thread t = new Thread(() -> {
            try {
                StarCatalog c = StarCatalog.loadCached();
                catalog.set(c != null ? c : StarCatalog.download());
            } catch (java.io.IOException e) {
                Log.warn("Identify Stars: the star catalogue could not be fetched", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                StarNames n = StarNames.loadCached();
                names.set(n != null ? n : StarNames.download());
            } catch (java.io.IOException e) {
                Log.warn("Identify Stars: the IAU star names could not be fetched; stars are named by Hipparcos number", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "identify-stars");
        t.setDaemon(true);
        t.start();
    }

    /** For checks: use these tables instead of loading. */
    static void use(@Nullable StarCatalog c, @Nullable StarNames n) {
        started.set(true);
        catalog.set(c);
        names.set(n);
        cache.set(null);
    }

    private Stars() {}
}

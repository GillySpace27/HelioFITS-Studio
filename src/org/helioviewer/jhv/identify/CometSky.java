package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.movie.Player;

/**
 * Comets that cross the loaded movie's field, as an Identify dataset: named under the pointer and
 * marked with the icon, each at the plane-of-sky radius and position angle JPL's ephemeris gives it.
 *
 * <p>This is the comet tracker's own data (Comets): JPL's Small-Body Database nominates the
 * candidates near the movie and Horizons decides which ones enter the field, as seen from SOHO.
 * That is exact for LASCO, within about a percent of parallax for anything else on the Sun-Earth
 * line (AIA, PUNCH), and wrong for STEREO or Solar Orbiter; see {@link Comets}. The search runs once
 * per movie range in the background, the first time the switch is on, and needs the network.
 */
public final class CometSky implements Drawn.Source {

    private static final CometSky SOURCE = new CometSky();
    private static final long RETRY_MS = 5 * 60_000L;

    private record Tracked(Comets.Comet comet, Comets.Track track) {}

    /** The answer for one movie range; {@code at} is when it was asked, so a failure can be retried later. */
    private record State(String key, List<Tracked> comets, long at, boolean failed) {}

    private static final AtomicReference<State> state = new AtomicReference<>();
    private static final AtomicBoolean running = new AtomicBoolean();

    public static CometSky source() {
        return SOURCE;
    }

    @Override
    public List<Drawn.Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
        long now = mv.viewpoint().time.milli;
        List<Drawn.Hit> out = new ArrayList<>();
        for (Tracked t : current()) {
            if (!inTrack(t, now))
                continue;
            double radius = t.track().radius(now), pa = t.track().positionAngle(now);
            Drawn.Hit h = Drawn.planeOfSky(vp, mv, x, y, tolerancePixels, t.comet().label(), "comet, JPL ephemeris for SOHO", radius, pa);
            if (h != null)
                out.add(new Drawn.Hit(h.name(), h.kind(), h.pixels(), h.distanceAu(), Kind.COMET, mark(t, radius, pa)));
        }
        return out;
    }

    @Override
    public List<Mark> marks(Viewport vp, MapView mv, Identifier.Target first) {
        long now = mv.viewpoint().time.milli;
        List<Mark> out = new ArrayList<>();
        for (Tracked t : current())
            if (inTrack(t, now))
                out.add(mark(t, t.track().radius(now), t.track().positionAngle(now)));
        return out;
    }

    private static boolean inTrack(Tracked t, long now) {
        long[] m = t.track().milli();
        return now >= m[0] && now <= m[m.length - 1]; // off the sampled track there is no position to name
    }

    private static Mark mark(Tracked t, double radius, double paDeg) {
        double theta = Math.toRadians(paDeg);
        return new Mark("comet:" + t.comet().designation(), Kind.COMET, null,
                Probes.plane(PolarBasis.x(radius, theta), PolarBasis.y(radius, theta)), 0);
    }

    /** The comets for the loaded movie, or none while they are still being fetched (or when the switch is off). */
    private static List<Tracked> current() {
        if (!IdentifySettings.dataComets() || !Player.isAvailable())
            return List.of();
        long start = Player.getStartTime(), end = Player.getEndTime();
        String key = start + "-" + end;
        State s = state.get();
        if (s != null && s.key().equals(key) && (!s.failed() || System.currentTimeMillis() - s.at() < RETRY_MS))
            return s.comets();
        if (running.compareAndSet(false, true)) {
            double fov = ImageLayers.getLargestRadialSize();
            org.helioviewer.jhv.thread.Task.submitBackground("identify-comets", () -> {
                List<Tracked> found = new ArrayList<>();
                for (Comets.Sighting c : Comets.search(start, end, fov > 1 ? fov : 30, msg -> {}))
                    found.add(new Tracked(c.comet(), Comets.ephemeris(c.comet(), start, end)));
                return found;
            }, found -> {
                state.set(new State(key, found, System.currentTimeMillis(), false));
                Log.info("Identify comets: " + found.size() + " in the field of the loaded movie");
                done();
            }, (logContext, t) -> {
                Log.warn("Identify comets: JPL did not answer; trying again in a few minutes", t);
                state.set(new State(key, List.of(), System.currentTimeMillis(), true));
                done();
            });
        }
        return s != null && s.key().equals(key) ? s.comets() : List.of();
    }

    private static void done() {
        running.set(false);
        DisplayController.display();
    }

    private CometSky() {}
}

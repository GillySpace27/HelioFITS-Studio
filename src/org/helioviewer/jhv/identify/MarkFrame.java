package org.helioviewer.jhv.identify;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Vec3;

/**
 * What the glow needs from the sky that does not depend on the view: the list of marks and where
 * each is in an observer's sky (an ephemeris lookup for a body). Both change with the frame time,
 * the layers' observers and the Identify switches, and with nothing else, so they are worked out
 * once for a frame time and reused for every frame at that time: panning, zooming, warping and
 * cropping move the marks on the page, not in the sky, and only the cheap placement is redone.
 *
 * <p>There is deliberately no cache of where a mark lands on the page: that depends on the whole
 * view and misses on every frame of exactly the interactions that need to be quick.
 *
 * <p>The datasets arrive late (the star tables, the comet search, the event layer), and none of
 * that is in the key, so a frame is also dropped after {@link #TTL_NANOS}: a late list shows up
 * within a second, a switch within the same frame.
 */
final class MarkFrame {

    private static final long TTL_NANOS = 1_000_000_000L;
    private static final AtomicReference<MarkFrame> LAST = new AtomicReference<>();
    /** Mark lists built, for the cost guard. */
    static final LongAdder BUILDS = new LongAdder();

    private final long milli;
    private final List<Position> observers;
    private final List<String> names;
    private final String settings;
    private final long born = System.nanoTime();
    private List<Mark> all;
    private final Map<String, Vec3> directions = new HashMap<>();

    private MarkFrame(long _milli, List<Position> _observers, List<String> _names, String _settings) {
        milli = _milli;
        observers = _observers;
        names = _names;
        settings = _settings;
    }

    /** The frame for this view's time, layers and switches: the last one when nothing in the key has changed. */
    static MarkFrame of(MapView mv, List<Identifier.Target> targets) {
        long milli = mv.viewpoint().time.milli;
        List<Position> observers = targets.stream().map(Identifier.Target::observer).toList();
        List<String> names = targets.stream().map(Identifier.Target::name).toList();
        String settings = signature();
        MarkFrame last = LAST.get();
        if (last != null && last.milli == milli && last.settings.equals(settings) && last.observers.equals(observers)
                && last.names.equals(names) && System.nanoTime() - last.born < TTL_NANOS)
            return last;
        MarkFrame fresh = new MarkFrame(milli, observers, names, settings);
        LAST.set(fresh);
        return fresh;
    }

    /** Everything in the Identify switches that decides which marks exist, as one string. */
    private static String signature() {
        return IdentifySettings.lightTime() + "," + IdentifySettings.pluto() + "," + IdentifySettings.earthUncertaintyKm() + ","
                + IdentifySettings.stars() + "," + IdentifySettings.starMaxMagnitude() + "," + IdentifySettings.dataComets() + ","
                + IdentifySettings.dataCmes() + "," + IdentifySettings.dataClusters() + "," + IdentifySettings.dataGalaxies() + ","
                + IdentifySettings.dataNebulae() + "," + IdentifySettings.deepSkyMaxMagnitude();
    }

    /** Every named thing the view holds, built once for this frame. */
    synchronized List<Mark> marks(Viewport vp, MapView mv, List<Identifier.Target> targets) {
        if (all == null) {
            all = IdentifyGlow.allMarks(vp, mv, targets);
            BUILDS.increment();
        }
        return all;
    }

    /** Where {@code m} is in {@code t}'s sky at this frame's time, or null; looked up once per mark and layer. */
    @Nullable
    synchronized Vec3 direction(Mark m, Identifier.Target t) {
        String key = m.key() + '\u0000' + t.name();
        if (directions.containsKey(key))
            return directions.get(key);
        Vec3 dir = m.where().direction(t.observer());
        directions.put(key, dir);
        return dir;
    }
}

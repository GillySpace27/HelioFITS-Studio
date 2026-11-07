package org.helioviewer.jhv.layers;

import java.util.concurrent.Future;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Frame;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.PositionLoad;
import org.helioviewer.jhv.astronomy.PositionResponse;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.io.HelioFleet;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;

import spice.basic.SpiceErrorException;

/**
 * Where a model layer's target is at a given time, in the Carrington frame.
 *
 * <p>Three sources. Bodies with a bundled kernel (planets, Moon, Solar Orbiter, STEREO Ahead) are
 * asked of SPICE directly, per frame, which is local and exact. Parker Solar Probe, SOHO, SDO,
 * STEREO Behind, BepiColombo and PROBA-2 come from the ROB position service through PositionLoad,
 * as the viewpoint layer does (docs/data-sources.md, "Models and positions"). Spacecraft neither
 * knows (ACE, Wind, DSCOVR, Ulysses, MESSENGER) come from HelioFleet or JPL Horizons
 * ({@link ExternalEphemeris}). The network sources make one request covering the movie interval,
 * interpolated per frame.
 *
 * <p>No position means nothing is drawn: never a fallback to the origin, which would put a false
 * spacecraft on the Sun (the rule PlanetMarkers keeps too).
 */
public final class ModelEphemeris { // public for the FOV layer's spacecraft not in the kernels

    private static final String CARRINGTON = Frame.SOLO_IAU_SUN_2009.toString();
    /** Padding around the movie interval, so a still frame (start == end) still gets a short series. */
    private static final long PAD = 3600_000L;

    @Nullable
    private SpaceObject target;
    @Nullable
    private ExternalEphemeris.Source external;
    private String targetName = "";
    @Nullable
    private PositionLoad load;
    @Nullable
    private Future<HelioFleet.Samples> externalLoad;
    @Nullable
    private volatile HelioFleet.Samples samples;
    private long loadStart, loadEnd;
    private volatile String status = "";
    /** Bumped by every cancel, so a request that lands after the target or interval moved on is dropped. */
    private volatile int generation;
    private final PositionResponse.Interpolated interpolated = new PositionResponse.Interpolated();
    private final double[] xyz = new double[3];

    /** A SpaceObject name or one of {@link ExternalEphemeris#names()}; anything else places nothing. */
    public void setTarget(@Nullable String name) {
        String n = name == null ? "" : name;
        if (n.equals(targetName) && (target != null || external != null))
            return;
        cancel();
        targetName = n;
        external = ExternalEphemeris.get(n);
        target = external == null ? SpaceObject.get(n) : null;
        status = "";
    }

    public String status() {
        return status;
    }

    public void cancel() {
        if (load != null)
            load.cancel();
        load = null;
        if (externalLoad != null)
            externalLoad.cancel(true);
        externalLoad = null;
        samples = null;
        generation++;
    }

    @Nullable
    public Position at(JHVTime time) {
        if (external != null)
            return fromExternal(external, time);
        if (target == null) {
            status = "No object chosen";
            return null;
        }
        if (target.isInternal()) {
            try {
                double[] v = Spice.getPosition("SUN", target.getSpiceName(), CARRINGTON, time);
                if (v[0] == 0 && v[1] == 0 && v[2] == 0) {
                    status = "No position for " + target + " at " + time;
                    return null;
                }
                status = "";
                return ModelPlacement.carringtonPosition(time, v[0], v[1], v[2]);
            } catch (SpiceErrorException e) { // outside the kernel's coverage: say so, do not log every frame
                status = "No position for " + target + " at " + time + " (outside the bundled ephemeris)";
                return null;
            }
        }
        return fromService(time);
    }

    /**
     * The movie's span padded by an hour, widened only for a time outside that, so times inside the
     * pad (the FOV layer's direction of travel, half an hour either side) reuse the same request.
     */
    private long[] interval(JHVTime time) {
        long lo = Player.getStartTime() - PAD, hi = Player.getEndTime() + PAD;
        return new long[]{time.milli < lo ? time.milli - PAD : lo, time.milli > hi ? time.milli + PAD : hi};
    }

    @Nullable
    private Position fromService(JHVTime time) {
        long[] iv = interval(time);
        // A new request only when the interval moves; a failed one is not retried every frame.
        if (load == null || iv[0] != loadStart || iv[1] != loadEnd) {
            cancel();
            loadStart = iv[0];
            loadEnd = iv[1];
            load = PositionLoad.submit(s -> {
                status = s == null || "Loaded".equals(s) ? "" : s;
                DisplayController.display();
            }, SpaceObject.SUN, target, Frame.SOLO_IAU_SUN_2009, loadStart, loadEnd);
        }
        PositionResponse response = load.getResponse();
        if (response == null) {
            if (status.isEmpty())
                status = "Loading positions for " + target + "...";
            return null;
        }
        response.interpolateRectangular(time.milli, loadStart, loadEnd, xyz, interpolated);
        return ModelPlacement.carringtonPosition(time, xyz[0], xyz[1], xyz[2]);
    }

    @Nullable
    private Position fromExternal(ExternalEphemeris.Source src, JHVTime time) {
        long[] iv = interval(time);
        if (externalLoad == null || iv[0] != loadStart || iv[1] != loadEnd) {
            cancel();
            loadStart = iv[0];
            loadEnd = iv[1];
            long s0 = loadStart, s1 = loadEnd;
            status = "Loading positions for " + src.name() + " from " + (src.kind() == ExternalEphemeris.Kind.HELIOFLEET ? "HelioFleet" : "JPL Horizons") + "...";
            int mine = generation;
            externalLoad = Task.submitBackground(() -> ExternalEphemeris.fetch(src, s0, s1), got -> {
                if (mine != generation)
                    return; // the interval or the target moved on
                samples = got;
                status = "";
                DisplayController.display();
            }, e -> {
                if (mine != generation)
                    return;
                status = "No position for " + src.name() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                DisplayController.display();
            });
        }
        HelioFleet.Samples s = samples;
        if (s == null)
            return null;
        try {
            Position p = ExternalEphemeris.at(s, time);
            status = p == null ? "No position for " + src.name() + " at " + time + " (" + src.coverage() + ")" : "";
            return p;
        } catch (SpiceErrorException e) {
            status = "No position for " + src.name() + " at " + time + " (Earth or the frame is outside the bundled kernels)";
            return null;
        }
    }

}

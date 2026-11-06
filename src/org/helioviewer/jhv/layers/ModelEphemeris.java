package org.helioviewer.jhv.layers;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Frame;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.PositionLoad;
import org.helioviewer.jhv.astronomy.PositionResponse;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.JHVTime;

import spice.basic.SpiceErrorException;

/**
 * Where a model layer's target is at a given time, in the Carrington frame.
 *
 * <p>Two sources, the same two the rest of Studio uses (docs/data-sources.md, "Models and
 * positions"). Bodies with a bundled kernel (planets, Moon, Solar Orbiter, STEREO Ahead) are asked
 * of SPICE directly, per frame, which is local and exact. The others (Parker Solar Probe, SOHO, SDO,
 * STEREO Behind, BepiColombo, PROBA-2) come from the ROB position service through PositionLoad, one
 * request covering the movie interval, interpolated per frame as the viewpoint layer does.
 *
 * <p>No position means nothing is drawn: never a fallback to the origin, which would put a false
 * spacecraft on the Sun (the rule PlanetMarkers keeps too).
 */
final class ModelEphemeris {

    private static final String CARRINGTON = Frame.SOLO_IAU_SUN_2009.toString();
    /** Padding around the movie interval, so a still frame (start == end) still gets a short series. */
    private static final long PAD = 3600_000L;

    @Nullable
    private SpaceObject target;
    @Nullable
    private PositionLoad load;
    private long loadStart, loadEnd;
    private String status = "";
    private final PositionResponse.Interpolated interpolated = new PositionResponse.Interpolated();
    private final double[] xyz = new double[3];

    void setTarget(@Nullable SpaceObject _target) {
        if (_target != null && _target.equals(target))
            return;
        cancel();
        target = _target;
        status = "";
    }

    String status() {
        return status;
    }

    void cancel() {
        if (load != null)
            load.cancel();
        load = null;
    }

    @Nullable
    Position at(JHVTime time) {
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

    @Nullable
    private Position fromService(JHVTime time) {
        long start = Math.min(Player.getStartTime(), time.milli) - PAD;
        long end = Math.max(Player.getEndTime(), time.milli) + PAD;
        // A new request only when the interval moves; a failed one is not retried every frame.
        if (load == null || start != loadStart || end != loadEnd) {
            cancel();
            loadStart = start;
            loadEnd = end;
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

}

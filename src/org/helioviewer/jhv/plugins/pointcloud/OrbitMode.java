package org.helioviewer.jhv.plugins.pointcloud;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

// Orbits the camera about a heliographic axis so a 3-D structure can be inspected and filmed
// from all sides. Two drivers, because a point cloud and a time series want opposite things:
//
//   TURNTABLE — the structure is static and the ORBIT supplies the frames. Installs its own
//               master clock of `framesPerRev` timestamps spaced one millisecond apart, so
//               every frame resolves to the same cloud and the same image, and only the camera
//               moves. This is the "rotation movie about solar north" shape.
//   PLAYBACK  — the data is a real time series and the orbit rides along: angle advances at a
//               fixed rate per second of movie time, so a long event slowly turns as it evolves.
//
// Export needs no special handling in either case. JHV's movie export advances Player frames
// and this is a Player frame listener, so whatever the viewer shows is what gets written out.
//
// The rotation is applied as the DELTA between consecutive frames via rotateDragRotation, never
// as an absolute set. That keeps any manual drag the user has dialled in, composes exactly
// because every delta is about the same axis, and needs no new setter on Camera.
//
// KNOWN LIMITATION (verified 2026-08-23). TURNTABLE works only when nothing else is driving the
// movie clock, i.e. no image layer or cloud time series is loaded. It gets its stationary subject
// by installing its OWN master clock of framesPerRev timestamps one millisecond apart, and that
// only holds while it is the sole claimant. With a real time series loaded, that clock is either
// refused or it flattens the series, so you cannot currently orbit a structure that is also
// evolving in time. PLAYBACK is the mode for that case, but it ties angular rate to movie time
// rather than giving a clean rotation independent of playback.
//
// Making TURNTABLE work in general is UNIMPLEMENTED. It needs the camera orbit decoupled from the
// Player frame sequence altogether (its own animation source, driven by wall time or an export
// frame counter) instead of borrowing the master clock to manufacture frames. Left as-is because
// the standalone rotation movie is the case that was actually needed.
final class OrbitMode implements Player.Listener {

    enum Driver {TURNTABLE, PLAYBACK}

    private static final OrbitMode INSTANCE = new OrbitMode();
    private static final long TURNTABLE_STEP_MILLI = 1; // frames differ, the data they resolve to does not

    private boolean enabled;
    private Driver driver = Driver.TURNTABLE;
    private double axisLon = 0;    // Stonyhurst; 0/90 is solar north, see setAxisSolarNorth
    private double axisLat = 90;
    private double degPerSec = 6;  // PLAYBACK: degrees per second of movie time
    private int framesPerRev = 180;

    private boolean listening;
    private double appliedAngle;   // degrees already folded into the camera
    private long turntableStart;

    static OrbitMode get() {
        return INSTANCE;
    }

    // The orbit axis as a scene-space unit vector. Shares PointCloudArrow's convention on
    // purpose: "orbit about the CME axis" then means exactly the axis the cone is drawn on.
    private double[] axis() {
        return PointCloudArrow.direction(axisLon, axisLat);
    }

    private double angleAt(int frame) {
        if (driver == Driver.PLAYBACK) {
            long t = Player.getTime().milli;
            return degPerSec * (t - Player.getStartTime()) / 1000.;
        }
        return framesPerRev <= 0 ? 0 : 360. * (frame % framesPerRev) / framesPerRev;
    }

    @Override
    public void frameChanged(int frame, boolean last) {
        if (!enabled)
            return;
        apply(angleAt(frame));
    }

    // Rotate by however much has not been applied yet. Going through the shortest signed
    // difference means a wrap from 359 back to 0 turns 1 degree forward, not 359 backward.
    private void apply(double angle) {
        double delta = angle - appliedAngle;
        delta -= 360 * Math.floor((delta + 180) / 360);
        if (delta == 0)
            return;
        appliedAngle = angle;

        double[] a = axis();
        double half = Math.toRadians(delta) / 2;
        double s = Math.sin(half);
        Display.getCamera().rotateDragRotation(
                new Quat(Math.cos(half), s * a[0], s * a[1], s * a[2]));
        DisplayController.display();
    }

    void setEnabled(boolean v) {
        if (v == enabled)
            return;
        enabled = v;
        if (!listening && v) {
            Player.addFrameListener(this);
            listening = true;
        }
        if (v) {
            appliedAngle = 0;
            if (driver == Driver.TURNTABLE)
                installTurntableClock();
            frameChanged(0, false);
        } else if (driver == Driver.TURNTABLE)
            Layers.setPlaceholderMasterTimes(List.of()); // hand the clock back
    }

    // Synthesizes the frame sequence a turntable needs. One millisecond apart so that every
    // consumer that resolves data by nearest-time — the cloud, and any image layer — returns
    // the identical frame throughout the revolution.
    //
    // Only takes effect when no image layer owns the clock (Layers ignores it otherwise), so
    // with LASCO loaded the turntable falls back to whatever frames that movie has. Documented
    // rather than worked around: seizing the clock from a real image layer would be worse.
    private void installTurntableClock() {
        turntableStart = Player.getTime().milli;
        List<JHVTime> times = new ArrayList<>(framesPerRev);
        for (int i = 0; i < framesPerRev; i++)
            times.add(new JHVTime(turntableStart + i * TURNTABLE_STEP_MILLI));
        Layers.setPlaceholderMasterTimes(times);
    }

    boolean getEnabled() {
        return enabled;
    }

    Driver getDriver() {
        return driver;
    }

    void setDriver(Driver v) {
        driver = v;
        if (enabled) { // re-arm under the new driver
            setEnabled(false);
            setEnabled(true);
        }
    }

    double getAxisLon() {
        return axisLon;
    }

    void setAxisLon(double v) {
        axisLon = v;
    }

    double getAxisLat() {
        return axisLat;
    }

    void setAxisLat(double v) {
        axisLat = v;
    }

    double getDegPerSec() {
        return degPerSec;
    }

    void setDegPerSec(double v) {
        degPerSec = v;
    }

    int getFramesPerRev() {
        return framesPerRev;
    }

    void setFramesPerRev(int v) {
        framesPerRev = Math.max(2, v);
        if (enabled && driver == Driver.TURNTABLE) {
            setEnabled(false);
            setEnabled(true);
        }
    }

    void serialize(JSONObject jo) {
        jo.put("orbitDriver", driver.name());
        jo.put("orbitAxisLon", axisLon);
        jo.put("orbitAxisLat", axisLat);
        jo.put("orbitDegPerSec", degPerSec);
        jo.put("orbitFramesPerRev", framesPerRev);
        // Deliberately not persisting `enabled`: restoring a state should not start moving the
        // camera on its own.
    }

    void deserialize(JSONObject jo) {
        try {
            driver = Driver.valueOf(jo.optString("orbitDriver", driver.name()));
        } catch (RuntimeException ignore) {}
        axisLon = Math.clamp(jo.optDouble("orbitAxisLon", axisLon), -360, 360);
        axisLat = Math.clamp(jo.optDouble("orbitAxisLat", axisLat), -90, 90);
        degPerSec = Math.clamp(jo.optDouble("orbitDegPerSec", degPerSec), -360, 360);
        framesPerRev = Math.clamp(jo.optInt("orbitFramesPerRev", framesPerRev), 2, 3600);
    }

    // Self-check: java -cp bin org.helioviewer.jhv.plugins.pointcloud.OrbitMode
    public static void main(String[] args) {
        OrbitMode o = new OrbitMode();

        // Solar north is lat +90, and at that latitude longitude is degenerate, so the axis
        // must come out as the scene's north (y) regardless of the longitude alongside it.
        for (double lon : new double[]{0, 45, -170, 300}) {
            o.axisLon = lon;
            o.axisLat = 90;
            double[] a = o.axis();
            if (Math.abs(a[0]) > 1e-12 || Math.abs(a[1] - 1) > 1e-12 || Math.abs(a[2]) > 1e-12)
                throw new AssertionError("solar-north axis wrong at lon=" + lon);
        }

        // A turntable must close exactly: the per-frame deltas over one revolution have to sum
        // to 360, with the 359->0 wrap counted as a step forward rather than a leap back.
        o.driver = Driver.TURNTABLE;
        o.framesPerRev = 180;
        double applied = 0, total = 0;
        for (int frame = 0; frame <= 180; frame++) {
            double angle = o.angleAt(frame);
            double delta = angle - applied;
            delta -= 360 * Math.floor((delta + 180) / 360);
            applied = angle;
            total += delta;
            if (Math.abs(delta) > 180)
                throw new AssertionError("delta took the long way round: " + delta);
        }
        if (Math.abs(total - 360) > 1e-9)
            throw new AssertionError("one revolution summed to " + total + ", expected 360");

        // Frame counts that do not divide 360 evenly must still close.
        for (int n : new int[]{7, 47, 360, 1000}) {
            o.framesPerRev = n;
            applied = 0;
            total = 0;
            for (int frame = 0; frame <= n; frame++) {
                double angle = o.angleAt(frame);
                double delta = angle - applied;
                delta -= 360 * Math.floor((delta + 180) / 360);
                applied = angle;
                total += delta;
            }
            if (Math.abs(total - 360) > 1e-9)
                throw new AssertionError("framesPerRev=" + n + " summed to " + total);
        }

        System.out.println("OrbitMode self-check passed (axis degeneracy, revolution closure)");
    }

}

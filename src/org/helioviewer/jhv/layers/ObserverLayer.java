package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

// Where the scene is watched from: orbits the camera about a heliographic axis so the corona can
// be inspected and filmed from all sides. Ticking the layer starts the orbit, which is why this
// is a layer rather than a button -- the layer list is where "is this acting on my scene?" is
// already answered for everything else.
//
// It began inside the point-cloud plugin, where the axis presets had a CME arrow to point at, but
// it drives the camera globally and needs no cloud, so keeping it there hid it behind a plugin
// that most sessions never load. The one thing lost in the move is the "CME axis" preset, which
// read the arrow's own lon/lat; core cannot reach into a plugin for that.
//
// Two drivers, because a static structure and a time series want opposite things:
//
//   TURNTABLE - the ORBIT supplies the frames. Installs its own master clock of framesPerRev
//               timestamps one millisecond apart, so every frame resolves to the same data and
//               only the camera moves. The "rotation movie about solar north" shape.
//   PLAYBACK  - the data is a real time series and the orbit rides along: the angle advances at a
//               fixed rate per second of movie time, so a long event slowly turns as it evolves.
//
// Export needs no special handling either way. JHV's movie export advances Player frames and this
// is a Player frame listener, so what the viewer shows is what gets written.
//
// The rotation is applied as the DELTA between consecutive frames via rotateDragRotation, never as
// an absolute set. That keeps any manual drag already dialled in, composes exactly because every
// delta is about the same axis, and needs no new setter on Camera.
//
// KNOWN LIMITATION (verified 2026-08-23, unchanged by the move). TURNTABLE gets its stationary
// subject by seizing the master clock, which only holds while nothing else claims it. With an
// image layer loaded the placeholder clock is ignored and the orbit instead advances over that
// movie's own frames -- still a recordable orbit, but the data evolves as it turns rather than
// standing still. Holding real imagery still while orbiting needs the camera decoupled from the
// Player frame sequence altogether, which is UNIMPLEMENTED.
public final class ObserverLayer extends AbstractLayer implements Player.Listener {

    public enum Driver {
        TURNTABLE("Turntable"), PLAYBACK("Playback");

        private final String label;

        Driver(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final long TURNTABLE_STEP_MILLI = 1; // frames differ, the data they resolve to does not

    private Driver driver = Driver.TURNTABLE;
    private double axisLon;        // Stonyhurst; lat 90 is solar north, where longitude is degenerate
    private double axisLat = 90;
    private double degPerSec = 6;  // PLAYBACK: degrees per second of movie time
    private int framesPerRev = 180;

    private boolean listening;
    private double appliedAngle;   // degrees already folded into the camera

    public ObserverLayer(JSONObject jo) {
        if (jo != null)
            deserialize(jo);
        // No else-branch enabling this the way GridLayer and TimestampLayer have: an observer that
        // armed itself would start moving the camera the moment the app opened.
    }

    // The orbit axis as a scene-space unit vector. Package-private, not private, so
    // extra/test/ObserverOrbitCheck.java can assert on it without a live camera.
    double[] axis() {
        double lon = Math.toRadians(axisLon), lat = Math.toRadians(axisLat);
        double cosLat = Math.cos(lat);
        return new double[]{cosLat * Math.sin(lon), Math.sin(lat), cosLat * Math.cos(lon)};
    }

    double angleAt(int frame) {
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

    @Override
    public void setEnabled(boolean _enabled) {
        if (_enabled == enabled) {
            super.setEnabled(_enabled); // still refresh isVisible[], which multiview rearranges
            return;
        }
        super.setEnabled(_enabled);

        if (!listening && _enabled) {
            Player.addFrameListener(this);
            listening = true;
        }
        if (_enabled) {
            appliedAngle = 0;
            if (driver == Driver.TURNTABLE)
                installTurntableClock();
            frameChanged(0, false);
        } else if (driver == Driver.TURNTABLE)
            Layers.setPlaceholderMasterTimes(List.of()); // hand the clock back
    }

    // Synthesizes the frame sequence a turntable needs. One millisecond apart so that every
    // consumer resolving data by nearest time returns the identical frame throughout the
    // revolution. Ignored while an image layer owns the clock -- see the limitation above.
    private void installTurntableClock() {
        long start = Player.getTime().milli;
        List<JHVTime> times = new ArrayList<>(framesPerRev);
        for (int i = 0; i < framesPerRev; i++)
            times.add(new JHVTime(start + i * TURNTABLE_STEP_MILLI));
        Layers.setPlaceholderMasterTimes(times);
    }

    public Driver getDriver() {
        return driver;
    }

    public void setDriver(Driver v) {
        driver = v;
        rearm();
    }

    public double getAxisLon() {
        return axisLon;
    }

    public void setAxisLon(double v) {
        axisLon = v;
    }

    public double getAxisLat() {
        return axisLat;
    }

    public void setAxisLat(double v) {
        axisLat = v;
    }

    public double getDegPerSec() {
        return degPerSec;
    }

    public void setDegPerSec(double v) {
        degPerSec = v;
    }

    public int getFramesPerRev() {
        return framesPerRev;
    }

    public void setFramesPerRev(int v) {
        framesPerRev = Math.max(2, v);
        if (driver == Driver.TURNTABLE)
            rearm();
    }

    // A live orbit has a clock and a zero angle already committed; changing what either means
    // has to go through the arm path rather than be patched underneath it.
    private void rearm() {
        if (!enabled)
            return;
        setEnabled(false);
        setEnabled(true);
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("driver", driver.name());
        jo.put("axisLon", axisLon);
        jo.put("axisLat", axisLat);
        jo.put("degPerSec", degPerSec);
        jo.put("framesPerRev", framesPerRev);
    }

    private void deserialize(JSONObject jo) {
        try {
            driver = Driver.valueOf(jo.optString("driver", driver.name()));
        } catch (RuntimeException ignore) {}
        axisLon = Math.clamp(jo.optDouble("axisLon", axisLon), -360, 360);
        axisLat = Math.clamp(jo.optDouble("axisLat", axisLat), -90, 90);
        degPerSec = Math.clamp(jo.optDouble("degPerSec", degPerSec), -360, 360);
        framesPerRev = Math.clamp(jo.optInt("framesPerRev", framesPerRev), 2, 3600);
    }

    @Override
    public String getName() {
        return "Camera";
    }

    @Override
    public void init() {}

    @Override
    public void remove() {
        dispose();
    }

    // Restore builds a fresh layer and drops this one. Without unhooking, the discarded instance
    // stays a Player frame listener and goes on turning the camera alongside its replacement.
    @Override
    public void dispose() {
        if (enabled)
            setEnabled(false);
        if (listening) {
            Player.removeFrameListener(this);
            listening = false;
        }
    }

}

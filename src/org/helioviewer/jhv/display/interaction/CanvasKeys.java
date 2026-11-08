package org.helioviewer.jhv.display.interaction;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.app.SceneUndo;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.KeyInputEvent;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.opengl.GLText;

/**
 * The keys the canvas answers to while it has focus (Gilly, 2026-11-08): W and S pitch, A and D yaw
 * and Q and E roll the scene, Up and Down change the playback rate. The arrows' stepping and Space
 * stay where they were ({@link Interaction}).
 *
 * <p>They are bare keys on purpose: the canvas is the one place they cannot be mistaken for typing,
 * and a held key turning the scene smoothly is the point. So they are not accelerators in the
 * {@link org.helioviewer.jhv.gui.ActionCatalog} (the catalogue refuses a key with no modifier, so that
 * it can never shadow a text field); they have their own table here, each key rebindable in
 * Settings &gt; Keybindings &gt; Canvas keys, kept as {@code canvasKeys.<id>} in the settings.
 *
 * <p>Signs are "grab the Sun": the key moves the near side of the scene the way a drag in that
 * direction would (D to the right, W up), counter-clockwise for Q. <b>Invert</b> flips pitch and
 * yaw. Option held makes it slow, for fine framing. Shift on the canvas means annotate, and Cmd and
 * Ctrl pick the pan and axis modes, so none of those three is ever taken as a camera key.
 *
 * <p>The turn is applied to the camera's drag rotation, as a trackball drag is, so it is part of the
 * saved scene and of undo; a hold is one undo step ({@link SceneUndo#keysDown}). It applies to the
 * 3D views. In Observer Sky, pitch and yaw aim the look direction as a drag does. Roll, and every
 * attitude key in the flat views, does nothing there (there is no camera attitude to turn).
 *
 * <p>While recording they are off unless Record live changes is on: what is turned during a recording
 * is in its pixels, but a replay from the sidecar cannot repeat it until the camera has an
 * automation track.
 */
public final class CanvasKeys {

    /** One bindable key: id (API, never renamed), name, default AWT key code, what it does. */
    public record Cmd(String id, String title, int defaultKey, String what) {}

    public static final List<Cmd> ALL = List.of(
            new Cmd("canvasPitchUp", "Pitch Up", KeyEvent.VK_W, "Turn the scene so its near side moves up"),
            new Cmd("canvasPitchDown", "Pitch Down", KeyEvent.VK_S, "Turn the scene so its near side moves down"),
            new Cmd("canvasYawLeft", "Yaw Left", KeyEvent.VK_A, "Turn the scene so its near side moves left"),
            new Cmd("canvasYawRight", "Yaw Right", KeyEvent.VK_D, "Turn the scene so its near side moves right"),
            new Cmd("canvasRollLeft", "Roll Counter-clockwise", KeyEvent.VK_Q, "Turn the scene counter-clockwise about the line of sight"),
            new Cmd("canvasRollRight", "Roll Clockwise", KeyEvent.VK_E, "Turn the scene clockwise about the line of sight"),
            new Cmd("canvasFasterPlayback", "Faster Playback", KeyEvent.VK_UP, "Raise the playback rate by one"),
            new Cmd("canvasSlowerPlayback", "Slower Playback", KeyEvent.VK_DOWN, "Lower the playback rate by one"));

    public static final String PREFIX = "canvasKeys.";
    public static final String INVERT = PREFIX + "invert";
    public static final String RATE = PREFIX + "degreesPerSecond";
    /** The Record panel's switch: keep the attitude keys live while recording. */
    public static final String LIVE_WHILE_RECORDING = "record.liveChanges";

    /** Turn rate, degrees a second (judgement: a quarter turn in two seconds), and the factor Option takes off it. */
    public static final double DEFAULT_DEGREES_PER_SECOND = 45;
    static final double SLOW = 0.2;
    static final int TICK_MS = 16;
    private static final String NONE = "none";
    private static final long TOAST_MS = 1400;

    private static final class State {
        final Set<String> down = new LinkedHashSet<>();
        boolean slow;
        long lastNanos;
        boolean sky; // a look-around was steered: commit it when the keys are let go
        String toast = "";
        long toastUntil;
    }

    private static final State STATE = new State();
    private static final Timer TICK = new Timer(TICK_MS, e -> tick());

    private CanvasKeys() {}

    // ---- the binding table ----

    /** The key code {@code id} answers to now, or -1 for none. */
    public static int key(String id) {
        String stored = Settings.getProperty(PREFIX + id);
        if (stored != null && !stored.isBlank()) {
            if (stored.equals(NONE))
                return -1;
            try {
                return KeyEvent.class.getField("VK_" + stored).getInt(null);
            } catch (ReflectiveOperationException e) {
                // an unreadable value keeps the default
            }
        }
        for (Cmd c : ALL)
            if (c.id().equals(id))
                return c.defaultKey();
        return -1;
    }

    /** Bind {@code id} to key code {@code code} (-1 for none). Returns why it was refused, or null. */
    @Nullable
    public static String set(String id, int code) {
        if (code >= 0) {
            if (name(code) == null)
                return "That key cannot be used.";
            for (Cmd c : ALL)
                if (!c.id().equals(id) && key(c.id()) == code)
                    return name(code) + " is already " + c.title() + ".";
            if (taken(code) >= 0)
                return name(code) + " is already used on the canvas.";
        }
        for (Cmd c : ALL)
            if (c.id().equals(id)) {
                Settings.setProperty(PREFIX + id, code < 0 ? NONE : code == c.defaultKey() ? "" : name(code));
                return null;
            }
        return "no canvas key '" + id + "'";
    }

    public static void reset(String id) {
        Settings.setProperty(PREFIX + id, "");
    }

    public static boolean isCustom(String id) {
        String s = Settings.getProperty(PREFIX + id);
        return s != null && !s.isBlank();
    }

    /** Keys the canvas already answers to for other reasons (Space, the arrows, X, Y, Z, N, P): not free to take. */
    private static int taken(int code) {
        return switch (code) {
            case KeyEvent.VK_SPACE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_X, KeyEvent.VK_Y, KeyEvent.VK_Z,
                 KeyEvent.VK_N, KeyEvent.VK_P, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> code;
            default -> -1;
        };
    }

    /** The name KeyEvent's VK_ constant carries, or null when it has none. */
    @Nullable
    public static String name(int code) {
        for (java.lang.reflect.Field f : KeyEvent.class.getFields()) {
            if (!f.getName().startsWith("VK_") || f.getType() != int.class)
                continue;
            try {
                if (f.getInt(null) == code)
                    return f.getName().substring(3);
            } catch (IllegalAccessException ignored) {
                // public static fields: not reachable
            }
        }
        return null;
    }

    /** Text for a key code, for the dialog: the name, or "none". */
    public static String text(int code) {
        return code < 0 ? "none" : name(code) == null ? Integer.toString(code) : name(code);
    }

    @Nullable
    static Cmd command(int code) {
        for (Cmd c : ALL)
            if (key(c.id()) == code)
                return c;
        return null;
    }

    public static boolean invert() {
        return "true".equals(Settings.getProperty(INVERT));
    }

    public static double degreesPerSecond() {
        try {
            double d = Double.parseDouble(Settings.getProperty(RATE));
            return Double.isFinite(d) ? Math.clamp(d, 1, 360) : DEFAULT_DEGREES_PER_SECOND;
        } catch (RuntimeException e) {
            return DEFAULT_DEGREES_PER_SECOND; // unset, or not a number
        }
    }

    public static boolean liveWhileRecording() {
        return "true".equals(Settings.getProperty(LIVE_WHILE_RECORDING));
    }

    // ---- key events, from Interaction ----

    /** A key went down. True when the canvas keys took it. */
    static boolean pressed(KeyInputEvent e) {
        if (e.metaDown() || e.ctrlDown() || e.shiftDown())
            return false;
        Cmd c = command(e.code());
        if (c == null)
            return false;
        STATE.slow = e.altDown();
        switch (c.id()) {
            case "canvasFasterPlayback" -> stepPlayback(1);
            case "canvasSlowerPlayback" -> stepPlayback(-1);
            default -> {
                if (STATE.down.add(c.id()) && STATE.down.size() == 1) {
                    STATE.lastNanos = System.nanoTime();
                    SceneUndo.keysDown(true);
                    TICK.start();
                }
            }
        }
        return true;
    }

    /** A key came up. */
    static void released(KeyInputEvent e) {
        STATE.slow = e.altDown();
        Cmd c = command(e.code());
        if (c != null)
            STATE.down.remove(c.id());
        if (STATE.down.isEmpty())
            stop();
    }

    /** The canvas lost focus: no release arrives for a key let go elsewhere. */
    static void focusLost() {
        STATE.down.clear();
        stop();
    }

    private static void stop() {
        TICK.stop();
        SceneUndo.keysDown(false);
        if (STATE.sky) {
            STATE.sky = false;
            Display.commitSkyLook(); // one write per hold, as the drag does
        }
    }

    // ---- the turn ----

    private static void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - STATE.lastNanos) / 1e9); // a stalled frame does not leap
        STATE.lastNanos = now;
        if (STATE.down.isEmpty() || (ExportMovie.isRecording() && !liveWhileRecording()))
            return;
        double step = Math.toRadians(degreesPerSecond()) * dt * (STATE.slow ? SLOW : 1);
        double[] turn = turn(STATE.down, step, invert());
        boolean three = Display.mode.rendersIn3D();
        if (three) {
            var camera = Display.getCamera();
            if (turn[0] != 0)
                camera.rotateAboutScreenAxis(Vec3.XAxis, turn[0]);
            if (turn[1] != 0)
                camera.rotateAboutScreenAxis(Vec3.YAxis, turn[1]);
            if (turn[2] != 0)
                camera.rotateAboutScreenAxis(Vec3.ZAxis, turn[2]);
        } else if (Display.mode == org.helioviewer.jhv.display.MapMode.ObserverSky && (turn[0] != 0 || turn[1] != 0)) {
            // The sky follows the key as it follows a drag, so the aim goes the other way
            Display.steerSkyLook(-turn[1], turn[0]);
            STATE.sky = true;
        } else {
            return;
        }
        DisplayController.display();
    }

    /**
     * The turns about the screen's x, y and z axes, in radians, that the held keys ask for in a step of
     * {@code step} radians. "Grab the Sun": positive about y moves the near side right, positive about x
     * moves it down, positive about z is counter-clockwise; so W is negative about x and D positive about y.
     */
    static double[] turn(Set<String> down, double step, boolean invert) {
        double x = 0, y = 0, z = 0;
        double flip = invert ? -1 : 1;
        for (String id : down)
            switch (id) {
                case "canvasPitchUp" -> x -= step * flip;
                case "canvasPitchDown" -> x += step * flip;
                case "canvasYawLeft" -> y -= step * flip;
                case "canvasYawRight" -> y += step * flip;
                case "canvasRollLeft" -> z += step;
                case "canvasRollRight" -> z -= step;
                default -> { }
            }
        return new double[]{x, y, z};
    }

    // ---- playback rate ----

    private static void stepPlayback(int by) {
        ViewState.PlaybackData p = ViewState.playbackData();
        int speed = Math.clamp((long) p.speed() + by, ViewState.PLAYBACK_SPEED_MIN, ViewState.PLAYBACK_SPEED_MAX);
        ViewState.setPlaybackSpeed(speed, p.speedUnit());
        toast(p.speedUnit().isRelative() ? speed + " fps" : speed + " " + p.speedUnit());
    }

    // ---- the reading, in the screen pass ----

    private static void toast(String text) {
        STATE.toast = "Playback " + text;
        STATE.toastUntil = System.currentTimeMillis() + TOAST_MS;
        DisplayController.display();
        Timer off = new Timer((int) TOAST_MS + 50, e -> DisplayController.display());
        off.setRepeats(false);
        off.start();
    }

    /** The playback-rate reading near the top left of the canvas for a moment after Up or Down; never recorded. */
    public static void render(Viewport vp) {
        if (System.currentTimeMillis() >= STATE.toastUntil || ExportMovie.isRecording())
            return;
        GLText.drawTextFloat(vp, List.of(STATE.toast), 14, 14);
    }

    /** For a check: the held ids, in order. */
    static List<String> heldForCheck() {
        return new ArrayList<>(STATE.down);
    }
}

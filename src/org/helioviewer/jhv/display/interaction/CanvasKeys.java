package org.helioviewer.jhv.display.interaction;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.SceneUndo;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.CMETracker;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.KeyInputEvent;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.opengl.GLText;

/**
 * The keys the canvas answers to while it has focus (Gilly, 2026-11-08, revised 2026-11-09 and 2026-11-11):
 * W and S pitch, A and D yaw and Q and E roll the scene; [ and ] zoom, comma and period warp, and
 * apostrophe and semicolon crop, continuously while held (Gilly's defaults, 2026-11-11) (2026-11-11: the right hand has zoom, warp, crop and the
 * playhead; F and V are free); G turns the grid on or off; I and O trim the movie and Home and End go to its first and last frame (2026-11-11); R resets the view; Cmd+Left and Cmd+Right (Ctrl off a Mac) lower and raise the playback
 * rate. The plain arrows' stepping and Space stay where they were ({@link Interaction}).
 *
 * <p>They are bare keys on purpose: the canvas is the one place they cannot be mistaken for typing,
 * and a held key turning the scene smoothly is the point. So they are not accelerators in the
 * {@link org.helioviewer.jhv.gui.ActionCatalog} (the catalogue refuses a key with no modifier, so that
 * it can never shadow a text field); they have their own table here, each key rebindable in
 * Settings &gt; Keybindings &gt; Canvas keys, kept as {@code canvasKeys.<id>} in the settings. The two
 * playback keys carry Cmd as part of the command; only the arrows, Page and Home/End can be put on
 * them, so they cannot meet a menu accelerator.
 *
 * <p>Signs are "grab the Sun": the key moves the near side of the scene the way a drag in that
 * direction would (D to the right, W up), counter-clockwise for Q. <b>Invert</b> flips pitch and
 * yaw. Option held makes any of the held keys slow, for fine framing, and Shift held makes them fast
 * (Gilly, 2026-11-11); both together cancel. Zoom runs at half the turn rate,
 * in the same terms: the turn rate in radians a second is the zoom in e-folds a second, halved, so the
 * picture grows or shrinks by the same factor each second whatever its size, the way the mouse
 * wheel zooms. Cmd and Ctrl pick the pan and axis modes, so neither is taken with a held key. Shift
 * with a drag, or with N, P or Delete, still annotates: no canvas key can sit on those keys, and while an
 * annotation is pending the keys go to it ({@link Interaction}).
 *
 * <p>The turn is applied to the camera's drag rotation, as a trackball drag is, so it is part of the
 * saved scene and of undo; a hold is one undo step ({@link SceneUndo#keysDown}). It applies to the
 * 3D views. In Observer Sky, pitch and yaw aim the look direction as a drag does. Roll, and every
 * attitude key in the flat views, does nothing there (there is no camera attitude to turn). Zoom works
 * in every view.
 *
 * <p>While recording they are off unless Record live changes is on, the one-press R and G included: what is turned during a recording
 * is in its pixels, but a replay from the sidecar cannot repeat it until the camera has an
 * automation track.
 */
public final class CanvasKeys {

    /**
     * One bindable key: id (API, never renamed), name, default AWT key code, whether it is pressed with
     * Cmd (Ctrl off a Mac), what it does.
     */
    public record Cmd(String id, String title, int defaultKey, boolean withCmd, String what) {}

    public static final List<Cmd> ALL = List.of(
            new Cmd("canvasPitchUp", "Pitch Up", KeyEvent.VK_W, false, "Turn the scene so its near side moves up"),
            new Cmd("canvasPitchDown", "Pitch Down", KeyEvent.VK_S, false, "Turn the scene so its near side moves down"),
            new Cmd("canvasYawLeft", "Yaw Left", KeyEvent.VK_A, false, "Turn the scene so its near side moves left"),
            new Cmd("canvasYawRight", "Yaw Right", KeyEvent.VK_D, false, "Turn the scene so its near side moves right"),
            new Cmd("canvasRollLeft", "Roll Counter-clockwise", KeyEvent.VK_Q, false, "Turn the scene counter-clockwise about the line of sight"),
            new Cmd("canvasRollRight", "Roll Clockwise", KeyEvent.VK_E, false, "Turn the scene clockwise about the line of sight"),
            new Cmd("canvasZoomIn", "Zoom In", KeyEvent.VK_OPEN_BRACKET, false, "Zoom in while held, at half the turn rate"),
            new Cmd("canvasZoomOut", "Zoom Out", KeyEvent.VK_CLOSE_BRACKET, false, "Zoom out while held, at half the turn rate"),
            new Cmd("canvasWarpWeaker", "Warp Weaker", KeyEvent.VK_COMMA, false, "Weaken the warp while held, as the Warp slider moved left"),
            new Cmd("canvasWarpStronger", "Warp Stronger", KeyEvent.VK_PERIOD, false, "Strengthen the warp while held, as the Warp slider moved right"),
            new Cmd("canvasCropTighter", "Crop Tighter", KeyEvent.VK_QUOTE, false, "Tighten the crop while held, as the Crop slider moved right"),
            new Cmd("canvasCropLooser", "Crop Looser", KeyEvent.VK_SEMICOLON, false, "Loosen the crop while held, back to none, as the Crop slider moved left"),
            new Cmd("canvasResetView", "Reset View", KeyEvent.VK_R, false, "Put the view back to its default"),
            new Cmd("canvasToggleGrid", "Show or Hide Grid", KeyEvent.VK_G, false, "Turn the grid on or off: the master switch of the Grid row"),
            new Cmd("canvasTrimStart", "Trim Start Here", KeyEvent.VK_I, false, "Start the movie at this frame, as I does on the time slider"),
            new Cmd("canvasTrimEnd", "Trim End Here", KeyEvent.VK_O, false, "End the movie at this frame, as O does on the time slider"),
            new Cmd("canvasFirstFrame", "First Frame", KeyEvent.VK_HOME, false, "Go to the first frame of the movie (its trimmed start)"),
            new Cmd("canvasLastFrame", "Last Frame", KeyEvent.VK_END, false, "Go to the last frame of the movie (its trimmed end)"),
            new Cmd("canvasFasterPlayback", "Faster Playback (with Cmd)", KeyEvent.VK_RIGHT, true, "Raise the playback rate by one"),
            new Cmd("canvasSlowerPlayback", "Slower Playback (with Cmd)", KeyEvent.VK_LEFT, true, "Lower the playback rate by one"));

    public static final String PREFIX = "canvasKeys.";
    public static final String INVERT = PREFIX + "invert";
    public static final String RATE = PREFIX + "degreesPerSecond";
    /** The Record panel's switch: keep the attitude keys live while recording. */
    public static final String LIVE_WHILE_RECORDING = "record.liveChanges";

    /** Turn rate, degrees a second (judgement: a quarter turn in two seconds), and the factor Option takes off it. */
    public static final double DEFAULT_DEGREES_PER_SECOND = 45;
    static final double SLOW = 0.2;
    /** The factor Shift puts on it: the inverse of Option's. */
    static final double FAST = 1 / SLOW;
    /** Zoom runs at this fraction of the turn rate: radians a second of turn, e-folds a second of zoom. Gilly: about half. */
    static final double ZOOM_FRACTION = 0.5;
    static final int TICK_MS = 16;
    private static final String NONE = "none";
    private static final long TOAST_MS = 1400;

    private static final class State {
        final Set<String> down = new LinkedHashSet<>();
        final Set<String> shot = new LinkedHashSet<>(); // one-press commands whose key is still down: a held key does not repeat them
        boolean slow;
        boolean fast;
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
        migrateZoom();
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
        Cmd own = byId(id);
        if (own == null)
            return -1;
        // A default added later (F, V, G on 2026-11-11) never lands on a key the person already gave another command
        for (Cmd c : ALL)
            if (c != own && c.withCmd() == own.withCmd() && own.defaultKey() == stored(c.id()))
                return -1;
        return own.defaultKey();
    }

    /**
     * Once per settings folder: a Keybindings dialog OK had pinned Up and Down on Zoom In and Out as stored
     * values (one debug folder had them swapped), which outrank the defaults. Drop a stored Up or Down on the
     * two zoom commands so the defaults ([ and ], 2026-11-11) apply; the flag keeps a later choice.
     */
    private static void migrateZoom() {
        String flag = PREFIX + "zoomOnFV";
        if (Settings.getProperty(flag) != null)
            return;
        for (String id : new String[] { "canvasZoomIn", "canvasZoomOut" }) {
            String s = Settings.getProperty(PREFIX + id);
            if ("UP".equals(s) || "DOWN".equals(s))
                Settings.setProperty(PREFIX + id, "");
        }
        Settings.setProperty(flag, "1");
    }

    /** The key code stored for {@code id}, or -1 when it is unset, none or unreadable. */
    private static int stored(String id) {
        String s = Settings.getProperty(PREFIX + id);
        if (s == null || s.isBlank() || s.equals(NONE))
            return -1;
        try {
            return KeyEvent.class.getField("VK_" + s).getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Bind {@code id} to key code {@code code} (-1 for none). Returns why it was refused, or null. */
    @Nullable
    public static String set(String id, int code) {
        if (code >= 0) {
            if (name(code) == null)
                return "That key cannot be used.";
            Cmd target = byId(id);
            if (target == null)
                return "no canvas key '" + id + "'";
            for (Cmd c : ALL)
                if (!c.id().equals(id) && c.withCmd() == target.withCmd() && key(c.id()) == code)
                    return name(code) + " is already " + c.title() + ".";
            if (target.withCmd()) {
                if (!cmdKeyAllowed(code))
                    return "With Cmd only the arrows, Page Up, Page Down, Home and End can be used, so the key cannot meet a menu shortcut.";
            } else if (taken(code) >= 0)
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

    @Nullable
    static Cmd byId(String id) {
        for (Cmd c : ALL)
            if (c.id().equals(id))
                return c;
        return null;
    }

    /** The keys a Cmd command may be put on: the navigation keys, which no menu item carries with Cmd alone. */
    public static boolean cmdKeyAllowed(int code) {
        return switch (code) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
                 KeyEvent.VK_HOME, KeyEvent.VK_END -> true;
            default -> false;
        };
    }

    /** Keys the canvas or the whole window already answers to (Space, Left, Right, X, Y, Z, N, P, the shuttle's J, K, L): not free to take. */
    private static int taken(int code) {
        return switch (code) {
            case KeyEvent.VK_SPACE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_X, KeyEvent.VK_Y, KeyEvent.VK_Z,
                 KeyEvent.VK_N, KeyEvent.VK_P, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE,
                 KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L -> code;
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

    /** The plain-key command on {@code code}, or null. */
    @Nullable
    static Cmd command(int code) {
        return command(code, false);
    }

    /** The command on {@code code} among those pressed with Cmd ({@code withCmd}) or plain, or null. */
    @Nullable
    static Cmd command(int code, boolean withCmd) {
        for (Cmd c : ALL)
            if (c.withCmd() == withCmd && key(c.id()) == code)
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

    /** A recording is running and Record live changes is off: the keys change nothing. */
    private static boolean recordingLocked() {
        return ExportMovie.isRecording() && !liveWhileRecording();
    }

    // ---- key events, from Interaction ----

    /** Cmd on a Mac, Ctrl elsewhere: the modifier the playback keys carry. */
    private static boolean cmd(KeyInputEvent e) {
        return Platform.isMacOS() ? e.metaDown() : e.ctrlDown();
    }

    /** The other of the two (Ctrl on a Mac, Meta elsewhere): pans and axis modes, never a canvas key. */
    private static boolean otherMod(KeyInputEvent e) {
        return Platform.isMacOS() ? e.ctrlDown() : e.metaDown();
    }

    /** A key went down. True when the canvas keys took it. */
    static boolean pressed(KeyInputEvent e) {
        STATE.slow = e.altDown(); // first, so Option or Shift pressed during a hold changes its speed at once
        STATE.fast = e.shiftDown();
        if (otherMod(e))
            return false;
        boolean withCmd = cmd(e);
        if (withCmd && e.shiftDown())
            return false; // Cmd+Shift is the menus' (Cmd+Shift+Left and Right select text)
        Cmd c = command(e.code(), withCmd);
        if (c == null)
            return false;
        if (withCmd && e.altDown())
            return false; // Cmd+Option+Left and Right are the Step Frame menu items
        switch (c.id()) {
            case "canvasFasterPlayback" -> {
                if (STATE.shot.add(c.id()))
                    stepPlayback(1);
            }
            case "canvasSlowerPlayback" -> {
                if (STATE.shot.add(c.id()))
                    stepPlayback(-1);
            }
            case "canvasResetView" -> {
                if (STATE.shot.add(c.id()) && !recordingLocked()) {
                    Commands.resetView();
                    toast("View reset");
                }
            }
            case "canvasTrimStart" -> once(c, Commands::trimStartHere);
            case "canvasTrimEnd" -> once(c, Commands::trimEndHere);
            case "canvasFirstFrame" -> once(c, Commands::firstFrame);
            case "canvasLastFrame" -> once(c, Commands::lastFrame);
            case "canvasToggleGrid" -> {
                if (STATE.shot.add(c.id()) && !recordingLocked()) {
                    Boolean on = Commands.toggleGrid();
                    if (on != null)
                        toast(on ? "Grid on" : "Grid off");
                }
            }
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

    /** A one-press command: runs on the press, not again while the key repeats. */
    private static void once(Cmd c, Runnable run) {
        if (STATE.shot.add(c.id()))
            run.run();
    }

    /** The factor on the turn and zoom rate: Option slow, Shift fast, both cancel. */
    static double speed(boolean slow, boolean fast) {
        return (slow ? SLOW : 1) * (fast ? FAST : 1);
    }

    /** A key came up. Whatever the modifiers were, the key's own commands end. */
    static void released(KeyInputEvent e) {
        STATE.slow = e.altDown();
        STATE.fast = e.shiftDown();
        for (Cmd c : ALL)
            if (key(c.id()) == e.code()) {
                STATE.down.remove(c.id());
                STATE.shot.remove(c.id());
            }
        if (STATE.down.isEmpty())
            stop();
    }

    /** The canvas lost focus: no release arrives for a key let go elsewhere. */
    static void focusLost() {
        STATE.down.clear();
        STATE.shot.clear();
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
        if (STATE.down.isEmpty() || recordingLocked())
            return;
        double step = Math.toRadians(degreesPerSecond()) * dt * speed(STATE.slow, STATE.fast);
        boolean changed = false;
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
            changed = turn[0] != 0 || turn[1] != 0 || turn[2] != 0;
        } else if (Display.mode == org.helioviewer.jhv.display.MapMode.ObserverSky && (turn[0] != 0 || turn[1] != 0)) {
            // The sky follows the key as it follows a drag, so the aim goes the other way
            Display.steerSkyLook(-turn[1], turn[0]);
            STATE.sky = true;
            changed = true;
        }
        double zoom = zoomFactor(STATE.down, step);
        if (zoom != 1) {
            if (Display.separateViewportZoom)
                Display.getActiveViewport().zoom *= zoom;
            else
                for (Viewport viewport : Display.getViewports())
                    viewport.zoom *= zoom;
            changed = true;
        }
        // Warp and crop act only where the projection uses them, and take the knob back from CME tracking as the sliders do
        double warp = warpDelta(STATE.down, step);
        if (warp != 0 && Display.mode.usesWarpLambda()) {
            if (CMETracker.getMode() == CMETracker.Mode.WARP)
                CMETracker.stop();
            Display.setWarpLambda(Display.getWarpLambda() + warp);
            changed = true;
        }
        double crop = cropFactor(STATE.down, step);
        if (crop != 1 && Display.mode.usesWarpCrop()) {
            if (CMETracker.getMode() == CMETracker.Mode.CROP)
                CMETracker.stop();
            Display.setWarpOuterRadius(nextCrop(Display.getWarpOuterRadius(), Display.fullWarpFieldRadius(), crop));
            changed = true;
        }
        if (changed)
            DisplayController.display();
    }

    /**
     * The change in warp lambda in a step: period stronger, comma weaker, at the zoom rate in lambda a radian of
     * turn. Stronger is lambda towards -1 (the Warp slider's right end); both keys held cancel.
     */
    static double warpDelta(Set<String> down, double step) {
        double d = (down.contains("canvasWarpWeaker") ? 1 : 0) - (down.contains("canvasWarpStronger") ? 1 : 0);
        return d * step * ZOOM_FRACTION;
    }

    /** The factor the crop radius is multiplied by in a step: exponential, as zoom is; below 1 tightens. */
    static double cropFactor(Set<String> down, double step) {
        double d = (down.contains("canvasCropLooser") ? 1 : 0) - (down.contains("canvasCropTighter") ? 1 : 0);
        return Math.exp(d * step * ZOOM_FRACTION);
    }

    /**
     * The next crop radius from {@code radius} (0 is none, the whole field {@code full}): scaled by {@code factor},
     * no tighter than 2 solar radii (the Crop slider's end), and none again once it reaches the field.
     */
    static double nextCrop(double radius, double full, double factor) {
        if (full <= 2)
            return 0;
        double next = (radius > 0 ? radius : full) * factor;
        return next >= full ? 0 : Math.max(next, 2);
    }

    /**
     * The factor the picture is zoomed by in a step of {@code step} radians of turn: exponential, so a
     * held key zooms by the same ratio every second whatever the size, at {@link #ZOOM_FRACTION} of the turn
     * rate in e-folds a second. Both keys held cancel; 1 for none.
     */
    static double zoomFactor(Set<String> down, double step) {
        double in = 0;
        for (String id : down)
            if (id.equals("canvasZoomIn"))
                in += 1;
            else if (id.equals("canvasZoomOut"))
                in -= 1;
        return in == 0 ? 1 : Math.exp(in * step * ZOOM_FRACTION);
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
        toast("Playback " + (p.speedUnit().isRelative() ? speed + " fps" : speed + " " + p.speedUnit()));
    }

    // ---- the reading, in the screen pass ----

    private static void toast(String text) {
        STATE.toast = text;
        STATE.toastUntil = System.currentTimeMillis() + TOAST_MS;
        DisplayController.display();
        Timer off = new Timer((int) TOAST_MS + 50, e -> DisplayController.display());
        off.setRepeats(false);
        off.start();
    }

    /** The playback-rate (or reset) reading near the top left of the canvas for a moment after its key; never recorded. */
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

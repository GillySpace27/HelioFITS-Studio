package org.helioviewer.jhv.display.interaction;

import java.awt.event.KeyEvent;
import java.util.LinkedHashSet;
import java.util.Set;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.input.KeyInputEvent;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;

/**
 * The canvas keys without a window: the default table has no clash and rebinding refuses one,
 * the signs are "grab the Sun" (D moves the near side right, W up, Q counter-clockwise), invert flips
 * pitch and yaw but not roll, a turn about a screen axis stays about that axis whatever the camera
 * already has, and Shift, Cmd and Ctrl are never taken as camera keys.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.interaction.CanvasKeysCheck
 */
public final class CanvasKeysCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean near(Vec3 a, Vec3 b) {
        return Math.abs(a.x - b.x) < 1e-9 && Math.abs(a.y - b.y) < 1e-9 && Math.abs(a.z - b.z) < 1e-9;
    }

    private static KeyInputEvent key(int code, boolean shift, boolean meta, boolean alt, boolean ctrl) {
        return new KeyInputEvent(KeyInputEvent.Key.OTHER, shift, meta, alt, ctrl, code);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-canvas-keys").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        Set<Integer> seen = new LinkedHashSet<>();
        boolean distinct = true;
        for (CanvasKeys.Cmd c : CanvasKeys.ALL)
            distinct &= seen.add(CanvasKeys.key(c.id()));
        expect("every canvas key starts on its own key", distinct && CanvasKeys.ALL.size() == 16);
        expect("W S A D Q E turn; F and V zoom; G the grid; R resets; Cmd+Right and Cmd+Left set the rate",
                CanvasKeys.key("canvasPitchUp") == KeyEvent.VK_W && CanvasKeys.key("canvasYawRight") == KeyEvent.VK_D
                        && CanvasKeys.key("canvasRollLeft") == KeyEvent.VK_Q && CanvasKeys.key("canvasZoomIn") == KeyEvent.VK_F && CanvasKeys.key("canvasZoomOut") == KeyEvent.VK_V
                        && CanvasKeys.key("canvasToggleGrid") == KeyEvent.VK_G
                        && CanvasKeys.key("canvasTrimStart") == KeyEvent.VK_I && CanvasKeys.key("canvasTrimEnd") == KeyEvent.VK_O
                        && CanvasKeys.key("canvasFirstFrame") == KeyEvent.VK_HOME && CanvasKeys.key("canvasLastFrame") == KeyEvent.VK_END
                        && CanvasKeys.key("canvasResetView") == KeyEvent.VK_R && CanvasKeys.key("canvasFasterPlayback") == KeyEvent.VK_RIGHT
                        && CanvasKeys.key("canvasSlowerPlayback") == KeyEvent.VK_LEFT);

        expect("a key already on another canvas command is refused", CanvasKeys.set("canvasPitchUp", KeyEvent.VK_S) != null);
        expect("so is one the canvas already uses (Space, the arrows, X)", CanvasKeys.set("canvasPitchUp", KeyEvent.VK_SPACE) != null
                && CanvasKeys.set("canvasPitchUp", KeyEvent.VK_LEFT) != null && CanvasKeys.set("canvasPitchUp", KeyEvent.VK_X) != null);
        expect("and the shuttle's J, K and L, which work in the whole window", CanvasKeys.set("canvasPitchUp", KeyEvent.VK_J) != null
                && CanvasKeys.set("canvasPitchUp", KeyEvent.VK_K) != null && CanvasKeys.set("canvasPitchUp", KeyEvent.VK_L) != null
                && CanvasKeys.key("canvasPitchUp") == KeyEvent.VK_W);

        // A key a person gave one command before a later default took it: theirs wins, the new command starts unbound
        org.helioviewer.jhv.app.Settings.setProperty(CanvasKeys.PREFIX + "canvasPitchUp", "G");
        expect("a stored G keeps Pitch Up and leaves Show or Hide Grid unbound", CanvasKeys.key("canvasPitchUp") == KeyEvent.VK_G
                && CanvasKeys.key("canvasToggleGrid") == -1 && CanvasKeys.command(KeyEvent.VK_G).id().equals("canvasPitchUp"));
        CanvasKeys.reset("canvasPitchUp");
        expect("reset gives G back to the grid", CanvasKeys.key("canvasToggleGrid") == KeyEvent.VK_G
                && CanvasKeys.command(KeyEvent.VK_G).id().equals("canvasToggleGrid"));
        expect("a free key is accepted and then drives the command", CanvasKeys.set("canvasPitchUp", KeyEvent.VK_H) == null
                && CanvasKeys.key("canvasPitchUp") == KeyEvent.VK_H && CanvasKeys.command(KeyEvent.VK_H).id().equals("canvasPitchUp")
                && CanvasKeys.command(KeyEvent.VK_W) == null && CanvasKeys.isCustom("canvasPitchUp"));
        expect("none unbinds it", CanvasKeys.set("canvasPitchUp", -1) == null && CanvasKeys.key("canvasPitchUp") == -1);
        CanvasKeys.reset("canvasPitchUp");
        expect("reset puts it back", CanvasKeys.key("canvasPitchUp") == KeyEvent.VK_W && !CanvasKeys.isCustom("canvasPitchUp"));

        double step = 0.1;
        double[] w = CanvasKeys.turn(Set.of("canvasPitchUp"), step, false);
        double[] d = CanvasKeys.turn(Set.of("canvasYawRight"), step, false);
        double[] q = CanvasKeys.turn(Set.of("canvasRollLeft"), step, false);
        Vec3 near = new Vec3(0, 0, 1);
        Vec3 afterW = Quat.axisAngle(w[0], Vec3.XAxis).rotateVector(near);
        Vec3 afterD = Quat.axisAngle(d[1], Vec3.YAxis).rotateVector(near);
        Vec3 right = new Vec3(1, 0, 0);
        Vec3 afterQ = Quat.axisAngle(q[2], Vec3.ZAxis).rotateVector(right);
        expect("W moves the near side of the scene up, as dragging up would", afterW.y > 0 && Math.abs(afterW.x) < 1e-12);
        expect("D moves it to the right", afterD.x > 0 && Math.abs(afterD.y) < 1e-12);
        expect("Q turns the scene counter-clockwise on the screen", afterQ.y > 0 && Math.abs(afterQ.z) < 1e-12);

        double[] inv = CanvasKeys.turn(Set.of("canvasPitchUp", "canvasYawRight", "canvasRollLeft"), step, true);
        double[] plain = CanvasKeys.turn(Set.of("canvasPitchUp", "canvasYawRight", "canvasRollLeft"), step, false);
        expect("invert flips pitch and yaw and leaves roll", inv[0] == -plain[0] && inv[1] == -plain[1] && inv[2] == plain[2]);
        double[] both = CanvasKeys.turn(Set.of("canvasYawLeft", "canvasYawRight"), step, false);
        expect("opposite keys held together cancel", Math.abs(both[1]) < 1e-15);

        double zin = CanvasKeys.zoomFactor(Set.of("canvasZoomIn"), step);
        double zout = CanvasKeys.zoomFactor(Set.of("canvasZoomOut"), step);
        expect("F zooms in, V out, by inverse factors", zin > 1 && zout < 1 && Math.abs(zin * zout - 1) < 1e-12);
        expect("zoom runs at half the turn rate (e-folds per radian of turn)", Math.abs(Math.log(zin) - 0.5 * step) < 1e-12);
        expect("both zoom keys held cancel", CanvasKeys.zoomFactor(Set.of("canvasZoomIn", "canvasZoomOut"), step) == 1
                && CanvasKeys.zoomFactor(Set.of("canvasPitchUp"), step) == 1);

        expect("a Cmd command takes only the navigation keys", CanvasKeys.set("canvasFasterPlayback", KeyEvent.VK_B) != null
                && CanvasKeys.set("canvasFasterPlayback", KeyEvent.VK_PAGE_UP) == null);
        CanvasKeys.reset("canvasFasterPlayback");
        expect("Up and Down are free now; Left and Right stay with the frame step",
                CanvasKeys.set("canvasPitchUp", KeyEvent.VK_RIGHT) != null && CanvasKeys.set("canvasPitchUp", KeyEvent.VK_UP) == null
                        && CanvasKeys.command(KeyEvent.VK_DOWN) == null);
        CanvasKeys.reset("canvasPitchUp");
        expect("a plain and a Cmd command may share a key", CanvasKeys.command(KeyEvent.VK_F, true) == null
                && CanvasKeys.command(KeyEvent.VK_F, false).id().equals("canvasZoomIn")
                && CanvasKeys.command(KeyEvent.VK_RIGHT, true).id().equals("canvasFasterPlayback")
                && CanvasKeys.command(KeyEvent.VK_RIGHT, false) == null);

        // A turn about a screen axis, whatever the camera already holds: scene' = R(axis) after scene
        Quat drag = Quat.createXY(0.7, -1.1);
        Vec3 axis = Vec3.YAxis;
        double angle = 0.3;
        Quat newDrag = Quat.rotate(drag, Quat.axisAngle(angle, drag.rotateInverseVector(axis)));
        Vec3 v = new Vec3(0.3, -0.8, 0.5);
        expect("a turn about a screen axis acts on the screen, not on the camera's own axes",
                near(newDrag.rotateVector(v), Quat.axisAngle(angle, axis).rotateVector(drag.rotateVector(v))));

        CanvasKeys.focusLost();
        boolean none = !CanvasKeys.pressed(key(KeyEvent.VK_W, true, false, false, false))
                && !CanvasKeys.pressed(key(KeyEvent.VK_W, false, true, false, false))
                && !CanvasKeys.pressed(key(KeyEvent.VK_W, false, false, false, true));
        expect("Shift (annotate), Cmd (pan) and Ctrl (axis) with a key are never camera keys", none && CanvasKeys.heldForCheck().isEmpty());
        expect("a plain W is taken and held", CanvasKeys.pressed(key(KeyEvent.VK_W, false, false, false, false)) && CanvasKeys.heldForCheck().equals(java.util.List.of("canvasPitchUp")));
        CanvasKeys.released(key(KeyEvent.VK_W, false, false, false, false));
        expect("and let go on release", CanvasKeys.heldForCheck().isEmpty());
        expect("a plain I is the canvas Trim Start and End is Last Frame, one press each, never held",
                CanvasKeys.command(KeyEvent.VK_I).id().equals("canvasTrimStart") && CanvasKeys.command(KeyEvent.VK_END).id().equals("canvasLastFrame")
                        && CanvasKeys.command(KeyEvent.VK_END, true) == null); // running one needs a movie, which this check has not
        expect("a key that is not bound is left alone", !CanvasKeys.pressed(key(KeyEvent.VK_B, false, false, false, false)));

        boolean mac = Platform.isMacOS();
        KeyInputEvent cmdOptLeft = new KeyInputEvent(KeyInputEvent.Key.OTHER, false, mac, true, !mac, KeyEvent.VK_LEFT);
        KeyInputEvent cmdF = new KeyInputEvent(KeyInputEvent.Key.OTHER, false, mac, false, !mac, KeyEvent.VK_F);
        expect("Cmd+Option+Left is Step Frame, not ours", !CanvasKeys.pressed(cmdOptLeft));
        expect("a plain F is the zoom key and Cmd+F (Find Body) is not", CanvasKeys.pressed(key(KeyEvent.VK_F, false, false, false, false))
                && CanvasKeys.heldForCheck().equals(java.util.List.of("canvasZoomIn")) && !CanvasKeys.pressed(cmdF));
        CanvasKeys.released(key(KeyEvent.VK_F, false, false, false, false));
        expect("F let go ends the zoom", CanvasKeys.heldForCheck().isEmpty());
        expect("a plain Up is no longer a canvas key", !CanvasKeys.pressed(key(KeyEvent.VK_UP, false, false, false, false)));
        expect("a plain Left is the frame step, left alone", !CanvasKeys.pressed(key(KeyEvent.VK_LEFT, false, false, false, false)));
        System.out.println(failures == 0 ? "CanvasKeysCheck: all passed" : "CanvasKeysCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

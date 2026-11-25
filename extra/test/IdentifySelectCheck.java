package org.helioviewer.jhv.identify;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.time.JHVTime;

import java.util.List;

/**
 * Clicking a highlighted object selects it, and the selection is followed: when time moves, the
 * answer moves to where the object now is instead of staying at the click. Click it again (or on
 * empty space) to let it go.
 *
 * <p>Helioradial's page is only about a solar radius wide, so the observer is put twice as far
 * out as Mercury on the Sun-Mercury line and shifted sideways by 0.4 solar radii; that puts Mercury
 * on the page, on one side of the Sun at the first time and on the other at the second.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifySelectCheck
 */
public final class IdentifySelectCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** An observer twice as far from the Sun as Mercury at {@code time}, on its line, shifted sideways by {@code side} solar radii. */
    private static Position observer(JHVTime time, double side) {
        Vec3 w = Bodies.world(Bodies.byName("Mercury"), time);
        Vec3 n = Identifier.unit(Vec3.cross(w, new Vec3(0, 0, 1)));
        Vec3 o = new Vec3(2 * w.x + side * n.x, 2 * w.y + side * n.y, 2 * w.z + side * n.z);
        return ModelPlacement.carringtonPosition(time, o.z, o.x, o.y); // toWorld is (y, z, x) of the Carrington vector
    }

    public static void main(String[] args) throws Exception {
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();
        run(false);
        run(true); // Helioradial drawn as the warp surface mesh: a click must select there too
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(boolean threeD) throws Exception {
        String label = threeD ? "3D Helioradial: " : "";
        IdentifyHover.deselect();
        Display.setHelioradial3D(false);
        Display.setMapMode(MapMode.Helioradial);
        MapView mv = GLRenderer.getMapView();
        if (mv.mode() != MapMode.Helioradial) {
            System.out.println("SKIP: could not build a Helioradial view headless (got " + mv.mode() + ")");
            return;
        }
        Position obs1 = observer(new JHVTime("2026-03-26T12:00:00"), 0.4);
        Position obs2 = observer(new JHVTime("2026-03-26T18:00:00"), -0.4); // six hours later
        if (threeD) {
            mv = Helioradial3DView.build(obs1, 0);
            expect(label + "the view is the 3D mesh", mv.rendersIn3D() && mv.mode() == MapMode.Helioradial);
        }
        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
        boolean light = Identifier.Options.fromSettings().lightTime();

        List<Identifier.Target> t1 = List.of(new Identifier.Target("t", obs1, null, null, 0, Double.POSITIVE_INFINITY));
        List<Identifier.Target> t2 = List.of(new Identifier.Target("t", obs2, null, null, 0, Double.POSITIVE_INFINITY));
        Bodies.Body mercury = Bodies.byName("Mercury");
        Vec3 d1 = Identifier.bodyDirection(mercury, obs1, light), d2 = Identifier.bodyDirection(mercury, obs2, light);
        double[] p1 = d1 == null ? null : IdentifyPins.locate(vp, mv, t1.getFirst(), d1);
        double[] p2 = d2 == null ? null : IdentifyPins.locate(vp, mv, t2.getFirst(), d2);
        expect(label + "Mercury is on the page at both times", p1 != null && p2 != null);
        if (p1 == null || p2 == null) {
            System.exit(1);
            return;
        }
        System.out.println("  info Mercury at " + p1[0] + "," + p1[1] + " then " + p2[0] + "," + p2[1]);
        expect(label + "and it moved across the page", Math.hypot(p1[0] - p2[0], p1[1] - p2[1]) > 50);

        IdentifyHover h = IdentifyHover.selectionForCheck();
        expect(label + "a click on empty space selects nothing", !h.clickSelect(vp, mv, 5, 5, t1) && !IdentifyHover.selected());
        expect(label + "a click on Mercury selects it", h.clickSelect(vp, mv, (int) Math.round(p1[0]), (int) Math.round(p1[1]), t1) && IdentifyHover.selected());
        expect(label + "the answer names Mercury", IdentifyHover.lastMatches().stream().anyMatch(m -> m.body().name().equals("Mercury")));
        double[] click = IdentifyHover.pointer();

        h.follow(vp, mv, t2);
        double[] now = IdentifyHover.pointer();
        expect(label + "still selected after the time change", IdentifyHover.selected());
        expect(label + "the answer moved with Mercury, not frozen at the click",
                now != null && Math.hypot(now[0] - p2[0], now[1] - p2[1]) < 2 && Math.hypot(now[0] - click[0], now[1] - click[1]) > 50);
        expect(label + "it still names Mercury", IdentifyHover.lastMatches().stream().anyMatch(m -> m.body().name().equals("Mercury")));

        expect(label + "a click on empty space lets it go", h.clickSelect(vp, mv, 5, 5, t2) && !IdentifyHover.selected());
    }
}

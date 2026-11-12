package org.helioviewer.jhv.identify;

import java.util.List;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The answer under the pointer follows the view. With the pointer fixed, a change of zoom, warp,
 * crop or orbit moves the sky under it; the glow and the panel used to keep the old answer until
 * the pointer moved. {@code IdentifyHover.refresh}, called once per drawn frame, asks again at the
 * last pointer position when the view at that pixel has changed, and does nothing when it has not,
 * while an object is selected, or while a result is frozen.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyRefreshCheck
 */
public final class IdentifyRefreshCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static Position observer(JHVTime time, double side) {
        Vec3 w = Bodies.world(Bodies.byName("Mercury"), time);
        Vec3 n = Identifier.unit(Vec3.cross(w, new Vec3(0, 0, 1)));
        Vec3 o = new Vec3(2 * w.x + side * n.x, 2 * w.y + side * n.y, 2 * w.z + side * n.z);
        return ModelPlacement.carringtonPosition(time, o.z, o.x, o.y);
    }

    private static boolean names(String body) {
        return IdentifyHover.lastMatches().stream().anyMatch(m -> m.body().name().equals(body));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-refresh").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        Display.setHelioradial3D(false);
        Display.setMapMode(MapMode.HPC);
        IdentifySettings.setEnabled(true);
        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
        Position obs = observer(new JHVTime("2026-03-26T12:00:00"), 0.4);
        var reset = org.helioviewer.jhv.display.Camera.class.getDeclaredMethod("reset", Position.class);
        reset.setAccessible(true);
        reset.invoke(Display.getCamera(), obs);
        var f = org.helioviewer.jhv.display.Camera.class.getDeclaredField("cameraWidth");
        f.setAccessible(true);
        f.setDouble(Display.getCamera(), 1);
        MapView mv = MapView.create(Display.getCamera(), obs, MapMode.HPC, GridType.Viewpoint, new MapScale[]{MapScale.hpc(2.5, 2.0)});
        List<Identifier.Target> targets = IdentifyHover.targets(vp, mv);
        double[] at = IdentifyPins.locate(vp, mv, targets.getFirst(), Identifier.bodyDirection(Bodies.byName("Mercury"), obs, false));
        expect("Mercury is on the page", at != null);
        if (at == null)
            System.exit(1);
        int x = (int) Math.round(at[0]), y = (int) Math.round(at[1]);

        IdentifyHover.exited();
        IdentifyHover.footer(vp, mv, x, y);
        expect("the pointer on Mercury names it", names("Mercury"));
        IdentifyHover.Snapshot first = IdentifyHover.snapshot();

        IdentifyHover.refresh(vp, mv);
        expect("a frame with the view unchanged asks nothing again", IdentifyHover.snapshot() == first && names("Mercury"));

        vp.zoom = 4; // the sky under the fixed pointer is now 4 times nearer the Sun's middle
        IdentifyHover.refresh(vp, mv);
        expect("zoomed with the pointer still: Mercury is no longer under it", !names("Mercury"));
        expect("and the panel's snapshot changed with it", IdentifyHover.snapshot() != first);
        IdentifyHover.Snapshot zoomed = IdentifyHover.snapshot();
        IdentifyHover.refresh(vp, mv);
        expect("a second frame at that zoom is quiet", IdentifyHover.snapshot() == zoomed);

        vp.zoom = 1;
        IdentifyHover.refresh(vp, mv);
        expect("back at the first zoom Mercury is under the pointer again", names("Mercury"));

        // Time moving under a still pointer is a view change too: Mercury has left the pixel six hours on
        Position later = observer(new JHVTime("2026-03-26T18:00:00"), -0.4);
        MapView mvLater = MapView.create(Display.getCamera(), later, MapMode.HPC, GridType.Viewpoint, new MapScale[]{MapScale.hpc(2.5, 2.0)});
        IdentifyHover.refresh(vp, mvLater);
        expect("six hours on, with the pointer still, the answer is asked for again (Mercury has moved off the pixel)", !names("Mercury"));
        IdentifyHover.refresh(vp, mv);
        expect("and at the first time it is there again", names("Mercury"));

        // A frozen result is held, a selected object is followed by its own path
        IdentifyHover.setFrozen(true);
        vp.zoom = 4;
        IdentifyHover.refresh(vp, mv);
        expect("frozen: the view moved and the result stayed", names("Mercury"));
        IdentifyHover.setFrozen(false);
        vp.zoom = 1;
        IdentifyHover.refresh(vp, mv);

        IdentifyHover h = IdentifyHover.selectionForCheck();
        expect("a click selects Mercury", h.clickSelect(vp, mv, x, y, targets) && IdentifyHover.selected());
        vp.zoom = 4;
        h.refresh(vp, mv, IdentifyHover.targets(vp, mv));
        expect("selected: refresh leaves the selection to follow()", IdentifyHover.selected() && names("Mercury"));
        vp.zoom = 1;
        IdentifyHover.deselect();

        IdentifyHover.exited();
        vp.zoom = 4;
        IdentifyHover.refresh(vp, mv);
        expect("with the pointer off the canvas nothing is asked", IdentifyHover.lastMatches().isEmpty());

        System.out.println(failures == 0 ? "IdentifyRefreshCheck: all passed" : "IdentifyRefreshCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private IdentifyRefreshCheck() {}
}

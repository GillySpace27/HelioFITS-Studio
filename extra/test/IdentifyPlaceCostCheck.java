package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The cost of a Highlight All frame, in counts rather than seconds (CI clocks are too coarse to
 * judge a rate): about 200 marks go through {@code IdentifyGlow.scene}, and what is counted is what
 * the work is made of. Each point on the page is one {@link SkyScreen#place}, never a solve; and
 * the ephemeris and the mark list are worked out once for a frame time, so a second frame at the
 * same time, with the view moved under it, looks nothing up.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyPlaceCostCheck
 */
public final class IdentifyPlaceCostCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final int MARKS = 200;
    private static int sourceCalls;

    /** 200 marks fanned over the middle of the page, as deep-sky objects with a small size. */
    private static final Drawn.Source SOURCE = new Drawn.Source() {
        private final List<Mark> marks = new ArrayList<>();

        {
            Random rnd = new Random(3);
            for (int i = 0; i < MARKS; i++) {
                Vec3 ray = Identifier.ray(Math.toRadians((rnd.nextDouble() - .5) * 2.4), Math.toRadians((rnd.nextDouble() - .5) * 1.8));
                marks.add(new Mark("dso:" + i, Kind.GALAXY, null, observer -> ray, Math.toRadians(0.01)));
            }
        }

        @Override
        public List<Drawn.Hit> at(Viewport vp, MapView mv, int x, int y, double tolerancePixels) {
            return List.of();
        }

        @Override
        public List<Mark> marks(Viewport vp, MapView mv, Identifier.Target first) {
            sourceCalls++;
            return marks;
        }
    };

    private static long places() {
        return SkyScreen.PLACES.sum();
    }

    private static long ephemeris() {
        return Bodies.APPARENT.sum();
    }

    private static long builds() {
        return MarkFrame.BUILDS.sum();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-place-cost").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        var fr = Display.class.getDeclaredField("fieldRadius");
        fr.setAccessible(true);
        fr.set(null, (DoubleSupplier) () -> 60);
        Display.setHelioradial3D(false);
        Drawn.register(SOURCE);

        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
        JHVTime t0 = new JHVTime("2026-03-26T12:00:00");
        Position obs = new Position(t0, 215, 0, 0);
        List<Identifier.Target> targets = List.of(new Identifier.Target("t", obs, null, null, 0, 0));
        IdentifyGlow.Flags all = new IdentifyGlow.Flags(true, false, true, false, false);
        int perMark = 1 + 4 * 5 + IdentifyGlow.POINTS; // a centre, five sizing passes of four limb points, then the outline

        for (MapMode mode : new MapMode[]{MapMode.HPC, MapMode.Helioradial, MapMode.Orthographic}) {
            Display.setMapMode(mode);
            vp.zoom = 1;
            MapScale scale = mode == MapMode.HPC ? MapScale.hpc(2.5, 2.0) : mode == MapMode.Helioradial ? Display.warpScale() : MapScale.ortho;
            var reset = org.helioviewer.jhv.display.Camera.class.getDeclaredMethod("reset", Position.class);
            reset.setAccessible(true);
            reset.invoke(Display.getCamera(), obs);
            if (mode == MapMode.HPC) {
                var f = org.helioviewer.jhv.display.Camera.class.getDeclaredField("cameraWidth");
                f.setAccessible(true);
                f.setDouble(Display.getCamera(), 1);
            }
            MapView mv = MapView.create(Display.getCamera(), obs, mode, GridType.Viewpoint, new MapScale[]{scale});
            String tag = mode.toString();
            long p0 = places(), e0 = ephemeris(), b0 = builds();
            int s0 = sourceCalls;
            IdentifyGlow.Scene one = IdentifyGlow.scene(vp, mv, targets, all, 1);
            long p1 = places() - p0, e1 = ephemeris() - e0;
            System.out.println("  info " + tag + " frame 1: " + one.rings().size() + " rings, " + p1 + " places, " + e1 + " ephemeris lookups");
            expect(tag + ": the first frame rings the marks on the page (" + one.rings().size() + ")", one.rings().size() > 60);
            expect(tag + ": every point is one place, at most " + perMark + " a mark (" + p1 + ")", p1 >= one.rings().size() && p1 <= (MARKS + 10L) * perMark);
            expect(tag + ": the ephemeris is looked up a few times a body, not per point (" + e1 + ")", e1 > 0 && e1 <= 3L * Bodies.ALL.size());
            expect(tag + ": the mark list was built once (" + (builds() - b0) + " build, " + (sourceCalls - s0) + " source call)", builds() - b0 == 1 && sourceCalls - s0 == 1);

            // The same time with the view moved under it (zoom): only placement is redone
            vp.zoom = 1.3;
            p0 = places();
            e0 = ephemeris();
            b0 = builds();
            s0 = sourceCalls;
            IdentifyGlow.Scene two = IdentifyGlow.scene(vp, mv, targets, all, 1);
            expect(tag + ": a second frame at the same time looks up no ephemeris (" + (ephemeris() - e0) + ")", ephemeris() == e0);
            expect(tag + ": and builds no mark list (" + (builds() - b0) + ", " + (sourceCalls - s0) + " source calls)", builds() == b0 && sourceCalls == s0);
            expect(tag + ": but places the marks again (" + (places() - p0) + ")", places() - p0 >= two.rings().size() && !two.rings().isEmpty());
            vp.zoom = 1;
            Thread.sleep(1100); // datasets arrive late and are not in the key: a frame is dropped after a second
            b0 = builds();
            IdentifyGlow.scene(vp, mv, targets, all, 1);
            expect(tag + ": a frame older than a second is built again (" + (builds() - b0) + ")", builds() - b0 == 1);

            // What the key holds: the frame time, the layers' observers and the Identify switches
            Position later = new Position(new JHVTime("2026-03-26T12:05:00"), 215, 0, 0);
            MapView mvLater = MapView.create(Display.getCamera(), later, mode, GridType.Viewpoint, new MapScale[]{scale});
            e0 = ephemeris();
            b0 = builds();
            IdentifyGlow.scene(vp, mvLater, List.of(new Identifier.Target("t", later, null, null, 0, 0)), all, 1);
            expect(tag + ": a new frame time builds the marks and looks the bodies up again", builds() - b0 == 1 && ephemeris() > e0);
            b0 = builds();
            IdentifySettings.set(IdentifySettings.DATA_CLUSTERS, "true");
            IdentifyGlow.scene(vp, mvLater, List.of(new Identifier.Target("t", later, null, null, 0, 0)), all, 1);
            IdentifySettings.set(IdentifySettings.DATA_CLUSTERS, "false");
            expect(tag + ": a changed Identify switch rebuilds them", builds() - b0 == 1);
            b0 = builds();
            Position other = new Position(new JHVTime("2026-03-26T12:05:00"), 150, 0.1, 0);
            IdentifyGlow.scene(vp, mvLater, List.of(new Identifier.Target("t", other, null, null, 0, 0)), all, 1);
            expect(tag + ": and so does another observer", builds() - b0 == 1);
        }

        Drawn.unregister(SOURCE);
        System.out.println(failures == 0 ? "IdentifyPlaceCostCheck: all passed" : "IdentifyPlaceCostCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private IdentifyPlaceCostCheck() {}
}

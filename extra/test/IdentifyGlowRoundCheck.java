package org.helioviewer.jhv.identify;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.time.JHVTime;

import java.util.List;

/**
 * A planet is far smaller than a pixel, so the glow is the minimum circle. The probe used to
 * snap to whole pixels, so the outline solved through it was a lattice of integer points
 * magnified to the minimum radius: ragged squares that flickered as the pointer moved. The
 * ring must be round (every point within 12% of the mean radius) and must not jitter when the
 * pointer moves a fraction of a pixel (centre within 0.5 px).
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyGlowRoundCheck [mode]
 */
public final class IdentifyGlowRoundCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();
        List<String> runs = new java.util.ArrayList<>();
        for (String name : args.length > 0 ? args : new String[]{"Helioradial"}) {
            runs.add(name);
            if (name.equals("Helioradial"))
                runs.add("Helioradial3D"); // the same mode drawn as the warp surface mesh
        }
        for (String name : runs) {
            boolean threeD = name.equals("Helioradial3D");
            MapMode mode;
            try {
                mode = MapMode.valueOf(threeD ? "Helioradial" : name);
            } catch (IllegalArgumentException e) {
                System.out.println("SKIP: no map mode " + name);
                continue;
            }
            Display.setHelioradial3D(false);
            Display.setMapMode(mode);
            MapView mv = GLRenderer.getMapView();
            if (mv.mode() != mode) {
                System.out.println("SKIP: could not build a " + mode + " view headless (got " + mv.mode() + ")");
                continue;
            }
            String label = threeD ? mode + " in 3D" : mode.toString();
            Position obs = new Position(new JHVTime("2026-03-26T12:00:00"), 215, 0, 0);
            if (threeD) {
                mv = Helioradial3DView.build(obs, 60);
                expect(label + ": the view is the 3D mesh", mv.rendersIn3D() && mv.mode() == MapMode.Helioradial);
            }
            var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
            Identifier.Target target = new Identifier.Target("t", obs, null, null, 0, 0);

            double worstRound = 0, worstJitter = 0;
            int rings = 0, missing = 0;
            for (int yy = 150; yy < 700; yy += 110)
                for (int xx = 160; xx < 900; xx += 130) {
                    Identifier.Probe q = Probes.at(mv, vp, xx, yy);
                    Vec3 c = q == null ? null : q.direction(obs);
                    if (c == null)
                        continue;
                    double ang = Math.toRadians(0.0005); // a planet: about 2 arcsec
                    List<double[]> a = IdentifyGlow.ring(vp, mv, target, c, ang, new double[]{xx, yy}, 7);
                    List<double[]> b = IdentifyGlow.ring(vp, mv, target, c, ang, new double[]{xx + 0.4, yy + 0.3}, 7);
                    if (a == null || b == null || a.contains(null) || b.contains(null)) {
                        missing++;
                        continue;
                    }
                    rings++;
                    double[] ctr = a.getFirst();
                    double mean = 0;
                    for (double[] p : a.subList(1, a.size()))
                        mean += Math.hypot(p[0] - ctr[0], p[1] - ctr[1]);
                    mean /= a.size() - 1;
                    for (double[] p : a.subList(1, a.size()))
                        worstRound = Math.max(worstRound, Math.abs(Math.hypot(p[0] - ctr[0], p[1] - ctr[1]) - mean) / mean);
                    worstJitter = Math.max(worstJitter, Math.hypot(ctr[0] - b.getFirst()[0], ctr[1] - b.getFirst()[1]));
                }
            System.out.println("  info " + label + ": " + rings + " rings, " + missing + " without one; worst radius deviation "
                    + worstRound + "; worst centre jitter " + worstJitter + " px");
            expect(label + ": rings were found", rings > 5);
            expect(label + ": the ring is round", worstRound < 0.12);
            expect(label + ": the centre holds still for a sub-pixel pointer move", worstJitter < 0.5);
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}

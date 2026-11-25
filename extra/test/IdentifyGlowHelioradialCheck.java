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

/**
 * The glow's outline is found by running the identifier backwards from a starting pixel. In the
 * flat Helioradial view (polar page, singular at the Sun) a start at the middle of the page
 * never converged, so a body that was named under the pointer got no on-canvas flash. The outline
 * must now be found from the pointer, in every flat mode.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyGlowHelioradialCheck
 */
public final class IdentifyGlowHelioradialCheck {

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
        MapMode base = MapMode.valueOf(args.length > 0 ? args[0] : "Helioradial");
        // Flat first, then Helioradial drawn in 3D (the warp surface mesh), where the same must hold
        for (boolean threeD : base == MapMode.Helioradial ? new boolean[]{false, true} : new boolean[]{false}) {
            MapMode mode = base;
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

            int px = 640, py = 280; // a pointer well off the middle of the page
            Identifier.Probe p = Probes.at(mv, vp, px, py);
            Vec3 want = p == null ? null : p.direction(obs);
            expect(label + ": the probe reads the pointer", want != null);
            if (want == null)
                continue;
            double[] fromMiddle = IdentifyPins.locate(vp, mv, target, want, null);
            double[] fromPointer = IdentifyPins.locate(vp, mv, target, want, new double[]{px, py});
            System.out.println("  info " + label + ": from middle " + (fromMiddle == null ? "no answer" : fromMiddle[0] + "," + fromMiddle[1])
                    + "; from pointer " + (fromPointer == null ? "no answer" : fromPointer[0] + "," + fromPointer[1]));
            expect(label + ": found when started at the pointer", fromPointer != null && Math.hypot(fromPointer[0] - px, fromPointer[1] - py) < 1);

            // A ring of limb points about each of many pointers, as the glow draws it, for a planet-sized and a Moon-sized body
            for (double angDeg : new double[]{0.0005, 0.02}) {
                int total = 0, noCentre = 0, brokenRing = 0, middleFails = 0;
                for (int yy = 60; yy < 760; yy += 70)
                    for (int xx = 60; xx < 960; xx += 90) {
                        Identifier.Probe q = Probes.at(mv, vp, xx, yy);
                        Vec3 c = q == null ? null : q.direction(obs);
                        if (c == null)
                            continue;
                        total++;
                        if (IdentifyPins.locate(vp, mv, target, c, null) == null)
                            middleFails++;
                        double[] ctr = IdentifyPins.locate(vp, mv, target, c, new double[]{xx, yy});
                        if (ctr == null) {
                            noCentre++;
                            continue;
                        }
                        double[] seed = ctr;
                        boolean broken = false;
                        for (Vec3 d : IdentifyGlow.limb(c, Math.toRadians(angDeg), IdentifyGlow.POINTS)) {
                            double[] r = IdentifyPins.locate(vp, mv, target, d, seed);
                            if (r == null)
                                broken = true;
                            else
                                seed = r;
                        }
                        if (broken)
                            brokenRing++;
                    }
                System.out.println("  info " + label + " body " + angDeg + " deg: " + total + " pointers; start-at-middle fails " + middleFails
                        + "; no centre from pointer " + noCentre + "; ring with a missing point " + brokenRing);
                // a larger body can legitimately run off the page or through the polar centre, which the glow draws as a broken ring
                expect(label + " " + angDeg + " deg: the centre is found from the pointer" + (angDeg < 0.001 ? ", and the whole ring" : ""),
                        noCentre == 0 && (angDeg >= 0.001 || brokenRing == 0));
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}

package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;

/**
 * The glow's geometry, without GL or an ephemeris: the limb is a ring of sight lines at the body's
 * angular radius, the identifier run backwards puts it on the page as the body's perimeter in that
 * page's projection (a stretched sky gives a stretched outline, not a circle), a tiny body is
 * enlarged about its centre keeping its shape, the pulse moves every point outward, and the Sun
 * is never outlined.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.IdentifyGlowCheck
 */
public final class IdentifyGlowCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        Vec3 centre = Identifier.ray(Math.toRadians(1.2), Math.toRadians(-0.4));
        double angR = Math.toRadians(0.25);
        List<Vec3> limb = IdentifyGlow.limb(centre, angR, IdentifyGlow.POINTS);
        double worst = 0;
        for (Vec3 d : limb)
            worst = Math.max(worst, Math.abs(Identifier.angle(centre, d) - angR));
        expect("every limb sight line is the body's angular radius from its centre", limb.size() == IdentifyGlow.POINTS && worst < 1e-12);

        // A page that stretches the sky twice as much across as up: the perimeter must follow it
        double perRad = Math.toDegrees(1) * 100; // 100 px per degree up the page
        IdentifyPins.Sky sky = (x, y) -> new Vec2((x - 500) / (2 * perRad), -(y - 400) / perRad);
        double[] c = IdentifyPins.solve(sky, Identifier.helioprojective(centre), 0, 0, 1000, 800);
        List<double[]> ring = new ArrayList<>();
        double[] seed = c;
        for (Vec3 d : limb) {
            double[] p = IdentifyPins.solve(sky, Identifier.helioprojective(d), 0, 0, 1000, 800, seed);
            ring.add(p);
            if (p != null)
                seed = p;
        }
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        boolean all = c != null;
        for (double[] p : ring) {
            if (p == null) {
                all = false;
                continue;
            }
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double across = maxX - minX, up = maxY - minY;
        expect("every limb point lands on the page from the one before", all);
        expect("the outline is the body's perimeter in the page's projection: twice as wide as tall here (" + Math.round(across) + " x " + Math.round(up) + " px)",
                Math.abs(across / up - 2) < 0.02 && Math.abs(up - 50) < 1);

        double[] mid = {100, 100};
        List<double[]> tiny = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double t = 2 * Math.PI * i / 8;
            tiny.add(new double[]{100 + 0.6 * Math.cos(t), 100 + 0.3 * Math.sin(t)});
        }
        tiny.set(3, null);
        List<double[]> big = IdentifyGlow.atLeast(tiny, mid, IdentifyGlow.MIN_RADIUS);
        double mean = 0;
        int n = 0;
        for (double[] p : big)
            if (p != null) {
                mean += Math.hypot(p[0] - 100, p[1] - 100);
                n++;
            }
        mean /= n;
        expect("a body under a pixel is enlarged to the smallest outline", Math.abs(mean - IdentifyGlow.MIN_RADIUS) < 1e-9);
        expect("and keeps its shape (still wider than tall) and its gaps", big.get(3) == null && big.get(0)[0] - 100 > 1.5 * (big.get(2)[1] - 100));
        expect("a body already big enough is left alone", IdentifyGlow.atLeast(ring, c, IdentifyGlow.MIN_RADIUS) == ring);

        List<double[]> out = IdentifyGlow.grown(big, mid, 5);
        boolean outward = true;
        for (int i = 0; i < big.size(); i++)
            if (big.get(i) != null)
                outward &= Math.abs(Math.hypot(out.get(i)[0] - 100, out.get(i)[1] - 100) - Math.hypot(big.get(i)[0] - 100, big.get(i)[1] - 100) - 5) < 1e-9;
        expect("the pulse moves every point of the outline outward by the same distance", outward);

        Identifier.Match sun = new Identifier.Match(new Bodies.Body("Sun", "SUN", 1), "AIA", 0.1, 0.27, 0.01, 1, 0, Identifier.Qualifier.NONE);
        Identifier.Match venus = new Identifier.Match(new Bodies.Body("Venus", "VENUS", 0.01), "AIA", 0.001, 0.008, 0.01, 0.3, 0.2, Identifier.Qualifier.NONE);
        expect("the Sun is never outlined; a planet in front of it is", IdentifyGlow.outlined(List.of(sun, venus)).equals(List.of(venus)));
        expect("moving over the Sun alone is not a change of what is outlined", IdentifyGlow.key(List.of(sun)).isEmpty() && !IdentifyGlow.key(List.of(venus)).isEmpty());
        expect("the pulse runs from 0 to 1 over its length and is over without a start",
                IdentifyGlow.pulsePhase(1000, 1000) == 0 && Math.abs(IdentifyGlow.pulsePhase(1000 + IdentifyGlow.PULSE_MS, 1000) - 1) < 1e-12
                        && IdentifyGlow.pulsePhase(5, 0) > 1);

        System.out.println(failures == 0 ? "IdentifyGlowCheck: all passed" : "IdentifyGlowCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

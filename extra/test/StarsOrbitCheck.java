package org.helioviewer.jhv.identify;

import org.helioviewer.jhv.layers.stars.StarSky;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;

/** Identify names the star the orbited 3D view draws: fromPlane inverts StarSky.plane. */
public final class StarsOrbitCheck {

    public static void main(String[] args) {
        // Unit rays in the observer frame (Sun along -z): two the drag keeps in view, one it throws out
        double[] rays = {0.3, 0.1, -Math.sqrt(1 - 0.1), -0.5, 0.2, -Math.sqrt(1 - 0.29), 0.9, 0, -Math.sqrt(0.19)};
        Quat drag = Quat.rotate(Quat.createAxisY(0.6), Quat.createAxisX(-0.3));
        StarSky.Points p = new StarSky.Points(3, toFloat(rays), new float[]{3, 3, 3}, new byte[12]);
        BufVertex buf = new BufVertex();
        StarSky.plane(p, 215, drag, buf);
        int ok = 0, fail = 0, shown = buf.getCount();
        java.nio.ByteBuffer b = buf.toBuffer().order(java.nio.ByteOrder.nativeOrder());
        int stride = b.limit() / Math.max(1, shown);
        int slot = 0;
        for (int i = 0; i < 3; i++) {
            Vec3 want = new Vec3(rays[3 * i], rays[3 * i + 1], rays[3 * i + 2]);
            Vec3 turned = drag.rotateVector(want);
            boolean drawn = -turned.z >= Math.cos(Math.toRadians(StarSky.MAX_ELONGATION_DEG));
            if (!drawn) {
                continue;
            }
            Vec3 got = Stars.fromPlane(b.getFloat(slot * stride), b.getFloat(slot * stride + 4), 215, drag);
            slot++;
            boolean good = got != null && Math.abs(got.x - want.x) + Math.abs(got.y - want.y) + Math.abs(got.z - want.z) < 1e-4;
            System.out.println((good ? "  ok   " : "  FAIL ") + "ray " + i + " comes back from its drawn position");
            if (good) ok++; else fail++;
        }
        boolean cut = Stars.fromPlane(1e6, 0, 215, drag) == null;
        System.out.println((cut ? "  ok   " : "  FAIL ") + "a point far from the view axis is beyond the cut");
        if (!cut) fail++;
        if (ok == 0) { System.out.println("  FAIL no ray was drawn"); fail++; }
        if (fail != 0) System.exit(1);
    }

    private static float[] toFloat(double[] a) {
        float[] f = new float[a.length];
        for (int i = 0; i < a.length; i++) f[i] = (float) a[i];
        return f;
    }

    private StarsOrbitCheck() {}
}

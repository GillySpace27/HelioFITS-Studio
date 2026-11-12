package org.helioviewer.jhv.layers.stars;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.identify.Probes;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.time.JHVTime;

/**
 * StarSky.plane puts a star where the Orthographic imagery shows its direction: the point on the
 * plane through Sun centre that Probes.ortho (imageOrtho.frag in Java) turns back into the star's
 * own sight line. The placement had used (d tan Tx, d tan Ty); the imagery has (-d x/z, -d y/z),
 * which is d tan Ty / cos Tx in y, so off the Sun line the stars sat off their pixels.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.stars.StarPlaneCheck
 */
public final class StarPlaneCheck {

    private static int failures, compared;

    public static void main(String[] args) {
        Position observer = new Position(new JHVTime("2025-09-22T05:46:30"), 215, 0.2, -0.1);
        // Tx, Ty in degrees: on the axes, and off them where the two forms part
        double[][] angles = {{1, 0}, {5, 0}, {0, 5}, {4, 4}, {-5.6, 5.6}, {15, 15}, {-30, 20}, {30, -30}, {60, 10}};
        float[] rays = new float[3 * angles.length];
        for (int i = 0; i < angles.length; i++) {
            Vec3 r = org.helioviewer.jhv.identify.Identifier.ray(Math.toRadians(angles[i][0]), Math.toRadians(angles[i][1]));
            rays[3 * i] = (float) r.x;
            rays[3 * i + 1] = (float) r.y;
            rays[3 * i + 2] = (float) r.z;
        }
        StarSky.Points p = new StarSky.Points(angles.length, rays, new float[angles.length], new byte[4 * angles.length]);
        BufVertex buf = StarSky.plane(p, observer.distance);
        java.nio.ByteBuffer b = buf.toBuffer().order(java.nio.ByteOrder.nativeOrder());
        int stride = b.limit() / Math.max(1, buf.getCount());

        // Straight on, and orbited: the imagery plane turns with the view, and the star with it
        Quat drag = Quat.rotate(Quat.createAxisY(0.6), Quat.createAxisX(-0.3));
        for (Quat d : new Quat[]{Quat.ZERO, drag}) {
            Quat view = Quat.rotate(d, observer.toQuat());
            Quat diff = Quat.rotateWithConjugate(view, observer.toQuat());
            for (int i = 0; i < angles.length; i++) {
                double len = Math.sqrt(rays[3 * i] * rays[3 * i] + rays[3 * i + 1] * rays[3 * i + 1] + rays[3 * i + 2] * rays[3 * i + 2]); // the float ray is unit to 1e-8: acos of a dot would read that as 1e-4
                Vec3 want = new Vec3(rays[3 * i] / len, rays[3 * i + 1] / len, rays[3 * i + 2] / len);
                Vec3 on = diff.rotateVector(new Vec3(b.getFloat(i * stride), b.getFloat(i * stride + 4), 0)); // the page point the vertex lands on
                Vec3 got = Probes.ortho(view, on.x, on.y).direction(observer);
                compared++;
                double miss = got == null ? Double.NaN : angle(got, want);
                boolean ok = miss < 1e-6; // 0.2 arcsec; the vertices are floats
                System.out.println((ok ? "  ok   " : "  FAIL ") + String.format("Tx %.1f, Ty %.1f%s: the imagery under the vertex shows the star's direction (off by %.4g arcsec)",
                        angles[i][0], angles[i][1], d == Quat.ZERO ? "" : ", orbited", Math.toDegrees(miss) * 3600));
                if (!ok) failures++;
            }
        }
        if (buf.getCount() != angles.length) { System.out.println("  FAIL every ray was within the cut"); failures++; }
        if (failures != 0) System.exit(1);
        System.out.println("StarPlaneCheck: PASS (" + compared + " placements)");
    }

    /** atan2 of the cross and dot products: acos of a dot of two near-equal vectors cannot resolve less than 1e-8 rad. */
    private static double angle(Vec3 a, Vec3 b) {
        double cx = a.y * b.z - a.z * b.y, cy = a.z * b.x - a.x * b.z, cz = a.x * b.y - a.y * b.x;
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), a.x * b.x + a.y * b.y + a.z * b.z);
    }

    private StarPlaneCheck() {}
}

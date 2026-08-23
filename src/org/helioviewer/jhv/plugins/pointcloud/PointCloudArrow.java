package org.helioviewer.jhv.plugins.pointcloud;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.opengl.DirectBufVertex;
import org.helioviewer.jhv.opengl.GLSLLine;

// A heliographic direction marker anchored at Sun centre, in one of two forms:
//
//   half-angle 0  -> a plain arrow along a Stonyhurst longitude/latitude. Built to run
//                    Yara De Leo's GCS sanity check, does the modelled propagation
//                    direction pass through the reconstructed point cloud.
//   half-angle >0 -> the "ice cream cone" CME model: a cone with its apex at Sun centre
//                    closed by a spherical cap, so every point on the leading edge sits
//                    at the same heliocentric distance. Four parameters (lon, lat,
//                    half-angle, apex height), which is exactly what DONKI's CME Analysis
//                    records carry, so a fit can be dropped straight in. NOT the GCS
//                    croissant, which is a six-parameter flux-rope shape with a tilt.
//
// Deliberately converts lon/lat to scene axes ITSELF rather than reusing PointCloudLoader's
// HEEQ permutation. The two agree analytically, so a marker that lands on the cloud but not
// on the context imagery indicts the scene convention, while one that lands on the imagery
// but not on the cloud indicts the loader. Sharing the code would collapse that distinction
// and make the check unable to fail informatively.
class PointCloudArrow {

    private static final int HEAD_BARBS = 4;
    private static final double HEAD_FRACTION = 0.12; // barb length as a fraction of the shaft
    private static final double HEAD_SPREAD = 0.45;   // barb half-width relative to barb length

    private static final int GENERATORS = 12; // cone edge lines from apex to rim
    private static final int RIM_STEPS = 48;  // rim circle resolution
    private static final int MERIDIANS = 4;   // cap arcs from cap centre to rim
    private static final int MERIDIAN_STEPS = 8;

    // JHV scene axes: x = cosLat sinLon, y = north (sinLat), z = cosLat cosLon toward the
    // sub-Earth point. Stonyhurst longitude is positive west, latitude positive north.
    static double[] direction(double lonDeg, double latDeg) {
        double lon = Math.toRadians(lonDeg), lat = Math.toRadians(latDeg);
        double cosLat = Math.cos(lat);
        return new double[]{cosLat * Math.sin(lon), Math.sin(lat), cosLat * Math.cos(lon)};
    }

    static DirectBufVertex build(double lonDeg, double latDeg, double height, double halfAngleDeg,
                                 Colors.NamedColor color) {
        double[] d = direction(lonDeg, latDeg);
        byte[] col = color.bytes();
        return halfAngleDeg <= 0 ? arrow(d, height, col) : cone(d, height, Math.toRadians(halfAngleDeg), col);
    }

    // ponytail: rebuilt per frame, a few hundred vertices. If this ever grows into a bundle
    // of directions, cache it the way the mesh is cached.
    private static DirectBufVertex arrow(double[] d, double length, byte[] col) {
        double tx = d[0] * length, ty = d[1] * length, tz = d[2] * length;
        double[][] basis = perpendicular(d);
        double[] u = basis[0], v = basis[1];

        double barb = length * HEAD_FRACTION;
        double half = barb * HEAD_SPREAD;

        BufVertex buf = new BufVertex(4 * (1 + HEAD_BARBS) * GLSLLine.stride);
        segment(buf, 0, 0, 0, tx, ty, tz, col);
        for (int i = 0; i < HEAD_BARBS; i++) {
            double a = 2 * Math.PI * i / HEAD_BARBS;
            double c = Math.cos(a), s = Math.sin(a);
            segment(buf, tx, ty, tz,
                    tx - d[0] * barb + (u[0] * c + v[0] * s) * half,
                    ty - d[1] * barb + (u[1] * c + v[1] * s) * half,
                    tz - d[2] * barb + (u[2] * c + v[2] * s) * half, col);
        }
        return new DirectBufVertex(buf);
    }

    private static DirectBufVertex cone(double[] d, double height, double alpha, byte[] col) {
        double[][] basis = perpendicular(d);
        double[] u = basis[0], v = basis[1];

        int segments = 1 + GENERATORS + RIM_STEPS + MERIDIANS * MERIDIAN_STEPS;
        BufVertex buf = new BufVertex(4 * segments * GLSLLine.stride);

        // Axis, so the propagation direction stays readable inside the cone.
        segment(buf, 0, 0, 0, d[0] * height, d[1] * height, d[2] * height, col);

        for (int i = 0; i < GENERATORS; i++) {
            double[] r = capPoint(d, u, v, height, alpha, 2 * Math.PI * i / GENERATORS);
            segment(buf, 0, 0, 0, r[0], r[1], r[2], col);
        }

        double[] prev = capPoint(d, u, v, height, alpha, 0);
        for (int i = 1; i <= RIM_STEPS; i++) {
            double[] r = capPoint(d, u, v, height, alpha, 2 * Math.PI * i / RIM_STEPS);
            segment(buf, prev[0], prev[1], prev[2], r[0], r[1], r[2], col);
            prev = r;
        }

        // Spherical cap: the leading edge is an arc of the sphere of radius `height`, not a
        // flat lid. Drawing a few meridians is what makes it read as a cap rather than a disc.
        for (int m = 0; m < MERIDIANS; m++) {
            double theta = 2 * Math.PI * m / MERIDIANS;
            double[] p = capPoint(d, u, v, height, 0, theta);
            for (int i = 1; i <= MERIDIAN_STEPS; i++) {
                double[] q = capPoint(d, u, v, height, alpha * i / MERIDIAN_STEPS, theta);
                segment(buf, p[0], p[1], p[2], q[0], q[1], q[2], col);
                p = q;
            }
        }
        return new DirectBufVertex(buf);
    }

    // A point on the cap: angle `beta` off the axis, azimuth `theta`, at radius `height`.
    private static double[] capPoint(double[] d, double[] u, double[] v, double height, double beta, double theta) {
        double cb = Math.cos(beta), sb = Math.sin(beta), ct = Math.cos(theta), st = Math.sin(theta);
        return new double[]{
                height * (cb * d[0] + sb * (u[0] * ct + v[0] * st)),
                height * (cb * d[1] + sb * (u[1] * ct + v[1] * st)),
                height * (cb * d[2] + sb * (u[2] * ct + v[2] * st))};
    }

    private static double[][] perpendicular(double[] d) {
        double[] a = Math.abs(d[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double[] u = normalize(cross(d, a));
        return new double[][]{u, normalize(cross(d, u))};
    }

    // GLSLLine wants each segment bracketed by transparent duplicates of its endpoints, so
    // consecutive segments do not get joined by the strip (same packing as buildWire).
    private static void segment(BufVertex buf, double x0, double y0, double z0,
                                double x1, double y1, double z1, byte[] col) {
        buf.putVertex((float) x0, (float) y0, (float) z0, 1, Colors.Null);
        buf.putVertex((float) x0, (float) y0, (float) z0, 1, col);
        buf.putVertex((float) x1, (float) y1, (float) z1, 1, col);
        buf.putVertex((float) x1, (float) y1, (float) z1, 1, Colors.Null);
    }

    private static double[] cross(double[] p, double[] q) {
        return new double[]{p[1] * q[2] - p[2] * q[1], p[2] * q[0] - p[0] * q[2], p[0] * q[1] - p[1] * q[0]};
    }

    private static double[] normalize(double[] p) {
        double n = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        return n == 0 ? new double[]{1, 0, 0} : new double[]{p[0] / n, p[1] / n, p[2] / n};
    }

    // Self-check: java -cp bin org.helioviewer.jhv.plugins.pointcloud.PointCloudArrow
    public static void main(String[] args) {
        // The diagnostic value of this marker rests on direction() agreeing with the
        // HEEQ->scene permutation in PointCloudLoader.parseSource, scene (x,y,z) = (Y,Z,X).
        // They are written independently on purpose, so assert they still agree; if this
        // fails, a marker that misses the cloud no longer means what the class comment says.
        for (double lon = -180; lon <= 180; lon += 7.5) {
            for (double lat = -90; lat <= 90; lat += 7.5) {
                double la = Math.toRadians(lat), lo = Math.toRadians(lon);
                double hx = Math.cos(la) * Math.cos(lo), hy = Math.cos(la) * Math.sin(lo), hz = Math.sin(la);
                double[] viaPermutation = {hy, hz, hx}; // what the loader does to a cloud point
                double[] viaArrow = direction(lon, lat);
                for (int i = 0; i < 3; i++)
                    if (Math.abs(viaArrow[i] - viaPermutation[i]) > 1e-12)
                        throw new AssertionError("arrow/loader frames disagree at lon=" + lon + " lat=" + lat);
            }
        }

        double[] d = direction(106, 25);
        if (Math.abs(norm(d) - 1) > 1e-12)
            throw new AssertionError("direction not unit: " + norm(d));
        // Scene axes, not HEEQ: x is the west-limb direction, z is sub-Earth. Longitude 106
        // is north (y > 0), west (x > 0) and just behind the west limb (z < 0).
        if (!(d[1] > 0) || !(d[0] > 0) || !(d[2] < 0))
            throw new AssertionError("lon=106 lat=25 should be north, west, behind the limb");

        // Cone: every cap point sits at the apex height (spherical cap, not a flat lid), and
        // the rim sits at exactly the half-angle off the axis.
        double h = 6.75, alphaDeg = 29;
        double[][] basis = perpendicular(d);
        for (int i = 0; i < 16; i++) {
            double theta = 2 * Math.PI * i / 16;
            for (double frac = 0; frac <= 1.0001; frac += 0.25) {
                double beta = Math.toRadians(alphaDeg) * frac;
                double[] p = capPoint(d, basis[0], basis[1], h, beta, theta);
                if (Math.abs(norm(p) - h) > 1e-9)
                    throw new AssertionError("cap point off the sphere: " + norm(p) + " != " + h);
                double cosOff = (p[0] * d[0] + p[1] * d[1] + p[2] * d[2]) / h;
                double offDeg = Math.toDegrees(Math.acos(Math.clamp(cosOff, -1., 1.)));
                if (Math.abs(offDeg - alphaDeg * frac) > 1e-9)
                    throw new AssertionError("cap point at " + offDeg + " deg, expected " + alphaDeg * frac);
            }
        }

        // DONKI kinematics: the 2021-10-28T14:00 fit reached 21.5 Rsun at 23:32Z at 395 km/s,
        // so back-extrapolation to the Metis frame must land in the corona, not below the
        // photosphere or out past the cloud.
        double back = DonkiCone.heightAtRsun(395, 25980); // 16:19 -> 23:32 is 7h13m
        if (!(back > 3 && back < 12))
            throw new AssertionError("implausible back-extrapolated height: " + back);

        // DONKI parsing, against a verbatim slice of the real 2021-10-28 payload. Guards the
        // field names and the "2021-10-28T23:32Z" minute-precision stamp, which is not a form
        // Instant.parse accepts unaided. The second record has a null halfAngle and must be
        // dropped rather than drawn with a substituted default.
        String payload = """
                [{"time21_5":"2021-10-28T23:32Z","latitude":18.0,"longitude":116.0,"halfAngle":29.0,\
                "speed":395.0,"associatedCMEstartTime":"2021-10-28T14:00Z","measurementTechnique":"SWPC_CAT",\
                "note":"","tilt":null,"minorHalfWidth":null},\
                {"time21_5":"2021-10-28T18:52Z","latitude":-17.0,"longitude":0.0,"halfAngle":null,\
                "speed":1109.0,"associatedCMEstartTime":"2021-10-28T15:53Z","measurementTechnique":"SWPC_CAT"}]""";
        var fits = DonkiCone.parse(payload);
        if (fits.size() != 1)
            throw new AssertionError("expected 1 drawable fit, got " + fits.size());
        DonkiCone.Fit f = fits.getFirst();
        if (f.longitude() != 116 || f.latitude() != 18 || f.halfAngle() != 29)
            throw new AssertionError("DONKI fields mis-parsed: " + f);
        if (f.time215Milli() == 0)
            throw new AssertionError("time21_5 failed to parse");
        double hBack = f.heightAt(f.time215Milli() - 25980_000L);
        if (Math.abs(hBack - back) > 1e-9)
            throw new AssertionError("heightAt disagrees with heightAtRsun: " + hBack + " vs " + back);

        System.out.printf("PointCloudArrow self-check passed "
                + "(frames, cone geometry, DONKI parse + kinematics: %.2f R☉ at the Metis frame)%n", back);
    }

    private static double norm(double[] p) {
        return Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
    }

}

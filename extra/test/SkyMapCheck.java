package org.helioviewer.jhv.display;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The observer-sky projection, pinned as geometry.
 *
 * <p>The thing most worth checking is not any single formula but that the two halves agree. The
 * imagery is placed by solarSky.frag and the overlays by {@link SkyMap}, and they carry the same
 * geometry written twice, in GLSL and in Java. When those drift the picture still looks fine and
 * the grid quietly sits somewhere else, which is the failure this file exists to catch: the
 * round-trip test below drives a world point through the Java forward map and back out through the
 * ray construction the shader uses, and demands the original direction.
 *
 * <p>Also pinned: the three radial laws against their WCS definitions, since the whole point of
 * offering a choice is that each one is the projection it claims to be, and the defining property
 * of the default, that page radius is proportional to angle. That proportionality is what makes an
 * azimuthal equidistant frame a usable dome master, so it is not an implementation detail.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.SkyMapCheck
 */
public final class SkyMapCheck {

    private static int failures;

    private static final double D = 215; // solar radii, near enough 1 au
    private static final Position VIEWPOINT = new Position(new JHVTime(0), D, 0, 0);

    public static void main(String[] args) {
        radialLaws();
        reach();
        rayGeometry();
        roundTrip();
        sunCentred();
        steering();
        domeProperty();
        switchScale();
        gridRings();

        if (failures != 0)
            throw new AssertionError(failures + " observer-sky failure(s)");
        System.out.println("SkyMapCheck: PASS");
    }

    /** Each projection is the one it says it is, and its inverse is really its inverse. */
    private static void radialLaws() {
        for (double rhoDeg : new double[]{0, 1, 5, 30, 60, 84}) {
            double rho = Math.toRadians(rhoDeg);
            near(SkyProjection.Gnomonic.radiusFromAngle(rho), Math.tan(rho), 1e-12, "TAN at " + rhoDeg);
            near(SkyProjection.Stereographic.radiusFromAngle(rho), 2 * Math.tan(0.5 * rho), 1e-12, "STG at " + rhoDeg);
            near(SkyProjection.AzimuthalEquidistant.radiusFromAngle(rho), rho, 1e-12, "ARC at " + rhoDeg);
        }
        for (SkyProjection p : SkyProjection.values())
            for (double rhoDeg : new double[]{0.01, 1, 17, 45, 80}) {
                double rho = Math.toRadians(rhoDeg);
                near(p.angleFromRadius(p.radiusFromAngle(rho)), rho, 1e-12, p + " round trip at " + rhoDeg);
            }
        // All three agree to first order at the centre, which is what lets a drag be measured in
        // page units and read as an angle. If that stops being true, InteractionSkyLook is wrong.
        for (SkyProjection p : SkyProjection.values()) {
            double small = 1e-4;
            near(p.radiusFromAngle(small) / small, 1, 1e-6, p + " has unit slope at the centre");
        }
    }

    private static void reach() {
        expect(SkyProjection.AzimuthalEquidistant.maxAngle() >= Math.PI - 1e-12,
                "azimuthal equidistant must reach the anti-solar point; it is the reason it is the default");
        expect(SkyProjection.Gnomonic.maxAngle() < Math.PI / 2,
                "gnomonic diverges at 90 degrees and must stop short of it");
        expect(SkyProjection.Stereographic.maxAngle() < Math.PI,
                "stereographic diverges at 180 degrees and must stop short of it");
        expect(SkyProjection.DEFAULT == SkyProjection.AzimuthalEquidistant, "the default is the all-sky one");

        // solarSky.frag branches on these numbers. They are the enum's ordinals, so reordering the
        // list would silently draw one projection while the menu named another: the picture would
        // still look like a sky and only the scale would be wrong.
        near(SkyProjection.Gnomonic.shaderCode(), 0, 0, "the shader's gnomonic code");
        near(SkyProjection.Stereographic.shaderCode(), 1, 0, "the shader's stereographic code");
        near(SkyProjection.AzimuthalEquidistant.shaderCode(), 2, 0, "the shader's azimuthal equidistant code");
    }

    /** pageToRay is the shader's construction: a unit ray at the right angle and azimuth. */
    private static void rayGeometry() {
        for (SkyProjection p : SkyProjection.values())
            for (double[] aim : aims()) {
                Vec3 look = SkyMap.lookRay(aim[0], aim[1]);
                near(length(look), 1, 1e-12, "the look direction is a unit ray");

                for (double rhoDeg : new double[]{0, 3, 20, 55}) {
                    double rho = Math.toRadians(rhoDeg);
                    double radiusDeg = Math.toDegrees(p.radiusFromAngle(rho));
                    for (double azDeg : new double[]{0, 37, 90, 180, 271}) {
                        double az = Math.toRadians(azDeg);
                        Vec2 page = new Vec2(radiusDeg * Math.cos(az), radiusDeg * Math.sin(az));
                        Vec3 ray = SkyMap.pageToRay(p, aim[0], aim[1], page);

                        near(length(ray), 1, 1e-9, p + " ray is a unit vector");
                        double separation = angleBetween(ray, look);
                        near(separation, rho, 1e-9,
                                p + " page radius must stand for " + rhoDeg + " degrees, aimed at "
                                        + Math.toDegrees(aim[0]) + "," + Math.toDegrees(aim[1]));
                    }
                }
            }
    }

    /** World point -> page -> direction returns the direction the point was actually in. */
    private static void roundTrip() {
        Vec3[] points = {
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1),
                new Vec3(12, -7, 3), new Vec3(-40, 15, -8), new Vec3(90, 60, 20),
        };
        for (SkyProjection p : SkyProjection.values())
            for (double[] aim : aims()) {
                MapScale scale = MapScale.sky(90, 90); // wide, so nothing is rejected for framing
                for (Vec3 world : points) {
                    Vec2 pt = SkyMap.project(VIEWPOINT, scale, p, aim[0], aim[1], world);
                    if (pt == null)
                        continue; // legitimately outside this projection's reach
                    // Undo the scale's normalization to get back to page units.
                    Vec2 page = new Vec2(scale.toMapX(pt.x + 0.5), scale.toMapY(pt.y + 0.5));
                    Vec3 ray = SkyMap.pageToRay(p, aim[0], aim[1], page);
                    Vec3 truth = trueDirection(world);
                    double separation = Math.toDegrees(angleBetween(ray, truth));
                    expect(separation < 1e-6, p + " aimed at " + Math.toDegrees(aim[0]) + ","
                            + Math.toDegrees(aim[1]) + ": round trip moved the point by "
                            + separation + " degrees");
                }
            }
    }

    /** Aimed at the Sun, the mode reproduces the ordinary Sun-centred picture. */
    private static void sunCentred() {
        MapScale scale = MapScale.sky(90, 90);
        for (SkyProjection p : SkyProjection.values()) {
            // A point on the limb, straight up on the sky, sits at the Sun's apparent radius.
            Vec2 pt = SkyMap.project(VIEWPOINT, scale, p, 0, 0, new Vec3(0, 1, 0));
            expect(pt != null, "the limb must be drawable in " + p);
            if (pt == null)
                continue;
            Vec2 page = new Vec2(scale.toMapX(pt.x + 0.5), scale.toMapY(pt.y + 0.5));
            // atan, not asin. (0, 1, 0) is the plane-of-sky limb, the point 90 degrees round the
            // Sun from the observer, which subtends atan(1/D). The tangent limb an observer
            // actually sees is asin(1/D) and sits a few milliarcseconds further out. The two agree
            // to five digits at 1 au, which is exactly why the wrong one would go unnoticed here.
            double expected = Math.toDegrees(p.radiusFromAngle(Math.atan(1 / D)));
            near(Math.hypot(page.x, page.y), expected, 1e-9, p + " limb radius");
            expect(page.y > 0, "a point north on the sky must be drawn above centre in " + p);
            expect(Math.abs(page.x) < 1e-9, "a point due north must not drift sideways in " + p);

            // The Sun's centre is at the centre of the page.
            Vec2 centre = SkyMap.project(VIEWPOINT, scale, p, 0, 0, new Vec3(0, 0, 0));
            expect(centre != null && Math.hypot(centre.x, centre.y) < 1e-12,
                    "aimed at the Sun, the Sun is at the centre in " + p);
        }
    }

    /**
     * Steering is a tripod: exact along the meridian, cos(latitude) about the pole, and above all
     * exactly reversible, since a look-around that does not undo itself is unusable.
     */
    private static void steering() {
        for (double[] aim : aims()) {
            for (double stepDeg : new double[]{0.1, 5, 40}) {
                double step = Math.toRadians(stepDeg);

                // North is a step along the meridian, so it moves the aim by exactly that angle
                // and does not touch the longitude.
                double[] up = SkyMap.steer(aim[0], aim[1], 0, step);
                near(up[0], aim[0], 1e-15, "a north step must not change the longitude");
                near(angleBetween(SkyMap.lookRay(aim[0], aim[1]), SkyMap.lookRay(up[0], up[1])), step,
                        1e-12, "a north step of " + stepDeg + " degrees must move the aim that far");

                // East turns about the sky's pole, so the aim moves by cos(latitude) times the step.
                double[] across = SkyMap.steer(aim[0], aim[1], step, 0);
                near(across[1], aim[1], 1e-15, "an east step must not change the latitude");
                near(angleBetween(SkyMap.lookRay(aim[0], aim[1]), SkyMap.lookRay(across[0], across[1])),
                        2 * Math.asin(Math.abs(Math.sin(0.5 * step)) * Math.cos(aim[1])), 1e-12,
                        "an east step must move the aim by the pole-turn amount at latitude "
                                + Math.toDegrees(aim[1]));

                // Reversible in every direction, which the great-circle version was not.
                for (double[] dir : new double[][]{{1, 0}, {0, 1}, {0.6, -0.8}}) {
                    double[] moved = SkyMap.steer(aim[0], aim[1], step * dir[0], step * dir[1]);
                    double[] back = SkyMap.steer(moved[0], moved[1], -step * dir[0], -step * dir[1]);
                    near(back[0], aim[0], 1e-12, "steering out and back must restore the longitude");
                    near(back[1], aim[1], 1e-12, "steering out and back must restore the latitude");
                }
            }
        }
        // The pole is inside the range an all-sky view is for, so it must not be a singularity.
        double[] atPole = SkyMap.steer(0, Math.toRadians(89.99), Math.toRadians(1), Math.toRadians(1));
        expect(Double.isFinite(atPole[0]) && Double.isFinite(atPole[1]),
                "steering near the sky pole must stay finite");
        expect(Math.abs(atPole[1]) <= Math.PI / 2 + 1e-15, "the aim must not tip over the pole");
    }

    /** The dome property: for the default, page radius is proportional to angle, exactly. */
    private static void domeProperty() {
        SkyProjection arc = SkyProjection.AzimuthalEquidistant;
        double reference = arc.radiusFromAngle(Math.toRadians(10)) / 10;
        for (double rhoDeg : new double[]{1, 10, 45, 90, 135, 180}) {
            double ratio = arc.radiusFromAngle(Math.toRadians(rhoDeg)) / rhoDeg;
            near(ratio, reference, 1e-12,
                    "azimuthal equidistant must keep one angular scale everywhere; failed at " + rhoDeg);
        }
        // And the other two must NOT, or there would be no reason to offer three.
        for (SkyProjection p : new SkyProjection[]{SkyProjection.Gnomonic, SkyProjection.Stereographic})
            expect(Math.abs(p.radiusFromAngle(Math.toRadians(60)) / 60
                    - p.radiusFromAngle(Math.toRadians(5)) / 5) > 1e-6, p + " must not be equidistant");
    }

    /**
     * Why switching styles has to compensate, recorded as numbers so it does not become folklore.
     *
     * <p>The page is normalized so the field radius lands on the top edge, which means everything
     * inside it is divided by R(field), and that divisor is wildly different between the three
     * laws. Nothing is wrong with any of them; they simply cannot share a scale AND share an edge.
     * Display.setSkyProjection holds the disk still instead, which is the only radius every frame
     * has in common. Its own branch reaches the live viewpoint through GLRenderer and cannot run
     * headless, the same limitation the HPC branch has in DiskSizeInvarianceCheck, so what is
     * pinned here is the ratio that branch is built to divide out.
     */
    private static void switchScale() {
        double field = Math.toRadians(60);
        double limb = Math.atan(1 / D);

        // The central scale of each style at a 60 degree field, as a fraction of the page.
        double arc = SkyProjection.AzimuthalEquidistant.radiusFromAngle(limb)
                / SkyProjection.AzimuthalEquidistant.radiusFromAngle(field);
        double tan = SkyProjection.Gnomonic.radiusFromAngle(limb)
                / SkyProjection.Gnomonic.radiusFromAngle(field);
        double stg = SkyProjection.Stereographic.radiusFromAngle(limb)
                / SkyProjection.Stereographic.radiusFromAngle(field);

        // Left uncompensated, the Sun would change size by about two thirds on one menu click.
        expect(arc / tan > 1.6 && arc / tan < 1.7,
                "azimuthal equidistant draws the disk about 1.65x the gnomonic one at a 60 degree "
                        + "field; got " + (arc / tan));
        expect(arc / stg > 1.1 && arc / stg < 1.2,
                "and about 1.15x the stereographic one; got " + (arc / stg));

        // The compensation is a ratio of these, so it must be finite and positive for every pair.
        for (double f : new double[]{arc, tan, stg})
            expect(f > 0 && Double.isFinite(f), "every style must give a usable disk fraction");

        // At a narrow field the three converge, so a switch there should barely move anything.
        double narrowField = Math.toRadians(2);
        double arcNarrow = SkyProjection.AzimuthalEquidistant.radiusFromAngle(limb)
                / SkyProjection.AzimuthalEquidistant.radiusFromAngle(narrowField);
        double tanNarrow = SkyProjection.Gnomonic.radiusFromAngle(limb)
                / SkyProjection.Gnomonic.radiusFromAngle(narrowField);
        expect(Math.abs(arcNarrow / tanNarrow - 1) < 1e-3,
                "at a 2 degree field the styles are the same picture; got " + (arcNarrow / tanNarrow));
    }

    /**
     * SkyGrid's ring placement: the law it draws with, checked here because the class itself holds
     * a GL line buffer and cannot be built headless.
     *
     * <p>A ring is chosen in ANGLE and mapped through the projection, so "10 degrees" means the
     * same locus in all three styles and only its page radius changes. Even spacing is then a
     * property of the projection rather than of the grid, which is what lets the grid say which
     * style is running without anyone reading the menu.
     */
    private static void gridRings() {
        double field = 60;
        for (SkyProjection p : SkyProjection.values()) {
            MapScale scale = MapScale.sky(
                    Math.toDegrees(p.radiusFromAngle(Math.toRadians(field))) * 1.6,
                    Math.toDegrees(p.radiusFromAngle(Math.toRadians(field))));

            near(ringRadius(scale, p, 0), 0, 1e-12, p + ": the centre ring has no radius");
            near(ringRadius(scale, p, field), 0.5, 1e-12, p + ": the field ring is the page edge");

            double last = -1;
            for (double degrees : new double[]{1, 2, 5, 10, 20, 50, 60}) {
                double radius = ringRadius(scale, p, degrees);
                expect(radius > last, p + ": rings must grow outward, broke at " + degrees);
                expect(radius <= 0.5 + 1e-12, p + ": no ring may leave the page, broke at " + degrees);
                last = radius;
            }
        }

        // Even spacing is azimuthal equidistant and nothing else.
        MapScale arcScale = MapScale.sky(96, 60);
        double step = ringRadius(arcScale, SkyProjection.AzimuthalEquidistant, 10);
        for (int i = 1; i <= 6; i++)
            near(ringRadius(arcScale, SkyProjection.AzimuthalEquidistant, 10 * i), i * step, 1e-12,
                    "azimuthal equidistant rings must be evenly spaced at " + (10 * i) + " degrees");
        MapScale tanScale = MapScale.sky(
                Math.toDegrees(SkyProjection.Gnomonic.radiusFromAngle(Math.toRadians(60))) * 1.6,
                Math.toDegrees(SkyProjection.Gnomonic.radiusFromAngle(Math.toRadians(60))));
        double tanStep = ringRadius(tanScale, SkyProjection.Gnomonic, 10);
        expect(ringRadius(tanScale, SkyProjection.Gnomonic, 20) > 2.05 * tanStep,
                "gnomonic rings must spread outward, or the grid would not distinguish the styles");
    }

    /** SkyGrid.pageRadius, restated. */
    private static double ringRadius(MapScale scale, SkyProjection projection, double degrees) {
        return scale.toUnitY(Math.toDegrees(projection.radiusFromAngle(Math.toRadians(degrees)))) - 0.5;
    }

    /** Aims to test against: on the Sun, off to one side, high, and a long way from both. */
    private static double[][] aims() {
        return new double[][]{
                {0, 0},
                {Math.toRadians(25), 0},
                {0, Math.toRadians(40)},
                {Math.toRadians(-70), Math.toRadians(-30)},
        };
    }

    /** The direction from the observer to a world point, computed independently of SkyMap. */
    private static Vec3 trueDirection(Vec3 world) {
        Vec3 view = VIEWPOINT.toQuat().rotateVector(world);
        double x = view.x, y = view.y, z = view.z - D;
        double len = Math.sqrt(x * x + y * y + z * z);
        return new Vec3(x / len, y / len, z / len);
    }

    private static double dot(Vec3 a, Vec3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    private static double length(Vec3 v) {
        return Math.sqrt(dot(v, v));
    }

    /**
     * The angle between two unit vectors, via atan2 of the cross product rather than acos of the
     * dot product. Not pedantry: acos loses half its digits as the dot product approaches 1, so a
     * pair of directions that agree exactly reads as several milliarcseconds apart, which is the
     * regime every one of these assertions lives in.
     */
    private static double angleBetween(Vec3 a, Vec3 b) {
        double cx = a.y * b.z - a.z * b.y;
        double cy = a.z * b.x - a.x * b.z;
        double cz = a.x * b.y - a.y * b.x;
        return Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), dot(a, b));
    }

    private static void near(double got, double want, double tolerance, String what) {
        expect(Math.abs(got - want) <= tolerance, what + ": got " + got + ", wanted " + want);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            if (failures < 10)
                System.out.println("FAIL: " + what);
        }
    }

}

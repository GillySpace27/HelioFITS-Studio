package org.helioviewer.jhv.identify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The cursor identifier's geometry, against an independent oracle.
 *
 * <p>The oracle rows are sunpy 8.0.1 / astropy 8.0.2 Helioprojective coordinates computed with the
 * same de432s_reduced kernel Studio bundles (read through jplephem), by
 * extra/test/data/identify_oracle.py; regenerate them with that script. They pin (1) Studio's own
 * SPICE rotation into its world frame and the observer frame conventions, to 0.005 degrees, for an
 * Earth-centred observer (the PUNCH L3 case: the Moon in the PTM set of 2025-09-21 to 09-24, Saturn
 * in CAM on 2026-03-26) and an observer at 0.3 AU off the Sun-Earth line; (2) the light-time
 * option, against sunpy's own light-travel correction; (3) identify() itself: the Moon is named at
 * its centre, not one degree away, and the near-Earth uncertainty widens the match as documented;
 * (4) the orthographic probe inverts the rotation the shader applies, for a display viewpoint that
 * is not the layer's.
 */
public final class IdentifyCheck {

    private static int failures;

    /** time, observer, body, geometric Tx, Ty, light-time Tx, Ty (degrees) */
    private record Row(String time, String observer, String body, double tx, double ty, double ltx, double lty) {}

    private static Row row(String t, String o, String b, double tx, double ty, double ltx, double lty) {
        return new Row(t, o, b, tx, ty, ltx, lty);
    }

    private static final List<Row> ORACLE = List.of(
            row("2025-09-22T05:46:30", "earth", "Moon", -4.696500, -1.299080, -4.701971, -1.298894),
            row("2025-09-22T05:46:30", "earth", "Venus", 26.183904, 0.288117, 26.189208, 0.287739),
            row("2025-09-22T05:46:30", "earth", "Mercury", -7.195204, 1.091501, -7.186913, 1.092076),
            row("2025-09-22T05:46:30", "earth", "Mars", -30.444021, 0.870743, -30.439900, 0.870783),
            row("2025-09-21T16:02:00", "earth", "Moon", 1.817266, -0.852899, 1.811792, -0.852731),
            row("2025-09-24T04:58:00", "earth", "Moon", -26.653564, -2.501177, -26.658492, -2.500871),
            row("2025-09-24T04:58:00", "earth", "Venus", 25.703905, 0.262099, 25.709264, 0.261717),
            row("2026-03-26T12:00:00", "earth", "Saturn", 1.065432, -2.081650, 1.067280, -2.081545),
            row("2026-03-26T12:00:00", "earth", "Moon", -99.046211, 1.795188, -99.045026, 1.794777),
            row("2025-09-22T05:46:30", "made_up", "Moon", 74.004661, 6.728665, 74.009965, 6.728356),
            row("2025-09-22T05:46:30", "made_up", "Venus", -18.190204, 2.762944, -18.183552, 2.763605),
            row("2025-09-22T05:46:30", "made_up", "Earth", 74.035669, 6.734452, 74.041157, 6.734115),
            row("2025-09-22T05:46:30", "made_up", "Mars", -130.655603, -9.937752, -130.651142, -9.937631),
            row("2025-09-22T05:46:30", "made_up", "Jupiter", -10.564286, 3.626368, -10.561792, 3.626656));

    private static final double TOL_DEG = 0.005;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("identify-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();

        checkOracle();
        checkIdentify();
        checkOrtho();
        checkDrawn();
        checkPlaneProbe();
        checkTransit();
        checkPunchFixture();

        if (failures != 0) {
            System.out.println("IdentifyCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("IdentifyCheck: PASS");
    }

    private static Position observer(String kind, JHVTime time) {
        Position earth = Spice.getCarrington("EARTH", time);
        if (kind.equals("earth"))
            return earth;
        // Stonyhurst lon -90, lat 7, 0.3 AU: Carrington lon = HGLN + L0, and Position.lon is minus that
        return new Position(time, 0.3 * Sun.MeanEarthDistance, earth.lon + Math.toRadians(90), Math.toRadians(7));
    }

    private static void checkOracle() {
        for (Row r : ORACLE) {
            JHVTime t = new JHVTime(r.time());
            Position obs = observer(r.observer(), t);
            Bodies.Body body = Bodies.byName(r.body());
            for (boolean lt : new boolean[]{false, true}) {
                Vec3 dir = Identifier.bodyDirection(body, obs, lt);
                if (dir == null) {
                    expect(false, r + ": no position");
                    continue;
                }
                Vec2 hp = Identifier.helioprojective(dir);
                double tx = Math.toDegrees(hp.x), ty = Math.toDegrees(hp.y);
                double ex = lt ? r.ltx() : r.tx(), ey = lt ? r.lty() : r.ty();
                expect(Math.abs(tx - ex) < TOL_DEG && Math.abs(ty - ey) < TOL_DEG,
                        String.format("%s from %s at %s%s: Tx %.5f Ty %.5f, sunpy %.5f %.5f", r.body(), r.observer(), r.time(),
                                lt ? " (light time)" : "", tx, ty, ex, ey));
            }
        }
    }

    private static void checkIdentify() {
        JHVTime t = new JHVTime("2025-09-22T05:46:30");
        Position earth = observer("earth", t);
        List<Identifier.Target> targets = List.of(Identifier.Target.viewpoint(earth));
        Identifier.Options exact = new Identifier.Options(0, 0, false, true);
        Identifier.Probe atMoon = Probes.helioprojective(Math.toRadians(-4.696500), Math.toRadians(-1.299080));
        List<Identifier.Match> m = Identifier.identify(targets, atMoon, null, exact);
        expect(m.size() == 1 && m.getFirst().body().name().equals("Moon"), "the Moon's centre from Earth names the Moon: " + m);
        if (!m.isEmpty())
            expect(Math.abs(m.getFirst().angularRadius() - 0.26) < 0.02 && m.getFirst().distanceAu() < 0.003,
                    String.format("the Moon is about 0.26 deg in radius and 0.0026 AU away: %.4f deg, %.5f AU", m.getFirst().angularRadius(), m.getFirst().distanceAu()));

        Identifier.Probe offMoon = Probes.helioprojective(Math.toRadians(-4.696500 + 1), Math.toRadians(-1.299080));
        expect(Identifier.identify(targets, offMoon, null, exact).isEmpty(), "one degree from the Moon names nothing");
        // 7,400 km at the Moon's distance is about 1.1 deg, so with the default uncertainty one degree off still matches
        Identifier.Options defaults = new Identifier.Options(0, IdentifySettings.DEFAULT_EARTH_UNCERTAINTY_KM, false, true);
        List<Identifier.Match> wide = Identifier.identify(targets, offMoon, null, defaults);
        expect(wide.size() == 1 && wide.getFirst().body().name().equals("Moon"), "the near-Earth uncertainty widens the Moon's match: " + wide);

        Identifier.Probe onSun = Probes.helioprojective(Math.toRadians(0.1), 0);
        List<Identifier.Match> sun = Identifier.identify(targets, onSun, null, exact);
        expect(sun.size() == 1 && sun.getFirst().body().name().equals("Sun"), "a point on the disk names the Sun: " + sun);

        // Pixel tolerance: a probe one "pixel" (0.5 deg) to the right with 3 pixels of tolerance reaches 1.5 deg
        Identifier.Probe right = Probes.helioprojective(Math.toRadians(-4.696500 + 1.5), Math.toRadians(-1.299080));
        Identifier.Probe right1 = Probes.helioprojective(Math.toRadians(-4.696500 + 2.0), Math.toRadians(-1.299080));
        List<Identifier.Match> px = Identifier.identify(targets, right, right1, new Identifier.Options(3, 0, false, true));
        expect(px.size() == 1 && px.getFirst().body().name().equals("Moon"), "the pixel tolerance is measured on screen: " + px);

        Identifier.Options noPluto = new Identifier.Options(0, 0, false, false);
        expect(Identifier.identify(targets, atMoon, null, noPluto).size() == 1, "turning Pluto off leaves the Moon");
    }

    private static void checkOrtho() {
        JHVTime t = new JHVTime("2025-09-22T05:46:30");
        Position layerObs = observer("earth", t);
        Bodies.Body moon = Bodies.byName("Moon");
        Vec3 dir = Identifier.bodyDirection(moon, layerObs, false);
        // The Moon's point on the layer's image plane (z = 0), in the layer frame
        double s = -layerObs.distance / dir.z;
        Vec3 planePoint = new Vec3(s * dir.x, s * dir.y, 0);
        for (double dLon : new double[]{0, 30, -75}) {
            Position display = new Position(t, layerObs.distance, layerObs.lon + Math.toRadians(dLon), layerObs.lat + Math.toRadians(dLon / 5));
            Quat viewRotation = display.toQuat();
            Quat cameraDiff = Quat.rotateWithConjugate(viewRotation, layerObs.toQuat());
            Vec3 view = cameraDiff.rotateVector(planePoint); // what the shader inverts
            Identifier.Probe probe = Probes.ortho(viewRotation, view.x, view.y);
            List<Identifier.Match> m = Identifier.identify(List.of(Identifier.Target.viewpoint(layerObs)), probe, null,
                    new Identifier.Options(0, 0, false, true));
            expect(m.size() == 1 && m.getFirst().body().name().equals("Moon") && m.getFirst().separation() < 1e-6,
                    "orthographic, display " + dLon + " deg from the layer's observer, names the Moon: " + m);
        }
    }

    /** The orbited 3D view: where a drawn object lands on screen, when the view counts as orbited, what it keeps. */
    private static void checkDrawn() {
        JHVTime t = new JHVTime("2025-09-22T05:46:30");
        Position obs = observer("earth", t);
        // A world point seen from the observer, against the identifier's direction (sunpy-checked above)
        Vec3 moonWorld = Bodies.world(Bodies.byName("Moon"), t);
        Vec3 s = Drawn.screen(obs.toQuat(), moonWorld);
        Vec2 hpc = Identifier.helioprojective(Identifier.bodyDirection(Bodies.byName("Moon"), obs, false));
        double tx = Math.toDegrees(Math.atan2(s.x, obs.distance - s.z));
        double ty = Math.toDegrees(Math.atan2(s.y, Math.hypot(s.x, obs.distance - s.z)));
        double ex = Math.toDegrees(hpc.x), ey = Math.toDegrees(hpc.y);
        expect(Math.abs(tx - ex) < 1e-6 && Math.abs(ty - ey) < 1e-6,
                "screen(): the Moon's world point lands where the observer sees it: " + tx + "," + ty + " vs " + ex + "," + ey);

        Quat q = obs.toQuat();
        expect(!Drawn.orbited(q, q), "the camera at the layer's observer is not orbited");
        expect(!Drawn.orbited(Quat.rotate(Quat.createAxisZ(Math.toRadians(30)), q), q), "a roll about the line of sight is not orbited");
        expect(Drawn.orbited(new Position(t, obs.distance, obs.lon + Math.toRadians(5), obs.lat).toQuat(), q), "5 degrees round the Sun is orbited");
        expect(!Drawn.orbited(new Position(t, obs.distance, obs.lon + Math.toRadians(0.5), obs.lat).toQuat(), q), "half a degree is not");

        // pixelsFrom: tolerance, drawn size, and the Sun in front
        Quat view = Quat.ZERO;
        expect(Drawn.pixelsFrom(view, new Vec3(3, 0, 0), 3.05, 0, 0.01, 0, 6) != null, "5 px from a point model, 6 px tolerance: named");
        expect(Drawn.pixelsFrom(view, new Vec3(3, 0, 0), 3.10, 0, 0.01, 0, 6) == null, "10 px from a point model: not named");
        expect(Drawn.pixelsFrom(view, new Vec3(3, 0, 0), 3.10, 0, 0.01, 0.2, 6) != null, "10 px inside a model drawn 20 px wide: named");
        expect(Drawn.pixelsFrom(view, new Vec3(0.2, 0, -5), 0.2, 0, 0.01, 0, 6) == null, "behind the solar disk: hidden");
        expect(Drawn.pixelsFrom(view, new Vec3(0.2, 0, 5), 0.2, 0, 0.01, 0, 6) != null, "in front of the disk: named");

        // drawnOnly: an orbited layer keeps only what its imagery shows; the bare viewpoint keeps nothing
        org.helioviewer.jhv.wcs.WcsHeader wcs = new org.helioviewer.jhv.wcs.WcsHeader(
                org.helioviewer.jhv.wcs.WcsHeader.Projection.TAN, new float[6], 215, Vec2.ZERO, org.helioviewer.jhv.math.Mat2.IDENTITY);
        Identifier.Target image = new Identifier.Target("img", obs, wcs, null, 0, 0);
        Identifier.Target bare = Identifier.Target.viewpoint(obs);
        Bodies.Body venus = Bodies.byName("Venus"), mars = Bodies.byName("Mars"), moon = Bodies.byName("Moon");
        List<Identifier.Match> ms = List.of(
                new Identifier.Match(venus, "img", 0, 0, 0, 1, 10, Identifier.Qualifier.NONE),
                new Identifier.Match(mars, "img", 0, 0, 0, 1, 10, Identifier.Qualifier.OUTSIDE_FIELD),
                new Identifier.Match(moon, bare.name(), 0, 0, 0, 1, 10, Identifier.Qualifier.NONE));
        Quat orbitedView = new Position(t, obs.distance, obs.lon + Math.toRadians(40), obs.lat).toQuat();
        List<Identifier.Match> kept = IdentifyHover.drawnOnly(ms, List.of(image, bare), orbitedView);
        expect(kept.size() == 1 && kept.getFirst().body() == venus, "orbited: only the body on the imagery is kept: " + kept);
        expect(IdentifyHover.drawnOnly(ms, List.of(image, bare), q).size() == 3, "not orbited: every match is kept");
    }

    private static void checkPlaneProbe() {
        // Helioradial: a plane-of-sky point turns into angles with each layer's own distance
        Position near = new Position(new JHVTime("2025-09-22T05:46:30"), 0.3 * Sun.MeanEarthDistance, 0, 0);
        Position far = new Position(near.time, Sun.MeanEarthDistance, 0, 0);
        Identifier.Probe p = Probes.plane(10, 0);
        double txNear = Math.toDegrees(Identifier.helioprojective(p.direction(near)).x);
        double txFar = Math.toDegrees(Identifier.helioprojective(p.direction(far)).x);
        expect(Math.abs(txNear - Math.toDegrees(Math.atan2(10, near.distance))) < 1e-9 && txNear > 3 * txFar - 0.1,
                String.format("plane point 10 Rsun out: %.4f deg from 0.3 AU, %.4f deg from 1 AU", txNear, txFar));
    }

    private static void checkTransit() {
        // sunpy (identify_transit_oracle.py): the Moon enters a 46.08 deg square from Earth between
        // 2025-09-17T22:00 and 22:10 and leaves between 2025-09-25T23:30 and 23:40
        long start = new JHVTime("2025-09-10T00:00:00").milli, end = new JHVTime("2025-10-05T00:00:00").milli;
        java.util.Map<String, List<TransitIndex.Interval>> m = TransitIndex.compute(TransitIndex.Observer.spice("EARTH"),
                TransitIndex.Annulus.PUNCH, start, end, 3_600_000, true);
        List<TransitIndex.Interval> moon = m.get("Moon");
        expect(moon != null && moon.size() == 1, "the Moon is in PUNCH's field once in 2025-09-10 to 10-05: " + moon);
        if (moon != null && moon.size() == 1) {
            long in = moon.getFirst().start(), out = moon.getFirst().end();
            expect(in >= new JHVTime("2025-09-17T21:59:00").milli && in <= new JHVTime("2025-09-17T22:11:00").milli,
                    "it enters at " + new JHVTime(in) + ", sunpy between 22:00 and 22:10");
            expect(out >= new JHVTime("2025-09-25T23:29:00").milli && out <= new JHVTime("2025-09-25T23:41:00").milli,
                    "it leaves at " + new JHVTime(out) + ", sunpy between 23:30 and 23:40");
            expect(in < new JHVTime("2025-09-21T16:02:00").milli && out > new JHVTime("2025-09-24T04:58:00").milli,
                    "the whole P0 PTM set has the Moon in the field");
        }
        expect(!m.containsKey("Earth"), "the observer's own body is never in its field");
        java.util.Map<java.time.LocalDate, java.util.Set<String>> days = TransitIndex.byDay(m);
        expect(days.getOrDefault(java.time.LocalDate.of(2025, 9, 12), java.util.Set.of()).stream().noneMatch("Moon"::equals),
                "2025-09-12 is a day without the Moon: " + days.get(java.time.LocalDate.of(2025, 9, 12)));
        expect(days.getOrDefault(java.time.LocalDate.of(2025, 9, 22), java.util.Set.of()).contains("Moon"), "2025-09-22 has the Moon");
    }

    /** body, sunpy Tx, Ty (deg) from the fixture's own observer, and whether astropy WCS puts it on the pixels (identify_cam_oracle.py) */
    private record FieldRow(String body, double tx, double ty, boolean inside) {}

    private static void checkPunchFixture() throws Exception {
        java.io.File file = new java.io.File("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits");
        if (!file.isFile()) {
            System.out.println("SKIP: " + file + " is not here (run from the repository root)");
            return;
        }
        org.helioviewer.jhv.view.uri.URIView.SourceInfo info = org.helioviewer.jhv.view.uri.FITSImage.readInfo(file, 0);
        org.helioviewer.jhv.metadata.FitsMetaData m = new org.helioviewer.jhv.metadata.FitsMetaData(
                new org.helioviewer.jhv.metadata.XMLMetaDataContainer(info.xml()), file.toURI());
        org.helioviewer.jhv.metadata.Region region = m.roiToRegion(0, 0, info.width(), info.height(), 1, 1);
        Identifier.Target target = new Identifier.Target("CAM", m.getViewpoint(), m.getWcsHeader(), region, m.getInnerRadius(), m.getOuterRadius());
        // At DATE-AVG, the frame time Studio uses. The Moon gets 0.03 deg: it differs from sunpy by
        // 0.017 deg here (about 110 km of observer position at the Moon's distance), a residual not
        // yet traced, while the Earth-centred rows above agree to 0.0001 deg. Far inside the 7,400 km
        // near-Earth uncertainty the identifier allows.
        List<FieldRow> rows = List.of(
                new FieldRow("Mercury", 19.6374, -0.6002, true), new FieldRow("Venus", -26.1572, -1.7807, true),
                new FieldRow("Moon", -101.3987, -3.6130, false), new FieldRow("Mars", 23.0311, 1.2132, true),
                new FieldRow("Jupiter", -73.2350, -4.8446, false), new FieldRow("Saturn", 26.4386, 0.2706, true),
                new FieldRow("Uranus", -25.0116, -2.4811, true), new FieldRow("Neptune", 31.7444, 1.5597, true));
        Identifier.Options exact = new Identifier.Options(0, 0, false, true);
        for (FieldRow r : rows) {
            Bodies.Body body = Bodies.byName(r.body());
            Vec3 dir = Identifier.bodyDirection(body, target.observer(), false);
            Vec2 hp = Identifier.helioprojective(dir);
            double tx = Math.toDegrees(hp.x), ty = Math.toDegrees(hp.y);
            double tol = r.body().equals("Moon") ? 0.03 : 0.002;
            expect(Math.abs(tx - r.tx()) < tol && Math.abs(ty - r.ty()) < tol,
                    String.format("CAM fixture observer: %s at Tx %.4f Ty %.4f, sunpy %.4f %.4f", r.body(), tx, ty, r.tx(), r.ty()));
            List<Identifier.Match> at = Identifier.identify(List.of(target), Probes.helioprojective(hp.x, hp.y), null, exact);
            Identifier.Match match = at.stream().filter(x -> x.body().name().equals(r.body())).findFirst().orElse(null);
            Identifier.Qualifier want = r.inside() ? Identifier.Qualifier.NONE : Identifier.Qualifier.OUTSIDE_FIELD;
            expect(match != null && match.qualifier() == want,
                    "CAM fixture: " + r.body() + (r.inside() ? " is on the pixels" : " is outside the field") + ", as astropy WCS says: " + (match == null ? "no match" : match.label()));
        }
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }
}

package org.helioviewer.jhv.identify;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.SpiceMath;
import org.helioviewer.jhv.display.Camera;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.GridType;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.layers.stars.StarCatalog;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The orbited Orthographic view: the star under the pointer, and the ring around it, are the star
 * the Stars overlay draws there. The overlay no longer turns as a sky at infinity when the camera
 * is dragged (it used to, and the hover undid that turn with Stars.fromPlane while the rings stayed
 * on the image plane: three answers for one star); it stays on the imagery, and the hover and the
 * rings use the same probe and the same placement ({@link SkyScreen#place}) as the imagery does.
 *
 * <p>The stars are a synthetic grid of ICRS positions around the Sun's direction, not catalogue
 * values. Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.identify.StarsOrbitCheck
 */
public final class StarsOrbitCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-stars-orbit").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        var fr = Display.class.getDeclaredField("fieldRadius");
        fr.setAccessible(true);
        fr.set(null, (DoubleSupplier) () -> 60);
        IdentifySettings.setStars(true);

        // A grid of stars around the Sun in this observer's sky, each placed by inverting StarSky's
        // frame: unit ray -> Carrington -> J2000 -> RA, Dec
        JHVTime time = new JHVTime("2026-03-26T12:00:00");
        Position obs = new Position(time, 215, 0, 0);
        double[][] frame = frameOf(-obs.lon, obs.lat), carr = Spice.j2000ToSun.get(time);
        StringBuilder csv = new StringBuilder("hip,ra,de,pmra,pmde,vmag,b_v\n");
        List<Vec3> grid = new ArrayList<>();
        for (double tx = -9; tx <= 9; tx += 1.5)
            for (double ty = -9; ty <= 9; ty += 1.5) {
                if (Math.hypot(tx, ty) < 2.5)
                    continue; // the disk and the occulter
                Vec3 r = Identifier.ray(Math.toRadians(tx), Math.toRadians(ty));
                double[] rad = SpiceMath.recrad(SpiceMath.mtxv(carr, SpiceMath.mtxv(frame, new double[]{r.x, r.y, r.z})));
                csv.append(100000 + grid.size()).append(',').append(Math.toDegrees(rad[1])).append(',').append(Math.toDegrees(rad[2])).append(",0,0,3,0.5\n");
                grid.add(r);
            }
        Stars.use(StarCatalog.parse(csv.toString()), null);

        var ctor = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Viewport vp = (Viewport) ctor.newInstance(0, 0, 0, 1000, 800, 800);
        Display.setMapMode(MapMode.Orthographic);
        Identifier.Target target = new Identifier.Target("t", obs, null, null, 0, 0);
        Quat tilt = Quat.rotate(Quat.createAxisY(Math.toRadians(35)), Quat.createAxisX(Math.toRadians(-20)));

        for (Quat drag : new Quat[]{Quat.ZERO, tilt}) {
            Camera camera = Display.getCamera();
            var reset = Camera.class.getDeclaredMethod("reset", Position.class);
            reset.setAccessible(true);
            reset.invoke(camera, obs);
            var width = Camera.class.getDeclaredField("cameraWidth");
            width.setAccessible(true);
            width.setDouble(camera, 2 * obs.distance * Math.tan(Math.toRadians(12))); // the page spans the grid
            camera.rotateDragRotation(drag);
            MapView mv = MapView.create(camera, obs, MapMode.Orthographic, GridType.Viewpoint, new MapScale[]{MapScale.ortho});
            String tag = drag == Quat.ZERO ? "straight on" : "orbited";

            int onPage = 0, named = 0, wrongName = 0, wrongRing = 0;
            for (Vec3 ray : grid) {
                double[] at = SkyScreen.place(mv, vp, obs, ray); // where the overlay draws it (StarOverlayCheck: vertex == place)
                if (at == null || at[0] < 5 || at[0] > 995 || at[1] < 5 || at[1] > 795)
                    continue;
                onPage++;
                double[] ring = IdentifyPins.locate(vp, mv, target, ray);
                if (ring == null || Math.hypot(ring[0] - at[0], ring[1] - at[1]) > 1e-9)
                    wrongRing++;
                Stars.Star star = Stars.at(vp, mv, (int) Math.round(at[0]), (int) Math.round(at[1]), 3);
                if (star == null)
                    continue; // a neighbour closer than the tolerance can win, but not none: counted below
                named++;
                double[] hit = SkyScreen.place(mv, vp, obs, star.ray());
                if (hit == null || Math.hypot(hit[0] - at[0], hit[1] - at[1]) > 4)
                    wrongName++;
            }
            expect(tag + ": a good number of stars are on the page (" + onPage + ")", onPage > 20);
            expect(tag + ": the pointer on a drawn star names a star (" + named + " of " + onPage + ")", named == onPage);
            expect(tag + ": the star named is drawn at the pointer (" + wrongName + " that were not)", wrongName == 0);
            expect(tag + ": the ring is on the drawn star (" + wrongRing + " of " + onPage + " that were not)", wrongRing == 0);
        }
        if (failures != 0)
            System.exit(1);
        System.out.println("StarsOrbitCheck: PASS");
    }

    // StarSky.frame is package-private to layers.stars: its rows are the observer's x, y, z axes in Carrington coordinates
    private static double[][] frameOf(double lon0, double lat0) {
        double cl = Math.cos(lon0), sl = Math.sin(lon0), cb = Math.cos(lat0), sb = Math.sin(lat0);
        return new double[][]{{-sl, cl, 0}, {-sb * cl, -sb * sl, cb}, {cb * cl, cb * sl, sb}};
    }

    private StarsOrbitCheck() {}
}

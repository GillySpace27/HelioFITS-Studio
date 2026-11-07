package org.helioviewer.jhv.layers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

/**
 * Model layer placement: a spacecraft model put at its real position, at true size or enlarged.
 *
 * <p>Pins four things. (1) An ephemeris position lands where it should in the world frame: Earth
 * between 0.983 and 1.017 AU from Sun centre, at a heliographic latitude within the 7.25 degree
 * tilt of the solar equator, and in the same direction the heliocentric glTF interface uses for an
 * observer at that CRLN/CRLT, so the two kinds of model agree about where a direction is. (2) The
 * Carrington conversion keeps Spice.getCarrington's sign conventions. (3) The scale arithmetic:
 * true size, the exaggeration, and the pixel floor that keeps a model visible. (4) A model layer
 * saves and restores its placement, and an old session without it opens at Sun centre as before.
 */
public final class ModelPlacementCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("model-placement-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();

        checkEarth(new JHVTime("2025-01-03T00:00:00")); // near perihelion
        checkEarth(new JHVTime("2025-07-04T00:00:00")); // near aphelion
        checkScale();
        checkSettings();
        checkLayer(home);

        if (failures != 0) {
            System.out.println("ModelPlacementCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ModelPlacementCheck: PASS");
    }

    private static void checkEarth(JHVTime time) throws Exception {
        double[] v = Spice.getPosition("SUN", "EARTH", "SOLO_IAU_SUN_2009", time);
        Position p = ModelPlacement.carringtonPosition(time, v[0], v[1], v[2]);
        Vec3 w = ModelPlacement.toWorld(p);
        double au = w.length() / Sun.MeanEarthDistance;
        expect(au > 0.983 && au < 1.017, time + ": Earth is " + String.format("%.4f", au) + " AU from Sun centre");

        // Display convention: the solar rotation axis is +y, so y / r is the sine of the latitude.
        double latDeg = Math.toDegrees(Math.asin(w.y / w.length()));
        expect(Math.abs(latDeg) <= 7.3, time + ": Earth's heliographic latitude is " + String.format("%.3f", latDeg) + " deg, within the 7.25 deg tilt");

        Position spice = Spice.getCarrington("EARTH", time);
        expect(spice != null && Math.abs(angle(spice.lon - p.lon)) < 1e-9 && Math.abs(spice.lat - p.lat) < 1e-9,
                time + ": Carrington conversion keeps Spice.getCarrington's longitude sign and latitude");

        // HeliocentricCartesianMetaData.observerRotation: Quat.createXY(CRLT, -CRLN); +Z points at the observer.
        double crln = -p.lon, crlt = p.lat;
        Vec3 observerZ = Quat.createXY(crlt, -crln).rotateInverseVector(Vec3.ZAxis);
        double dot = (observerZ.x * w.x + observerZ.y * w.y + observerZ.z * w.z) / w.length();
        expect(dot > 1 - 1e-12, time + ": same direction as a heliocentric glTF observer at that CRLN/CRLT (cos = " + dot + ")");
    }

    private static double angle(double a) {
        return Math.IEEEremainder(a, 2 * Math.PI);
    }

    private static void checkScale() {
        ModelPlacement m = new ModelPlacement();
        expect(Math.abs(m.trueScale() - 1 / Sun.RadiusMeter) < 1e-24, "one metre per file unit is 1/RSUN solar radii per unit");

        m.setMinPixels(0);
        expect(m.drawnScale(10, 430, 1000) == m.trueScale(), "with no floor and no enlargement the model is drawn at true size");
        m.setExaggeration(1e9);
        expect(Math.abs(m.drawnScale(10, 430, 1000) / m.trueScale() - 1e9) < 1e-3, "exaggeration multiplies true size");

        m.setExaggeration(1);
        m.setMinPixels(24);
        double s = m.drawnScale(10, 430, 1000);
        double px = 10 * s * 1000 / 430;
        expect(Math.abs(px - 24) < 1e-9, "the floor makes a 10 m model 24 px across in a 430 Rsun wide view (got " + px + ")");

        m.setExaggeration(1e10);
        expect(m.drawnScale(10, 430, 1000) == m.trueScale() * 1e10, "a large enough enlargement is not cut back to the floor");

        m.setExaggeration(1e20);
        expect(m.exaggeration() == 1e10, "enlargement is capped at the slider's top");
        m.setExaggeration(0.5);
        expect(m.exaggeration() == 1, "enlargement never shrinks below true size");
        m.setMetersPerUnit(-3);
        expect(m.metersPerUnit() == 1, "a non-positive unit falls back to metres");

        expect(ModelLayer.times(1e9).equals("1.0x10^9"), "factor text for 1e9 (got " + ModelLayer.times(1e9) + ")");
        expect(ModelLayer.times(250).equals("250x"), "factor text for 250 (got " + ModelLayer.times(250) + ")");
    }

    private static void checkSettings() {
        ModelPlacement m = new ModelPlacement();
        m.setMode(ModelPlacement.Mode.EPHEMERIS);
        m.setTarget("Parker Solar Probe");
        m.setMetersPerUnit(0.01);
        m.setExaggeration(3e8);
        m.setMinPixels(40);
        JSONObject jo = new JSONObject();
        m.serialize(jo);

        ModelPlacement r = new ModelPlacement();
        r.deserialize(new JSONObject(jo.toString()));
        expect(r.mode() == ModelPlacement.Mode.EPHEMERIS && r.target().equals("Parker Solar Probe")
                && r.metersPerUnit() == 0.01 && r.exaggeration() == 3e8 && r.minPixels() == 40, "placement survives a JSON round trip");

        ModelPlacement old = new ModelPlacement();
        old.deserialize(new JSONObject().put("uri", "file:///x.glb"));
        expect(old.mode() == ModelPlacement.Mode.ORIGIN, "a session saved before placement opens at Sun centre");

        ModelPlacement bad = new ModelPlacement();
        bad.deserialize(new JSONObject().put("placement", new JSONObject().put("mode", "SIDEWAYS").put("target", "Vulcan")));
        expect(bad.mode() == ModelPlacement.Mode.ORIGIN && bad.target().equals(ModelPlacement.DEFAULT_TARGET),
                "an unknown mode or object falls back instead of failing the session");
        bad.setTarget("Sun");
        expect(bad.target().equals(ModelPlacement.DEFAULT_TARGET), "the Sun is not a target: a model there is just Sun centre");
    }

    private static void checkLayer(Path dir) throws Exception {
        Path model = dir.resolve("triangle.gltf");
        Files.writeString(model, triangle());
        ModelLayer layer = new ModelLayer(model.toUri());
        expect(!layer.isHeliocentric(), "an ordinary glTF is not heliocentric");
        expect(Math.abs(layer.extent() - 2) < 1e-6, "extent is the largest bounding-box side (got " + layer.extent() + ")");
        expect(layer.getPlacementMode() == ModelPlacement.Mode.ORIGIN, "a new model layer starts at Sun centre, as before");

        layer.setPlacementMode(ModelPlacement.Mode.EPHEMERIS);
        layer.setTarget("Mars");
        layer.setExaggeration(1e9);
        JSONObject state = new JSONObject();
        layer.serialize(state);
        ModelLayer restored = new ModelLayer(new JSONObject(state.toString()));
        expect(restored.getPlacementMode() == ModelPlacement.Mode.EPHEMERIS && restored.getTarget().equals("Mars")
                && restored.getExaggeration() == 1e9, "a model layer restores its placement from the session");
        layer.remove();
        restored.remove();
    }

    /** One lit triangle, 2 m by 1 m, with normals, as an embedded-buffer glTF. */
    private static String triangle() {
        float[] data = {0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data)
            b.putFloat(f);
        String uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(b.array());
        return """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"material":0}]}],
                 "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]}}],
                 "buffers":[{"byteLength":72,"uri":"%s"}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[2,1,0]},
                              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"}]}
                """.formatted(uri);
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}

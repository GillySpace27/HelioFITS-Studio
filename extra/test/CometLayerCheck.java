package org.helioviewer.jhv.layers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.identify.CometStyle;
import org.helioviewer.jhv.identify.Kind;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The comet layer's session form, its fetch gate and ring size, and the comet colours (comet3d
 * plan, revision 2, section 2.9, CometLayerCheck). Headless, no network.
 *
 * <p>Pins: (1) a layer written to a session and read back through the constructor State uses
 * keeps its comet, colour and switches; (2) damaged fields keep their defaults and a missing
 * designation is refused; (3) a NaN perihelion distance is not written (JSON has no NaN); (4) a
 * layer that is off starts no fetch; (5) the ring scales with an export's capture scale;
 * (6) the first comet colour is the Identify comet red, the next free one is taken, and hex round
 * trips. SPICE is loaded, as at startup, since the layer classes reach Layers.
 */
public final class CometLayerCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-layer-check");
        System.setProperty("user.home", home.toString());
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        AppInit.loadSpice(); // CometLayer's class init reaches Layers, whose blank view asks Sun for Earth

        checkRoundTrip();
        checkDamaged();
        checkNaN();
        checkDisabledFetch();
        checkRingScale();
        checkStyle();

        if (failures != 0) {
            System.out.println("CometLayerCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometLayerCheck: PASS");
    }

    private static CometLayer read(JSONObject jo) throws ReflectiveOperationException {
        // As State.json2Object builds a layer: by class name, through its JSONObject constructor
        return (CometLayer) Class.forName(CometLayer.class.getName()).getConstructor(JSONObject.class).newInstance(jo);
    }

    // Row 1
    private static void checkRoundTrip() throws ReflectiveOperationException {
        Comets.Comet c = new Comets.Comet("C/2025 R3", "PANSTARRS", 0.499, 1_777_000_000_000L);
        CometLayer layer = new CometLayer(c, new int[]{12, 34, 56});
        layer.setShowRings(false);
        layer.setLightTime(false);
        layer.setOrbitWidth(2.5);
        layer.setDotScale(9);
        JSONObject jo = new JSONObject();
        layer.serialize(jo);
        CometLayer back = read(new JSONObject(jo.toString()));
        expect(back.comet().equals(c), "the comet comes back from a session (" + back.comet() + ")");
        int[] rgb = back.rgb();
        expect(rgb[0] == 12 && rgb[1] == 34 && rgb[2] == 56, "its colour comes back (" + CometStyle.hex(rgb) + ")");
        expect(!back.showRings() && !back.lightTime(), "the rings and light-time switches come back off");
        expect(back.orbitWidth() == 2.5 && back.dotScale() == CometLayer.SCALE_MAX, "the orbit width comes back, the marker size clamped to the top of its range (" + back.orbitWidth() + ", " + back.dotScale() + ")");
        expect(back.getName().equals("C/2025 R3 (PANSTARRS)"), "its name is the comet's label (" + back.getName() + ")");
    }

    // Row 2
    private static void checkDamaged() throws ReflectiveOperationException {
        JSONObject jo = new JSONObject("{\"designation\":\"C/2025 R3\",\"perihelion\":-5,\"color\":\"blue\",\"showRings\":\"maybe\",\"lightTime\":7}");
        CometLayer layer = read(jo);
        int[] rgb = layer.rgb(), red = CometStyle.palette(0);
        expect(rgb[0] == red[0] && rgb[1] == red[1] && rgb[2] == red[2], "an unreadable colour falls back to the first comet colour");
        expect(layer.showRings() && layer.lightTime(), "unreadable switches keep their defaults (on)");
        expect(layer.comet().perihelion() == 0, "a negative perihelion time is read as 0 (" + layer.comet().perihelion() + ")");
        expect(Double.isNaN(layer.comet().perihelionAu()), "a missing perihelion distance is NaN");
        boolean refused;
        try {
            read(new JSONObject("{\"designation\":\"  \"}"));
            refused = false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            refused = e.getCause() instanceof JSONException;
        }
        expect(refused, "a blank designation is refused, so State skips the layer");
        try {
            read(new JSONObject("{\"name\":\"no designation\"}"));
            refused = false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            refused = e.getCause() instanceof JSONException;
        }
        expect(refused, "a missing designation is refused");
    }

    // Row 3
    private static void checkNaN() throws ReflectiveOperationException {
        CometLayer layer = new CometLayer(new Comets.Comet("C/2099 Z1", "", Double.NaN, 0), CometStyle.palette(1));
        JSONObject jo = new JSONObject();
        try {
            layer.serialize(jo);
        } catch (JSONException e) {
            expect(false, "a layer whose comet has no perihelion distance can be saved (" + e.getMessage() + ")");
            return;
        }
        String text = jo.toString();
        expect(!jo.has("perihelionAu"), "a NaN perihelion distance is not written (" + text + ")");
        expect(Double.isNaN(read(new JSONObject(text)).comet().perihelionAu()), "and reads back as NaN");
    }

    // Row 4
    private static void checkDisabledFetch() {
        CometLayer layer = new CometLayer(new Comets.Comet("C/2025 R3", "", 0.5, 0), CometStyle.palette(0));
        layer.setEnabled(false);
        int before = CometLayer.FETCHES.get();
        layer.prerender();
        expect(CometLayer.FETCHES.get() == before, "a comet layer that is off starts no fetch (" + (CometLayer.FETCHES.get() - before) + " started)");
    }

    // Row 5
    private static void checkRingScale() {
        double one = CometLayer.ringRadiusPx(1, 1), two = CometLayer.ringRadiusPx(1, 2), retina = CometLayer.ringRadiusPx(2, 1);
        expect(one == CometLayer.RING_RADIUS_PX, "the ring radius on screen is " + CometLayer.RING_RADIUS_PX + " px (" + one + ")");
        expect(two == 2 * one, "an export at capture scale 2 draws the ring twice as large (" + two + ")");
        expect(retina == 2 * one, "a display at pixel scale 2 draws it twice as large (" + retina + ")");
        expect(CometLayer.ringWidthPx(1, 2) == 2 * CometLayer.ringWidthPx(1, 1), "and its line twice as wide");
        long[] w = CometLayer.fetchWindow(2000, 1000);
        expect(w[0] == 1000 && w[1] == 2000, "the fetch window is ordered whatever order the movie gives");
    }

    // Row 6
    private static void checkStyle() {
        int[] first = CometStyle.palette(0);
        expect(first[0] == Kind.COMET.rgb[0] && first[1] == Kind.COMET.rgb[1] && first[2] == Kind.COMET.rgb[2],
                "the first comet colour is the Identify comet red (" + CometStyle.hex(first) + ")");
        int[] second = CometStyle.next(List.of(CometStyle.palette(0)));
        expect(CometStyle.hex(second).equals(CometStyle.hex(CometStyle.palette(1))), "with the first in use, the next layer takes the second (" + CometStyle.hex(second) + ")");
        int[] skip = CometStyle.next(List.of(CometStyle.palette(0), CometStyle.palette(2)));
        expect(CometStyle.hex(skip).equals(CometStyle.hex(CometStyle.palette(1))), "a freed colour is reused before a new one (" + CometStyle.hex(skip) + ")");
        java.util.ArrayList<int[]> all = new java.util.ArrayList<>();
        for (int i = 0; i < CometStyle.size(); i++)
            all.add(CometStyle.palette(i));
        expect(CometStyle.hex(CometStyle.next(all)).equals(CometStyle.hex(first)), "with every colour in use, the first again");
        int[] back = CometStyle.fromHex(CometStyle.hex(new int[]{1, 171, 255}));
        expect(back != null && back[0] == 1 && back[1] == 171 && back[2] == 255, "a colour round trips through hex (" + CometStyle.hex(new int[]{1, 171, 255}) + ")");
        expect(CometStyle.fromHex("blue") == null && CometStyle.fromHex("#12345") == null && CometStyle.fromHex(null) == null, "anything but six hex digits reads as no colour");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }
}

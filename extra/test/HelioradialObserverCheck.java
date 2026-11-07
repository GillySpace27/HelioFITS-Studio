package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

/**
 * "Each image at its observer" in Helioradial 3D (Display.surfaceObserver, used by ImageLayer to
 * place each layer's surface mesh). Pins: on by default, a layer's surface is placed in its own
 * observer's frame; off, or for an image with no observer distance, in the viewpoint's; a session
 * records the choice and reads it back; a session from before the choice opens with it off, so it
 * looks as it was drawn.
 */
public final class HelioradialObserverCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        org.helioviewer.jhv.app.Platform.init(); // ViewState reaches Layers, and that reaches SPICE
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        JHVTime t = new JHVTime("2025-10-02T15:00:00");
        Position earth = new Position(t, 211.5, Math.toRadians(-154), Math.toRadians(-4));
        Position solo = new Position(t, 96.4, Math.toRadians(-40), Math.toRadians(15));

        expect("on by default, in a fresh process and in DEFAULT_MODE",
                Display.isHelioradialPerObserver() && ViewState.DEFAULT_MODE.helioradialPerObserver());
        expect("a Solar Orbiter layer seen from Earth is placed in Solar Orbiter's frame", Display.surfaceObserver(earth, solo) == solo);
        expect("an image with no observer distance falls back to the viewpoint",
                Display.surfaceObserver(earth, new Position(t, 0, 0, 0)) == earth);

        Display.setHelioradialPerObserver(false);
        expect("off, every layer faces the viewpoint", Display.surfaceObserver(earth, solo) == earth);

        JSONObject written = new JSONObject();
        ViewState.writeModeJson(written);
        expect("a session records the choice", written.has("helioradialPerObserver") && !written.getBoolean("helioradialPerObserver"));
        Display.setHelioradialPerObserver(true);
        written = new JSONObject();
        ViewState.writeModeJson(written);
        expect("and reads it back", ViewState.readModeJson(written).helioradialPerObserver());

        written.remove("helioradialPerObserver");
        expect("a session from before the choice opens with it off", !ViewState.readModeJson(written).helioradialPerObserver());

        System.out.println(failures == 0 ? "HelioradialObserverCheck: PASS" : "HelioradialObserverCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private HelioradialObserverCheck() {}
}

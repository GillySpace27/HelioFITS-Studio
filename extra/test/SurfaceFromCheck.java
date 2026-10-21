package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.state.ViewState;
import org.json.JSONObject;

/**
 * "Surface from" in the Projection palette: which observer the composed sky measures its surface
 * from. The imagery (solarSky.frag, bound per layer) and the overlays (SkyMap, Display's limb) both
 * ask Display.surfaceDistance, so this pins what that answers, and that a session carries it.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.SurfaceFromCheck
 */
public final class SurfaceFromCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        org.helioviewer.jhv.app.Platform.init(); // Layers reaches NullView, and that reaches SPICE
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        double viewpoint = 215, image = 212; // solar radii: Earth, and an observer at L1-like distance

        expect("the default is the viewpoint", !Display.isSurfaceFromImage() && !ViewState.DEFAULT_MODE.surfaceFromImage());
        expect("from the viewpoint, the imagery uses the viewpoint distance",
                Display.surfaceDistance(viewpoint, image) == viewpoint);
        expect("and so do the overlays", Display.surfaceDistance(viewpoint) == viewpoint);

        Display.setSurfaceFromImage(true);
        expect("from the image observer, the imagery uses that observer's distance",
                Display.surfaceDistance(viewpoint, image) == image);
        expect("an image with no distance falls back to the viewpoint", Display.surfaceDistance(viewpoint, 0) == viewpoint);
        org.helioviewer.jhv.layers.ImageLayer active = org.helioviewer.jhv.layers.Layers.getActiveImageLayer();
        double overlays = Display.surfaceDistance(viewpoint);
        if (active == null)
            expect("with no active layer the overlays fall back to the viewpoint", overlays == viewpoint);
        else
            expect("the overlays follow the active layer's observer (" + overlays + ")",
                    overlays == Display.surfaceDistance(viewpoint, active.getMetaData().getViewpoint().distance));

        JSONObject written = new JSONObject();
        ViewState.writeModeJson(written);
        expect("a session records the choice", written.optBoolean("surfaceFromImage", false));
        expect("and reads it back", ViewState.readModeJson(written).surfaceFromImage());

        written.remove("surfaceFromImage");
        expect("a session from before the choice reads as the viewpoint", !ViewState.readModeJson(written).surfaceFromImage());

        Display.setSurfaceFromImage(false);
        System.out.println(failures == 0 ? "SurfaceFromCheck: PASS" : "SurfaceFromCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private SurfaceFromCheck() {}
}

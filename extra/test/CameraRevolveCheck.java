package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.layers.ViewpointLayerOptions.CameraBehaviour;

import org.json.JSONObject;

/**
 * Revolving the camera is not a viewpoint, and this pins the consequence: it runs with the
 * Viewpoint layer switched off.
 *
 * <p>It used to be a fourth CameraBehaviour, armed only while that layer was driving the camera,
 * so "turn the scene round to look at it" also meant "take the viewpoint away from the observer",
 * which is a different act with its own effects on the render. All a revolution does is write the
 * camera's drag rotation (Turntable.apply), the same thing dragging with the mouse does, so it
 * never needed the layer. What it does still need is a projection that can show a rotation.
 *
 * <p>Also checks that the two ways an old session can ask for a revolution still arrive at one:
 * a state naming the TURNTABLE behaviour, and the pre-merge separate Camera layer.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.CameraRevolveCheck
 */
public final class CameraRevolveCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        MapMode savedMode = Display.mode;
        Display.mode = MapMode.Orthographic; // rendersIn3D, so a revolution can be seen

        ViewpointLayer layer = Layers.getViewpointLayer();
        expect("there is a viewpoint layer", layer != null);
        if (layer == null)
            System.exit(1);
        ViewpointLayerOptions options = layer.getOptions();
        Turntable turntable = options.getTurntable();

        layer.setEnabled(false);
        expect("the layer starts off for this check", !layer.isEnabled());
        expect("and nothing is revolving yet", !turntable.isRunning());

        // The point of the change.
        options.setRevolving(true);
        expect("revolving runs with the viewpoint layer OFF", turntable.isRunning());
        expect("and the options agree", options.isRevolving());

        // The layer's own switch is now unrelated to it, in both directions.
        layer.setEnabled(true);
        expect("switching the layer on leaves the revolution alone", turntable.isRunning() && options.isRevolving());
        layer.setEnabled(false);
        expect("switching it off again leaves the revolution alone", turntable.isRunning() && options.isRevolving());

        // The one condition it cannot survive, and it suspends rather than forgetting the choice.
        Display.mode = MapMode.HelioradialUnrolled; // flat
        ViewpointLayerOptions.projectionChanged();
        expect("a flat projection suspends the revolution", !turntable.isRunning());
        expect("but keeps the user's choice", options.isRevolving());
        Display.mode = MapMode.Orthographic;
        ViewpointLayerOptions.projectionChanged();
        expect("a 3D projection resumes it", turntable.isRunning());

        // A session round trip.
        JSONObject jo = new JSONObject();
        options.serialize(jo);
        expect("the choice is written to the session", jo.optBoolean("revolve", false));
        ViewpointLayerOptions restored = new ViewpointLayerOptions(jo);
        expect("and read back", restored.isRevolving());

        // An old session that named TURNTABLE as the behaviour: the viewpoint it always installed
        // was FREE's, so it restores as FREE, revolving.
        JSONObject legacy = new JSONObject();
        legacy.put("behaviour", CameraBehaviour.TURNTABLE.name());
        ViewpointLayerOptions migrated = new ViewpointLayerOptions(legacy);
        expect("a TURNTABLE state restores as FREE", migrated.getBehaviour() == CameraBehaviour.FREE);
        expect("and revolving", migrated.isRevolving());

        options.setRevolving(false);
        expect("and it can be stopped", !turntable.isRunning() && !options.isRevolving());

        Display.mode = savedMode;
        System.out.println(failures == 0 ? "CameraRevolveCheck: PASS" : "CameraRevolveCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private CameraRevolveCheck() {}

}

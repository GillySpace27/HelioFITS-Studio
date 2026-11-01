package org.helioviewer.jhv.app;

import org.helioviewer.jhv.display.StudyView;
import org.helioviewer.jhv.layers.ImageLayer;
import org.json.JSONObject;

/**
 * A study row's view is applied and taken back without an undo step, a dirty session, or a changed
 * layer (parameter study, WP1).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.StudyViewCheck
 */
public final class StudyViewCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            expect("no exception: " + t, false);
        }
        System.out.println(failures == 0 ? "StudyViewCheck: ok" : "StudyViewCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() throws Exception {
        // Same boilerplate as BlankSessionCheck: Sun's static setup reaches SPICE; never touch the real user.properties.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-study-view").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        AppInit.loadSpice();
        JSONObject before = Commands.getView();
        String beforeText = before.toString();
        expect("getView holds the mode block and a camera", before.has("projection") && before.has("warpLambda")
                && before.has("helioradial3D") && before.has("surfaceModel") && before.optJSONObject("camera") != null);

        ImageLayer layer = ImageLayer.createDetached(null);
        boolean enabled = layer.isEnabled();
        boolean dirty = Session.isDirty();
        int depth = SceneUndo.labels(false).size();

        String other = "Orthographic".equals(before.getString("projection")) ? "Helioradial" : "Orthographic";
        JSONObject partial = new JSONObject().put("projection", other).put("warpLambda", 0.5)
                .put("camera", new JSONObject().put("fov", 0.02).put("zoom", 1.7))
                .put("layers", new JSONObject().put(layer.getName(), false));
        Commands.setView(partial);
        JSONObject during = Commands.getView();
        expect("setView changes the projection", other.equals(during.getString("projection")));
        expect("setView changes lambda", during.getDouble("warpLambda") == 0.5);
        expect("setView changes the camera fov and zoom", during.getJSONObject("camera").getDouble("fov") == 0.02
                && during.getJSONObject("camera").getDouble("zoom") == 1.7);
        expect("a key the partial lacks keeps its value", during.getBoolean("showCorona") == before.getBoolean("showCorona"));
        expect("the override hides the named layer at draw time", StudyView.hides(layer));
        expect("the hidden layer still reports enabled", layer.isEnabled() == enabled);

        // The poll itself needs the window (State.snapshot reaches Swing statics), so what is pinned
        // here is the switch it reads: held while a row is applied, so it records no step.
        expect("the scene poll is held while a row is applied", SceneUndo.isHeld());

        Commands.restoreView(before);
        expect("restoreView returns getView to byte-identical JSON", beforeText.equals(Commands.getView().toString()));
        expect("the override is gone", !StudyView.hides(layer));
        expect("the scene poll runs again after restoreView", !SceneUndo.isHeld());
        expect("the undo stack depth is unchanged", SceneUndo.labels(false).size() == depth);
        expect("the session dirty flag is unchanged", Session.isDirty() == dirty);
        expect("the layer still reports enabled", layer.isEnabled() == enabled);

        Commands.setView(new JSONObject().put("layers", new JSONObject().put(layer.getName(), false)));
        Commands.clearViewOverride();
        expect("clearViewOverride drops the layer override", !StudyView.hides(layer));
        Commands.restoreView(before);

        expect("getView names the pizza, Off when it is off", before.getJSONObject("pizza").getString("shape").equals("Off"));
        Commands.setView(new JSONObject().put("pizza", new JSONObject().put("shape", "Wedges").put("seed", 7)));
        expect("setView turns the sun pizza on", org.helioviewer.jhv.display.Pizza.isOn());
        Commands.restoreView(before);
        expect("restoreView turns it off again, byte-identical", !org.helioviewer.jhv.display.Pizza.isOn()
                && beforeText.equals(Commands.getView().toString()));
    }
}

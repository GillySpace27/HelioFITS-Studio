package org.helioviewer.jhv.display;

import java.util.HashMap;
import java.util.Map;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.layers.ImageLayer;
import org.json.JSONObject;

/**
 * A partial scene applied for a parameter study row, then taken back (see Commands.setView).
 *
 * <p>Layer visibility is decided at draw time, like {@link Compare#hides}: ImageLayer.render asks
 * {@link #hides} and skips a layer the override turned off, so the layer's enabled flag, its
 * settings and the session's dirty state never change. Mode and camera keys go through the setters
 * a session load uses. Nothing here is saved.
 */
public final class StudyView {

    private static final class State {
        final Map<String, Boolean> layers = new HashMap<>();
    }

    private static final State S = new State();

    private StudyView() {}

    /** The mode block ({@link ViewState#writeModeJson}) plus the camera, the full view a row inherits from. */
    public static JSONObject get() {
        JSONObject view = new JSONObject();
        ViewState.writeModeJson(view);
        view.put("camera", DisplayController.cameraToJson());
        return new JSONObject(view.toString()); // writeModeJson puts enums; a job file holds their names
    }

    /** Apply only the keys present. Mode keys absent keep their value, as readModeJson leaves them. */
    public static void set(JSONObject partial) {
        ViewState.applyMode(ViewState.readModeJson(partial));
        JSONObject camera = partial.optJSONObject("camera");
        if (camera != null)
            DisplayController.cameraFromJson(camera);
        JSONObject layers = partial.optJSONObject("layers");
        synchronized (S) {
            S.layers.clear();
            if (layers != null)
                for (String name : layers.keySet())
                    S.layers.put(name, layers.optBoolean(name, true));
        }
        DisplayController.display();
    }

    /** Put back a view {@link #get} returned and drop the layer override. */
    public static void restore(JSONObject full) {
        set(full); // a full view names no layers, so the override ends too
    }

    public static void clearOverride() {
        synchronized (S) {
            S.layers.clear();
        }
        DisplayController.display();
    }

    /** True while the override turns this layer off. A layer it does not name draws as usual. */
    public static boolean hides(ImageLayer layer) {
        synchronized (S) {
            return !S.layers.isEmpty() && Boolean.FALSE.equals(S.layers.get(layer.getName()));
        }
    }
}

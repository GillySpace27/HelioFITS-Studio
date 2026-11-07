package org.helioviewer.jhv.layers;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.dialog.ModelManager;
import org.helioviewer.jhv.io.ModelAssets;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.thread.Task;

import org.json.JSONObject;

/**
 * An image layer's spacecraft, seen in place: "Spacecraft" in the layer's options adds the 3D
 * model of the observatory that took the image, placed at its position, and "Outside" swings the
 * camera out to look down from solar north on the Sun and that observer.
 *
 * <p>Which model belongs to a layer comes from the header's observatory (FitsMetaData:
 * TELESCOP, or OBSRVTRY for SECCHI, which reads "STEREO-A"), matched loosely against the
 * registry. A shown model is found again by its target, so nothing here remembers layers.
 */
public final class ObserverModels {

    /** A model to show: the registry entry and the object to place it at. */
    record Match(ModelAssets.Entry entry, String target) {}

    private static final ObserverModels INSTANCE = new ObserverModels();

    // The camera before Outside was pressed, so a second press restores it.
    private ViewpointLayerOptions.CameraBehaviour savedBehaviour;
    private JSONObject savedCamera;

    private ObserverModels() {}

    // ---- which model ----

    /**
     * The registry entry for an observatory name, or null. Exact names first (an import named after
     * its spacecraft matches too), then the spellings headers use for the bundled missions; the
     * STEREO model serves both observatories, placed at Ahead or Behind by the name's last letter.
     */
    @Nullable
    static Match match(String observatory, List<ModelAssets.Entry> entries) {
        String o = squash(observatory.split("/", 2)[0]);
        if (o.isEmpty())
            return null;
        // STEREO first: a bare "STEREO" would match the model's name, but not say which observatory.
        if (o.startsWith("STEREO")) {
            String target = o.endsWith("B") || o.endsWith("BEHIND") ? "STEREO Behind" : o.endsWith("A") || o.endsWith("AHEAD") ? "STEREO Ahead" : null;
            return target == null ? null : byId("stereo", target, entries);
        }
        for (ModelAssets.Entry e : entries)
            if (squash(e.name()).equals(o) || squash(e.id()).equals(o))
                return e.target() == null ? null : new Match(e, e.target());
        if (o.equals("PSP") || o.startsWith("PARKER"))
            return byId("psp", null, entries);
        return null;
    }

    @Nullable
    private static Match byId(String id, @Nullable String target, List<ModelAssets.Entry> entries) {
        for (ModelAssets.Entry e : entries)
            if (e.id().equals(id)) {
                String t = target != null ? target : e.target();
                return t == null ? null : new Match(e, t);
            }
        return null;
    }

    private static String squash(String s) {
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    @Nullable
    private static Match match(ImageLayer layer) {
        if (!(layer.getMetaData() instanceof FitsMetaData fm))
            return null;
        try {
            return match(fm.getObservatory(), ModelAssets.entries());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- what the layer's options use ----

    /** True when a model exists for this layer's observatory, so the button can say so. */
    public static boolean hasModel(ImageLayer layer) {
        return match(layer) != null;
    }

    /** The observatory name behind the button's tooltip, or empty. */
    public static String observatory(ImageLayer layer) {
        return layer.getMetaData() instanceof FitsMetaData fm ? fm.getObservatory() : "";
    }

    public static boolean isShown(ImageLayer layer) {
        Match m = match(layer);
        return m != null && shown(m.target()) != null;
    }

    @Nullable
    private static ModelLayer shown(String target) {
        for (Layer l : Layers.getLayers())
            if (l instanceof ModelLayer ml && ml.getPlacementMode() == ModelPlacement.Mode.EPHEMERIS && target.equals(ml.getTarget()))
                return ml;
        return null;
    }

    /** Add the layer's spacecraft model at its position, or remove it when shown; then {@code done}. */
    public static void toggle(ImageLayer layer, Runnable done) {
        Match m = match(layer);
        if (m == null) {
            done.run();
            return;
        }
        ModelLayer existing = shown(m.target());
        if (existing != null) {
            Layers.remove(existing);
            DisplayController.display();
            done.run();
            return;
        }
        Task.submitBackground(m.entry().name(), () -> new ModelLayer(ModelAssets.localPath(m.entry()).toUri()), ml -> {
            ModelManager.configure(ml, m.entry());
            ml.setTarget(m.target());
            ml.setPlacementMode(ModelPlacement.Mode.EPHEMERIS);
            Layers.add(ml);
            done.run();
        }, "Could not load the spacecraft model");
    }

    // ---- looking from outside ----

    public static boolean isOutside() {
        return INSTANCE.savedBehaviour != null;
    }

    /**
     * Look down from solar north on the Sun and this layer's observer, the observer straight
     * below the Sun; pressed again, go back to the camera as it was.
     */
    public static void toggleOutside(ImageLayer layer) {
        ViewpointLayer vl = Layers.getViewpointLayer();
        if (vl == null)
            return;
        ViewpointLayerOptions o = vl.getOptions();
        if (INSTANCE.savedBehaviour != null) {
            o.setBehaviour(INSTANCE.savedBehaviour, DisplayController.ViewpointApplyMode.RESET);
            if (INSTANCE.savedCamera != null)
                DisplayController.cameraFromJson(INSTANCE.savedCamera);
            INSTANCE.savedBehaviour = null;
            INSTANCE.savedCamera = null;
            return;
        }
        MetaData md = layer.getMetaData();
        Position p = md == null ? null : md.getViewpoint();
        if (p == null || !(p.distance > 0))
            return;
        INSTANCE.savedBehaviour = o.getBehaviour();
        INSTANCE.savedCamera = DisplayController.cameraToJson();
        o.setBehaviour(ViewpointLayerOptions.CameraBehaviour.OVERVIEW, DisplayController.ViewpointApplyMode.RESET);
        DisplayController.frameAround(halfSize(p.distance * Math.cos(p.lat)));
        DisplayController.turnToBelow(ModelPlacement.toWorld(p));
    }

    /** Half the framed width, in solar radii: the observer's in-plane distance plus margin, at least 0.6 au. */
    static double halfSize(double inPlane) {
        return Math.max(0.6 * Sun.MeanEarthDistance, 1.25 * inPlane);
    }

}

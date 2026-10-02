package org.helioviewer.jhv.app.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.fourier.SequenceParams;
import org.helioviewer.jhv.layers.ConnectionLayer;
import org.helioviewer.jhv.layers.FOVLayer;
import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.ImageLayers;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.MiniviewLayer;
import org.helioviewer.jhv.layers.TimestampLayer;
import org.helioviewer.jhv.layers.ViewpointLayer;
import org.helioviewer.jhv.layers.selector.LayerOptionSections;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * {@link State#apply}: bring the live scene to a snapshot, keeping every layer whose data did not change.
 *
 * <p>State.load builds every layer afresh, and for an image layer that means reading its whole movie
 * again: fine for opening a session, not for taking back a colour table. So layers are matched
 * instead, an image layer by its id and a built-in overlay by its class. A matched image layer keeps
 * its view and takes only the snapshot's display settings, visibility and filters; its source stays
 * as it is, because UndoStack never counts a source change as a step. Only an image layer the
 * snapshot has and the scene does not is created (from the snapshot's entry, which reads its files
 * from the cache where it can), and one the scene has and the snapshot does not is removed. A
 * built-in overlay loads nothing, so one that differs is rebuilt from its entry, less the camera:
 * the pan and zoom stay where the viewer left them.
 */
final class StateApply {

    /** The overlays undo covers. The data overlays (PFSS, events, point clouds, models) reload when rebuilt. */
    static final Set<String> OVERLAYS = Set.of(ViewpointLayer.class.getName(), ConnectionLayer.class.getName(),
            GridLayer.class.getName(), FOVLayer.class.getName(), TimestampLayer.class.getName(), MiniviewLayer.class.getName());

    static void apply(JSONObject snapshot) {
        JSONObject main = snapshot.optJSONObject(UndoStack.ROOT);
        if (main == null)
            return;
        if (main.has("projection"))
            ViewState.applyMode(ViewState.readModeJson(main)); // a no-op when nothing differs
        JSONArray overlays = main.optJSONArray("layers");
        if (overlays != null)
            applyOverlays(overlays);
        JSONArray images = main.optJSONArray("imageLayers");
        if (images != null)
            applyImageLayers(images);
        if (main.has("annotations")) {
            JSONObject annotations = main.optJSONObject("annotations");
            if (!Annotations.toJson().similar(annotations))
                Annotations.fromJson(annotations);
        }
        ViewState.applyRecordingJson(main.optJSONObject("recording"));
        ViewState.applyPlaybackJson(main.optJSONObject("playback")); // after the layers, as in State.load: the trim refers to this movie
        DisplayController.render(1);
    }

    static void applyOverlays(JSONArray target) {
        for (Object o : target) {
            if (!(o instanceof JSONObject entry) || !OVERLAYS.contains(entry.optString("className")))
                continue;
            Layer live = null;
            for (Layer layer : Layers.getLayers())
                if (layer.getClass().getName().equals(entry.optString("className")))
                    live = layer;
            if (live == null)
                continue;

            JSONObject want = UndoStack.overlayKey(entry);
            JSONObject have = UndoStack.overlayKey(State.layer2json(live, false));
            if (have.similar(want))
                continue;
            boolean enabled = entry.optBoolean("enabled", false);
            if (have.getJSONObject("data").similar(want.getJSONObject("data"))) { // only its tick differs
                live.setEnabled(enabled);
                Layers.fireLayerUpdated(live);
                continue;
            }
            JSONObject rebuild = new JSONObject().put("className", entry.optString("className")).put("data", want.getJSONObject("data"));
            if (State.json2Object(rebuild) instanceof Layer fresh) {
                State.restoreId(entry, fresh);
                fresh.setEnabled(enabled);
                replace(live, fresh);
            }
        }
    }

    // Into the old one's place, so the draw order and the sidebar row stay where they were.
    private static void replace(Layer old, Layer fresh) {
        int at = Layers.getLayers().indexOf(old);
        Layers.add(fresh);
        Layers.reorder(fresh, at);
        Layers.remove(old);
    }

    static void applyImageLayers(JSONArray target) {
        Map<String, ImageLayer> live = new LinkedHashMap<>();
        Layers.forEachImageLayer(layer -> live.put(layer.getId(), layer));
        List<ImageLayer> order = new ArrayList<>();
        Map<ImageLayer, Boolean> created = new LinkedHashMap<>();
        ImageLayer master = null;

        for (Object o : target) {
            if (!(o instanceof JSONObject entry) || !(entry.optJSONObject("data") instanceof JSONObject data))
                continue;
            boolean enabled = entry.optBoolean("enabled", false);
            ImageLayer layer = live.remove(entry.optString("id"));
            if (layer != null) {
                boolean changed = applyDisplay(layer, data);
                if (layer.isEnabled() != enabled) {
                    layer.setEnabled(enabled);
                    changed = true;
                }
                if (changed) {
                    LayerOptionSections.settingsReplaced(layer);
                    Layers.fireLayerUpdated(layer);
                }
            } else if (State.hasRestorableData(data)) {
                layer = ImageLayer.createDetached(data); // starts reading its source
                State.restoreId(entry, layer);
                Layers.add(layer);
                created.put(layer, enabled);
            } else
                continue;
            order.add(layer);
            if (entry.optBoolean("master", false))
                master = layer;
        }
        live.values().forEach(Layers::remove); // in the scene, not in the snapshot
        if (reorder(order))
            Layers.fireLayersRearranged();

        if (master != null && !created.containsKey(master) && Layers.getActiveImageLayer() != master)
            takeClock(master);
        if (!created.isEmpty()) {
            ImageLayer chosen = master;
            // As State.load does once its layers have loaded: drop the ones that failed, then put
            // back the ticks and the clock, which a view arriving would otherwise decide.
            Task.submitBackground(new ImageLayers.WaitUntilLoaded(created.keySet()), ignored -> {
                created.keySet().forEach(ImageLayer::unload);
                created.forEach((layer, on) -> {
                    if (Layers.getImageLayers().contains(layer))
                        layer.setEnabled(on);
                });
                if (chosen != null && created.containsKey(chosen) && Layers.getImageLayers().contains(chosen))
                    takeClock(chosen);
            }, Log::error);
        }
    }

    // Handing a layer the clock rewinds the movie to its first frame; the playhead is not undo's to move.
    private static void takeClock(ImageLayer layer) {
        JHVTime time = Player.getTime();
        Layers.setActiveImageLayer(layer);
        Commands.seekTime(time);
    }

    /** Set what the snapshot says about how this layer is drawn. True when anything changed. */
    private static boolean applyDisplay(ImageLayer layer, JSONObject data) {
        JSONObject now = new JSONObject();
        layer.serialize(now);
        boolean changed = false;

        JSONObject params = data.optJSONObject("imageParams");
        if (params != null && !params.similar(now.optJSONObject("imageParams"))) {
            int plane = layer.getProcessingSettings().fitsParameters().plane();
            layer.applyImageParams(params);
            // As the plane chooser does: the clip set is sampled per image, so the frames are read
            // again. From disk; nothing is downloaded.
            if (layer.getProcessingSettings().fitsParameters().plane() != plane)
                layer.reloadSources();
            changed = true;
        }

        String filter = data.optString("filter", ImageFilter.Type.None.name());
        if (!filter.equals(now.optString("filter", ImageFilter.Type.None.name()))) {
            try {
                layer.setFilter(ImageFilter.Type.valueOf(filter));
                changed = true;
            } catch (IllegalArgumentException ignore) { // a filter this build does not have
            }
        }

        JSONObject sequence = data.optJSONObject("sequence");
        JSONObject current = now.optJSONObject("sequence");
        if (sequence == null ? current != null : !sequence.similar(current)) {
            layer.setSequence(SequenceParams.fromJson(sequence)); // recomputed over the frames in memory
            changed = true;
        }
        return changed;
    }

    // Image layers into the snapshot's order. The ones before i are already placed, so the one for
    // place i is never in front of it.
    private static boolean reorder(List<ImageLayer> order) {
        boolean moved = false;
        for (int i = 0; i < order.size(); i++) {
            if (Layers.getLayers().indexOf(order.get(i)) != i) {
                Layers.reorder(order.get(i), i);
                moved = true;
            }
        }
        return moved;
    }

    private StateApply() {}
}

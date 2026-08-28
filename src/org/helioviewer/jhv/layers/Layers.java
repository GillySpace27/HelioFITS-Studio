package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.opengl.RenderGuard;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeListener;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.view.NullView;

@SuppressWarnings("unchecked")
public final class Layers {

    public interface Listener {
        void layerAdded(int index, Layer layer);

        void layerRemoved(int index, Layer layer);

        void layersCleared();

        void nameUpdated(Layer layer);

        void layerUpdated(Layer layer);

        void timeUpdated(Layer layer);
    }

    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private static final NullImageLayer nullImageLayer = new NullImageLayer(NullView.create(TimeUtils.START.milli - 2 * TimeUtils.DAY_IN_MILLIS, TimeUtils.START.milli,
            TimeUtils.defaultCadence(TimeUtils.START.milli - 2 * TimeUtils.DAY_IN_MILLIS, TimeUtils.START.milli)));
    private static ImageLayer activeLayer = nullImageLayer;

    public static final TimeListener.Selection timeSelectionListener = Layers::timeSelectionChanged;

    private static void timeSelectionChanged(long start, long end) {
        // Re-assert the exact placeholder timestamps (e.g. a point cloud series) that fall inside
        // the new selection, so a selection change doesn't replace real data times with an even
        // cadence. Falls back to the cadence view when nothing real lands in the window.
        List<JHVTime> inRange = placeholderTimesWithin(start, end);
        nullImageLayer.setView(inRange.isEmpty()
                ? NullView.create(start, end, TimeUtils.defaultCadence(start, end))
                : NullView.create(inRange));
        // Replacing the placeholder NullView also needs a full Movie resync when it is active.
        if (activeLayer == nullImageLayer)
            Player.setMaster(activeLayer);
        else
            Player.timeRangeChanged();
    }

    public static ImageLayer getActiveImageLayer() {
        return activeLayer;
    }

    // Timestamps supplied by a non-image layer to drive the movie clock; remembered so a later
    // time-selection change can restore them instead of clobbering them with an even cadence.
    private static List<JHVTime> placeholderTimes = List.of();

    private static List<JHVTime> placeholderTimesWithin(long start, long end) {
        if (placeholderTimes.isEmpty())
            return List.of();
        List<JHVTime> out = new ArrayList<>();
        for (JHVTime t : placeholderTimes)
            if (t.milli >= start && t.milli <= end)
                out.add(t);
        return out;
    }

    // Let a non-image layer (e.g. a point cloud time series) drive the movie clock when no real
    // image layer is loaded, by installing its timestamps as the placeholder master's frames.
    // A real image layer always wins — it carries the authoritative cadence — so installing is a
    // no-op once one is active (the times are still remembered). Pass an empty list to forget them.
    public static void setPlaceholderMasterTimes(java.util.Collection<JHVTime> times) {
        placeholderTimes = times.isEmpty() ? List.of() : List.copyOf(times);
        if (activeLayer != nullImageLayer || placeholderTimes.isEmpty())
            return;
        nullImageLayer.setView(NullView.create(placeholderTimes));
        Player.setMaster(nullImageLayer);
    }

    public static void setActiveImageLayer(ImageLayer layer) {
        activeLayer = layer == null ? nullImageLayer : layer;
        Player.setMaster(activeLayer);
    }

    private static int imageLayersCount;
    private static final ArrayList<Layer> layers = new ArrayList<>();
    private static final ArrayList<Layer> newLayers = new ArrayList<>();
    private static final ArrayList<Layer> removedLayers = new ArrayList<>();

    private static ViewpointLayer viewpointLayer;
    private static MiniviewLayer miniviewLayer;
    private static ConnectionLayer connectionLayer;

    // Layer constructors have side effects, so keep constructors here and build layers only when used.
    private static final LinkedHashMap<Class<? extends Layer>, Supplier<? extends Layer>> DEFAULT_LAYERS = new LinkedHashMap<>();

    static {
        DEFAULT_LAYERS.put(ViewpointLayer.class, () -> new ViewpointLayer(null));
        DEFAULT_LAYERS.put(ConnectionLayer.class, () -> new ConnectionLayer(null));
        DEFAULT_LAYERS.put(GridLayer.class, () -> new GridLayer(null));
        DEFAULT_LAYERS.put(FOVLayer.class, () -> new FOVLayer(null));
        // DEFAULT_LAYERS.put(StarLayer.class, () -> new StarLayer(null));
        DEFAULT_LAYERS.put(TimestampLayer.class, () -> new TimestampLayer(null));
        DEFAULT_LAYERS.put(MiniviewLayer.class, () -> new MiniviewLayer(null));

        DEFAULT_LAYERS.values().forEach(supplier -> add(supplier.get()));
    }

    public static ViewpointLayer getViewpointLayer() {
        return viewpointLayer;
    }

    public static MiniviewLayer getMiniviewLayer() {
        return miniviewLayer;
    }

    public static ConnectionLayer getConnectionLayer() {
        return connectionLayer;
    }

    public static void add(Layer layer) {
        if (layer instanceof ImageLayer) {
            layers.add(imageLayersCount++, layer);
        } else {
            layers.add(layer);
        }
        newLayers.add(layer);
        cacheLayer(layer);

        int row = layers.indexOf(layer);
        listeners.forEach(listener -> listener.layerAdded(row, layer));
        DisplayController.display(); // e.g., PFSS layer
    }

    private static void cacheLayer(Layer layer) {
        if (layer instanceof ViewpointLayer vl)
            viewpointLayer = vl;
        else if (layer instanceof MiniviewLayer ml)
            miniviewLayer = ml;
        else if (layer instanceof ConnectionLayer cl)
            connectionLayer = cl;
    }

    public static void remove(Layer layer) {
        int row = layers.indexOf(layer);
        if (row < 0)
            return;

        layers.remove(row);
        selection.remove(layer); // a removed layer must not keep receiving fanned-out edits
        if (layer instanceof ImageLayer il)
            fanned.remove(il);
        if (layer instanceof ImageLayer) {
            imageLayersCount--;
        }
        detach(layer);

        if (layer == activeLayer) {
            setActiveImageLayer(imageLayersCount == 0 ? null : (ImageLayer) layers.get(imageLayersCount - 1));
        }

        listeners.forEach(listener -> listener.layerRemoved(row, layer));
        DisplayController.display();
    }

    private static void detach(Layer layer) {
        if (!newLayers.remove(layer))
            removedLayers.add(layer);
    }

    public static void prerender() {
        removeLayers();
        initLayers();
        layers.forEach(layer -> RenderGuard.run(layer.getName() + " (prerender)", layer::prerender));
        reapImageBuffers();
    }

    public static void render(MapView mv, Viewport vp) {
        layers.forEach(layer -> RenderGuard.run(layer.getName(), () -> layer.render(mv, vp)));
    }

    public static void renderScale(MapView mv, Viewport vp) {
        layers.forEach(layer -> RenderGuard.run(layer.getName(), () -> layer.renderScale(mv, vp)));
    }

    public static void renderFloat(MapView mv, Viewport vp) {
        layers.forEach(layer -> RenderGuard.run(layer.getName() + " (float)", () -> layer.renderFloat(mv, vp)));
    }

    public static void renderFullFloat(Viewport vp) {
        layers.forEach(layer -> RenderGuard.run(layer.getName() + " (overlay)", () -> layer.renderFullFloat(vp)));
    }

    public static void renderMiniview(MapView mv, Viewport vp) {
        layers.forEach(layer -> layer.renderMiniview(mv, vp));
    }

    private static void initLayers() {
        newLayers.forEach(Layer::init);
        newLayers.clear();
    }

    private static void removeLayers() {
        removedLayers.forEach(Layer::remove);
        removedLayers.clear();
    }

    public static void reorderImageLayer(int fromIndex, int toIndex) {
        if (toIndex > layers.size()) {
            return;
        }
        Layer toMove = layers.get(fromIndex);
        if (!(toMove instanceof ImageLayer)) {
            return;
        }

        int target = Math.clamp(toIndex, 0, imageLayersCount);
        if (fromIndex < target)
            target--; // adjust insertion index after removal
        if (fromIndex == target)
            return;

        layers.remove(fromIndex);
        layers.add(target, toMove);

        if (Display.multiview) {
            ImageLayers.arrangeMultiView(true);
        }
    }

    public static void fireTimeUpdated(Layer layer) {
        listeners.forEach(listener -> listener.timeUpdated(layer));
    }

    public static void fireNameUpdated(Layer layer) {
        listeners.forEach(listener -> listener.nameUpdated(layer));
    }

    public static void fireLayerUpdated(Layer layer) {
        listeners.forEach(listener -> listener.layerUpdated(layer));
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener))
            listeners.add(listener);
    }

    public static void dispose() {
        removeLayers();
        for (Layer layer : layers) {
            if (!newLayers.contains(layer))
                layer.dispose();
        }
        newLayers.clear();
        newLayers.addAll(layers);
    }

    public static void forEachImageLayer(Consumer<? super ImageLayer> action) {
        for (int i = 0; i < imageLayersCount; i++)
            action.accept((ImageLayer) layers.get(i));
    }

    private static final ThreadLocal<Set<ImageBuffer>> retainedSet = ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private static void reapImageBuffers() {
        Set<ImageBuffer> retained = retainedSet.get();
        retained.clear();
        for (int i = 0; i < imageLayersCount; i++) {
            ((ImageLayer) layers.get(i)).collectImageBuffers(retained);
        }
        ImageBufferCache.reap(retained);
        retained.clear();
    }

    public static void setImageLayersNearestFrame(JHVTime dateTime) {
        if (imageLayersCount == 0)
            nullImageLayer.getView().setNearestFrame(dateTime);
        else
            forEachImageLayer(layer -> layer.getView().setNearestFrame(dateTime));
    }

    public static List<ImageLayer> getImageLayers() {
        return Collections.unmodifiableList((List<ImageLayer>) (Object) layers.subList(0, imageLayersCount));
    }

    public static List<Layer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    // ---- selection -------------------------------------------------------------------------
    // Which layers the user currently has picked in the layer table. Distinct from activeLayer,
    // which is the movie master and is single by nature (Player.setMaster). Selection lives here
    // rather than only in the JTable so a filter panel can ask "am I editing several at once?"
    // without reaching up into the GUI.
    private static final ArrayList<Layer> selection = new ArrayList<>();

    public static void setSelection(List<Layer> newSelection) {
        selection.clear();
        selection.addAll(newSelection);
    }

    public static List<Layer> getSelection() {
        return Collections.unmodifiableList(selection);
    }

    /**
     * Apply one edit to every image layer the edit should reach: the layer whose control was
     * touched, plus every other image layer selected alongside it.
     *
     * <p>Each filter panel binds its listeners to a single layer at construction and is cached
     * per layer, so fanning out has to happen at the point of edit rather than by rebuilding
     * panels. Routing every setter through here is what makes a control apply to the whole
     * selection. An edit on a layer that is not itself selected stays local, which is what
     * happens when a panel is driven programmatically rather than by a click.
     */
    public static void applyToSelected(ImageLayer origin, Consumer<org.helioviewer.jhv.opengl.GLImage> edit) {
        applyToSelectedLayers(origin, il -> edit.accept(il.getGLImage()));
    }

    /** As {@link #applyToSelected}, for edits that touch the layer or its View rather than its GLImage. */
    public static void applyToSelectedLayers(ImageLayer origin, Consumer<ImageLayer> edit) {
        edit.accept(origin);
        if (!selection.contains(origin))
            return;
        for (Layer layer : selection)
            if (layer != origin && layer instanceof ImageLayer il) {
                edit.accept(il);
                fanned.add(il);
            }
    }

    // Layers changed by a fan-out rather than through their own panel. Each layer's options
    // panel reads the GLImage once, when it is built, and is then cached -- so a peer edited
    // this way keeps showing the slider positions it had before, and selecting it would report
    // stale values for imagery that has already changed. Recorded here and consumed by the
    // options panel, which rebuilds from current state the next time the layer is shown.
    private static final Set<ImageLayer> fanned = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Whether this layer was edited via a fan-out since its panel was last built; clears the flag. */
    public static boolean consumeFannedEdit(ImageLayer layer) {
        return fanned.remove(layer);
    }

    private static ArrayList<Layer> normalizeRestoreList(List<Layer> restoredLayers) {
        Map<Class<? extends Layer>, Layer> restoredDefaults = new HashMap<>();
        ArrayList<Layer> normalized = new ArrayList<>();
        ArrayList<Layer> restoredOtherLayers = new ArrayList<>();

        for (Layer layer : restoredLayers) {
            if (layer instanceof ImageLayer) {
                normalized.add(layer);
            } else if (DEFAULT_LAYERS.containsKey(layer.getClass())) {
                restoredDefaults.putIfAbsent(layer.getClass(), layer);
            } else {
                restoredOtherLayers.add(layer);
            }
        }

        for (Map.Entry<Class<? extends Layer>, Supplier<? extends Layer>> entry : DEFAULT_LAYERS.entrySet()) {
            Layer layer = restoredDefaults.get(entry.getKey());
            normalized.add(layer == null ? entry.getValue().get() : layer);
        }
        normalized.addAll(restoredOtherLayers);
        return normalized;
    }

    public static void restore(List<Layer> restoredLayers) {
        ArrayList<Layer> normalizedLayers = normalizeRestoreList(restoredLayers);

        layers.forEach(Layers::detach);
        layers.clear();
        selection.clear();
        fanned.clear();
        imageLayersCount = 0;

        newLayers.addAll(normalizedLayers);

        viewpointLayer = null;
        miniviewLayer = null;
        connectionLayer = null;

        for (Layer layer : normalizedLayers) {
            if (layer instanceof ImageLayer) {
                layers.add(imageLayersCount++, layer);
            } else {
                layers.add(layer);
            }
            cacheLayer(layer);
        }

        setActiveImageLayer(null);
        listeners.forEach(Listener::layersCleared);
        DisplayController.display();
    }

    private Layers() {}
}

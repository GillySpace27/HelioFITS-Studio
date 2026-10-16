package org.helioviewer.jhv.layers;

import java.util.Map;
import java.util.WeakHashMap;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.image.ImageFilter;

/**
 * Hold to see one layer without RHEF (RH-17). Only the layer asked for changes: no selection fan-out,
 * and nothing is fired, so the other copy of the filter panel and the undo history never see it. While
 * held, a save writes the filter the layer had (ImageLayer.serialize reads {@link #persisted}), so a
 * peek can never be stored.
 */
public final class RhefPeek {

    private static final Map<ImageLayer, ImageFilter.Type> held = new WeakHashMap<>();

    /** Show {@code layer} unfiltered; nothing unless it is on RHEF and not already held. */
    public static void begin(ImageLayer layer) {
        if (held.containsKey(layer) || layer.getFilter() != ImageFilter.Type.RHEF)
            return;
        held.put(layer, layer.getFilter());
        layer.setFilter(ImageFilter.Type.None);
        DisplayController.render(1);
    }

    /** Put back what {@link #begin} took off; nothing if it was not held. */
    public static void end(ImageLayer layer) {
        ImageFilter.Type was = held.remove(layer);
        if (was == null)
            return;
        layer.setFilter(was);
        DisplayController.render(1);
    }

    public static boolean isHeld(ImageLayer layer) {
        return held.containsKey(layer);
    }

    /** The filter to save for {@code layer}: the one it had before a peek, else its own. */
    public static ImageFilter.Type persisted(ImageLayer layer) {
        ImageFilter.Type was = held.get(layer);
        return was == null ? layer.getFilter() : was;
    }

    private RhefPeek() {}

}

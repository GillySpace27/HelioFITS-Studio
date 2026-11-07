package org.helioviewer.jhv.identify;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.view.View;

/**
 * Which field the calendar marks and the timeline's Bodies in Field track draws: one setting for
 * both, so the two always agree.
 */
public enum FieldChoice {
    OFF("Off"), PUNCH("PUNCH field"), LAYER("Active layer");

    public static final String KEY = "identify.calendar.field";

    private final String label;

    FieldChoice(String _label) {
        label = _label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static FieldChoice get() {
        try {
            String v = Settings.getProperty(KEY);
            return v == null ? OFF : valueOf(v);
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }

    public static void set(FieldChoice c) {
        IdentifySettings.set(KEY, c.name());
    }

    /** The observer and field for a choice, with a key that changes when either does; null when off or no layer. */
    public record Resolved(String key, TransitIndex.Observer observer, TransitIndex.Field field) {}

    @Nullable
    public Resolved resolve() {
        String pluto = IdentifySettings.pluto() ? "+P" : "";
        if (this == PUNCH)
            return new Resolved("punch" + pluto, TransitIndex.Observer.spice("EARTH"), TransitIndex.Annulus.PUNCH);
        if (this == OFF)
            return null;
        ImageLayer layer = Layers.getActiveImageLayer();
        View.ImageData data = layer == null ? null : layer.getImageData();
        if (data == null)
            return null;
        MetaData m = data.metaData();
        String key = "layer:" + System.identityHashCode(m.getWcsHeader()) + ':' + data.region().hashCode() + ':'
                + Math.round(m.getViewpoint().distance * 10) + pluto;
        return new Resolved(key, TransitIndex.Observer.fixedFromEarth(m.getViewpoint()),
                new TransitIndex.LayerField(m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius()));
    }
}

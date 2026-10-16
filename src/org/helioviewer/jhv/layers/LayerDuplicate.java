package org.helioviewer.jhv.layers;

import java.awt.event.ActionEvent;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.state.State;
import org.json.JSONObject;

/**
 * Duplicate Layer (HS-11): a second copy of an image layer, made the way a session restore makes
 * one, so RHEF on against the same data raw is one click from the Compare palette.
 *
 * <p>The source is only read: its serialize() writes what a saved session would hold, and the copy
 * is built from that and loads its own frames (from the cache when they are there). The copy gets
 * its own fresh id.
 */
public final class LayerDuplicate {

    private LayerDuplicate() {}

    /**
     * What the copy is built from: the source's session entry. Null when the source has nothing a
     * session could restore yet (still loading with no request), the same rule State.save applies.
     */
    @Nullable
    public static JSONObject recipe(ImageLayer source) {
        JSONObject jo = new JSONObject();
        source.serialize(jo);
        return State.hasRestorableData(jo) ? jo : null;
    }

    /** A new layer from the source's recipe, added to the scene; null, with a message, when there is none. */
    @Nullable
    public static ImageLayer duplicate(@Nullable ImageLayer source) {
        if (source == null) {
            Message.warn("Duplicate Layer", "No image layer is selected.");
            return null;
        }
        JSONObject recipe = recipe(source);
        if (recipe == null) {
            Message.warn("Duplicate Layer", "This layer has nothing to copy yet; try again once it has loaded.");
            return null;
        }
        return ImageLayer.create(recipe);
    }

    /** The catalogued action: duplicates the active image layer. */
    public static Action action() {
        return new AbstractAction("Duplicate Layer") {
            @Override
            public void actionPerformed(ActionEvent e) {
                duplicate(Layers.getActiveImageLayer());
            }
        };
    }

}

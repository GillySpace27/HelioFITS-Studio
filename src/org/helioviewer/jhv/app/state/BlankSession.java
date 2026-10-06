package org.helioviewer.jhv.app.state;

import java.util.List;

import org.helioviewer.jhv.layers.Layers;

/**
 * The scene a fresh install opens with, for Start New Session and a blank start (HS-10).
 *
 * <p>Takes the two steps a session load takes (State.load): Layers.restore with no saved layers
 * builds every built-in layer again from its constructor and drops the image layers, and the view
 * mode goes back to {@link ViewState#DEFAULT_MODE}. So the projection, the grid, the viewpoint and
 * the timestamp reset along with the layer list, which removing layers one by one never did.
 * BlankSessionCheck pins it.
 */
public final class BlankSession {

    public static void apply() {
        Layers.restore(List.of());
        ViewState.applyMode(ViewState.DEFAULT_MODE);
    }

    private BlankSession() {}

}

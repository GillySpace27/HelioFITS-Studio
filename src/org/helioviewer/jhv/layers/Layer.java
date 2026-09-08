package org.helioviewer.jhv.layers;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;

import org.json.JSONObject;

public interface Layer {

    /**
     * Which list a layer belongs in, and nothing else.
     *
     * <p>Three answers rather than two, because "not an image" turned out to cover two unrelated
     * things. An overlay is drawn on top of the picture; a viewpoint layer decides where the
     * picture is seen from and may draw nothing at all. Putting the camera among the grid and the
     * timestamps filed a cause under its effects.
     */
    enum Kind {IMAGE, VIEWPOINT, OVERLAY}

    /** Overlay unless a layer says otherwise, which is what most of them are. */
    default Kind kind() {
        return Kind.OVERLAY;
    }

    default void render(MapView mv, Viewport vp) {}

    default void renderScale(MapView mv, Viewport vp) {}

    default void renderFloat(MapView mv, Viewport vp) {}

    default void renderFullFloat(Viewport vp) {}

    default void renderMiniview(MapView mv, Viewport vp) {}

    default void prerender() {}

    void remove();

    String getName();

    boolean isEnabled();

    void setEnabled(boolean b);

    int isVisibleIdx();

    boolean isVisible(int idx);

    void setVisible(int idx);

    @Nullable
    default String getTimeString() {
        return null;
    }

    default boolean isDeletable() {
        return false;
    }

    default boolean isDownloading() {
        return false;
    }

    default boolean isLocal() {
        return false;
    }

    void init();

    void dispose();

    void serialize(JSONObject jo);

}

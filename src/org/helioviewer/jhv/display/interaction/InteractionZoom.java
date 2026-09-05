package org.helioviewer.jhv.display.interaction;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.PointerEvent;

/**
 * Zoom by dragging: up to zoom in, down to zoom out.
 *
 * <p>Zoom has always been the wheel, which a mouse user has and a touchpad user has as a pinch. This
 * exists for the momentary modes, so that holding a modifier and dragging gives zoom the way the
 * other modifiers give pan and rotate, and so a user with neither a wheel nor a pinch has a way in.
 * A hundred pixels of drag is one factor of e, which is a comfortable pace: a full-height drag on a
 * laptop screen is about a factor of ten.
 */
final class InteractionZoom extends Interaction.Type {

    private static final double PIXELS_PER_E_FOLD = 100;

    private int lastY;
    private boolean dragStartSet;

    @Override
    void mousePressed(PointerEvent e, Viewport vp) {
        lastY = e.y();
        dragStartSet = true;
    }

    @Override
    void mouseDragged(PointerEvent e, Viewport vp) {
        if (!dragStartSet)
            return;
        int dy = e.y() - lastY;
        lastY = e.y();
        if (dy == 0)
            return;
        double factor = Math.exp(-dy / PIXELS_PER_E_FOLD); // screen y grows downward, so up is in
        if (Display.separateViewportZoom)
            vp.zoom *= factor;
        else
            for (Viewport viewport : Display.getViewports())
                viewport.zoom *= factor;
        DisplayController.display();
    }

    @Override
    void mouseReleased() {
        dragStartSet = false;
    }

}

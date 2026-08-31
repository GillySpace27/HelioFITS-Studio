package org.helioviewer.jhv.display.interaction;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.opengl.GLRenderer;

/**
 * Look around, in {@link org.helioviewer.jhv.display.MapMode#ObserverSky}.
 *
 * <p>Takes the place of the trackball there, because in that mode there is nothing to orbit. The
 * observer does not move: what a drag changes is which way it is facing, so this steers the aim
 * rather than rotating a scene.
 *
 * <p>The step is measured by asking the map itself what page coordinate each mouse position stands
 * for and differencing the two, instead of deriving degrees per pixel from the viewport. That keeps
 * one definition of the mapping: zoom, aspect and the field-of-view control all reach the drag
 * through the same route they reach the picture, so the sky tracks the pointer under any of them.
 * Near the centre of the page all three projections have unit slope, and the centre is exactly
 * where the aim is, so page units can be read as angles here without a correction.
 */
final class InteractionSkyLook extends Interaction.Type {

    private int lastX;
    private int lastY;
    private boolean dragStartSet; // guard against a mouseDragged arriving before mousePressed

    @Override
    void mousePressed(PointerEvent e, Viewport vp) {
        lastX = e.x();
        lastY = e.y();
        dragStartSet = true;
    }

    @Override
    void mouseDragged(PointerEvent e, Viewport vp) {
        if (!dragStartSet)
            return;

        Vec2 was = GLRenderer.getMapView().mouseToMap(vp, lastX, lastY);
        Vec2 now = GLRenderer.getMapView().mouseToMap(vp, e.x(), e.y());
        lastX = e.x();
        lastY = e.y();

        // Grab-and-drag: the sky follows the pointer, so the aim moves the other way.
        Display.steerSkyLook(-Math.toRadians(now.x - was.x), -Math.toRadians(now.y - was.y));
        DisplayController.display();
    }

    @Override
    void mouseReleased() {
        dragStartSet = false;
        Display.commitSkyLook(); // one write per drag, not one per motion event
    }

}

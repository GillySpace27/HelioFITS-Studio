package org.helioviewer.jhv.display.interaction;

import org.helioviewer.jhv.annotation.Annotateable;
import org.helioviewer.jhv.annotation.AnnotationMode;
import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.KeyInputEvent;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.opengl.GLRenderer;

final class InteractionAnnotate extends Interaction.Type {

    @Override
    void mousePressed(PointerEvent e, Viewport vp) {
        AnnotationMode mode = ViewState.getAnnotationMode();
        // A rectangle lies on the solar sphere, so a press off the disk (a coronagraph or
        // heliospheric field) has no surface point and used to draw nothing, silently. The sky
        // rectangle (FOV) is the same shape in the plane of the sky and can start anywhere.
        if (mode == AnnotationMode.Rectangle && GLRenderer.getMapView().mouseToSurface(vp, e.x(), e.y()) == null)
            mode = AnnotationMode.FOV;
        Annotateable annotateable = mode.generate(null);
        Annotations.start(annotateable);
        annotateable.mousePressed(vp, e.x(), e.y());
        if (!annotateable.isDraggable()) {
            finishAnnotateable();
        }
        DisplayController.display();
    }

    @Override
    void mouseDragged(PointerEvent e, Viewport vp) {
        Annotateable pending = Annotations.pending();
        if (pending != null && pending.isDraggable()) {
            pending.mouseDragged(vp, e.x(), e.y());
            DisplayController.display();
        }
    }

    private void finishAnnotateable() {
        Annotations.finishPending();
        DisplayController.display();
    }

    @Override
    void mouseReleased() {
        finishAnnotateable();
    }

    void keyPressed(KeyInputEvent e) {
        if (e.key() == KeyInputEvent.Key.BACKSPACE || e.key() == KeyInputEvent.Key.DELETE) {
            Annotations.removeActive();
            DisplayController.display();
        } else if (e.key() == KeyInputEvent.Key.N && Annotations.selectNext()) {
            DisplayController.display();
        } else if (e.key() == KeyInputEvent.Key.P && Annotations.selectPrevious()) {
            DisplayController.display();
        }
    }

}

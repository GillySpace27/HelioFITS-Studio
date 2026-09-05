package org.helioviewer.jhv.display.interaction;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.annotation.Annotations;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.display.Camera;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.input.KeyInputEvent;
import org.helioviewer.jhv.input.PointerEvent;
import org.helioviewer.jhv.input.ScrollEvent;

public final class Interaction {

    /** What a drag does. ZOOM is reachable only momentarily, on a modifier: the toolbar has no button for it. */
    public enum Mode {PAN, ROTATE, AXIS, ZOOM}

    abstract static class Type {
        abstract void mousePressed(PointerEvent e, Viewport vp);

        abstract void mouseDragged(PointerEvent e, Viewport vp);

        void mouseReleased() {}
    }

    private final InteractionAnnotate interactionAnnotate;
    private final InteractionTrackball interactionAxis;
    private final InteractionPan interactionPan;
    private final InteractionTrackball interactionRotate;
    private final InteractionSkyLook interactionSkyLook;
    private final InteractionZoom interactionZoom;
    private final Zoom zoom;

    private Mode mode = Mode.ROTATE; // the mode chosen on the toolbar, and remembered
    @Nullable
    private Mode held; // a mode held on a modifier key, over the chosen one, until the key is let go
    private boolean annotating = false;
    @Nullable
    private Consumer<Mode> modeListener;

    public Interaction() {
        Camera camera = Display.getCamera();
        interactionAnnotate = new InteractionAnnotate();
        interactionAxis = InteractionTrackball.axis(camera);
        interactionPan = new InteractionPan(camera);
        interactionRotate = InteractionTrackball.rotate(camera);
        interactionSkyLook = new InteractionSkyLook();
        interactionZoom = new InteractionZoom();
        zoom = new Zoom();
    }

    /** The chosen mode: a toolbar click. Remembered across sessions. */
    public void setMode(Mode _mode) {
        mode = _mode;
        Settings.setProperty("display.interaction", mode.toString());
        if (held == null)
            announce();
    }

    /** The mode in effect right now: the held one while a modifier is down, else the chosen one. */
    public Mode getMode() {
        return held != null ? held : mode;
    }

    public void setModeListener(Consumer<Mode> listener) {
        modeListener = listener;
        announce();
    }

    private void announce() {
        if (modeListener != null)
            modeListener.accept(getMode());
    }

    /**
     * Which mode a set of held modifiers stands for, or null for none.
     *
     * <p>The mapping, in one place so it can be changed in one place:
     * <ul>
     * <li>Option (Alt): ROTATE. The widest convention there is for orbiting with a drag.</li>
     * <li>Command (Meta): PAN.</li>
     * <li>Control: AXIS.</li>
     * <li>Command + Option: ZOOM, dragging up to zoom in.</li>
     * </ul>
     * Shift is not here because a shift-drag has meant "draw an annotation" for years. The point of
     * all of it is that a touchpad user, who has one button and no wheel, can reach every mode
     * without leaving the view, and gets the old mode back the moment the key is released.
     */
    @Nullable
    static Mode momentary(boolean meta, boolean alt, boolean ctrl) {
        if (meta && alt)
            return Mode.ZOOM;
        if (alt)
            return Mode.ROTATE;
        if (meta)
            return Mode.PAN;
        if (ctrl)
            return Mode.AXIS;
        return null;
    }

    // Both the key events and the pointer events report the modifier state, and both feed this, so a
    // held key is seen whether or not the view has keyboard focus: the first pointer movement over
    // the view carries the modifiers with it.
    private void hold(boolean meta, boolean alt, boolean ctrl) {
        Mode wanted = momentary(meta, alt, ctrl);
        if (wanted == held)
            return;
        held = wanted;
        announce();
        DisplayController.display();
    }

    private Type getType() {
        Mode effective = getMode();
        // The observer's sky has nothing to orbit: the observer stays put and a drag changes which
        // way it is facing. So the rotate tool becomes a look-around there, which is what a user
        // reaching for the rotate tool in that mode is asking for. Pan still pans the page.
        if (Display.mode == org.helioviewer.jhv.display.MapMode.ObserverSky && effective != Mode.PAN && effective != Mode.ZOOM)
            return interactionSkyLook;
        return switch (effective) {
            case PAN -> interactionPan;
            case ROTATE -> interactionRotate;
            case AXIS -> interactionAxis;
            case ZOOM -> interactionZoom;
        };
    }

    private boolean isAnnotating() {
        return annotating || Annotations.hasPending();
    }

    public void mouseWheelMoved(ScrollEvent e, Viewport vp) {
        zoom.zoom(vp, e.preciseWheelRotation());
    }

    public void mouseMoved(PointerEvent e) {
        hold(e.metaDown(), e.altDown(), e.ctrlDown());
    }

    public void mouseDragged(PointerEvent e, Viewport vp) {
        if (isAnnotating())
            interactionAnnotate.mouseDragged(e, vp);
        else
            getType().mouseDragged(e, vp);
    }

    public void mouseReleased() {
        if (isAnnotating())
            interactionAnnotate.mouseReleased();
        else
            getType().mouseReleased();
        annotating = false;
    }

    public void mouseClicked(PointerEvent e) {
        if (e.clickCount() == 2) {
            Display.resetViewportZoom();
            DisplayController.resetCamera();
        }
    }

    public void mousePressed(PointerEvent e, Viewport vp) {
        hold(e.metaDown(), e.altDown(), e.ctrlDown()); // decided at the press, so the whole drag agrees
        if (e.shiftDown()) {
            annotating = true;
        }
        if (annotating)
            interactionAnnotate.mousePressed(e, vp);
        else
            getType().mousePressed(e, vp);
    }

    public void keyPressed(KeyInputEvent e) {
        hold(e.metaDown(), e.altDown(), e.ctrlDown());
        if (e.shiftDown()) {
            annotating = true;
        }
        if (annotating)
            interactionAnnotate.keyPressed(e);
    }

    public void keyReleased(KeyInputEvent e) {
        hold(e.metaDown(), e.altDown(), e.ctrlDown()); // the event's modifiers no longer include the released key
        annotating = e.shiftDown();
    }

}

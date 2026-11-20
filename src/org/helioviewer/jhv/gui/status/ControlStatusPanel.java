package org.helioviewer.jhv.gui.status;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.interaction.CanvasKeys;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;

/**
 * Beside the FPS, always: the scene's zoom, warp and crop as they stand, and, while a canvas key is
 * held, the name of what it edits, with the value it is changing bracketed (Gilly, 2026-11-19: so the
 * keybindings explain themselves). Polled, as the palette's zoom readout is, so the mouse wheel and
 * the sliders show here too.
 */
@SuppressWarnings("serial")
public final class ControlStatusPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    private String shown = "";

    public ControlStatusPanel() {
        setToolTipText("The scene's zoom, warp and crop. While a canvas key is held: what it edits, its value in brackets.");
        lazyRepaint();
        UITimer.register(this);
    }

    @Override
    public void lazyRepaint() {
        String editing = CanvasKeys.editing();
        String text = (editing.isEmpty() ? "" : editing + ":  ") + CanvasKeys.scaling(Display.getActiveViewport(), true);
        if (!text.equals(shown)) {
            shown = text;
            setText(text);
        }
    }

}

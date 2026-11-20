package org.helioviewer.jhv.gui.status;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.interaction.CanvasKeys;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;

/**
 * Beside the FPS: the scene's zoom, warp and crop, with the value a held canvas key is changing
 * bracketed, and the name of what it edits in a fixed slot at the end (Gilly, 2026-11-19 and
 * 2026-11-20: so the keybindings explain themselves without the eye moving). Every part keeps its
 * width, and the whole line blanks after CanvasKeys.READOUT_IDLE_MS with nothing changing. Polled, as
 * the palette's zoom readout is, so the mouse wheel and the sliders bring it back too.
 */
@SuppressWarnings("serial")
public final class ControlStatusPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    private String shown = "";
    private String lastRaw = "";
    private long changedAt = System.currentTimeMillis();

    public ControlStatusPanel() {
        setToolTipText("The scene's zoom, warp and crop, shown for a few seconds after any change. While a canvas key is held: its value in brackets, its name at the end.");
        lazyRepaint();
        UITimer.register(this);
    }

    @Override
    public void lazyRepaint() {
        // Idle is measured on the contents, so a wheel zoom or a slider brings the readout back as a key does
        String raw = CanvasKeys.scaling(Display.getActiveViewport(), false) + CanvasKeys.editing();
        long now = System.currentTimeMillis();
        if (!raw.equals(lastRaw)) {
            lastRaw = raw;
            changedAt = now;
        }
        String text = CanvasKeys.readout(Display.getActiveViewport(), now - changedAt);
        if (!text.equals(shown)) {
            shown = text;
            setText(text);
        }
    }

}

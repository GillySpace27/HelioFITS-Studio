package org.helioviewer.jhv.gui.status;

import java.awt.Color;

import javax.annotation.Nullable;

import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.io.FitsRequest;
import org.helioviewer.jhv.io.PunchLatest;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * How old the newest frame of the active PUNCH layer is. Empty for any other layer. Amber past a week
 * (PunchLatest.STALE_MILLIS). The age is now minus the frame's own time, nothing more; it says nothing
 * about when the archive last changed.
 */
@SuppressWarnings("serial")
public final class PunchAgeStatusPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    private static final Color AMBER = new Color(0xC77700);

    private String shown = "";
    private @Nullable Color tint;

    public PunchAgeStatusPanel() {
        setToolTipText("The newest frame of the active PUNCH layer, and how old it is now. "
                + "Source: the PUNCH archive at the Solar Data Analysis Center. Amber means older than a week.");
        lazyRepaint();
        UITimer.register(this);
    }

    @Override
    public void lazyRepaint() {
        ImageLayer layer = Layers.getActiveImageLayer();
        FitsRequest req = layer == null ? null : layer.getFitsRequest();
        String text = "";
        Color color = null;
        if (req != null && req.archive() == FitsRequest.Archive.PUNCH) {
            long newest = layer.getView().getLastTime().milli, now = System.currentTimeMillis();
            text = "PUNCH " + PunchLatest.ageText(newest, now);
            color = PunchLatest.stale(newest, now) ? AMBER : null;
        }
        if (!text.equals(shown)) {
            shown = text;
            setText(text);
        }
        if (color != tint) {
            tint = color;
            setForeground(color == null ? javax.swing.UIManager.getColor("Label.foreground") : color);
        }
    }
}

package org.helioviewer.jhv.gui.status;

import java.util.Objects;

import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.movie.ExportMovie;

/**
 * Beside the FPS: how far a movie export has got, from the first captured frame until the file
 * is closed, and empty the rest of the time. A long export used to give no sign of life once
 * capture ended, while encoding and writing went on for minutes.
 */
@SuppressWarnings("serial")
public final class ExportStatusPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    private String shown = "";

    public ExportStatusPanel() {
        lazyRepaint();
        UITimer.register(this);
    }

    @Override
    public void lazyRepaint() {
        String text = Objects.requireNonNullElse(ExportMovie.progress(), "");
        if (!text.equals(shown)) {
            shown = text;
            setText(text);
            setVisible(!text.isEmpty());
        }
    }

}

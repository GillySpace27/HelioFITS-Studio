package org.helioviewer.jhv.gui.status;

import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.component.StatusPanel;
import org.helioviewer.jhv.movie.Player;

/**
 * The footer's rate. While the movie plays it is the movie's: frames the playhead actually
 * advanced per second (Player.achievedFps). It used to be the canvas draw rate at all times, which
 * counts redraws of the same frame too, so it read 47 FPS while the movie crawled at 2 or 3
 * (Gilly, 2026-11-22). Stopped, the draw rate is still shown, named for what it is.
 */
@SuppressWarnings("serial")
public final class FramerateStatusPanel extends StatusPanel.StatusPlugin implements Interfaces.LazyComponent {

    private String shown = "";

    public FramerateStatusPanel() {
        setToolTipText("<html>Playing: movie frames shown per second, over the last two seconds.<br>"
                + "Stopped: how often the view is redrawn, which is not a movie rate.</html>");
        lazyRepaint();
        UITimer.register(this);
    }

    /** The footer text. Pure, for FramerateTextCheck. */
    static String text(boolean playing, double movieFps, int drawsPerSecond) {
        if (playing)
            return Double.isNaN(movieFps) ? "FPS: --" : String.format(java.util.Locale.ROOT, "FPS: %.1f", movieFps);
        return "Draws/s: " + drawsPerSecond;
    }

    @Override
    public void lazyRepaint() {
        int draws = MainFrame.getFramerate(); // called every tick either way: it is what rolls the draw count over
        String t = text(Player.isPlaying(), Player.achievedFps(), draws);
        if (!t.equals(shown)) {
            shown = t;
            setText(t);
        }
    }

}

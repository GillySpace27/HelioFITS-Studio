package org.helioviewer.jhv.gui.component;

import javax.swing.JCheckBox;
import javax.swing.JLabel;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.Interfaces;
import org.helioviewer.jhv.gui.UITimer;
import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.movie.Player;

/**
 * The achieved playback rate beside the Clock choice (RH-16), off by default: a checkbox that shows
 * it and a label that reads Player.achievedFps twice a second while it is shown.
 */
public final class AchievedRateReadout implements Interfaces.LazyComponent {

    static final String KEY = "player.show_fps_readout";
    private static final long REFRESH_NANOS = 500_000_000L;

    final JCheckBox toggle = new JCheckBox("Achieved");
    final JLabel label = new JLabel(text(Double.NaN));
    private long lastRefresh;

    public AchievedRateReadout() {
        boolean shown = isShown();
        toggle.setSelected(shown);
        toggle.setFont(UIGlobals.uiFontSmall);
        toggle.setToolTipText("Show the frame rate playback actually reaches, beside the Play rate it asks for");
        toggle.addItemListener(e -> {
            Settings.setProperty(KEY, Boolean.toString(toggle.isSelected()));
            label.setVisible(toggle.isSelected());
        });
        label.setFont(UIGlobals.uiFontSmall);
        label.setToolTipText("<html>Movie frames shown per second over the last two seconds while playing.<br>"
                + "The FPS at the bottom left counts canvas draws, which is not the same thing.</html>");
        label.setVisible(shown);
        UITimer.register(this);
    }

    static boolean isShown() {
        return Boolean.parseBoolean(Settings.getProperty(KEY)); // unset reads false: off by default
    }

    static String text(double fps) {
        return Double.isNaN(fps) ? "-- fps" : String.format("%.1f fps", fps);
    }

    @Override
    public void lazyRepaint() {
        if (!label.isVisible())
            return;
        long now = System.nanoTime();
        if (now - lastRefresh < REFRESH_NANOS)
            return;
        lastRefresh = now;
        String t = text(Player.achievedFps());
        if (!t.equals(label.getText()))
            label.setText(t);
    }

}

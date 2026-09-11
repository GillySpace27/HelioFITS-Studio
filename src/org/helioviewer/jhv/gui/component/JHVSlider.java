package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;

import org.helioviewer.jhv.automation.Automation;
import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.timelines.AutomationTimelineLayer;
import org.helioviewer.jhv.timelines.draw.DrawController;

@SuppressWarnings("serial")
public final class JHVSlider extends JSlider {

    // The parameter this slider drives, in the grammar Automation resolves, or null for the
    // several dozen sliders that are out of scope. This field IS the registration the animation
    // spec asked for: a map from slider to key, kept on the slider so nothing has to be
    // unregistered when a layer's options panel is thrown away with the layer.
    @Nullable
    private String paramKey;

    // The number printed beside this slider, when it has one. While a curve is driving the
    // parameter the slider's position and this number are both stale: the applier writes straight
    // to the parameter and deliberately does not push the value back into Swing (half the sliders
    // would feed back on themselves). Greying the number is the cheap, honest alternative: it says
    // "not in charge" without pretending to track a value it is not being told about. The live
    // number is in the lane, which is where the curve is.
    @Nullable
    private JLabel readout;

    public JHVSlider(int min, int max, int defaultValue) {
        super(JSlider.HORIZONTAL, min, max, defaultValue);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !e.isConsumed()) {
                    e.consume();
                    setValue(defaultValue);
                }
            }

            // Both, because the popup trigger is a press on macOS and a release on Windows.
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });

        addChangeListener(e -> {
            if (paramKey == null)
                return;
            if (getValueIsAdjusting())
                Automation.setLatched(paramKey); // hands off, curve: see Automation.setLatched
            else if (paramKey.equals(Automation.getLatched())) {
                Automation.setLatched(null);
                // The drag that just ended is the key. Only a drag: a programmatic setValue, of
                // which there are many when panels are rebuilt for another layer, never latched
                // and so never writes, which is what keeps switching layers from editing a curve.
                AutomationTimelineLayer.writeKeyAndRedraw(paramKey);
            }
        });

        WheelSupport.installMouseWheelSupport(this);
    }

    /** Binds this slider to an animatable parameter, which is what puts Animate in its menu. */
    public JHVSlider animates(String _paramKey) {
        paramKey = _paramKey;
        return this;
    }

    /** The number printed beside this slider, greyed out whenever a curve is driving it. */
    public JHVSlider readout(JLabel _readout) {
        readout = _readout;
        refreshReadout();
        return this;
    }

    private void refreshReadout() {
        if (readout != null)
            readout.setEnabled(paramKey == null || !Automation.isDriving(paramKey));
    }

    /**
     * Re-greys every bound slider's readout, after anything that changed who is in charge.
     *
     * <p>By walking the windows rather than by a listener list, the way UIGlobals.refreshThemed
     * does. A registry of live sliders would have to be pruned as layer panels come and go, and a
     * stale entry in it is a leak that holds a whole options panel; there are a few dozen sliders
     * and this runs when a menu item is chosen, not per frame.
     */
    public static void refreshAll() {
        for (Window w : Window.getWindows())
            refresh(w);
    }

    private static void refresh(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JHVSlider slider)
                slider.refreshReadout();
            if (child instanceof Container inner)
                refresh(inner);
        }
    }

    private void popup(MouseEvent e) {
        if (paramKey == null || !e.isPopupTrigger())
            return;
        String key = paramKey;
        Track track = Automation.get(key);
        boolean armed = track != null;
        boolean suspended = armed && track.isSuspended();

        JPopupMenu menu = new JPopupMenu();
        JMenuItem toggle = new JMenuItem(armed ? "Stop animating" : "Animate");
        toggle.setToolTipText(armed
                ? "Remove the curve and leave the value where it is now"
                : "Make this a curve across the movie's clock, edited in the Timelines plot");
        toggle.addActionListener(a -> {
            if (armed)
                AutomationTimelineLayer.disarm(key);
            else
                AutomationTimelineLayer.arm(key);
            refreshAll();
        });
        menu.add(toggle);

        if (armed) {
            // The override. Its two halves are one item because they are one question -- who has
            // this parameter -- and a person who can see which way it currently reads can answer
            // it without having to remember a mode.
            JMenuItem override = new JMenuItem(suspended ? "Return to curve" : "Take manual control");
            override.setToolTipText(suspended
                    ? "Give the parameter back to its curve, which takes it at the next frame"
                    : "Hold this value by hand. The curve is kept exactly as it is, and nothing "
                            + "you do to the slider is written to it until you hand it back.");
            override.addActionListener(a -> {
                Automation.setSuspended(key, !suspended);
                refreshAll();
                DrawController.drawRequest();
                DisplayController.display(); // the picture changes the instant the wheel changes hands
            });
            menu.add(override);

            JMenuItem add = new JMenuItem("Add key at playhead");
            // Writing while overridden would be the one thing the override promises not to do.
            add.setEnabled(!suspended);
            if (suspended)
                add.setToolTipText("Under manual control. Return to the curve to write to it.");
            add.addActionListener(a -> AutomationTimelineLayer.writeKeyAndRedraw(key));
            menu.add(add);
        }
        menu.show(this, e.getX(), e.getY());
    }

}

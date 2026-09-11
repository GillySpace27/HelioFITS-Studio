package org.helioviewer.jhv.gui.component;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.annotation.Nullable;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;

import org.helioviewer.jhv.automation.Automation;
import org.helioviewer.jhv.timelines.AutomationTimelineLayer;

@SuppressWarnings("serial")
public final class JHVSlider extends JSlider {

    // The parameter this slider drives, in the grammar Automation resolves, or null for the
    // several dozen sliders that are out of scope. This field IS the registration the animation
    // spec asked for: a map from slider to key, kept on the slider so nothing has to be
    // unregistered when a layer's options panel is thrown away with the layer.
    @Nullable
    private String paramKey;

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

    private void popup(MouseEvent e) {
        if (paramKey == null || !e.isPopupTrigger())
            return;
        String key = paramKey;
        boolean armed = Automation.get(key) != null;

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
        });
        menu.add(toggle);

        if (armed) {
            JMenuItem add = new JMenuItem("Add key at playhead");
            add.addActionListener(a -> AutomationTimelineLayer.writeKeyAndRedraw(key));
            menu.add(add);
        }
        menu.show(this, e.getX(), e.getY());
    }

}

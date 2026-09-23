package org.helioviewer.jhv.gui.component;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import com.jidesoft.swing.RangeSlider;

@SuppressWarnings("serial")
public final class JHVRangeSlider extends RangeSlider {

    public JHVRangeSlider(int min, int max, int low, int high) {
        super(min, max, low, high);
        setRangeDraggable(true);
        // No wheel handling here, deliberately. A MouseWheelEvent is delivered to the deepest
        // component under the pointer that has a listener, so a slider that listens swallows every
        // scroll that crosses it: dragging the sidebar's scrollbar-less content past a row of
        // sliders adjusted each one in turn instead of scrolling, which is a scroll gesture that
        // silently edits the picture. Without a listener the event walks up to the sidebar's
        // JScrollPane, which is what the user was aiming at.

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    setLowValue(low);
                    setHighValue(high);
                }
            }
        });
    }

}

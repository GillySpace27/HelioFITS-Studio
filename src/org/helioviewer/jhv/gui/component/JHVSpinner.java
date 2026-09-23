package org.helioviewer.jhv.gui.component;

import javax.swing.JSpinner;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;

@SuppressWarnings("serial")
public final class JHVSpinner extends JSpinner {

    public JHVSpinner(SpinnerModel model) {
        super(model);
        // No wheel handling here, deliberately. A MouseWheelEvent is delivered to the deepest
        // component under the pointer that has a listener, so a slider that listens swallows every
        // scroll that crosses it: dragging the sidebar's scrollbar-less content past a row of
        // sliders adjusted each one in turn instead of scrolling, which is a scroll gesture that
        // silently edits the picture. Without a listener the event walks up to the sidebar's
        // JScrollPane, which is what the user was aiming at.
    }

    public JHVSpinner(double value, double min, double max, double step) {
        this(new SpinnerNumberModel(value, min, max, step));
    }

    public JHVSpinner(int value, int min, int max, int step) {
        this(new SpinnerNumberModel(value, min, max, step));
    }

    @Override
    public Object getValue() {
        try {
            // Return the parsed editor value, not a stale pre-edit model value.
            commitEdit();
        } catch (Exception ignore) {}
        return super.getValue();
    }

}

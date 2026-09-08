package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.gui.component.CollapsiblePane;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.layers.TimestampLayer;

@SuppressWarnings("serial")
final class TimestampLayerOptions extends JPanel {

    TimestampLayerOptions(TimestampLayer layer) {
        JHVSlider slider = new JHVSlider(TimestampLayer.MIN_SCALE, TimestampLayer.MAX_SCALE, layer.getScale());
        slider.addChangeListener(e -> layer.setScale(slider.getValue()));

        JHVSlider sliderX = createOffsetSlider(layer.getOffsetX(), layer::setOffsetX);
        sliderX.setToolTipText("Horizontal placement as a fraction of the viewport, so it holds when the window is resized or the recording aspect changes. The text stays inside the edge at both ends.");
        JHVSlider sliderY = createOffsetSlider(layer.getOffsetY(), layer::setOffsetY);
        sliderY.setToolTipText("Vertical placement as a fraction of the viewport. Zero is the bottom, the top of the range is where the old Top checkbox put it.");

        JPanel panelSlider = new JPanel(new GridBagLayout());
        addSliderRow(panelSlider, "Size", slider, 0);
        addSliderRow(panelSlider, "X", sliderX, 1);
        addSliderRow(panelSlider, "Y", sliderY, 2);

        JPanel panelCheck = new JPanel(new GridBagLayout());
        GridBagConstraints c1 = new GridBagConstraints();
        c1.weightx = 1;
        c1.weighty = 1;
        c1.gridy = 0;
        c1.anchor = GridBagConstraints.LINE_END;
        c1.gridx = 0;
        JCheckBox showExtra = new JCheckBox("Extra info", layer.isExtra());
        showExtra.setHorizontalTextPosition(SwingConstants.LEFT);
        showExtra.setToolTipText("Appends the observer distance and the field of view to the timestamp line itself.");
        showExtra.addActionListener(e -> layer.setExtra(showExtra.isSelected()));
        panelCheck.add(showExtra, c1);

        c1.anchor = GridBagConstraints.LINE_START;
        c1.gridx = 1;
        JCheckBox showClock = new JCheckBox("Show clock", layer.isShowClock());
        showClock.setHorizontalTextPosition(SwingConstants.LEFT);
        showClock.addActionListener(e -> layer.setShowClock(showClock.isSelected()));
        panelCheck.add(showClock, c1);

        // Their own collapsed section, following GridLayerOptions in this package: four more ticks
        // in the row above would bury the two that shape the timestamp itself among settings that
        // add lines beneath it.
        JPanel panelAnnotate = new JPanel(new GridBagLayout());
        addCheckBox(panelAnnotate, "Version", "The build that drew the frame: program name, version and revision.",
                layer.isShowVersion(), layer::setShowVersion, 0, 0);
        addCheckBox(panelAnnotate, "Projection", "The projection, with the warp exponent, radial crop and disk scale where they apply to it.",
                layer.isShowProjection(), layer::setShowProjection, 1, 0);
        addCheckBox(panelAnnotate, "Filter", "The sequence filter on the master image layer: the velocity band or the noise gate.",
                layer.isShowFilter(), layer::setShowFilter, 0, 1);
        addCheckBox(panelAnnotate, "Observer", "Where the view is taken from, and its distance from the Sun.",
                layer.isShowObserver(), layer::setShowObserver, 1, 1);

        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        add(panelSlider);
        add(panelCheck);
        add(new CollapsiblePane("Annotations", panelAnnotate, false, true));
    }

    private static void addSliderRow(JPanel panel, String text, Component component, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.weighty = 1;
        c.gridy = y;
        c.anchor = GridBagConstraints.LINE_END;
        c.gridx = 0;
        panel.add(new JLabel(text, JLabel.RIGHT), c);
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 1;
        panel.add(component, c);
    }

    /** Two to a row, anchored like the pair above them: left column trailing, right column leading. */
    private static void addCheckBox(JPanel panel, String text, String tooltip, boolean selected, Consumer<Boolean> setter, int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = x;
        c.gridy = y;
        c.anchor = x == 0 ? GridBagConstraints.LINE_END : GridBagConstraints.LINE_START;
        JCheckBox box = new JCheckBox(text, selected);
        box.setHorizontalTextPosition(SwingConstants.LEFT);
        box.setToolTipText(tooltip);
        box.addActionListener(e -> setter.accept(box.isSelected()));
        panel.add(box, c);
    }

    /** Percent on the widget, fraction on the layer, matching the other 0..1 sliders in this package. */
    private static JHVSlider createOffsetSlider(double initialValue, DoubleConsumer valueSetter) {
        JHVSlider slider = new JHVSlider(0, 100, (int) Math.round(initialValue * 100));
        slider.addChangeListener(e -> valueSetter.accept(slider.getValue() / 100.));
        return slider;
    }

}

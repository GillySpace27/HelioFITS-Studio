package org.helioviewer.jhv.layers.filters;

import java.awt.Component;

import javax.swing.JLabel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.JHVRangeSlider;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

public class LevelsPanel implements FilterDetails {

    private final JHVRangeSlider slider;
    private final JLabel label;
    private final JLabel title = new JLabel("Levels ", JLabel.RIGHT);

    static String formatPercent(int value) {
        return "<html><p align='right'>" + value + "%</p>";
    }

    static String formatPercent(int low, int high) {
        return "<html><p align='right'>" + low + "%</p><p align='right'>" + high + "%</p>";
    }

    public LevelsPanel(ImageLayer layer) {
        double offset = layer.getGLImage().getBrightOffset();
        double scale = layer.getGLImage().getBrightScale();
        int high = (int) (100 * (offset + scale));
        slider = new JHVRangeSlider(-101, 201, (int) (offset * 100), high);

        label = new JLabel(formatPercent(slider.getLowValue(), slider.getHighValue()), JLabel.RIGHT);
        slider.addChangeListener(e -> {
            int lo = slider.getLowValue();
            int hi = slider.getHighValue();
            label.setText(formatPercent(lo, hi));
            if (syncing)
                return; // mirroring the layer, not editing it
            Layers.applyToSelected(layer, gl -> gl.setBrightness(lo / 100., (hi - lo) / 100.));
            DisplayController.display();
        });
    }

    private boolean syncing;

    /**
     * Mirror the layer's Levels into the slider. They can move without this row: the Fourier
     * palette's gain is a contrast applied through Levels, and a restored session brings its own.
     * Before this the row was read once, at construction, and then described whatever it had
     * last been dragged to.
     */
    public void refresh(ImageLayer layer) {
        double offset = layer.getGLImage().getBrightOffset();
        double scale = layer.getGLImage().getBrightScale();
        int lo = (int) Math.round(offset * 100), hi = (int) Math.round((offset + scale) * 100);
        if (slider.getLowValue() == lo && slider.getHighValue() == hi)
            return;
        syncing = true;
        slider.setLowValue(lo);
        slider.setHighValue(hi);
        syncing = false;
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return slider;
    }

    @Override
    public Component getThird() {
        return label;
    }

}

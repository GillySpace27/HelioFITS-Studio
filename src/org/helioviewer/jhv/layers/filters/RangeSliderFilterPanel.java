package org.helioviewer.jhv.layers.filters;

import javax.swing.JLabel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.JHVRangeSlider;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;

public final class RangeSliderFilterPanel {

    private RangeSliderFilterPanel() {
    }

    public static FilterDetails levels(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        double offset = settings.getBrightOffset();
        double scale = settings.getBrightScale();
        return create("Levels ", -101, 201, (int) (offset * 100), (int) ((offset + scale) * 100),
                RangeSliderFilterPanel::formatPercent,
                (low, high) -> settings.setBrightness(low / 100., (high - low) / 100.));
    }

    public static FilterDetails mask(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        int maximum = ImageDisplaySettings.MAX_MASK * 100;
        int outer = Double.isFinite(settings.getOuterMask()) ? (int) (settings.getOuterMask() * 100) : maximum;
        return create("Mask ", 0, maximum, (int) (settings.getInnerMask() * 100), outer,
                (low, high) -> formatMask(low, high, maximum),
                (low, high) -> settings.setMask(low / 100., high == maximum ? Double.POSITIVE_INFINITY : high / 100.));
    }

    public static FilterDetails slit(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("Slit ", 0, 100,
                (int) (settings.getSlitLeft() * 100), (int) (settings.getSlitRight() * 100),
                RangeSliderFilterPanel::formatPercent,
                (low, high) -> settings.setSlit(low / 100., high / 100.));
    }

    private static FilterDetails create(
            String titleText,
            int min, int max, int initialLow, int initialHigh,
            RangeFormatter formatter,
            RangeConsumer onValueChange) {
        JLabel title = new JLabel(titleText, JLabel.RIGHT);
        JHVRangeSlider slider = new JHVRangeSlider(min, max, initialLow, initialHigh);
        JLabel label = new JLabel(formatter.format(initialLow, initialHigh), JLabel.RIGHT);
        slider.addChangeListener(e -> {
            int low = slider.getLowValue();
            int high = slider.getHighValue();
            onValueChange.accept(low, high);
            label.setText(formatter.format(low, high));
            DisplayController.display();
        });
        return new FilterRow(title, slider, label);
    }

    private static String formatPercent(int low, int high) {
        return "<html><p align='right'>" + low + "%</p><p align='right'>" + high + "%</p>";
    }

    private static String formatMask(int low, int high, int maximum) {
        String outer = high == maximum ? "∞" : String.format("%.2f", high / 100.);
        return "<html><p align='right'>" + String.format("%.2f", low / 100.) + "R☉</p><p align='right'>" + outer + "R☉</p>";
    }

    @FunctionalInterface
    private interface RangeConsumer {
        void accept(int low, int high);
    }

    @FunctionalInterface
    private interface RangeFormatter {
        String format(int low, int high);
    }

}

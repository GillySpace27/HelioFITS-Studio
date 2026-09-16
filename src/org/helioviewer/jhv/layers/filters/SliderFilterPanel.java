package org.helioviewer.jhv.layers.filters;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import javax.annotation.Nullable;
import javax.swing.JLabel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

public final class SliderFilterPanel {

    private SliderFilterPanel() {
    }

    public static FilterDetails blend(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("Blend ", 0, 100, (int) (settings.getBlend() * 100),
                SliderFilterPanel::formatPercent,
                value -> Layers.applyToSelected(layer, s -> s.setBlend(value / 100.)),
                "layer:" + layer.getId() + "/blend");
    }

    public static FilterDetails deltaCROTA(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("δCROTA", ImageDisplaySettings.MIN_DCROTA * 10, ImageDisplaySettings.MAX_DCROTA * 10,
                (int) (settings.getDeltaCROTA() * 10),
                value -> formatDegree(value / 10.0),
                value -> Layers.applyToSelected(layer, s -> s.setDeltaCROTA(value / 10.0)));
    }

    public static FilterDetails deltaCRVAL1(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("δCRVAL1", ImageDisplaySettings.MIN_DCRVAL, ImageDisplaySettings.MAX_DCRVAL,
                settings.getDeltaCRVAL1(), SliderFilterPanel::formatArcsec,
                value -> Layers.applyToSelected(layer, s -> s.setDeltaCRVAL1(value)));
    }

    public static FilterDetails deltaCRVAL2(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("δCRVAL2", ImageDisplaySettings.MIN_DCRVAL, ImageDisplaySettings.MAX_DCRVAL,
                settings.getDeltaCRVAL2(), SliderFilterPanel::formatArcsec,
                value -> Layers.applyToSelected(layer, s -> s.setDeltaCRVAL2(value)));
    }

    public static FilterDetails opacity(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("Opacity ", 0, 100, (int) (settings.getOpacity() * 100),
                SliderFilterPanel::formatPercent,
                value -> Layers.applyToSelected(layer, s -> s.setOpacity(value / 100.)),
                "layer:" + layer.getId() + "/opacity");
    }

    public static FilterDetails sharpen(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("Sharpen ", -100, 100, (int) (settings.getSharpen() * 100),
                SliderFilterPanel::formatPercent,
                value -> Layers.applyToSelected(layer, s -> s.setSharpen(value / 100.)),
                "layer:" + layer.getId() + "/sharpen");
    }

    private static String formatDegree(double value) {
        return "<html><p align='right'>" + String.format("%.1f", value) + "°</p>";
    }

    private static String formatArcsec(int value) {
        return "<html><p align='right'>" + value + "″</p>";
    }

    static String formatPercent(int value) {
        return "<html><p align='right'>" + value + "%</p>";
    }

    static FilterDetails create(
            String titleText,
            int min, int max, int initial,
            IntFunction<String> formatter,
            IntConsumer onValueChange) {
        return create(titleText, min, max, initial, formatter, onValueChange, null);
    }

    /**
     * A slider row. {@code paramKey} binds it to an automation track, so the value can be a
     * function of time rather than a constant; null for the rows that have no track, which is the
     * geometry set (δCROTA, δCRVAL) and the sector.
     */
    static FilterDetails create(
            String titleText,
            int min, int max, int initial,
            IntFunction<String> formatter,
            IntConsumer onValueChange,
            @Nullable String paramKey) {
        JLabel title = new JLabel(titleText, JLabel.RIGHT);
        JHVSlider slider = new JHVSlider(min, max, initial);
        JLabel label = new JLabel(formatter.apply(initial), JLabel.RIGHT);
        if (paramKey != null)
            slider.animates(paramKey).readout(label);
        slider.addChangeListener(e -> {
            int value = slider.getValue();
            onValueChange.accept(value);
            label.setText(formatter.apply(value));
            DisplayController.display();
        });
        return new FilterRow(title, slider, label);
    }

}

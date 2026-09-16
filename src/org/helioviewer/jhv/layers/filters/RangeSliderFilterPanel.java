package org.helioviewer.jhv.layers.filters;

import java.awt.Component;

import javax.swing.JLabel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.JHVRangeSlider;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.wcs.ImageBounds;

public final class RangeSliderFilterPanel {

    private RangeSliderFilterPanel() {
    }

    public static Levels levels(ImageLayer layer) {
        return new Levels(layer);
    }

    /**
     * The double-ended radial mask: the band between the two handles is shown.
     *
     * <p>The slider runs over the layer's own radial extent rather than over a fixed span in solar
     * radii, so one tick is the same fine step whatever the field of view: 0.001 of the way out to
     * the far corner for a full-disk imager and for a coronagraph alike. The value stored is still
     * in solar radii, which is what the shader reads, and the top of the slider stores an infinite
     * outer radius so "no outer mask" stays exactly unbounded rather than a large number.
     */
    public static FilterDetails mask(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        // The corner (outermost) radius, so 1.0 is the far corner and the handle reaches the edge
        // of the frame without a jump.
        double radial = ImageBounds.radial(layer.getMetaData());
        double outerR = radial > 0 ? radial : 1;
        int low = (int) Math.round(settings.getInnerMask() / outerR * MASK_STEPS);
        int high = Double.isFinite(settings.getOuterMask())
                ? (int) Math.round(settings.getOuterMask() / outerR * MASK_STEPS) : MASK_STEPS;
        return create("Mask ", 0, MASK_STEPS, Math.clamp(low, 0, MASK_STEPS), Math.clamp(high, 0, MASK_STEPS),
                (lo, hi) -> formatMask(inner(lo, outerR), outer(hi, outerR)),
                (lo, hi) -> Layers.applyToSelected(layer, s -> s.setMask(inner(lo, outerR), outer(hi, outerR))));
    }

    public static FilterDetails slit(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        return create("Slit ", 0, 100,
                (int) (settings.getSlitLeft() * 100), (int) (settings.getSlitRight() * 100),
                RangeSliderFilterPanel::formatPercent,
                (low, high) -> Layers.applyToSelected(layer, s -> s.setSlit(low / 100., high / 100.)));
    }

    private static final int MASK_STEPS = 1000; // 0.001 of the layer's radial extent per tick

    private static double inner(int low, double outerR) {
        return low * outerR / MASK_STEPS;
    }

    private static double outer(int high, double outerR) {
        return high == MASK_STEPS ? Double.POSITIVE_INFINITY : high * outerR / MASK_STEPS;
    }

    /**
     * The Levels row: the display window, as a pair of handles.
     *
     * <p>A class rather than a plain row because the window moves without anyone touching this
     * slider: the Contrast row is the same pair held differently, and a restored session brings
     * its own. Before {@link #refresh} the row was read once, at construction, and then described
     * whatever it had last been dragged to.
     */
    public static final class Levels implements FilterDetails {

        private final JLabel title = new JLabel("Levels ", JLabel.RIGHT);
        private final JHVRangeSlider slider;
        private final JLabel label;
        private boolean syncing;

        private Levels(ImageLayer layer) {
            ImageDisplaySettings settings = layer.getDisplaySettings();
            double offset = settings.getBrightOffset();
            double scale = settings.getBrightScale();
            slider = new JHVRangeSlider(-101, 201, (int) (offset * 100), (int) ((offset + scale) * 100));
            label = new JLabel(formatPercent(slider.getLowValue(), slider.getHighValue()), JLabel.RIGHT);
            slider.addChangeListener(e -> {
                int low = slider.getLowValue();
                int high = slider.getHighValue();
                label.setText(formatPercent(low, high));
                if (syncing)
                    return; // mirroring the layer, not editing it
                Layers.applyToSelected(layer, s -> s.setBrightness(low / 100., (high - low) / 100.));
                DisplayController.display();
            });
        }

        /** Mirror the layer's window into the slider. */
        public void refresh(ImageLayer layer) {
            ImageDisplaySettings settings = layer.getDisplaySettings();
            double offset = settings.getBrightOffset();
            double scale = settings.getBrightScale();
            int low = (int) Math.round(offset * 100), high = (int) Math.round((offset + scale) * 100);
            if (slider.getLowValue() == low && slider.getHighValue() == high)
                return;
            syncing = true;
            slider.setLowValue(low);
            slider.setHighValue(high);
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

    private static String formatMask(double inner, double outer) {
        String high = Double.isFinite(outer) ? String.format("%.2f", outer) : "∞";
        return "<html><p align='right'>" + String.format("%.2f", inner) + "R☉</p><p align='right'>" + high + "R☉</p>";
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

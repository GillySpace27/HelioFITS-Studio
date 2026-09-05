package org.helioviewer.jhv.layers.filters;

import java.awt.Component;

import javax.swing.JLabel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;

/**
 * The "Contrast" row: the Levels window scaled about its own centre, as one knob.
 *
 * <p>Levels is a window, [offset, offset + scale], and the display value is value * scale + offset.
 * Its width IS the contrast, so this row is not a second control over the picture, it is the same
 * control held differently: two handles moved in opposite directions, or one number. It exists
 * because the symmetric case is the common one and is awkward with two handles, and because the
 * Fourier filter's pass output is a signed fluctuation about mid-grey whose contrast used to be a
 * "gain" inside the filter's own palette, where a display setting had no business being.
 *
 * <p>3.0 is where GLImage.setBrightness's clamps bind for a window centred at 0.5, so that is the
 * end of the slider. Off centre the setter clamps earlier, which it does silently and correctly.
 */
public class ContrastPanel implements FilterDetails {

    private final JHVSlider slider = new JHVSlider(10, 300, 100); // hundredths: 0.10 to 3.00
    private final JLabel label = new JLabel("1.00", JLabel.RIGHT);
    private final JLabel title = new JLabel("Contrast ", JLabel.RIGHT);
    private boolean syncing;

    public ContrastPanel(ImageLayer layer) {
        slider.setToolTipText("Width of the Levels window about its centre: the picture's contrast as one number. "
                + "Dragging the two Levels handles apart does the same thing by hand.");
        slider.addChangeListener(e -> {
            double g = slider.getValue() / 100.;
            label.setText(String.format("%.2f", g));
            if (syncing)
                return; // mirroring the layer, not editing it
            double offset = layer.getGLImage().getBrightOffset();
            double scale = layer.getGLImage().getBrightScale();
            double centre = offset + scale / 2;
            Layers.applyToSelected(layer, gl -> gl.setBrightness(centre - g / 2, g));
            Layers.fireLayerUpdated(layer); // so the Levels row follows
            DisplayController.display();
        });
        refresh(layer);
    }

    /** Mirror the layer's Levels width into the slider; Levels can move without this row. */
    public void refresh(ImageLayer layer) {
        int g = (int) Math.round(layer.getGLImage().getBrightScale() * 100);
        if (slider.getValue() == g)
            return;
        syncing = true;
        slider.setValue(Math.clamp(g, slider.getMinimum(), slider.getMaximum()));
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

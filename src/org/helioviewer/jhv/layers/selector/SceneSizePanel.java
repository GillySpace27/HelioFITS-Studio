package org.helioviewer.jhv.layers.selector;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.layers.ModelLayer;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.layers.SceneScale;

/**
 * The scene-wide sizes at the top of the 3D Scene pane: Enlarge, which multiplies every placed
 * model's own enlargement, and Min size, the smallest a model is drawn on screen, which each
 * model's own Min size scales. Saved with the session (SceneScale).
 */
@SuppressWarnings("serial")
public final class SceneSizePanel extends JPanel {

    /** Slider steps per decade, as on a model layer's own Enlarge. */
    private static final int STEPS = 10;

    private boolean updating;

    public SceneSizePanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));

        int decades = ModelPlacement.MAX_EXAGGERATION_DECADES;
        JHVSlider enlarge = new JHVSlider(0, decades * STEPS, toSteps(SceneScale.enlarge())).nominal(0);
        enlarge.setToolTipText("Enlarges every model placed at a spacecraft or planet, from 1x (true size) to 10^" + decades
                + "x. A model's own Enlarge multiplies this. Double-click for true size.");
        JLabel enlargeValue = new JLabel(ModelLayer.times(SceneScale.enlarge()));

        SpinnerNumberModel pixelsModel = new SpinnerNumberModel(SceneScale.minPixels(), 0., SceneScale.MAX_MIN_PIXELS, 2.);
        JSpinner pixels = new JSpinner(pixelsModel);
        pixels.setToolTipText("The smallest any model is drawn, in pixels across, so spacecraft stay visible at their real distance. A model's own Min size scales this; 0 switches the floor off.");

        enlarge.addChangeListener(e -> {
            if (updating)
                return;
            SceneScale.setEnlarge(Math.pow(10, enlarge.getValue() / (double) STEPS));
            enlargeValue.setText(ModelLayer.times(SceneScale.enlarge()));
        });
        pixels.addChangeListener(e -> {
            if (!updating)
                SceneScale.setMinPixels(pixelsModel.getNumber().doubleValue());
        });
        // A session load sets the sizes from outside; follow it without writing back.
        SceneScale.addListener(() -> {
            updating = true;
            try {
                enlarge.setValue(toSteps(SceneScale.enlarge()));
                enlargeValue.setText(ModelLayer.times(SceneScale.enlarge()));
                pixelsModel.setValue(SceneScale.minPixels());
            } finally {
                updating = false;
            }
        });

        addRow("Enlarge", enlarge, enlargeValue, 0);
        addRow("Min size (px)", pixels, null, 1);

        // Models are drawn only in a 3D view: in a flat projection the sizes stay visible but
        // greyed, and say where they apply.
        String enlargeTip = enlarge.getToolTipText(), pixelsTip = pixels.getToolTipText();
        Runnable follow = () -> {
            boolean in3D = ViewState.getProjection().rendersIn3D();
            String note = in3D ? "" : " Models are drawn in Orthographic and 3D Helioradial only.";
            enlarge.setEnabled(in3D);
            pixels.setEnabled(in3D);
            enlarge.setToolTipText(enlargeTip + note);
            pixels.setToolTipText(pixelsTip + note);
        };
        follow.run();
        ViewState.addModeListener(follow::run);
    }

    private static int toSteps(double factor) {
        return (int) Math.round(Math.log10(factor) * STEPS);
    }

    private void addRow(String text, JComponent component, JComponent extra, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(2, 0, 2, 4);
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_END;
        add(new JLabel(text), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        add(component, c);
        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            add(extra, c);
        }
    }

}

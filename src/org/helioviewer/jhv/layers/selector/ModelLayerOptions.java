package org.helioviewer.jhv.layers.selector;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;

import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.layers.ModelLayer;
import org.helioviewer.jhv.layers.ModelPlacement;

/**
 * Placement and size of a 3D model layer: at Sun centre as loaded, or at a tracked object's real
 * position at true size, enlarged by a factor and by a floor on its on-screen size. The status line
 * under the controls always says how much the model is enlarged, so a spacecraft drawn a billion
 * times too big is never mistaken for one drawn to scale.
 */
@SuppressWarnings("serial")
final class ModelLayerOptions extends JPanel {

    private static final String AT_ORIGIN = "Sun centre (as loaded)";
    private static final String AT_OBJECT = "Real position of";
    /** Slider steps per decade of exaggeration. */
    private static final int STEPS = 10;

    private final JLabel status = new JLabel(" ");
    private final Timer refresh;

    ModelLayerOptions(ModelLayer layer) {
        super(new GridBagLayout());

        JComboBox<String> mode = new JComboBox<>(new String[]{AT_ORIGIN, AT_OBJECT});
        mode.setSelectedItem(layer.getPlacementMode() == ModelPlacement.Mode.EPHEMERIS ? AT_OBJECT : AT_ORIGIN);
        mode.setToolTipText("Sun centre draws the file's coordinates as they are. Real position puts the model's centre where the chosen object is at the frame's time, at true size unless enlarged below.");

        java.util.List<String> names = new java.util.ArrayList<>();
        for (SpaceObject o : SpaceObject.getTargets(SpaceObject.SUN))
            names.add(o.toString());
        names.addAll(ModelPlacement.externalTargets());
        JComboBox<String> target = new JComboBox<>(names.toArray(String[]::new));
        target.setSelectedItem(layer.getTarget());
        target.setToolTipText("Planets, the Moon, Solar Orbiter and STEREO Ahead come from the bundled SPICE kernels; SOHO, SDO, PSP and the others listed with them from the ROB position service; ACE, Wind and DSCOVR from HelioFleet; Ulysses and MESSENGER from JPL Horizons. All but the kernels need the network.");

        SpinnerNumberModel unitsModel = new SpinnerNumberModel(layer.getMetersPerUnit(), 1e-6, 1e6, 0.1);
        JSpinner units = new JSpinner(unitsModel);
        units.setEditor(new JSpinner.NumberEditor(units, "0.######"));
        units.setToolTipText("Metres per file unit. glTF declares metres, so 1 is right for a well-made file; some exports use centimetres (0.01) or arbitrary units. The status line shows the size this gives.");

        int decades = ModelPlacement.MAX_EXAGGERATION_DECADES;
        JHVSlider enlarge = new JHVSlider(0, decades * STEPS, (int) Math.round(Math.log10(layer.getExaggeration()) * STEPS)).nominal(0);
        JLabel enlargeValue = new JLabel();
        enlarge.setToolTipText("Enlarges this model by a factor from 1x to 10^" + decades + "x on top of the 3D Scene pane's Enlarge. Double-click for 1x, the scene's size.");

        // Shown relative to the scene's Min size: 1 is the scene's, 2 twice it. Stored as pixels at
        // the built-in scene size (ModelPlacement.DEFAULT_MIN_PIXELS), as sessions always held it.
        double perScene = ModelPlacement.DEFAULT_MIN_PIXELS;
        SpinnerNumberModel pixelsModel = new SpinnerNumberModel(layer.getMinPixels() / perScene, 0., 2000. / perScene, 0.25);
        JSpinner pixels = new JSpinner(pixelsModel);
        pixels.setToolTipText("The smallest this model is drawn, as a multiple of the 3D Scene pane's Min size: 1 is the scene's, 0 draws exactly true size times the enlargement.");

        Runnable enable = () -> {
            boolean placed = !layer.isHeliocentric() && mode.getSelectedItem() == AT_OBJECT;
            mode.setEnabled(!layer.isHeliocentric());
            for (JComponent c : new JComponent[]{target, units, enlarge, pixels})
                c.setEnabled(placed);
        };
        Runnable showFactor = () -> enlargeValue.setText(ModelLayer.times(layer.getExaggeration()));

        mode.addActionListener(e -> {
            layer.setPlacementMode(mode.getSelectedItem() == AT_OBJECT ? ModelPlacement.Mode.EPHEMERIS : ModelPlacement.Mode.ORIGIN);
            enable.run();
            updateStatus(layer);
        });
        target.addActionListener(e -> {
            if (target.getSelectedItem() instanceof String name)
                layer.setTarget(name);
            updateStatus(layer);
        });
        units.addChangeListener(e -> layer.setMetersPerUnit(unitsModel.getNumber().doubleValue()));
        enlarge.addChangeListener(e -> {
            layer.setExaggeration(Math.pow(10, enlarge.getValue() / (double) STEPS));
            showFactor.run();
        });
        pixels.addChangeListener(e -> layer.setMinPixels(pixelsModel.getNumber().doubleValue() * perScene));

        addRow("Place at", mode, null, 0);
        addRow("Object", target, null, 1);
        addRow("Units (m)", units, null, 2);
        addRow("Enlarge (x scene)", enlarge, enlargeValue, 3);
        addRow("Min size (x scene)", pixels, null, 4);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 2, 4);
        add(status, c);

        enable.run();
        showFactor.run();
        updateStatus(layer);

        // The status depends on the frame drawn (time, zoom, position loads), so it is polled while
        // the panel is on screen and not otherwise.
        refresh = new Timer(500, e -> updateStatus(layer));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0)
                return;
            if (isShowing())
                refresh.start();
            else
                refresh.stop();
        });
    }

    private void updateStatus(ModelLayer layer) {
        String text = layer.placementStatus();
        status.setText("<html>" + (text.isEmpty() ? "&nbsp;" : text) + "</html>");
    }

    private void addRow(String text, JComponent component, JComponent extra, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(1, 4, 1, 4);
        c.anchor = GridBagConstraints.LINE_END;
        c.gridx = 0;
        add(new JLabel(text, JLabel.RIGHT), c);
        c.anchor = GridBagConstraints.LINE_START;
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(component, c);
        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            add(extra, c);
        }
    }

}

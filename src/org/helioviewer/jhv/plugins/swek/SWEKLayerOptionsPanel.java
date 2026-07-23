package org.helioviewer.jhv.plugins.swek;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

@SuppressWarnings("serial")
final class SWEKLayerOptionsPanel extends JPanel {

    SWEKLayerOptionsPanel(SWEKLayer layer) {
        super(new GridBagLayout());

        JCheckBox check = new JCheckBox("Icons", layer.isIcons());
        check.setHorizontalTextPosition(SwingConstants.LEFT);
        check.addActionListener(e -> layer.setIcons(check.isSelected()));

        JCheckBox extend = new JCheckBox("Extend CMEs to distance", layer.isExtendCactus());
        extend.setToolTipText("Propagate each CACTus CME front past the LASCO edge by constant-speed extrapolation, out to the chosen distance");
        extend.setHorizontalTextPosition(SwingConstants.LEFT);

        // Distance slider (R☉), live-labeled. Enabled only while the extend toggle is on. It starts
        // on "auto", which tracks the loaded field of view so fronts run out at the edge of the
        // data; moving it pins an explicit reach, and double-clicking returns it to auto.
        int min = (int) Math.round(SWEKLayer.extendDistanceMin());
        int max = (int) Math.round(SWEKLayer.extendDistanceMax());
        JSlider distance = new JSlider(min, max, (int) Math.round(layer.effectiveExtendDistance()));
        JLabel distanceLabel = new JLabel();
        distanceLabel.setToolTipText("Extrapolation reach in solar radii. Auto follows the loaded field of view; double-click to return to auto");
        Runnable relabel = () -> distanceLabel.setText(layer.isExtendDistanceAuto()
                ? "auto (" + Math.round(layer.effectiveExtendDistance()) + " R☉)"
                : distance.getValue() + " R☉");
        relabel.run();
        distance.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    // setValue fires the change listener, which pins an explicit distance — so
                    // park the knob first and switch back to auto last, letting auto win.
                    distance.setValue((int) Math.round(layer.effectiveExtendDistance()));
                    layer.setExtendDistanceAuto();
                    relabel.run();
                }
            }
        });
        distance.setEnabled(extend.isSelected());
        distanceLabel.setEnabled(extend.isSelected());
        distance.addChangeListener(e -> {
            layer.setExtendDistance(distance.getValue());
            relabel.run();
        });
        extend.addActionListener(e -> {
            boolean on = extend.isSelected();
            layer.setExtendCactus(on);
            distance.setEnabled(on);
            distanceLabel.setEnabled(on);
        });

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.weightx = 1.;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        add(check, c);

        c.gridy = 1;
        add(extend, c);

        c.gridy = 2;
        c.gridwidth = 1;
        c.weightx = 1.;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(distance, c);
        c.gridx = 1;
        c.weightx = 0.;
        c.fill = GridBagConstraints.NONE;
        add(distanceLabel, c);
    }

}

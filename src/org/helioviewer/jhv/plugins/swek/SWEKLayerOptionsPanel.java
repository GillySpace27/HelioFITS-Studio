package org.helioviewer.jhv.plugins.swek;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

@SuppressWarnings("serial")
final class SWEKLayerOptionsPanel extends JPanel {

    SWEKLayerOptionsPanel(SWEKLayer layer) {
        super(new GridBagLayout());

        JCheckBox check = new JCheckBox("Icons", layer.isIcons());
        check.setHorizontalTextPosition(SwingConstants.LEFT);
        check.addActionListener(e -> layer.setIcons(check.isSelected()));

        JCheckBox extend = new JCheckBox("Extend CMEs to FOV", layer.isExtendCactus());
        extend.setToolTipText("Propagate each CACTus CME front past the LASCO edge, out to the loaded field of view (constant-speed extrapolation)");
        extend.setHorizontalTextPosition(SwingConstants.LEFT);
        extend.addActionListener(e -> layer.setExtendCactus(extend.isSelected()));

        GridBagConstraints c0 = new GridBagConstraints();
        c0.anchor = GridBagConstraints.LINE_START;
        c0.weightx = 1.;
        c0.weighty = 1.;
        c0.gridy = 0;
        c0.gridx = 0;
        add(check, c0);

        GridBagConstraints c1 = new GridBagConstraints();
        c1.anchor = GridBagConstraints.LINE_START;
        c1.weightx = 1.;
        c1.weighty = 1.;
        c1.gridy = 1;
        c1.gridx = 0;
        add(extend, c1);
    }

}

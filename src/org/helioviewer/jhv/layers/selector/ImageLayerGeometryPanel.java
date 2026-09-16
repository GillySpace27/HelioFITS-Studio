package org.helioviewer.jhv.layers.selector;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.layers.filters.RangeSliderFilterPanel;
import org.helioviewer.jhv.layers.filters.SectorPanel;
import org.helioviewer.jhv.layers.filters.SliderFilterPanel;

// Geometry/crop controls for the selected image layer: slit, radial mask, sector, delta
// CROTA/CRVAL. Shown in the "Geometry / crop" wrapper. All rows are always visible (no toggle);
// upstream keeps the same set behind a "More adjustments" disclosure instead.
@SuppressWarnings("serial")
final class ImageLayerGeometryPanel extends JPanel {

    ImageLayerGeometryPanel(ImageLayer layer) {
        FilterDetails slitPanel = RangeSliderFilterPanel.slit(layer);
        FilterDetails maskPanel = RangeSliderFilterPanel.mask(layer);
        SectorPanel sectorPanel = new SectorPanel(layer);
        FilterDetails deltaCROTAPanel = SliderFilterPanel.deltaCROTA(layer);
        FilterDetails deltaCRVAL1Panel = SliderFilterPanel.deltaCRVAL1(layer);
        FilterDetails deltaCRVAL2Panel = SliderFilterPanel.deltaCRVAL2(layer);

        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;

        c.gridy = 0;
        FilterRowLayout.addFilterRow(this, c, slitPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, maskPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, sectorPanel.getDirectionDetails());
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, sectorPanel.getWidthDetails());
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, deltaCROTAPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, deltaCRVAL1Panel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, deltaCRVAL2Panel);
    }

}

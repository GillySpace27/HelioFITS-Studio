package org.helioviewer.jhv.layers.selector;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.filters.ChannelMixerPanel;
import org.helioviewer.jhv.layers.filters.DifferencePanel;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.layers.filters.ImageFilterPanel;
import org.helioviewer.jhv.layers.filters.LUTPanel;
import org.helioviewer.jhv.layers.filters.LevelsPanel;
import org.helioviewer.jhv.layers.filters.SliderFilterPanel;

// Rendering controls for the selected image layer: difference, opacity, blend, sharpen,
// levels, colormap (LUT), channels, filter. Shown in the "Layer options" wrapper.
@SuppressWarnings("serial")
final class ImageLayerRenderingPanel extends JPanel {

    private final LUTPanel lutPanel;
    private final FilterDetails levelsPanel;
    private final FilterDetails sharpenPanel;
    private final DifferencePanel differencePanel;

    ImageLayerRenderingPanel(ImageLayer layer) {
        differencePanel = new DifferencePanel(layer);
        FilterDetails opacityPanel = new SliderFilterPanel.Opacity(layer);
        FilterDetails blendPanel = new SliderFilterPanel.Blend(layer);
        FilterDetails channelMixerPanel = new ChannelMixerPanel(layer);
        lutPanel = new LUTPanel(layer);
        levelsPanel = new LevelsPanel(layer);
        sharpenPanel = new SliderFilterPanel.Sharpen(layer);
        FilterDetails imageFilterPanel = new ImageFilterPanel(layer);

        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;

        c.gridy = 0;
        FilterRowLayout.addFilterRow(this, c, differencePanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, opacityPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, blendPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, sharpenPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, levelsPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, lutPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, channelMixerPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, imageFilterPanel);

        // Usually refreshed through ImageLayer activation; initialize here too in case that activation already happened before panel creation.
        refresh(layer);
    }

    void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        lutPanel.setLUT(imageLayer.getView().getDefaultLUT());

        // Indexed categorical maps encode a category ID per pixel; levels, sharpen, and difference
        // all blend or rescale pixel values before the LUT lookup, so any of them would remap
        // index -> wrong colour. Hide these controls rather than let them silently corrupt the legend.
        boolean indexed = imageLayer.getMetaData().isIndexedSurfaceMap();
        levelsPanel.getFirst().setVisible(!indexed);
        levelsPanel.getSecond().setVisible(!indexed);
        levelsPanel.getThird().setVisible(!indexed);
        sharpenPanel.getFirst().setVisible(!indexed);
        sharpenPanel.getSecond().setVisible(!indexed);
        sharpenPanel.getThird().setVisible(!indexed);
        differencePanel.getFirst().setVisible(!indexed);
        differencePanel.getSecond().setVisible(!indexed);
        differencePanel.getThird().setVisible(!indexed);
    }

}

package org.helioviewer.jhv.layers.selector;

import java.util.StringJoiner;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.CollapsiblePane;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.layers.filters.RangeSliderFilterPanel;
import org.helioviewer.jhv.layers.filters.SectorPanel;
import org.helioviewer.jhv.layers.filters.SliderFilterPanel;

/**
 * Where the selected layer's pixels are and which of them are kept: the Geometry section.
 *
 * <p>Mask, sector and slit crop the frame, and are what makes a stack of coronagraphs a ladder
 * rather than a pile, so they sit at the top. The delta CROTA/CRVAL trio is nested one level
 * further in, under Alignment: it corrects the frame's own WCS, which is something done once for a
 * dataset and then left alone, and it has no business costing three rows of sidebar every time
 * someone wants the mask. The section defaults closed, which its header badge makes safe.
 */
@SuppressWarnings("serial")
final class ImageLayerGeometryPanel extends JPanel {

    private static final ImageDisplaySettings DEFAULTS = new ImageDisplaySettings();

    private final LayerSection section;

    /** @param rebuild rebuild this layer's options from current state, after a revert has moved the rows */
    ImageLayerGeometryPanel(ImageLayer layer, Runnable rebuild) {
        FilterDetails slitPanel = RangeSliderFilterPanel.slit(layer);
        FilterDetails maskPanel = RangeSliderFilterPanel.mask(layer);
        SectorPanel sectorPanel = new SectorPanel(layer);
        FilterDetails deltaCROTAPanel = SliderFilterPanel.deltaCROTA(layer);
        FilterDetails deltaCRVAL1Panel = SliderFilterPanel.deltaCRVAL1(layer);
        FilterDetails deltaCRVAL2Panel = SliderFilterPanel.deltaCRVAL2(layer);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.add(FilterRowLayout.rows(maskPanel, sectorPanel.getDirectionDetails(), sectorPanel.getWidthDetails(), slitPanel));
        content.add(new CollapsiblePane("Alignment",
                FilterRowLayout.rows(deltaCROTAPanel, deltaCRVAL1Panel, deltaCRVAL2Panel),
                false, true, null, "layer_alignment"));

        section = new LayerSection("Geometry", "layer_geometry", content, false,
                () -> summary(layer), () -> {
            revert(layer);
            rebuild.run();
        });

        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        add(section);
    }

    /** The header, re-read from the layer. Polled while this panel is the one on screen. */
    void updateBadges() {
        section.updateBadge();
    }

    private static String summary(ImageLayer layer) {
        ImageDisplaySettings s = layer.getDisplaySettings();
        StringJoiner joiner = new StringJoiner(" · ");
        if (s.getInnerMask() != DEFAULTS.getInnerMask() || s.getOuterMask() != DEFAULTS.getOuterMask())
            joiner.add("Mask");
        if (s.getSectorWidth() != DEFAULTS.getSectorWidth() || s.getSectorCenter() != DEFAULTS.getSectorCenter())
            joiner.add("Sector");
        if (s.getSlitLeft() != DEFAULTS.getSlitLeft() || s.getSlitRight() != DEFAULTS.getSlitRight())
            joiner.add("Slit");
        if (s.getDeltaCROTA() != 0 || s.getDeltaCRVAL1() != 0 || s.getDeltaCRVAL2() != 0)
            joiner.add("Aligned");
        return joiner.toString();
    }

    private static void revert(ImageLayer layer) {
        Layers.applyToSelected(layer, s -> {
            s.setMask(DEFAULTS.getInnerMask(), DEFAULTS.getOuterMask());
            s.setSector(DEFAULTS.getSectorCenter(), DEFAULTS.getSectorWidth());
            s.setSlit(DEFAULTS.getSlitLeft(), DEFAULTS.getSlitRight());
            s.setDeltaCROTA(0);
            s.setDeltaCRVAL1(0);
            s.setDeltaCRVAL2(0);
        });
        DisplayController.display();
    }

}

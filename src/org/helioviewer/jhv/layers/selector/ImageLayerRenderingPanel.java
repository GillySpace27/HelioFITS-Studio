package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.image.lut.LUTLabels;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.filters.ChannelMixerPanel;
import org.helioviewer.jhv.layers.filters.DifferencePanel;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.layers.filters.ImageFilterPanel;
import org.helioviewer.jhv.layers.filters.LUTPanel;
import org.helioviewer.jhv.layers.filters.ContrastPanel;
import org.helioviewer.jhv.layers.filters.LevelsPanel;
import org.helioviewer.jhv.layers.filters.SequencePanel;
import org.helioviewer.jhv.layers.filters.SliderFilterPanel;

// Rendering controls for the selected image layer: difference, opacity, blend, sharpen,
// levels, colormap (LUT), channels, filter. Shown in the "Layer options" wrapper.
@SuppressWarnings("serial")
final class ImageLayerRenderingPanel extends JPanel {

    // Everything here changes the pixel value before the LUT lookup (levels, sharpen, difference,
    // the RHEF/MGN/WOW filter and its enhance/upsilon curves) or distorts the LUT's output color
    // afterward (the channel mixer). Either way a categorical layer's index -> colour promise no
    // longer holds, so these are the controls refresh() greys out for one. Opacity and Blend are
    // deliberately not in this list: they scale the whole premultiplied colour uniformly (see
    // GLImage's color[]), so they fade a swatch but never turn it into a different one.
    private final LUTPanel lutPanel;
    private final LevelsPanel levelsPanel;
    private final ContrastPanel contrastPanel;
    private final FilterDetails sharpenPanel;
    private final DifferencePanel differencePanel;
    private final FilterDetails channelMixerPanel;
    private final ImageFilterPanel imageFilterPanel;
    private final SequencePanel sequencePanel;

    ImageLayerRenderingPanel(ImageLayer layer) {
        differencePanel = new DifferencePanel(layer);
        FilterDetails opacityPanel = new SliderFilterPanel.Opacity(layer);
        FilterDetails blendPanel = new SliderFilterPanel.Blend(layer);
        channelMixerPanel = new ChannelMixerPanel(layer);
        // The callback must not touch lutPanel/the combo itself: LUTPanel.setLUT() (called from
        // refresh() below) fires this same listener, and looping back into the combo from here
        // reopened that cycle -- a real infinite recursion that crashed the app (StackOverflow
        // through FlatLaf's caret code, itself just a bystander walking an already-huge stack).
        lutPanel = new LUTPanel(layer, () -> applyIndexedGating(layer));
        levelsPanel = new LevelsPanel(layer);
        contrastPanel = new ContrastPanel(layer);
        sharpenPanel = new SliderFilterPanel.Sharpen(layer);
        imageFilterPanel = new ImageFilterPanel(layer);
        sequencePanel = new SequencePanel(layer);

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
        FilterRowLayout.addFilterRow(this, c, contrastPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, lutPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, channelMixerPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, imageFilterPanel);
        c.gridy++;
        FilterRowLayout.addFilterRow(this, c, sequencePanel);

        // Usually refreshed through ImageLayer activation; initialize here too in case that activation already happened before panel creation.
        refresh(layer);
    }

    void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        lutPanel.setLUT(imageLayer.getView().getDefaultLUT());
        // refresh() also fires from layerUpdated, which arrives while a multi-selection may be
        // live. Gating on this one layer there would re-enable controls the selection as a whole
        // disqualifies, so defer to the selection whenever this layer is part of one.
        List<Layer> selection = Layers.getSelection();
        if (selection.size() > 1 && selection.contains(layer))
            refreshForSelection(selection);
        else
            applyIndexedGating(imageLayer);
        sequencePanel.refresh(imageLayer);
        levelsPanel.refresh(imageLayer); // Levels move from outside this row: Contrast, a restored session
        contrastPanel.refresh(imageLayer);
        imageFilterPanel.syncFromLayer(imageLayer); // a computed sequence takes the per-frame filter on top, like a raw frame
    }

    // Gate on the LUT currently in use, not the FITS product: the same indexed data reads fine
    // through a continuous LUT (e.g. inspecting raw category IDs as a heatmap), and a categorical
    // LUT promises its pixel value renders as exactly one colour, which the value-affecting
    // controls would break. Grey them out instead of hiding them, so it stays visible that they
    // exist and why they are inactive here. Called both from refresh() and directly from
    // LUTPanel's listener so switching the colormap updates this live -- must never touch
    // lutPanel itself, see the comment on its construction above.
    // With several layers selected these controls fan out to all of them, so the gate has to ask
    // about all of them: one categorical layer in the selection is enough to make a value-affecting
    // control meaningless for that layer, and a control that silently skips one of its targets is
    // worse than one that is visibly unavailable.
    void refreshForSelection(List<Layer> selection) {
        boolean anyIndexed = selection.stream()
                .anyMatch(l -> l instanceof ImageLayer il && LUTLabels.isCategorical(il.getGLImage().getLUT()));
        setIndexedGating(anyIndexed, selection.size());
    }

    private void applyIndexedGating(ImageLayer imageLayer) {
        setIndexedGating(LUTLabels.isCategorical(imageLayer.getGLImage().getLUT()), 1);
    }

    private void setIndexedGating(boolean indexed, int selectionSize) {
        String reason = indexed
                ? (selectionSize > 1
                        ? "Disabled: one of the selected layers uses a fixed category legend, not a value range to adjust"
                        : "Disabled: this layer's colours are a fixed category legend, not a value range to adjust")
                : null;
        for (FilterDetails details : List.of(levelsPanel, contrastPanel, sharpenPanel, channelMixerPanel, imageFilterPanel, sequencePanel))
            setInteractable(!indexed, reason, details.getFirst(), details.getSecond(), details.getThird());
        // differencePanel's third column is the sync-time-span button, unrelated to pixel-value
        // remapping (it only aligns other layers' movie interval to this one's) -- grey only the
        // "Difference" label and its None/Running/Base radios, and leave the button alone.
        setInteractable(!indexed, reason, differencePanel.getFirst(), differencePanel.getSecond());
    }

    private static void setInteractable(boolean enabled, String disabledReason, Component... components) {
        for (Component c : components)
            ComponentUtils.setEnabled(c, enabled);
        if (components.length > 0 && components[0] instanceof javax.swing.JComponent title) // always a JLabel in practice
            title.setToolTipText(disabledReason);
    }

}

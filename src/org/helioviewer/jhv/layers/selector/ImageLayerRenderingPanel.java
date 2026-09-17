package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.ComponentUtils;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTLabels;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.filters.ChannelMixerPanel;
import org.helioviewer.jhv.layers.filters.ContrastPanel;
import org.helioviewer.jhv.layers.filters.DifferencePanel;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.layers.filters.ImageFilterPanel;
import org.helioviewer.jhv.layers.filters.LUTPanel;
import org.helioviewer.jhv.layers.filters.RangeSliderFilterPanel;
import org.helioviewer.jhv.layers.filters.SliderFilterPanel;
import org.helioviewer.jhv.view.uri.FITSSettings;

/**
 * Rendering controls for the selected image layer, in two sections.
 *
 * <p><b>Display</b> is how the layer is coloured and composited over the ones beneath it: opacity,
 * blend, colour table, channels. <b>Intensity</b> is what a pixel value becomes before any of that
 * happens: difference, levels, contrast, sharpen, the per-frame filter and the Fourier sequence,
 * with the FITS clipping and scaling that feed them at the bottom. Within each section the rows
 * run in pipeline order, which the single flat column they replace did not.
 *
 * <p>Both sections state in their header what they hold that is off its default, so either can be
 * collapsed without hiding why the picture looks as it does. See {@link LayerSection}.
 */
@SuppressWarnings("serial")
final class ImageLayerRenderingPanel extends JPanel {

    /** What every row here reads at rest: one pristine settings object, so nothing restates a default. */
    private static final ImageDisplaySettings DEFAULTS = new ImageDisplaySettings();

    // Everything in Intensity changes the pixel value before the LUT lookup (levels, sharpen,
    // difference, the RHEF/MGN/WOW filter and its enhance/upsilon curves) or distorts the LUT's
    // output color afterward (the channel mixer). Either way a categorical layer's index -> colour
    // promise no longer holds, so these are the controls refresh() greys out for one. Opacity and
    // Blend are deliberately not in this list: they scale the whole premultiplied colour uniformly
    // (see the color[] GLSLImage builds from ImageDisplaySettings), so they fade a swatch but
    // never turn it into a different one.
    private final LUTPanel lutPanel;
    private final RangeSliderFilterPanel.Levels levelsPanel;
    private final ContrastPanel contrastPanel;
    private final FilterDetails sharpenPanel;
    private final DifferencePanel differencePanel;
    private final FilterDetails channelMixerPanel;
    private final ImageFilterPanel imageFilterPanel;
    private final SequencePointer sequencePanel; // the Fourier row: a readout and the way to the palette, not a second copy of it

    // How a FITS frame is clipped and scaled before any of the above touches it. Upstream moved
    // these out of a global preferences page and into the layer, which is where they belong: two
    // FITS layers in one scene rarely want the same clip.
    private final FITSSettings fitsSettings;
    private final JToggleButton fitsButton = Buttons.flatToggle(Buttons.fitsRight);

    private final LayerSection displaySection;
    private final LayerSection intensitySection;

    /** @param rebuild rebuild this layer's options from current state; a revert moves rows this panel does not own */
    ImageLayerRenderingPanel(ImageLayer layer, Runnable rebuild) {
        differencePanel = new DifferencePanel(layer);
        FilterDetails opacityPanel = SliderFilterPanel.opacity(layer);
        FilterDetails blendPanel = SliderFilterPanel.blend(layer);
        channelMixerPanel = new ChannelMixerPanel(layer);
        // The callback must not touch lutPanel/the combo itself: LUTPanel.refresh() (called from
        // refresh() below) fires this same listener, and looping back into the combo from here
        // reopened that cycle -- a real infinite recursion that crashed the app (StackOverflow
        // through FlatLaf's caret code, itself just a bystander walking an already-huge stack).
        lutPanel = new LUTPanel(layer, () -> applyIndexedGating(layer));
        levelsPanel = RangeSliderFilterPanel.levels(layer);
        contrastPanel = new ContrastPanel(layer);
        sharpenPanel = SliderFilterPanel.sharpen(layer);
        imageFilterPanel = new ImageFilterPanel(layer);
        sequencePanel = new SequencePointer(layer);
        fitsSettings = new FITSSettings(layer.getProcessingSettings());

        fitsButton.addActionListener(e -> {
            boolean expanded = fitsButton.isSelected();
            fitsButton.setText(expanded ? Buttons.fitsDown : Buttons.fitsRight);
            fitsSettings.setVisible(expanded);
        });

        FilterDetails[] intensityRows = {differencePanel, levelsPanel, contrastPanel, sharpenPanel, imageFilterPanel, sequencePanel};
        JPanel intensityContent = FilterRowLayout.rows(intensityRows);
        // The FITS disclosure is the bottom of Intensity rather than a section of its own: it is
        // the same question as Levels asked one step earlier, on the data rather than on the
        // display, and only a layer with FITS behind it has it at all.
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = intensityRows.length;
        intensityContent.add(fitsButton, c);
        c.gridy++;
        intensityContent.add(fitsSettings, c);

        displaySection = new LayerSection("Display", "layer_display",
                FilterRowLayout.rows(opacityPanel, blendPanel, lutPanel, channelMixerPanel), true,
                () -> displaySummary(layer), () -> {
            revertDisplay(layer);
            rebuild.run();
        });
        intensitySection = new LayerSection("Intensity", "layer_intensity", intensityContent, true,
                () -> intensitySummary(layer), () -> {
            revertIntensity(layer);
            rebuild.run();
        });

        setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        add(displaySection);
        add(intensitySection);

        // Usually refreshed through ImageLayer activation; initialize here too in case that activation already happened before panel creation.
        refresh(layer);
    }

    void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        lutPanel.refresh();
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

        boolean hasFITS = imageLayer.getView().hasFITS();
        fitsButton.setVisible(hasFITS);
        fitsSettings.setVisible(hasFITS && fitsButton.isSelected());
        updateBadges();
    }

    /** Both headers, re-read from the layer. Polled while this panel is the one on screen. */
    void updateBadges() {
        displaySection.updateBadge();
        intensitySection.updateBadge();
    }

    // ---- what each section says about itself ---------------------------------------------------

    private static String displaySummary(ImageLayer layer) {
        ImageDisplaySettings s = layer.getDisplaySettings();
        StringJoiner joiner = new StringJoiner(" · ");
        if (s.getOpacity() != DEFAULTS.getOpacity())
            joiner.add("Opacity " + percent(s.getOpacity()));
        if (s.getBlend() != DEFAULTS.getBlend())
            joiner.add("Blend " + percent(s.getBlend()));
        // A view that names no table is read through grey, which is then this layer's default too.
        LUT def = layer.getView().getDefaultLUT();
        if (!s.getLUT().name().equals(def == null ? LUT.gray().name() : def.name()))
            joiner.add(s.getLUT().name());
        if (s.getInvertLUT())
            joiner.add("inverted");
        List<String> off = new ArrayList<>(3);
        if (!s.getRed())
            off.add("R");
        if (!s.getGreen())
            off.add("G");
        if (!s.getBlue())
            off.add("B");
        if (!off.isEmpty())
            joiner.add(String.join("", off) + " off");
        return joiner.toString();
    }

    private static String intensitySummary(ImageLayer layer) {
        ImageDisplaySettings s = layer.getDisplaySettings();
        StringJoiner joiner = new StringJoiner(" · ");
        if (s.getDifferenceMode() != DEFAULTS.getDifferenceMode())
            joiner.add(s.getDifferenceMode() + " diff");
        if (s.getBrightOffset() != DEFAULTS.getBrightOffset() || s.getBrightScale() != DEFAULTS.getBrightScale())
            joiner.add("Levels " + percent(s.getBrightOffset()) + "–" + percent(s.getBrightOffset() + s.getBrightScale()));
        if (s.getSharpen() != DEFAULTS.getSharpen())
            joiner.add("Sharpen " + percent(s.getSharpen()));
        if (layer.getFilter() != ImageFilter.Type.None)
            joiner.add(layer.getFilter().toString());
        if (s.getEnhanced() != DEFAULTS.getEnhanced())
            joiner.add("Enhance");
        if (layer.getSequence() != null)
            joiner.add("Fourier");
        return joiner.toString();
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    // ---- and how to put it back ----------------------------------------------------------------

    private static void revertDisplay(ImageLayer layer) {
        // The colour table's default is the view's, not a constant: a dataset arrives with the one
        // it is read through, and reverting to grey would be reverting to something that never was.
        Layers.applyToSelectedLayers(layer, il -> {
            ImageDisplaySettings s = il.getDisplaySettings();
            s.setOpacity(DEFAULTS.getOpacity());
            s.setBlend(DEFAULTS.getBlend());
            s.setColor(1, 1, 1);
            s.setLUT(il.getView().getDefaultLUT(), false);
        });
        DisplayController.display();
    }

    private static void revertIntensity(ImageLayer layer) {
        Layers.applyToSelectedLayers(layer, il -> {
            ImageDisplaySettings s = il.getDisplaySettings();
            s.setDifferenceMode(DEFAULTS.getDifferenceMode());
            s.setBrightness(DEFAULTS.getBrightOffset(), DEFAULTS.getBrightScale());
            s.setSharpen(DEFAULTS.getSharpen());
            s.setEnhanced(DEFAULTS.getEnhanced());
            s.setUpsilon(DEFAULTS.getUpsilonLow(), DEFAULTS.getUpsilonHigh());
            il.setFilter(ImageFilter.Type.None);
            if (il.getSequence() != null)
                il.setSequence(null); // abolishes the computed view; not free, so only when there is one
        });
        // The FITS clip and scale are not reverted with this: they say what the data means rather
        // than how it is shown, and they keep their own disclosure to say so.
        DisplayController.render(1);
    }

    // ---- gating for a categorical colour table -------------------------------------------------

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
                .anyMatch(l -> l instanceof ImageLayer il && LUTLabels.isCategorical(il.getDisplaySettings().getLUT()));
        setIndexedGating(anyIndexed, selection.size());
    }

    private void applyIndexedGating(ImageLayer imageLayer) {
        setIndexedGating(LUTLabels.isCategorical(imageLayer.getDisplaySettings().getLUT()), 1);
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

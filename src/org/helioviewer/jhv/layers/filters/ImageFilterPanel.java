package org.helioviewer.jhv.layers.filters;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.gui.component.SplitButton;
import org.helioviewer.jhv.gui.component.ToneCurve;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.image.FilterChain;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.RhefPeek;

public final class ImageFilterPanel implements FilterDetails {

    // The nominal defaults the Intensity revert uses; the sliders' double-click goes there too.
    private static final ImageDisplaySettings DEFAULTS = new ImageDisplaySettings();

    private final JPanel filterPanel = new JPanel(new BorderLayout());
    private final JPanel buttonPanel = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel("Filter ", JLabel.RIGHT);
    private JComboBox<ImageFilter.Type> filterCombo;
    private ImageLayer layer;
    // The chain below the first filter: one row per stage, then "add". Rebuilt whenever the layer's chain or first filter differs from what is shown.
    private final JPanel chainPanel = new JPanel();
    private FilterChain shownChain = FilterChain.EMPTY;
    private ImageFilter.Type shownFirst = ImageFilter.Type.None;
    static final ImageFilter.Type[] STAGE_TYPES = {ImageFilter.Type.MGN, ImageFilter.Type.WOW, ImageFilter.Type.RHEF};
    SplitButton upsilonButton; // package-private for RhefControlsCheck

    static final String UPSILON_TIP = "Soften shadows (Υ low, below median) and highlights (Υ high, above median) of RHEF output independently";
    static final String UPSILON_OFF_TIP = "Choose RHEF to use these";

    /**
     * A named Υ pair (RH-17). Only two: the defaults the sliders revert to and the straight line
     * (Υ = 1 on both sides, which the shader skips). A per-instrument look needs a sourced pair.
     */
    record Look(String name, double low, double high) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final Look[] LOOKS = {
            new Look(String.format("Default (%.2f / %.2f)", DEFAULTS.getUpsilonLow(), DEFAULTS.getUpsilonHigh()),
                    DEFAULTS.getUpsilonLow(), DEFAULTS.getUpsilonHigh()),
            new Look("Neutral (1.00 / 1.00, no curve)", 1, 1)};

    // Greyed, never hidden, when the filter is not RHEF: the control stays where it is found.
    private void setUpsilonEnabled(boolean on) {
        upsilonButton.setEnabled(on);
        upsilonButton.setToolTipText(on ? UPSILON_TIP : UPSILON_OFF_TIP);
    }

    // Programmatic sync of the combo to the layer. setSelectedItem fires the combo's listener, and
    // this runs from inside that listener's own fan-out (fireLayerUpdated), so without the flag the
    // listener re-entered applyToSelectedLayers mid-iteration: ConcurrentModificationException.
    private boolean syncing;

    /** Mirrors the layer's filter in the combo (a sequence filter forces it to None) without fanning it out. */
    public void syncFromLayer(ImageLayer layer) {
        if (filterCombo != null && filterCombo.getSelectedItem() != layer.getFilter()) {
            syncing = true;
            try {
                filterCombo.setSelectedItem(layer.getFilter());
            } finally {
                syncing = false;
            }
        }
        // Here as well as in the combo's listener: that one returns early on an equal value, which is
        // exactly what a sync produces, so a copy of this panel brought into step from elsewhere (the
        // Filters palette and the Image Layers row are two copies of one setting) kept the wrong Υ.
        if (upsilonButton != null)
            setUpsilonEnabled(layer.usesRhef());
        rebuildChain(layer);
    }

    // ---- the chain -------------------------------------------------------------------------------

    /** One change to the layer's filters, fanned out to the selected layers as the combo's is. */
    private void commit(ImageFilter.Type first, FilterChain chain) {
        Layers.applyToSelectedLayers(layer, il -> {
            il.setFilter(first, chain);
            Layers.fireLayerUpdated(il); // the other copy of this panel re-syncs on it
        });
        DisplayController.render(1);
    }

    private void rebuildChain(ImageLayer layer) {
        ImageFilter.Type first = layer.getFilter();
        FilterChain chain = layer.getFilterChain();
        if (first == shownFirst && chain.equals(shownChain) && chainPanel.getComponentCount() > 0)
            return;
        shownFirst = first;
        shownChain = chain;
        boolean was = syncing;
        syncing = true;
        try {
            chainPanel.removeAll();
            if (first != ImageFilter.Type.None) {
                for (int i = 0; i < chain.size(); i++)
                    chainPanel.add(stageRow(first, chain, i));
                if (chain.size() < FilterChain.maxExtraStages()) {
                    JButton add = new JButton("+ Add another filter in series");
                    add.setToolTipText("Apply a second filter to the result, mixed in by a weight");
                    add.getAccessibleContext().setAccessibleName("Add filter in series");
                    add.addActionListener(e -> {
                        ImageFilter.Type previous = chain.isEmpty() ? first : chain.stages().get(chain.size() - 1).type();
                        // Offer a different filter than the one it follows; the user changes it in the row.
                        ImageFilter.Type next = previous == ImageFilter.Type.MGN ? ImageFilter.Type.RHEF : ImageFilter.Type.MGN;
                        commit(first, chain.add(new FilterChain.Stage(next, 0.5f)));
                    });
                    JPanel line = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 2));
                    line.add(add);
                    chainPanel.add(line);
                }
            }
        } finally {
            syncing = was;
        }
        chainPanel.revalidate();
        chainPanel.repaint();
        java.awt.Container parent = filterPanel.getParent();
        if (parent != null)
            parent.revalidate();
    }

    // Stage i of the chain (stage i + 2 in all): its filter, a swap with the one before, remove, weight, and what it comes to.
    private JPanel stageRow(ImageFilter.Type first, FilterChain chain, int i) {
        FilterChain.Stage stage = chain.stages().get(i);
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.PAGE_AXIS));

        JComboBox<ImageFilter.Type> combo = new JComboBox<>(STAGE_TYPES);
        combo.setSelectedItem(stage.type());
        combo.setToolTipText(stage.type().description);
        combo.getAccessibleContext().setAccessibleName("Filter in series " + (i + 2));
        combo.addActionListener(e -> {
            if (!syncing && combo.getSelectedItem() instanceof ImageFilter.Type t && t != stage.type())
                commit(first, chain.with(i, new FilterChain.Stage(t, stage.strength())));
        });

        JButton swap = new JButton("⇅");
        swap.setToolTipText("Swap with the filter before it; the weight stays with this position");
        swap.getAccessibleContext().setAccessibleName("Swap order");
        swap.addActionListener(e -> {
            ImageFilter.Type before = i == 0 ? first : chain.stages().get(i - 1).type();
            ImageFilter.Type swappedFirst = i == 0 ? stage.type() : first;
            FilterChain swapped = chain.with(i, new FilterChain.Stage(before, stage.strength()));
            if (i > 0)
                swapped = swapped.with(i - 1, new FilterChain.Stage(stage.type(), chain.stages().get(i - 1).strength()));
            commit(swappedFirst, swapped);
        });

        JButton remove = new JButton("✕");
        remove.setToolTipText("Remove this filter from the chain");
        remove.getAccessibleContext().setAccessibleName("Remove filter in series");
        remove.addActionListener(e -> commit(first, chain.remove(i)));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.add(new JLabel("In series "), BorderLayout.LINE_START);
        top.add(combo, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 2, 0));
        buttons.add(swap);
        buttons.add(remove);
        top.add(buttons, BorderLayout.LINE_END);

        // Percent of this filter's output; double-click goes back to all of it. The change is applied on release:
        // each value is a fresh pass of the chain over the frame.
        JHVSlider weight = new JHVSlider(0, 100, Math.round(stage.strength() * 100)).nominal(100);
        JLabel weightLabel = new JLabel(weight.getValue() + "%", JLabel.RIGHT);
        weight.getAccessibleContext().setAccessibleName("Weight of filter in series " + (i + 2));
        weight.setToolTipText("How far the result moves from the previous step toward this filter: 0% is none, 100% is all");
        weight.readout(weightLabel);
        weight.addChangeListener(e -> {
            weightLabel.setText(weight.getValue() + "%");
            if (syncing || weight.getValueIsAdjusting())
                return;
            float w = weight.getValue() / 100f;
            if (w != stage.strength())
                commit(first, chain.with(i, new FilterChain.Stage(stage.type(), w)));
        });
        JPanel weightRow = new JPanel(new BorderLayout(4, 0));
        weightRow.add(new JLabel("Weight "), BorderLayout.LINE_START);
        weightRow.add(weight, BorderLayout.CENTER);
        weightRow.add(weightLabel, BorderLayout.LINE_END);

        JLabel result = new JLabel("Result: " + FilterChain.describe(first, chain));
        result.setEnabled(false);
        result.setToolTipText("y = (1 - weight) * previous + weight * filter(previous), one step per row");

        row.add(top);
        row.add(weightRow);
        if (i == chain.size() - 1)
            row.add(result);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static String formatLabel(double value) {
        return String.format("%.1f", value);
    }

    private static String formatUpsilon(double value) {
        return String.format("%.2f", value);
    }

    private static JPanel createUpsilonRow(String title, String accessibleName, JHVSlider slider, JLabel label) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel(title);
        titleLabel.setLabelFor(slider);
        slider.getAccessibleContext().setAccessibleName(accessibleName);
        panel.add(titleLabel, BorderLayout.LINE_START);
        panel.add(slider, BorderLayout.CENTER);
        panel.add(label, BorderLayout.LINE_END);
        return panel;
    }

    private static JPanel createEnhancePanel(ImageLayer layer) {
        ImageDisplaySettings settings = layer.getDisplaySettings();
        JHVSlider slider = new JHVSlider(0, 30, (int) (settings.getEnhanced() * 10)).nominal((int) (DEFAULTS.getEnhanced() * 10)).animates("layer:" + layer.getId() + "/enhanced");
        JLabel label = new JLabel(formatLabel(slider.getValue() / 10.), JLabel.RIGHT);
        slider.readout(label);
        label.setToolTipText("<html><body>pixel⋅R<sup>v");
        slider.addChangeListener(e -> {
            double value = slider.getValue() / 10.;
            Layers.applyToSelected(layer, s -> s.setEnhanced(value));
            label.setText(formatLabel(value));
            DisplayController.display();
        });
        JPanel enhancePanel = new JPanel(new BorderLayout());
        enhancePanel.add(slider, BorderLayout.LINE_START);
        enhancePanel.add(label, BorderLayout.LINE_END);
        return enhancePanel;
    }

    public ImageFilterPanel(ImageLayer _layer) {
        layer = _layer;
        ImageDisplaySettings settings = layer.getDisplaySettings();
        filterCombo = new JComboBox<>(ImageFilter.Type.values());
        filterCombo.setSelectedItem(layer.getFilter());
        filterCombo.setToolTipText(layer.getFilter().description);

        JPanel enhancePanel = createEnhancePanel(layer);
        SplitButton enhanceButton = new SplitButton(Buttons.corona);
        enhanceButton.setToolTipText("Enhance radially the off-disk corona");
        enhanceButton.setAlwaysDropdown(true);
        enhanceButton.addItem(enhancePanel);

        JHVSlider upsilonLowSlider = new JHVSlider(5, 100, (int) (settings.getUpsilonLow() * 100))
                .nominal((int) Math.round(DEFAULTS.getUpsilonLow() * 100)).animates("layer:" + layer.getId() + "/upsilonLow");
        JLabel upsilonLowLabel = new JLabel(formatUpsilon(upsilonLowSlider.getValue() / 100.), JLabel.RIGHT);
        upsilonLowSlider.readout(upsilonLowLabel);
        upsilonLowSlider.addChangeListener(e -> {
            double value = upsilonLowSlider.getValue() / 100.;
            // The other handle is read per layer inside the fan-out, so a peer keeps its own.
            Layers.applyToSelected(layer, s -> s.setUpsilon(value, s.getUpsilonHigh()));
            upsilonLowLabel.setText(formatUpsilon(value));
            DisplayController.display();
        });
        JHVSlider upsilonHighSlider = new JHVSlider(5, 100, (int) (settings.getUpsilonHigh() * 100))
                .nominal((int) Math.round(DEFAULTS.getUpsilonHigh() * 100)).animates("layer:" + layer.getId() + "/upsilonHigh");
        JLabel upsilonHighLabel = new JLabel(formatUpsilon(upsilonHighSlider.getValue() / 100.), JLabel.RIGHT);
        upsilonHighSlider.readout(upsilonHighLabel);
        upsilonHighSlider.addChangeListener(e -> {
            double value = upsilonHighSlider.getValue() / 100.;
            Layers.applyToSelected(layer, s -> s.setUpsilon(s.getUpsilonLow(), value));
            upsilonHighLabel.setText(formatUpsilon(value));
            DisplayController.display();
        });
        JPanel upsilonPanel = new JPanel(new GridLayout(2, 1));
        upsilonPanel.add(createUpsilonRow("Shadows (Υ low) ", "Shadows", upsilonLowSlider, upsilonLowLabel));
        upsilonPanel.add(createUpsilonRow("Highlights (Υ high) ", "Highlights", upsilonHighSlider, upsilonHighLabel));

        // RH-17: the curve the two sliders make, a Look menu, and hold-to-compare, around the rows.
        ToneCurve curve = new ToneCurve();
        curve.setUpsilon(upsilonLowSlider.getValue() / 100., upsilonHighSlider.getValue() / 100.);
        javax.swing.event.ChangeListener redraw = e -> curve.setUpsilon(upsilonLowSlider.getValue() / 100., upsilonHighSlider.getValue() / 100.);
        upsilonLowSlider.addChangeListener(redraw);
        upsilonHighSlider.addChangeListener(redraw);

        JComboBox<Look> lookCombo = new JComboBox<>(LOOKS);
        lookCombo.setSelectedIndex(-1);
        lookCombo.setToolTipText("Set both sliders to a named pair");
        lookCombo.getAccessibleContext().setAccessibleName("Look");
        lookCombo.addActionListener(e -> {
            if (lookCombo.getSelectedItem() instanceof Look look) {
                // Through the sliders, so the change fans out exactly as a drag of each one does.
                upsilonLowSlider.setValue((int) Math.round(look.low() * 100));
                upsilonHighSlider.setValue((int) Math.round(look.high() * 100));
            }
        });

        JButton peek = new JButton("Hold to compare");
        peek.setToolTipText("While held, this layer only is shown without RHEF; nothing is saved");
        peek.getModel().addChangeListener(e -> {
            if (peek.getModel().isPressed())
                RhefPeek.begin(layer);
            else
                RhefPeek.end(layer);
        });

        JPanel lookRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        lookRow.add(new JLabel("Look"));
        lookRow.add(lookCombo);
        lookRow.add(peek);
        JPanel upsilonControls = new JPanel(new BorderLayout(6, 4));
        upsilonControls.add(upsilonPanel, BorderLayout.CENTER);
        upsilonControls.add(curve, BorderLayout.LINE_END);
        upsilonControls.add(lookRow, BorderLayout.PAGE_END);

        upsilonButton = new SplitButton("Υ");
        upsilonButton.setAlwaysDropdown(true);
        upsilonButton.addItem(upsilonControls);
        upsilonButton.getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                lookCombo.setSelectedIndex(-1); // a menu of actions, not a state the sliders could drift from
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                RhefPeek.end(layer); // a menu closed mid-hold never sees the release
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        setUpsilonEnabled(layer.usesRhef());
        filterCombo.addActionListener(e -> {
            if (filterCombo.getSelectedItem() instanceof ImageFilter.Type type) {
                filterCombo.setToolTipText(type.description);
                if (syncing)
                    return;
                setUpsilonEnabled(type != ImageFilter.Type.None && (type == ImageFilter.Type.RHEF || layer.getFilterChain().usesRhef(ImageFilter.Type.None)));
                Layers.applyToSelectedLayers(layer, il -> {
                    // The filter lives on the layer's processing settings now, and setting it
                    // already drops the decoded frames and re-renders; the view follows from there.
                    il.setFilter(type);
                    // Setting the filter fires nothing by itself, so say so: the other copy of this panel
                    // (Filters palette or Image Layers row, whichever this is not) re-syncs on it.
                    Layers.fireLayerUpdated(il);
                });
                DisplayController.render(1);
            }
        });

        JPanel firstRow = new JPanel(new BorderLayout());
        firstRow.add(filterCombo, BorderLayout.CENTER);
        firstRow.add(upsilonButton, BorderLayout.LINE_END);
        chainPanel.setLayout(new BoxLayout(chainPanel, BoxLayout.PAGE_AXIS));
        filterPanel.add(firstRow, BorderLayout.PAGE_START);
        filterPanel.add(chainPanel, BorderLayout.CENTER);
        rebuildChain(layer);
        buttonPanel.add(enhanceButton, BorderLayout.LINE_END);
    }

    @Override
    public Component getFirst() {
        return title;
    }

    @Override
    public Component getSecond() {
        return filterPanel;
    }

    @Override
    public Component getThird() {
        return buttonPanel;
    }

}

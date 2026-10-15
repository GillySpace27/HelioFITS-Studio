package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import org.helioviewer.jhv.display.Compare;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.LayerDuplicate;
import org.helioviewer.jhv.layers.Layers;

/**
 * The Compare palette (HS-11): pick two image layers, then blink between them or swipe a divider
 * across every viewport. The state is {@link Compare}'s; this only edits it.
 *
 * <p>Difference is shown and greyed, with the reason: a true A minus B in physical units needs both
 * stretches linear and the same BUNIT, and is a later phase ("grey out, do not hide").
 *
 * <p>Widgets are static finals and the two flags live in one final holder (guards.py ratchet).
 */
final class ComparePaletteContent {

    private static final JPanel panel = new JPanel();
    private static final JComboBox<ImageLayer> comboA = new JComboBox<>();
    private static final JComboBox<ImageLayer> comboB = new JComboBox<>();
    private static final JRadioButton off = new JRadioButton("Off");
    private static final JRadioButton blink = new JRadioButton("Blink");
    private static final JRadioButton swipe = new JRadioButton("Swipe");
    private static final JRadioButton difference = new JRadioButton("Difference");
    private static final JHVSlider rate = new JHVSlider(5, 80, 20); // tenths of a hertz: 0.5 to 8 Hz
    private static final JLabel rateLabel = new JLabel();
    private static final JHVSlider split = new JHVSlider(0, 100, 50); // percent of each viewport's width
    private static final JCheckBox aLeft = new JCheckBox("A on the left");

    private static final class Flags {
        boolean built;
        boolean syncing;
        List<ImageLayer> lastLayers = List.of();
    }

    private static final Flags F = new Flags();

    private ComparePaletteContent() {}

    static Component build() {
        if (F.built) {
            refresh();
            return panel;
        }
        F.built = true;
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));

        for (JComboBox<ImageLayer> c : List.of(comboA, comboB)) {
            c.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
                    setText(value instanceof ImageLayer layer ? layer.getName() : "(none)");
                    return this;
                }
            });
            c.addActionListener(e -> pickLayers());
        }
        comboA.setToolTipText("Layer A");
        comboB.setToolTipText("Layer B");
        comboA.getAccessibleContext().setAccessibleName("Compare layer A");
        comboB.getAccessibleContext().setAccessibleName("Compare layer B");
        JButton swap = new JButton("Swap");
        swap.setToolTipText("Exchange layers A and B");
        swap.addActionListener(e -> {
            Compare.setLayers(Compare.getLayerB(), Compare.getLayerA());
            refresh();
        });
        JButton duplicate = new JButton("Duplicate Layer");
        duplicate.setToolTipText("Add a copy of the selected image layer, to compare it against itself with RHEF or another filter on");
        duplicate.addActionListener(e -> LayerDuplicate.duplicate(Layers.getActiveImageLayer()));

        panel.add(row(new JLabel("A"), comboA));
        panel.add(row(new JLabel("B"), comboB));
        panel.add(row(swap, duplicate));

        ButtonGroup group = new ButtonGroup();
        for (JRadioButton b : List.of(off, blink, swipe, difference)) {
            group.add(b);
            b.setOpaque(false);
        }
        off.setToolTipText("No comparison: both layers draw as usual. Also the off switch for the blinking");
        blink.setToolTipText("Show A, then B, at the rate below; neither layer is switched off");
        swipe.setToolTipText("A on one side of a vertical divider and B on the other, in every viewport");
        difference.setEnabled(false);
        difference.setToolTipText("Not available yet: A minus B in physical units needs both layers on a linear stretch "
                + "with the same unit (BUNIT); it is a later phase of this palette");
        off.addActionListener(e -> Compare.setMode(Compare.Mode.Off));
        blink.addActionListener(e -> Compare.setMode(Compare.Mode.Blink));
        swipe.addActionListener(e -> Compare.setMode(Compare.Mode.Swipe));
        panel.add(row(off, blink, swipe, difference));

        rate.setToolTipText("How many times a second the view goes A, B and back (0.5 to 8)");
        rate.getAccessibleContext().setAccessibleName("Blink rate");
        rate.addChangeListener(e -> {
            if (!F.syncing)
                Compare.setRate(rate.getValue() / 10.);
            syncRateLabel();
        });
        panel.add(row(new JLabel("Rate"), rate, rateLabel));

        split.setToolTipText("Where the divider sits, as a share of each viewport's width; arrow keys nudge it");
        split.getAccessibleContext().setAccessibleName("Swipe divider position");
        split.addChangeListener(e -> {
            if (!F.syncing)
                Compare.setSplit(split.getValue() / 100.);
        });
        JButton centre = new JButton("Centre");
        centre.setToolTipText("Put the divider in the middle of each viewport");
        centre.addActionListener(e -> split.setValue(50));
        aLeft.setOpaque(false);
        aLeft.setToolTipText("Which side of the divider layer A takes");
        aLeft.addActionListener(e -> Compare.setALeft(aLeft.isSelected()));
        panel.add(row(new JLabel("Divider"), split, centre));
        panel.add(row(aLeft));

        refresh();
        return panel;
    }

    private static JPanel row(JComponent... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 1));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JComponent c : parts)
            p.add(c);
        return p;
    }

    private static void syncRateLabel() {
        rateLabel.setText(String.format("%.1f Hz", rate.getValue() / 10.));
    }

    private static void pickLayers() {
        if (F.syncing)
            return;
        Compare.setLayers((ImageLayer) comboA.getSelectedItem(), (ImageLayer) comboB.getSelectedItem());
        refresh();
    }

    /** The first layer that is not {@code other}, for a default pick. */
    @Nullable
    private static ImageLayer firstOther(List<ImageLayer> layers, @Nullable ImageLayer other) {
        for (ImageLayer l : layers)
            if (l != other)
                return l;
        return null;
    }

    /** Mirror Compare's state and the current layer list into the widgets. */
    static void refresh() {
        if (!F.built)
            return;
        F.syncing = true;
        try {
            List<ImageLayer> current = new ArrayList<>(Layers.getImageLayers()); // a copy: the list is a live view
            ImageLayer a = Compare.getLayerA(), b = Compare.getLayerB();
            if (a != null && !current.contains(a))
                a = null;
            if (b != null && !current.contains(b))
                b = null;
            if (a == null)
                a = firstOther(current, b);
            if (b == null)
                b = firstOther(current, a);
            if (a != Compare.getLayerA() || b != Compare.getLayerB())
                Compare.setLayers(a, b);

            if (!current.equals(F.lastLayers)) {
                F.lastLayers = List.copyOf(current);
                for (JComboBox<ImageLayer> c : List.of(comboA, comboB)) {
                    c.removeAllItems();
                    for (ImageLayer l : current)
                        c.addItem(l);
                }
            }
            comboA.setSelectedItem(a);
            comboB.setSelectedItem(b);
            boolean two = current.size() >= 2;
            comboA.setEnabled(!current.isEmpty());
            comboB.setEnabled(!current.isEmpty());
            String needTwo = two ? null : "Needs two image layers: add one, or use Duplicate Layer";
            for (JRadioButton r : List.of(blink, swipe)) {
                r.setEnabled(two);
                if (!two)
                    r.setToolTipText(needTwo);
            }
            if (two) {
                blink.setToolTipText("Show A, then B, at the rate below; neither layer is switched off");
                swipe.setToolTipText("A on one side of a vertical divider and B on the other, in every viewport");
            }

            switch (Compare.getMode()) {
                case Off -> off.setSelected(true);
                case Blink -> blink.setSelected(true);
                case Swipe -> swipe.setSelected(true);
            }
            rate.setValue((int) Math.round(Compare.getRate() * 10));
            syncRateLabel();
            split.setValue((int) Math.round(Compare.getSplit() * 100));
            aLeft.setSelected(Compare.isALeft());
        } finally {
            F.syncing = false;
        }
    }

    static {
        Layers.addListener(new Layers.Listener() {
            @Override
            public void layerAdded(int index, Layer layer) {
                refresh();
            }

            @Override
            public void layerRemoved(int index, Layer layer) {
                refresh();
            }

            @Override
            public void layersCleared() {
                refresh();
            }

            @Override
            public void nameUpdated(Layer layer) {
                if (F.built) {
                    comboA.repaint();
                    comboB.repaint();
                }
            }

            @Override
            public void layerUpdated(Layer layer) {
            }

            @Override
            public void timeUpdated(Layer layer) {
            }
        });
    }

}

package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import org.helioviewer.jhv.display.Pizza;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;

/**
 * The Pizza palette (HS-25): cut every enabled image layer into its own wedges or tiles of one
 * Sun, turn the cut, and record a seamless spin. The state is {@link Pizza}'s; this only edits it.
 *
 * <p>Widgets are static finals and the flags live in one final holder (guards.py ratchet).
 */
final class PizzaPaletteContent {

    private static final JPanel panel = new JPanel();
    private static final JComboBox<Pizza.Shape> shape = new JComboBox<>(Pizza.Shape.values());
    private static final JComboBox<Pizza.Space> space = new JComboBox<>(Pizza.Space.values());
    private static final JLabel repeatsLabel = new JLabel();
    private static final JHVSlider repeats = new JHVSlider(1, Pizza.MAX_REPEATS, 1).nominal(1);
    private static final JLabel turnLabel = new JLabel();
    private static final JHVSlider turn = new JHVSlider(0, 360, 0).nominal(0).animates("pizza.turn").readout(turnLabel);
    private static final JLabel gapLabel = new JLabel();
    private static final JHVSlider gap = new JHVSlider(0, 20, 0).nominal(0); // degrees, or percent of a tile
    private static final JLabel outerLabel = new JLabel();
    private static final JHVSlider outer = new JHVSlider(0, 300, 0).nominal(0); // hundredths of a solar radius, 0 none
    private static final JLabel jitterLabel = new JLabel();
    private static final JHVSlider jitter = new JHVSlider(0, 90, 0).nominal(0); // percent
    private static final JSpinner seed = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));

    private static final JPanel tileRows = new JPanel();
    private static final JLabel tileLabel = new JLabel();
    private static final JHVSlider tile = new JHVSlider(5, 500, 50).nominal(50); // hundredths
    private static final JSpinner rows = new JSpinner(new SpinnerNumberModel(6, 1, 64, 1));
    private static final JSpinner shift = new JSpinner(new SpinnerNumberModel(1, 0, 64, 1));

    private static final JCheckBox twoRings = new JCheckBox("Coronagraph ring");
    private static final JPanel ringRows = new JPanel();
    private static final JLabel boundaryLabel = new JLabel();
    private static final JHVSlider boundary = new JHVSlider(100, 600, 115).nominal(115); // hundredths of a solar radius
    private static final JLabel outerRepeatsLabel = new JLabel();
    private static final JHVSlider outerRepeats = new JHVSlider(1, Pizza.MAX_REPEATS, 1).nominal(1);
    private static final JCheckBox counter = new JCheckBox("Outer ring turns the other way");

    private static final JPanel layerList = new JPanel();

    private static final JComboBox<Pizza.Labels> labels = new JComboBox<>(Pizza.Labels.values());
    private static final JCheckBox angstrom = new JCheckBox("Å");
    private static final JCheckBox colourNames = new JCheckBox("Colour names");
    private static final JHVSlider labelScale = new JHVSlider(50, 300, 100).nominal(100); // percent

    private static final JComboBox<Pizza.Spin> spin = new JComboBox<>(Pizza.Spin.values());
    private static final JLabel speedLabel = new JLabel();
    private static final JHVSlider speed = new JHVSlider(-60, 60, 6).nominal(6); // degrees a second
    private static final JLabel stepLabel = new JLabel();
    private static final JHVSlider step = new JHVSlider(-20, 20, 2).nominal(2); // degrees a movie frame
    private static final JSpinner frames = new JSpinner(new SpinnerNumberModel(180, 2, Pizza.MAX_FRAMES, 10));
    private static final JButton record = new JButton("Record Spin");
    private static final JLabel loopNote = new JLabel();

    private static final class Flags {
        boolean built;
        boolean syncing;
        String layerRows = "";
    }

    private static final Flags F = new Flags();

    private PizzaPaletteContent() {}

    static Component build() {
        if (F.built) {
            refresh();
            return panel;
        }
        F.built = true;
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));

        shape.setToolTipText("How the Sun is cut: each included layer keeps its own wedges or tiles. Off draws every layer whole");
        shape.getAccessibleContext().setAccessibleName("Pizza shape");
        shape.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setShape((Pizza.Shape) shape.getSelectedItem());
        });
        space.setToolTipText("Sun: the cut follows each image's solar north, so it stays on the Sun as the camera moves."
                + " Screen: the cut is fixed to the viewport");
        space.getAccessibleContext().setAccessibleName("Pizza space");
        space.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setSpace((Pizza.Space) space.getSelectedItem());
        });
        panel.add(row(new JLabel("Cut"), shape, space));

        repeats.setToolTipText("How many times the channels go round: 1 gives one wedge each, 3 gives three narrower ones");
        repeats.getAccessibleContext().setAccessibleName("Pizza repeats");
        repeats.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setRepeats(Pizza.INNER, repeats.getValue());
            syncLabels();
        });
        panel.add(row(new JLabel("Repeats"), repeats, repeatsLabel));

        turn.setToolTipText("Where the first wedge starts, counterclockwise from solar north; animate it for a turntable");
        turn.getAccessibleContext().setAccessibleName("Pizza turn");
        turn.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setTurn(turn.getValue());
            syncLabels();
        });
        panel.add(row(new JLabel("Turn"), turn, turnLabel));

        gap.setToolTipText("Black between the cells: degrees between wedges, a share of each tile for tiles");
        gap.getAccessibleContext().setAccessibleName("Pizza gap");
        gap.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setGap(gap.getValue());
            syncLabels();
        });
        panel.add(row(new JLabel("Gap"), gap, gapLabel));

        outer.setToolTipText("Cut every layer off at this many solar radii (0: no edge, each layer keeps its own field)");
        outer.getAccessibleContext().setAccessibleName("Pizza outer edge");
        outer.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setOuterRadius(outer.getValue() / 100.);
            syncLabels();
        });
        panel.add(row(new JLabel("Edge"), outer, outerLabel));

        jitter.setToolTipText("Spokes of uneven length, like the NASA composite; needs an edge");
        jitter.getAccessibleContext().setAccessibleName("Pizza spoke jitter");
        jitter.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setJitter(jitter.getValue() / 100.);
            syncLabels();
        });
        seed.setToolTipText("0 keeps the channels in the same order every repeat; any other number shuffles each repeat on");
        seed.getAccessibleContext().setAccessibleName("Pizza shuffle");
        seed.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setSeed((Integer) seed.getValue());
        });
        panel.add(row(new JLabel("Jitter"), jitter, jitterLabel, new JLabel("Shuffle"), seed));

        tileRows.setLayout(new BoxLayout(tileRows, BoxLayout.PAGE_AXIS));
        tileRows.setOpaque(false);
        tileRows.setAlignmentX(Component.LEFT_ALIGNMENT);
        tile.setToolTipText("Square size in solar radii (Sun) or hundreds of pixels (Screen); for polar tiles, the depth of a row");
        tile.getAccessibleContext().setAccessibleName("Pizza tile size");
        tile.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setTile(tile.getValue() / 100.);
            syncLabels();
        });
        rows.setToolTipText("Polar tiles in Helioradial: rows from the centre to the edge of the page");
        rows.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setRows((Integer) rows.getValue());
        });
        shift.setToolTipText("How many channels each row moves along, so neighbours above and below differ");
        shift.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setShift((Integer) shift.getValue());
        });
        tileRows.add(row(new JLabel("Tile"), tile, tileLabel));
        tileRows.add(row(new JLabel("Rows"), rows, new JLabel("Row shift"), shift));
        panel.add(tileRows);

        twoRings.setOpaque(false);
        twoRings.setToolTipText("Coronagraphs (layers whose metadata masks past the limb) get their own ring outside the disk");
        twoRings.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setTwoRings(twoRings.isSelected());
        });
        panel.add(row(twoRings));
        ringRows.setLayout(new BoxLayout(ringRows, BoxLayout.PAGE_AXIS));
        ringRows.setOpaque(false);
        ringRows.setAlignmentX(Component.LEFT_ALIGNMENT);
        boundary.setToolTipText("Where the inner ring ends and the coronagraph ring begins, in solar radii");
        boundary.getAccessibleContext().setAccessibleName("Pizza ring boundary");
        boundary.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setBoundary(boundary.getValue() / 100.);
            syncLabels();
        });
        outerRepeats.setToolTipText("Repeats of the coronagraph ring");
        outerRepeats.getAccessibleContext().setAccessibleName("Pizza outer ring repeats");
        outerRepeats.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setRepeats(Pizza.OUTER, outerRepeats.getValue());
            syncLabels();
        });
        counter.setOpaque(false);
        counter.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setCounterRotating(Pizza.OUTER, counter.isSelected());
        });
        ringRows.add(row(new JLabel("Boundary"), boundary, boundaryLabel));
        ringRows.add(row(new JLabel("Repeats"), outerRepeats, outerRepeatsLabel));
        ringRows.add(row(counter));
        panel.add(ringRows);

        panel.add(row(new JLabel("Channels")));
        layerList.setLayout(new BoxLayout(layerList, BoxLayout.PAGE_AXIS));
        layerList.setOpaque(false);
        layerList.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(layerList);

        labels.setToolTipText("Per-wavelength labels: on every wedge, on the first repeat only, or none");
        labels.getAccessibleContext().setAccessibleName("Pizza labels");
        labels.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setLabels((Pizza.Labels) labels.getSelectedItem());
        });
        angstrom.setOpaque(false);
        angstrom.setToolTipText("Wavelengths in angstrom rather than nanometres");
        angstrom.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setAngstrom(angstrom.isSelected());
        });
        colourNames.setOpaque(false);
        colourNames.setToolTipText("Add each channel's colour name (typed beside it above) to its label");
        colourNames.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setColourNames(colourNames.isSelected());
        });
        labelScale.setToolTipText("Label size");
        labelScale.getAccessibleContext().setAccessibleName("Pizza label size");
        labelScale.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setLabelScale(labelScale.getValue() / 100.);
        });
        panel.add(row(new JLabel("Labels"), labels, angstrom, colourNames));
        panel.add(row(new JLabel("Label size"), labelScale));

        spin.setToolTipText("Still; Live turns the cut on screen; With the movie turns it a step per movie frame, so a loop recording turns too");
        spin.getAccessibleContext().setAccessibleName("Pizza spin");
        spin.addActionListener(e -> {
            if (!F.syncing)
                Pizza.setSpin((Pizza.Spin) spin.getSelectedItem());
        });
        panel.add(row(new JLabel("Spin"), spin));
        speed.setToolTipText("Live spin in degrees a second; negative turns clockwise");
        speed.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setDegPerSec(speed.getValue());
            syncLabels();
        });
        step.setToolTipText("Degrees per movie frame while spinning with the movie");
        step.addChangeListener(e -> {
            if (!F.syncing)
                Pizza.setDegPerFrame(step.getValue());
            syncLabels();
        });
        panel.add(row(new JLabel("Speed"), speed, speedLabel));
        panel.add(row(new JLabel("Per frame"), step, stepLabel));

        frames.setToolTipText("Frames in one seamless turn; the data stands still while the cut turns");
        frames.getAccessibleContext().setAccessibleName("Spin frames");
        record.setToolTipText("Record one seamless turn of the cut at the recording size and format, with the movie paused");
        record.addActionListener(e -> ExportMovie.startSpin(null, (Integer) frames.getValue()));
        panel.add(row(record, frames, new JLabel("frames")));
        panel.add(row(loopNote));

        Pizza.addListener(PizzaPaletteContent::refresh);
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

    private static void syncLabels() {
        boolean wedges = Pizza.getShape() != Pizza.Shape.Squares && Pizza.getShape() != Pizza.Shape.Tiles;
        repeatsLabel.setText("×" + repeats.getValue());
        turnLabel.setText(turn.getValue() + "°");
        gapLabel.setText(gap.getValue() + (wedges ? "°" : "%"));
        outerLabel.setText(outer.getValue() == 0 ? "none" : String.format("%.2f R☉", outer.getValue() / 100.));
        jitterLabel.setText(jitter.getValue() + "%");
        tileLabel.setText(String.format(Pizza.getSpace() == Pizza.Space.Screen ? "%.0f px" : "%.2f R☉",
                Pizza.getSpace() == Pizza.Space.Screen ? tile.getValue() : tile.getValue() / 100.));
        boundaryLabel.setText(String.format("%.2f R☉", boundary.getValue() / 100.));
        outerRepeatsLabel.setText("×" + outerRepeats.getValue());
        speedLabel.setText(speed.getValue() + "°/s");
        stepLabel.setText(step.getValue() + "°");
        loopNote.setText(String.format("One seamless turn is %.0f°", Pizza.loopDegrees()));
    }

    /** Mirror Pizza's state and the current layer list into the widgets. */
    static void refresh() {
        if (!F.built)
            return;
        F.syncing = true;
        try {
            Pizza.Shape s = Pizza.getShape();
            shape.setSelectedItem(s);
            space.setSelectedItem(Pizza.getSpace());
            repeats.setValue(Pizza.getRepeats(Pizza.INNER));
            turn.setValue((int) Math.round(Pizza.getTurn()) % 360);
            gap.setValue((int) Math.round(Pizza.getGap()));
            outer.setValue((int) Math.round(Pizza.getOuterRadius() * 100));
            jitter.setValue((int) Math.round(Pizza.getJitter() * 100));
            seed.setValue(Pizza.getSeed());
            tile.setValue((int) Math.round(Pizza.getTile() * 100));
            rows.setValue(Pizza.getRows());
            shift.setValue(Pizza.getShift());
            tileRows.setVisible(s == Pizza.Shape.Squares || s == Pizza.Shape.Tiles);
            twoRings.setSelected(Pizza.isTwoRings());
            ringRows.setVisible(Pizza.isTwoRings());
            boundary.setValue((int) Math.round(Pizza.getBoundary() * 100));
            outerRepeats.setValue(Pizza.getRepeats(Pizza.OUTER));
            counter.setSelected(Pizza.isCounterRotating(Pizza.OUTER));
            labels.setSelectedItem(Pizza.getLabels());
            angstrom.setSelected(Pizza.isAngstrom());
            colourNames.setSelected(Pizza.isColourNames());
            labelScale.setValue((int) Math.round(Pizza.getLabelScale() * 100));
            spin.setSelectedItem(Pizza.getSpin());
            speed.setValue((int) Math.round(Pizza.getDegPerSec()));
            step.setValue((int) Math.round(Pizza.getDegPerFrame()));
            record.setEnabled(Pizza.isOn() && !ExportMovie.isRecording());
            refreshLayers();
            syncLabels();
        } finally {
            F.syncing = false;
        }
    }

    private static void refreshLayers() {
        List<ImageLayer> current = new ArrayList<>(Layers.getImageLayers()); // a copy: the list is a live view
        // Rebuilt only when what the rows show changed, not on every drag of the turn slider.
        StringBuilder sig = new StringBuilder().append(Pizza.isTwoRings()).append(Pizza.isColourNames());
        for (ImageLayer l : current)
            sig.append('|').append(l.getId()).append(l.getName()).append(l.isEnabled()).append(Pizza.isIncluded(l))
                    .append(Pizza.getRing(l)).append(Pizza.getColourName(l));
        if (sig.toString().equals(F.layerRows))
            return;
        F.layerRows = sig.toString();
        layerList.removeAll();
        if (current.isEmpty())
            layerList.add(row(new JLabel("Add image layers: each enabled one gets its own wedges")));
        for (ImageLayer layer : current) {
            JCheckBox in = new JCheckBox(layer.getName(), Pizza.isIncluded(layer));
            in.setOpaque(false);
            in.setEnabled(layer.isEnabled());
            in.setToolTipText(layer.isEnabled() ? "Give this layer wedges" : "Hidden layers take no wedges");
            in.addActionListener(e -> Pizza.setIncluded(layer, in.isSelected()));
            JComboBox<String> ring = new JComboBox<>(new String[]{"Inner", "Outer"});
            ring.setSelectedIndex(Pizza.getRing(layer));
            ring.setVisible(Pizza.isTwoRings());
            ring.setToolTipText("Which ring this layer is cut into");
            ring.addActionListener(e -> {
                if (!F.syncing)
                    Pizza.setRing(layer, ring.getSelectedIndex());
            });
            JTextField name = new JTextField(Pizza.getColourName(layer), 7);
            name.setToolTipText("A colour name for the label, such as Teal or Gold (optional)");
            name.getAccessibleContext().setAccessibleName("Colour name for " + layer.getName());
            name.addActionListener(e -> Pizza.setColourName(layer, name.getText()));
            name.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    if (!name.getText().strip().equals(Pizza.getColourName(layer)))
                        Pizza.setColourName(layer, name.getText());
                }
            });
            name.setVisible(Pizza.isColourNames());
            layerList.add(row(in, ring, name));
        }
        layerList.revalidate();
        layerList.repaint();
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
                refresh();
            }

            @Override
            public void layerUpdated(Layer layer) {
                refresh(); // an enabled box: hidden layers take no wedges
            }

            @Override
            public void timeUpdated(Layer layer) {}
        });
    }

}

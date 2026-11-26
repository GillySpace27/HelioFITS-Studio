package org.helioviewer.jhv.timelines.band;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import java.awt.EventQueue;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.gui.CompletionNotifications;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.component.Buttons;
import org.helioviewer.jhv.gui.component.TerminatedFormatterFactory;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONObject;

@SuppressWarnings("serial")
class BandOptions extends JPanel {

    private static final String LINEAR = "Linear";
    private static final String LOG = "Log";

    private final Band band;
    private final JCheckBox autoBox = new JCheckBox("Auto");
    private final JTextField minField = new JTextField(7);
    private final JTextField maxField = new JTextField(7);
    private final JComboBox<String> scaleCombo = new JComboBox<>(new String[]{LINEAR, LOG});
    private boolean syncing;
    private String shownMin = "";
    private String shownMax = "";

    BandOptions(Band _band) {
        band = _band;
        setLayout(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.NONE;

        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_START;
        JButton pickColor = new JButton("Line color");
        pickColor.setToolTipText("Change the color of the current line");
        pickColor.addActionListener(e -> {
            Color newColor = JColorChooser.showDialog(MainFrame.get(), "Choose Line Color", band.getDataColor());
            if (newColor != null) {
                band.setDataColor(newColor);
            }
        });
        add(pickColor, c);
        JButton levels = new JButton("Colour levels…");
        levels.setToolTipText("Draw the line in different colours over value ranges (the speed rows start with slow, intermediate and fast)");
        levels.addActionListener(e -> BandLevelsDialog.show(band));

        c.gridx = 1;
        c.anchor = GridBagConstraints.LINE_START;
        JFormattedTextField propagationField = new JFormattedTextField(new TerminatedFormatterFactory("%.3f", " days", 0, 28));
        propagationField.setValue(0.);
        propagationField.setColumns(10);
        propagationField.addPropertyChangeListener("value", e -> {
            double value = (Double) propagationField.getValue();
            band.setPropagationModel(new PropagationModel.Delay(value));
        });
        add(propagationField, c);

        c.gridx = 2;
        c.anchor = GridBagConstraints.LINE_END;
        JButton downloadButton = getDownloadButton(band);
        add(downloadButton, c);

        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.LINE_START;
        add(yAxisRow(), c);

        // Which spacecraft the 3D buoy for this row sits at: guessed from the name until picked.
        c.gridy = 2;
        add(spacecraftRow(band), c);

        // The buoy switches are the 3D scene's, shared by every row: shown here so the row and its dot are set up together
        c.gridy = 3;
        // Built when first shown, not here: this panel is made while its Band is still being constructed,
        // and the controls reach into the layer lists (CI: bandType null in BandYAxisCheck and ImapSourceCheck)
        JPanel buoys = new JPanel(new java.awt.BorderLayout()) {
            private boolean built;

            @Override
            public void addNotify() {
                super.addNotify();
                if (!built) {
                    built = true;
                    add(org.helioviewer.jhv.layers.selector.GridLayerOptions.buoyControls(), java.awt.BorderLayout.CENTER);
                }
            }
        };
        add(buoys, c);
        c.gridy = 4;
        add(levels, c);
    }

    // The spacecraft picker, and beside it a switch that puts the craft's 3D model at its position
    private static JPanel spacecraftRow(Band band) {
        // sync runs when the row is shown, not while the Band is still being constructed (its name is not set yet)
        Runnable[] syncRef = new Runnable[1];
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0)) {
            @Override
            public void addNotify() {
                super.addNotify();
                syncRef[0].run();
            }
        };
        JComboBox<String> picker = spacecraftPicker(band);
        javax.swing.JToggleButton model = new javax.swing.JToggleButton("Show model");
        Runnable sync = () -> {
            var craft = band.getSpacecraft();
            String target = craft == null ? null : craft.target();
            boolean has = target != null && org.helioviewer.jhv.layers.ObserverModels.hasModel(target);
            boolean offered = target != null && !has && org.helioviewer.jhv.layers.ObserverModels.offeredSource(target) != null;
            model.setEnabled(has || offered);
            model.setSelected(has && org.helioviewer.jhv.layers.ObserverModels.isShown(target));
            model.setToolTipText(target == null ? "This row has no spacecraft"
                    : has ? "Show " + target + " in the 3D scene, at its position"
                    : offered ? "Download the " + target + " model from its maintainers (asks first), then show it"
                    : "No 3D model is listed for " + target + " (the dot and ring still mark it)");
        };
        model.addActionListener(e -> {
            var craft = band.getSpacecraft();
            if (craft == null)
                return;
            String target = craft.target();
            if (!org.helioviewer.jhv.layers.ObserverModels.hasModel(target) && org.helioviewer.jhv.layers.ObserverModels.offeredSource(target) != null) {
                // Not bundled: no licence to redistribute it. Fetching it is the user's own import, so ask first.
                String source = org.helioviewer.jhv.layers.ObserverModels.offeredSource(target);
                int ok = javax.swing.JOptionPane.showConfirmDialog(model, "Download the " + target + " model from\n" + source
                        + "\ninto your models folder?\nIt is the maintainers' file, not bundled with Studio: check their terms before sharing it.",
                        "Import " + target + " model", javax.swing.JOptionPane.OK_CANCEL_OPTION);
                if (ok != javax.swing.JOptionPane.OK_OPTION) {
                    sync.run();
                    return;
                }
                org.helioviewer.jhv.layers.ObserverModels.importOffered(target,
                        () -> org.helioviewer.jhv.layers.ObserverModels.toggle(target, sync));
                return;
            }
            org.helioviewer.jhv.layers.ObserverModels.toggle(target, sync);
        });
        picker.addActionListener(e -> sync.run()); // a new spacecraft changes what the switch means
        syncRef[0] = sync;
        row.add(picker);
        row.add(model);
        return row;
    }

    private static final String AUTO = "Spacecraft: automatic", NONE = "Spacecraft: none (no buoy)", NONE_KEY = "none";

    private static JComboBox<String> spacecraftPicker(Band band) {
        java.util.List<org.helioviewer.jhv.layers.grid.DataBuoys.Craft> craft = java.util.List.of(org.helioviewer.jhv.layers.grid.DataBuoys.craft());
        JComboBox<String> box = new JComboBox<>();
        box.addItem(AUTO);
        craft.forEach(k -> box.addItem(k.target()));
        box.addItem(NONE);
        String key = band.getSpacecraftKey();
        if (key == null)
            box.setSelectedItem(AUTO);
        else if (NONE_KEY.equals(key))
            box.setSelectedItem(NONE);
        else
            craft.stream().filter(k -> k.key().equals(key)).findFirst().ifPresent(k -> box.setSelectedItem(k.target()));
        box.setToolTipText("Where this row's buoy sits in the 3D scene");
        box.addActionListener(e -> {
            String sel = (String) box.getSelectedItem();
            if (AUTO.equals(sel))
                band.setSpacecraftKey(null);
            else if (NONE.equals(sel))
                band.setSpacecraftKey(NONE_KEY);
            else
                craft.stream().filter(k -> k.target().equals(sel)).findFirst().ifPresent(k -> band.setSpacecraftKey(k.key()));
        });
        return box;
    }

    private JPanel yAxisRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        autoBox.setToolTipText("Keep the y range fitted to the data in view");
        autoBox.addActionListener(e -> {
            if (!syncing)
                band.setAutoFit(autoBox.isSelected());
        });
        minField.setToolTipText("Lower y limit (turns Auto off)");
        maxField.setToolTipText("Upper y limit (turns Auto off)");
        for (JTextField f : new JTextField[]{minField, maxField}) {
            f.addActionListener(e -> applyRange());
            f.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    applyRange();
                }
            });
        }
        scaleCombo.setToolTipText("Linear or logarithmic y axis");
        scaleCombo.addActionListener(e -> {
            if (!syncing)
                band.setLogScale(LOG.equals(scaleCombo.getSelectedItem()));
        });
        JButton fit = new JButton("Fit");
        fit.setToolTipText("Fit the y range to the data in view once");
        fit.addActionListener(e -> band.zoomToFitAxis());

        row.add(new JLabel("Y"));
        row.add(minField);
        row.add(new JLabel("to"));
        row.add(maxField);
        row.add(autoBox);
        row.add(fit);
        row.add(scaleCombo);
        return row;
    }

    // Text that does not parse, or a range this scale cannot show, goes back to the live range
    private void applyRange() {
        if (syncing)
            return;
        try {
            boolean minEdited = !minField.getText().equals(shownMin);
            boolean maxEdited = !maxField.getText().equals(shownMax);
            if (!minEdited && !maxEdited)
                return; // untouched: a focus change must not switch Auto off
            // the endpoint left alone keeps its exact value, not the rounded text shown for it
            double min = minEdited ? Double.parseDouble(minField.getText().trim()) : band.yMin();
            double max = maxEdited ? Double.parseDouble(maxField.getText().trim()) : band.yMax();
            if (band.setYRange(min, max))
                return;
        } catch (NumberFormatException ignore) {
            // fall through to the restore
        }
        syncAxis();
    }

    /** Show the band's live y range and scale; a field being typed in is left alone. */
    void syncAxis() {
        EventQueue.invokeLater(() -> {
            syncing = true;
            try {
                autoBox.setSelected(band.isAutoFit());
                scaleCombo.setSelectedItem(band.isLogScale() ? LOG : LINEAR);
                scaleCombo.setEnabled(!band.getBandType().isBarPlot());
                if (!minField.isFocusOwner()) {
                    shownMin = format(band.yMin());
                    minField.setText(shownMin);
                }
                if (!maxField.isFocusOwner()) {
                    shownMax = format(band.yMax());
                    maxField.setText(shownMax);
                }
            } finally {
                syncing = false;
            }
        });
    }

    private static String format(double v) {
        return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.5g", v) : "";
    }

    private static JButton getDownloadButton(Band band) {
        JButton downloadButton = Buttons.flat(Buttons.download);
        downloadButton.setToolTipText("Download selected layer");
        downloadButton.addActionListener(e -> {
            Path path = Path.of(Directories.DOWNLOADS.getPath(),
                    band.getBandType().getName().replace(' ', '_') + "__" + TimeUtils.formatFilename(System.currentTimeMillis()) + ".json");
            JSONObject jo = band.toJson();

            Task.submitBackground(() -> {
                try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                    jo.write(writer);
                }
                return path.toString();
            }, CompletionNotifications::fileReady, ex -> Log.error("Failed to write JSON", ex));
        });
        return downloadButton;
    }

}

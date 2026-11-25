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
            double min = Double.parseDouble(minField.getText().trim());
            double max = Double.parseDouble(maxField.getText().trim());
            if (minField.getText().equals(format(band.yMin())) && maxField.getText().equals(format(band.yMax())))
                return; // untouched: a focus change must not switch Auto off
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
                if (!minField.isFocusOwner())
                    minField.setText(format(band.yMin()));
                if (!maxField.isFocusOwner())
                    maxField.setText(format(band.yMax()));
            } finally {
                syncing = false;
            }
        });
    }

    private static String format(double v) {
        return Double.isFinite(v) ? String.format("%.5g", v) : "";
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

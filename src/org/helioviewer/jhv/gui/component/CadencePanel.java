package org.helioviewer.jhv.gui.component;

import java.awt.FlowLayout;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.time.TimeSelectorPanel;
import org.helioviewer.jhv.io.APIRequest;

@SuppressWarnings("serial")
public final class CadencePanel extends JPanel {

    private static final String[] timeStepUnits = {"sec", "min", "hours", "days", "get all"};
    private static final int CADENCE_MIN = 1, CADENCE_MAX = 10000;
    private static final int FRAMES_MIN = 1, FRAMES_MAX = 100_000;

    private final TimeSelectorPanel timeSelectorPanel;
    private final JHVSpinner cadenceSpinner = new JHVSpinner(1, CADENCE_MIN, CADENCE_MAX, 1);
    private final JComboBox<String> unitCombo = new JComboBox<>(timeStepUnits);
    private final JHVSpinner framesSpinner = new JHVSpinner(1, FRAMES_MIN, FRAMES_MAX, 1);
    private final JCheckBox byFramesCheck = new JCheckBox("Frame count");

    private boolean updating; // ponytail: reentrancy guard, the two spinners derive each other

    public CadencePanel(TimeSelectorPanel timeSelectorPanel) {
        this.timeSelectorPanel = timeSelectorPanel;
        setLayout(new FlowLayout(FlowLayout.TRAILING, 5, 0));

        applyCadenceValue(APIRequest.CADENCE_DEFAULT);
        unitCombo.setSelectedItem("min");
        unitCombo.addActionListener(e -> onCadenceEdited());
        cadenceSpinner.addChangeListener(e -> onCadenceEdited());
        ((JHVSpinner.DefaultEditor) cadenceSpinner.getEditor()).getTextField().setColumns(6);

        framesSpinner.addChangeListener(e -> onFramesEdited());
        ((JHVSpinner.DefaultEditor) framesSpinner.getEditor()).getTextField().setColumns(6);

        byFramesCheck.addActionListener(e -> setByFrames(byFramesCheck.isSelected()));
        timeSelectorPanel.addListener((start, end) -> resync());

        add(new JLabel("Time step", JLabel.RIGHT));
        add(cadenceSpinner);
        add(unitCombo);
        add(byFramesCheck);
        add(framesSpinner);

        setByFrames(false);
    }

    private void setByFrames(boolean byFrames) {
        cadenceSpinner.setEnabled(!byFrames && unitCombo.getSelectedIndex() != 4);
        unitCombo.setEnabled(!byFrames);
        framesSpinner.setEnabled(byFrames);
        resync();
    }

    private void resync() {
        if (byFramesCheck.isSelected())
            onFramesEdited();
        else
            onCadenceEdited();
    }

    // Cadence is the independent control; derive the live frame count from it.
    private void onCadenceEdited() {
        if (updating || byFramesCheck.isSelected())
            return;
        int cadence = getCadence();
        if (cadence == APIRequest.CADENCE_ALL) // ponytail: frame count is unknowable ahead of a "get all" fetch, leave last value
            return;

        updating = true;
        try {
            framesSpinner.setValue(Math.clamp(frameCount(cadence), FRAMES_MIN, FRAMES_MAX));
        } finally {
            updating = false;
        }
    }

    // Frame count is the independent control; derive the equivalent cadence from it.
    private void onFramesEdited() {
        if (updating || !byFramesCheck.isSelected())
            return;

        updating = true;
        try {
            applyCadenceValue(cadenceFromFrames((Integer) framesSpinner.getValue()));
        } finally {
            updating = false;
        }
    }

    private long frameCount(int cadenceSec) {
        long spanMs = Math.max(0, timeSelectorPanel.getEndTime() - timeSelectorPanel.getStartTime());
        return spanMs / (cadenceSec * 1000L) + 1;
    }

    private int cadenceFromFrames(int frames) {
        long spanMs = Math.max(0, timeSelectorPanel.getEndTime() - timeSelectorPanel.getStartTime());
        return (int) Math.max(1, spanMs / Math.max(1, frames - 1) / 1000);
    }

    // Returns the number of seconds of the selected cadence
    public int getCadence() {
        int value = (Integer) cadenceSpinner.getValue();
        return switch (unitCombo.getSelectedIndex()) {
            case 0 -> value; // sec
            case 1 -> value * 60; // min
            case 2 -> value * 3600; // hrs
            case 3 -> value * 86400; // days
            default -> APIRequest.CADENCE_ALL;
        };
    }

    public void setCadence(int value) {
        byFramesCheck.setSelected(false);
        applyCadenceValue(value);
        setByFrames(false);
    }

    private void applyCadenceValue(int value) {
        if (value == APIRequest.CADENCE_ALL) {
            unitCombo.setSelectedItem(timeStepUnits[4]);
            return;
        }

        if (value / 86400 != 0) {
            unitCombo.setSelectedItem(timeStepUnits[3]);
            cadenceSpinner.setValue(Math.clamp(value / 86400, CADENCE_MIN, CADENCE_MAX));
        } else if (value / 3600 != 0) {
            unitCombo.setSelectedItem(timeStepUnits[2]);
            cadenceSpinner.setValue(Math.clamp(value / 3600, CADENCE_MIN, CADENCE_MAX));
        } else if (value / 60 != 0) {
            unitCombo.setSelectedItem(timeStepUnits[1]);
            cadenceSpinner.setValue(Math.clamp(value / 60, CADENCE_MIN, CADENCE_MAX));
        } else {
            unitCombo.setSelectedItem(timeStepUnits[0]);
            cadenceSpinner.setValue(Math.clamp(value, CADENCE_MIN, CADENCE_MAX));
        }
    }

}

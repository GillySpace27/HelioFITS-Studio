package org.helioviewer.jhv.gui.dialog;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.helioviewer.jhv.identify.IdentifySettings;

/**
 * Settings &gt; Identify: the cursor identifier's numbers and its finer switches. The three main
 * switches are in the View menu under Show Status Bar; they are repeated here so this tab is the
 * whole picture.
 */
final class IdentifyTab {

    static JComponent build() {
        JCheckBox enabled = box("Identify under cursor (footer)", IdentifySettings.enabled(), IdentifySettings.ENABLED);
        JCheckBox tooltip = box("Show the name beside the pointer", IdentifySettings.tooltip(), IdentifySettings.TOOLTIP);
        JCheckBox stars = box("Include stars", IdentifySettings.stars(), IdentifySettings.STARS);
        JCheckBox iau = box("IAU proper names (Sirius, Vega)", IdentifySettings.starNamesIau(), IdentifySettings.STAR_NAMES_IAU);
        JCheckBox bayer = box("Bayer designations (α CMa), for the IAU-named stars", IdentifySettings.starNamesBayer(), IdentifySettings.STAR_NAMES_BAYER);
        JCheckBox pluto = box("Include Pluto", IdentifySettings.pluto(), IdentifySettings.PLUTO);
        JCheckBox light = box("<html>Correct for light travel time<br/><small>Where the body was when its light left it; about 0.005° for the Moon from Earth",
                IdentifySettings.lightTime(), IdentifySettings.LIGHT_TIME);
        IdentifySettings.addListener(() -> {
            enabled.setSelected(IdentifySettings.enabled());
            tooltip.setSelected(IdentifySettings.tooltip());
            stars.setSelected(IdentifySettings.stars());
        });

        return new SettingsForm()
                .row("Switches:", enabled)
                .row(null, tooltip)
                .row(null, stars)
                .row("Pointer tolerance (screen pixels):", spinner(IdentifySettings.tolerancePixels(), 0, 100, 1, IdentifySettings.TOLERANCE_PX))
                .row("Label delay (ms):", spinner(IdentifySettings.dwellMillis(), 0, 5000, 50, IdentifySettings.DWELL_MS))
                .row("<html>Near-Earth observer uncertainty (km)<br/><small>PUNCH mosaics place the observer at Earth's centre",
                        spinner(IdentifySettings.earthUncertaintyKm(), 0, 1e6, 100, IdentifySettings.EARTH_UNCERTAINTY_KM))
                .row("Bodies:", pluto)
                .row(null, light)
                .row("Star names:", iau)
                .row(null, bayer)
                .row("Faintest star (magnitude):", spinner(IdentifySettings.starMaxMagnitude(), -2, 12, 0.5, IdentifySettings.STAR_MAX_MAG))
                .row(null, new javax.swing.JLabel("<html><small>Stars without a name or designation are named by Hipparcos number. Both tables are fetched on first use:<br/>"
                        + org.helioviewer.jhv.layers.stars.StarCatalog.CREDIT + "<br/>" + org.helioviewer.jhv.layers.stars.StarNames.CREDIT));
    }

    private static JCheckBox box(String text, boolean value, String key) {
        JCheckBox b = new JCheckBox(text, value);
        b.addActionListener(e -> IdentifySettings.set(key, Boolean.toString(b.isSelected())));
        return b;
    }

    private static JSpinner spinner(double value, double min, double max, double step, String key) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        s.addChangeListener(e -> IdentifySettings.set(key, String.valueOf(((Number) s.getValue()).doubleValue())));
        return s;
    }

    private IdentifyTab() {}
}

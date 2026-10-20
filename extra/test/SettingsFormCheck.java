package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import org.helioviewer.jhv.io.HeliadLibrary;
import org.json.JSONObject;

/**
 * The Settings pages never draw two rows on one line, and the Heliad tab says what it found.
 *
 * <p>The single grid the tabs replaced numbered its rows by hand; three controls added later
 * reused rows 7 and 8, so "Download layers when saving a session" was drawn under the observer,
 * Sun and Earth radios and "Morph between coronagraph surfaces" under the first plugin
 * (screenshot of 2026-10-20). {@link SettingsForm} numbers its own rows; this lays one out with
 * the same mix of controls and checks that no two of them overlap.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.dialog.SettingsFormCheck
 */
public final class SettingsFormCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        JPanel time = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        for (String s : List.of("Observer", "Sun", "Earth"))
            time.add(new JRadioButton(s));
        SettingsForm form = new SettingsForm()
                .row("Preferred server:", new JComboBox<>(new String[]{"ROB", "GSFC"}))
                .row("Projection:", new JCheckBox("Crossfade between projections"))
                .row(null, new JCheckBox("Download layers when saving a session"))
                .row(null, new JCheckBox("Morph between coronagraph surfaces"))
                .row("Use time at:", time)
                .row("Plugins:", new JCheckBox("<html>Timelines<br/><small>Visualize 1D and 2D time series"))
                .row(null, new JCheckBox("<html>PFSS<br/><small>Visualize PFSS model data"))
                .wide(new JLabel("a table"))
                .end();
        Dimension size = form.getPreferredSize();
        form.setSize(size);
        form.doLayout();

        Component[] parts = form.getComponents();
        expect("every row and label is in the form (" + parts.length + " components)", parts.length == 3 + 1 + 1 + 1 + 2 + 2 + 1 + 1 + 1);
        expect("the form numbered " + form.rowCount() + " rows", form.rowCount() == 9);
        int overlaps = 0;
        for (int i = 0; i < parts.length; i++)
            for (int j = i + 1; j < parts.length; j++) {
                Rectangle a = parts[i].getBounds(), b = parts[j].getBounds();
                if (a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0 && a.intersects(b)) {
                    overlaps++;
                    System.out.println("       overlap: " + describe(parts[i]) + " and " + describe(parts[j]));
                }
            }
        expect("no two rows overlap", overlaps == 0);

        Rectangle download = find(parts, "Download layers when saving a session").getBounds();
        Rectangle radios = time.getBounds();
        expect("the observer, Sun and Earth row sits below the session row", radios.y >= download.y + download.height);
        Rectangle morph = find(parts, "Morph between coronagraph surfaces").getBounds();
        Rectangle plugin = find(parts, "<html>Timelines<br/><small>Visualize 1D and 2D time series").getBounds();
        expect("the first plugin sits below the morph row", plugin.y >= morph.y + morph.height);
        Rectangle server = find(parts, "Preferred server:").getBounds();
        expect("labels share one right edge", find(parts, "Plugins:").getBounds().x + find(parts, "Plugins:").getBounds().width
                == server.x + server.width);

        // The Heliad tab's wording, from what HeliadLibrary and heliad.hello return.
        List<HeliadLibrary.Dataset> sets = List.of(
                new HeliadLibrary.Dataset("sdo/aia/-/-/1/193", "SDO AIA 193 L1", "AIA", 193.0, 2, 3L << 20, 0, 1, 0),
                new HeliadLibrary.Dataset("punch/wfi/-/-/3/-", "PUNCH WFI L3", "WFI", null, 1, 1L << 20, 0, 1, 0));
        expect("library contents read as datasets, frames and size", HeliadSettings.holds(sets).equals("2 datasets, 3 frames, 4 MB"));
        expect("an empty library reads as empty", HeliadSettings.holds(List.of()).equals("0 datasets, 0 frames, 0 bytes"));
        Path root = Path.of("/data/HeliadLibrary");
        expect("a running core names its version",
                HeliadSettings.coreText(new JSONObject().put("version", "0.0.1").put("readonly", false).put("library_root", root.toString()), root)
                        .equals("Heliad 0.0.1 is running"));
        expect("a read-only core serving another library says both",
                HeliadSettings.coreText(new JSONObject().put("version", "0.0.1").put("readonly", true).put("library_root", "/elsewhere"), root)
                        .equals("Heliad 0.0.1 is running, read-only, serving another library: /elsewhere"));
        expect("no core means downloads stay in Studio's cache", HeliadSettings.downloadsText(false, 0).startsWith("Kept in Studio's cache only"));
        expect("a core means downloads go to the library", HeliadSettings.downloadsText(true, Long.MIN_VALUE)
                .equals("Studio adds each FITS download to this library"));
        expect("the Get Heliad link is https on gilly.space", HeliadSettings.GET_URL.startsWith("https://gilly.space/"));

        System.out.println(failures == 0 ? "SettingsFormCheck: all passed" : "SettingsFormCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Component find(Component[] parts, String text) {
        for (Component part : parts)
            if (part instanceof JLabel l && text.equals(l.getText()) || part instanceof JCheckBox b && text.equals(b.getText()))
                return part;
        throw new IllegalStateException("no component " + text);
    }

    private static String describe(Component part) {
        return part instanceof JLabel l ? "'" + l.getText() + "'" : part instanceof JCheckBox b ? "'" + b.getText() + "'" : part.getClass().getSimpleName();
    }

}

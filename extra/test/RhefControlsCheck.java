package org.helioviewer.jhv.layers.filters;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;

import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.gui.component.ToneCurve;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * RHEF's Υ controls (RH-17): greyed, not hidden, off RHEF, and then they cannot open; the sliders read
 * Shadows and Highlights to a screen reader; the Look menu has the defaults and Neutral; the curve
 * drawn beside them is the shader's.
 *
 * <p>Run (from the repository root): java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.filters.RhefControlsCheck
 */
public final class RhefControlsCheck {

    private static final String FIXTURE = "extra/test/data/mrzqs260301t2314c2308_169.fits"; // as UndoStackCheck

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c))
                out.add(type.cast(c));
            if (c instanceof Container k)
                out.addAll(all(k, type));
        }
        return out;
    }

    private static JHVSlider slider(Container root, String name) {
        for (JHVSlider s : all(root, JHVSlider.class))
            if (name.equals(s.getAccessibleContext().getAccessibleName()))
                return s;
        return null;
    }

    private static JLabel labelFor(Container root, Component target) {
        for (JLabel l : all(root, JLabel.class))
            if (l.getLabelFor() == target)
                return l;
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-rhef-controls").toString()); // never the real settings

        // The curve beside the sliders is the shader's own line.
        String frag = Files.readString(Path.of("resources/glsl/imageCommon.frag"), StandardCharsets.UTF_8);
        expect("imageCommon.frag still has the two-sided gamma ToneCurve.apply mirrors",
                frag.contains("v = v < .5 ? .5 * pow(2. * v, display.upsilon.x) : 1. - .5 * pow(2. - 2. * v, display.upsilon.y);"));
        expect("Neutral (1, 1) is the identity", Math.abs(ToneCurve.apply(.3, 1, 1) - .3) < 1e-12 && Math.abs(ToneCurve.apply(.8, 1, 1) - .8) < 1e-12);
        expect("both halves meet at the median", Math.abs(ToneCurve.apply(.5, .6, .4) - .5) < 1e-12);
        expect("shadows: 0.25 at 0.6 is 0.5 * 0.5^0.6", Math.abs(ToneCurve.apply(.25, .6, .4) - .5 * Math.pow(.5, .6)) < 1e-12);
        expect("highlights: 0.75 at 0.4 is 1 - 0.5 * 0.5^0.4", Math.abs(ToneCurve.apply(.75, .6, .4) - (1 - .5 * Math.pow(.5, .4))) < 1e-12);

        File fits = new File(FIXTURE);
        if (!fits.isFile()) {
            expect("fixture " + FIXTURE + " readable from the repository root (run from there)", false);
            System.exit(1);
        }
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        String uri = fits.getAbsoluteFile().toURI().toString();

        EventQueue.invokeAndWait(() -> {
            JSONObject data = new JSONObject().put("uris", new JSONArray().put(uri))
                    .put("imageParams", new JSONObject()).put("filter", "None");
            ImageLayer layer = ImageLayer.createDetached(data);
            ImageFilterPanel panel = new ImageFilterPanel(layer);
            var button = panel.upsilonButton;

            expect("off RHEF the Υ button is still shown", button.isVisible());
            expect("but greyed", !button.isEnabled());
            List<JButton> halves = all(button, JButton.class);
            expect("both of its halves are greyed", halves.size() == 2 && halves.stream().noneMatch(Component::isEnabled));
            expect("and it says why: \"" + ImageFilterPanel.UPSILON_OFF_TIP + "\"",
                    halves.stream().allMatch(h -> ImageFilterPanel.UPSILON_OFF_TIP.equals(h.getToolTipText())));
            halves.forEach(JButton::doClick);
            expect("a click on it opens nothing", !button.getPopupMenu().isVisible());

            layer.setFilter(ImageFilter.Type.RHEF);
            panel.syncFromLayer(layer);
            expect("on RHEF it is live", button.isEnabled() && halves.stream().allMatch(Component::isEnabled));
            expect("with its own tooltip back", ImageFilterPanel.UPSILON_TIP.equals(halves.getFirst().getToolTipText()));

            Container menu = button.getPopupMenu();
            JHVSlider shadows = slider(menu, "Shadows");
            JHVSlider highlights = slider(menu, "Highlights");
            expect("a slider a screen reader calls Shadows", shadows != null);
            expect("and one it calls Highlights", highlights != null);
            JLabel sl = shadows == null ? null : labelFor(menu, shadows);
            JLabel hl = highlights == null ? null : labelFor(menu, highlights);
            expect("Shadows is labelled \"Shadows (Υ low)\" (got " + (sl == null ? "none" : sl.getText()) + ")",
                    sl != null && sl.getText().strip().equals("Shadows (Υ low)"));
            expect("Highlights is labelled \"Highlights (Υ high)\" (got " + (hl == null ? "none" : hl.getText()) + ")",
                    hl != null && hl.getText().strip().equals("Highlights (Υ high)"));
            expect("the tone curve is beside them", !all(menu, ToneCurve.class).isEmpty());

            expect("two looks: the defaults and Neutral", ImageFilterPanel.LOOKS.length == 2
                    && ImageFilterPanel.LOOKS[0].low() == new org.helioviewer.jhv.image.ImageDisplaySettings().getUpsilonLow()
                    && ImageFilterPanel.LOOKS[1].low() == 1 && ImageFilterPanel.LOOKS[1].high() == 1);
            @SuppressWarnings("unchecked")
            JComboBox<ImageFilterPanel.Look> look = (JComboBox<ImageFilterPanel.Look>) all(menu, JComboBox.class).getFirst();
            look.setSelectedItem(ImageFilterPanel.LOOKS[1]);
            expect("choosing Neutral sets the layer to 1 / 1 (got " + layer.getDisplaySettings().getUpsilonLow() + " / "
                    + layer.getDisplaySettings().getUpsilonHigh() + ")",
                    layer.getDisplaySettings().getUpsilonLow() == 1 && layer.getDisplaySettings().getUpsilonHigh() == 1);
            look.setSelectedItem(ImageFilterPanel.LOOKS[0]);
            expect("and the default look puts the defaults back",
                    layer.getDisplaySettings().getUpsilonLow() == ImageFilterPanel.LOOKS[0].low()
                            && layer.getDisplaySettings().getUpsilonHigh() == ImageFilterPanel.LOOKS[0].high());
        });

        System.out.println(failures == 0 ? "RhefControlsCheck: PASS" : "RhefControlsCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}

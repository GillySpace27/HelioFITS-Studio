package org.helioviewer.jhv.layers.filters;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;

import org.helioviewer.jhv.gui.component.JHVSlider;
import org.helioviewer.jhv.image.FilterChain;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.RhefPeek;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The chain in the Filters panel and in the session: the add, weight, swap and remove controls change
 * the layer's chain the way the scope says (swap keeps the weight with its position), the chain is
 * written beside "filter" and read back, an older reader sees a single filter, and a held peek
 * can neither lose the chain nor save itself.
 *
 * <p>Run (from the repository root): java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.filters.ChainedFilterPanelCheck
 */
public final class ChainedFilterPanelCheck {

    private static final String FIXTURE = "extra/test/data/mrzqs260301t2314c2308_169.fits"; // as RhefControlsCheck

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

    private static JButton button(Container root, String name) {
        for (JButton b : all(root, JButton.class))
            if (name.equals(b.getAccessibleContext().getAccessibleName()))
                return b;
        return null;
    }

    private static JHVSlider slider(Container root, String name) {
        for (JHVSlider s : all(root, JHVSlider.class))
            if (name.equals(s.getAccessibleContext().getAccessibleName()))
                return s;
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-chain-panel").toString()); // never the real settings
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
            JSONObject data = new JSONObject().put("uris", new JSONArray().put(uri)).put("imageParams", new JSONObject()).put("filter", "None");
            ImageLayer layer = ImageLayer.createDetached(data);
            ImageFilterPanel panel = new ImageFilterPanel(layer);
            Container root = (Container) panel.getSecond();

            expect("with no filter there is no chain row and no add button", button(root, "Add filter in series") == null);
            layer.setFilter(ImageFilter.Type.RHEF);
            panel.syncFromLayer(layer);
            JButton add = button(root, "Add filter in series");
            expect("with a first filter there is an add button", add != null);

            add.doClick();
            expect("add puts a second filter in the layer's chain: " + layer.getFilterChain(), layer.getFilterChain().size() == 1
                    && layer.getFilterChain().stages().get(0).type() == ImageFilter.Type.MGN && layer.getFilterChain().stages().get(0).strength() == 0.5f);
            panel.syncFromLayer(layer);
            expect("the panel shows the row", slider(root, "Weight of filter in series 2") != null && button(root, "Swap order") != null);
            expect("one extra filter leaves room for another under the cap of three in all", button(root, "Add filter in series") != null);

            panel.syncFromLayer(layer);
            slider(root, "Weight of filter in series 2").setValue(60);
            expect("the weight slider sets the strength to 0.6: " + layer.getFilterChain(), layer.getFilterChain().stages().get(0).strength() == 0.6f);
            expect("RHEF stays the first filter, so the Υ button is live", layer.getFilter() == ImageFilter.Type.RHEF && panel.upsilonButton.isEnabled());

            panel.syncFromLayer(layer);
            button(root, "Add filter in series").doClick();
            panel.syncFromLayer(layer);
            expect("at three filters in all the add button is gone: " + layer.getFilterChain(),
                    layer.getFilterChain().size() == 2 && button(root, "Add filter in series") == null);
            List<JButton> removes = all(root, JButton.class).stream().filter(b -> "Remove filter in series".equals(b.getAccessibleContext().getAccessibleName())).toList();
            removes.get(removes.size() - 1).doClick(); // the added one, the second of the two rows
            panel.syncFromLayer(layer);
            expect("removing one brings it back", layer.getFilterChain().size() == 1 && button(root, "Add filter in series") != null);

            // the session form
            JSONObject saved = new JSONObject();
            layer.serialize(saved);
            expect("the session keeps filter as stage 1: " + saved.optString("filter"), "RHEF".equals(saved.optString("filter")));
            expect("and writes filterChain for the rest: " + saved.opt("filterChain"), saved.getJSONArray("filterChain").length() == 1
                    && "MGN".equals(saved.getJSONArray("filterChain").getJSONObject(0).getString("type")));
            ImageLayer back = ImageLayer.createDetached(saved);
            expect("a restored layer has the same filters", back.getFilter() == ImageFilter.Type.RHEF && back.getFilterChain().equals(layer.getFilterChain()));
            JSONObject older = new JSONObject(saved.toString());
            older.remove("filterChain");
            expect("a session from a build without chains is one filter", ImageLayer.createDetached(older).getFilterChain().isEmpty());
            JSONObject single = new JSONObject();
            ImageLayer.createDetached(data).serialize(single);
            expect("a single-filter layer writes no filterChain key", !single.has("filterChain"));

            // a held peek hides the filters but saves them
            RhefPeek.begin(layer);
            expect("a peek shows the layer without any filter", layer.getFilter() == ImageFilter.Type.None && layer.getFilterChain().isEmpty());
            JSONObject during = new JSONObject();
            layer.serialize(during);
            expect("and a save during it writes the filters, chain included", "RHEF".equals(during.optString("filter")) && during.has("filterChain"));
            RhefPeek.end(layer);
            expect("releasing puts back both: " + layer.getFilter() + " " + layer.getFilterChain(),
                    layer.getFilter() == ImageFilter.Type.RHEF && layer.getFilterChain().stages().get(0).strength() == 0.6f);

            // swap: the weight stays with its position
            panel.syncFromLayer(layer);
            button(root, "Swap order").doClick();
            expect("swap makes MGN first and RHEF second at the same 0.6: " + layer.getFilter() + " " + layer.getFilterChain(),
                    layer.getFilter() == ImageFilter.Type.MGN && layer.getFilterChain().stages().get(0).type() == ImageFilter.Type.RHEF
                            && layer.getFilterChain().stages().get(0).strength() == 0.6f);
            panel.syncFromLayer(layer);
            expect("the panel's row now offers RHEF", all(root, JComboBox.class).stream().anyMatch(c -> c.getSelectedItem() == ImageFilter.Type.RHEF));
            expect("RHEF second at 0.6 is a partial rank: " + layer.rankFraction(), Math.abs(layer.rankFraction() - 0.6) < 1e-6);
            button(root, "Swap order").doClick();
            expect("swap again restores the order", layer.getFilter() == ImageFilter.Type.RHEF && layer.getFilterChain().stages().get(0).type() == ImageFilter.Type.MGN);

            // remove, and None drops everything
            panel.syncFromLayer(layer);
            button(root, "Remove filter in series").doClick();
            expect("remove takes the stage out", layer.getFilterChain().isEmpty() && layer.getFilter() == ImageFilter.Type.RHEF);
            layer.setFilter(ImageFilter.Type.RHEF, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.WOW, 0.3f)));
            layer.setFilter(ImageFilter.Type.None);
            panel.syncFromLayer(layer);
            expect("None removes the chain and its rows", layer.getFilterChain().isEmpty() && button(root, "Add filter in series") == null);
        });

        System.out.println(failures == 0 ? "ChainedFilterPanelCheck: PASS" : "ChainedFilterPanelCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}

package org.helioviewer.jhv.layers.selector;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JScrollPane;

import org.helioviewer.jhv.gui.component.CollapsiblePane;
import org.helioviewer.jhv.gui.component.PassWheelScrollPane;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.view.uri.FITSSettings;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The sub-sections of an image layer's options fold, and a fold is a preference of the section.
 *
 * <p>Gilly, 2026-11-23: "allow the Image layers palette to have sub-collapsables". Display,
 * Intensity and Geometry used to be pinned open; the readout and the FITS clipping and scaling
 * had no fold at all. This pins: each of them has a chevron and a click hides its content; the
 * fold reaches a panel built for another layer, cached or new (LayerOptionSections calls
 * CollapsiblePane.followRemembered); reopening Intensity does not bring back the FITS rows of a
 * layer without FITS; and no scroll pane inside the options can trap the sidebar's wheel (#332).
 *
 * <p>Run (from the repository root): java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.selector.ImageLayerFoldsCheck
 */
public final class ImageLayerFoldsCheck {

    private static final String FIXTURE = "extra/test/data/mrzqs260301t2314c2308_169.fits"; // as RhefControlsCheck

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static <T extends Component> List<T> all(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(root))
            out.add(type.cast(root));
        if (root instanceof Container k)
            for (Component c : k.getComponents())
                out.addAll(all(c, type));
        return out;
    }

    private static CollapsiblePane section(Component root, String titleStart) {
        for (CollapsiblePane p : all(root, CollapsiblePane.class))
            if (p.getTitle().startsWith(titleStart))
                return p;
        return null;
    }

    /** Click the header as a user would. */
    private static void click(CollapsiblePane p) {
        p.actionPerformed(new java.awt.event.ActionEvent(p, java.awt.event.ActionEvent.ACTION_PERFORMED, "click"));
    }

    private static boolean contentShown(CollapsiblePane p) {
        return p.getComponentCount() > 1 && p.getComponent(1).isVisible();
    }

    private static ImageLayer layer(String uri) {
        JSONObject data = new JSONObject().put("uris", new JSONArray().put(uri)).put("imageParams", new JSONObject()).put("filter", "None");
        return ImageLayer.createDetached(data);
    }

    public static void main(String[] args) throws Exception {
        // The Levels row is a JHVRangeSlider, whose JIDE UI needs java.desktop/sun.swing exported
        // (JvmExportsCheck); run-checks.sh starts checks with -cp and no flags, so ask for them.
        if (System.getProperty("hfs.foldsChild") == null) {
            List<String> cmd = new ArrayList<>();
            cmd.add(System.getProperty("java.home") + "/bin/java");
            cmd.add("-Dhfs.foldsChild=1");
            if (Boolean.getBoolean("java.awt.headless"))
                cmd.add("-Djava.awt.headless=true");
            cmd.addAll(org.helioviewer.jhv.app.JvmExports.flags());
            cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), ImageLayerFoldsCheck.class.getName()));
            System.exit(new ProcessBuilder(cmd).inheritIO().start().waitFor());
        }
        java.nio.file.Path home = Files.createTempDirectory("hfs-layer-folds");
        System.setProperty("user.home", home.toString()); // never the real settings
        Files.createDirectories(home.resolve("HFStudio/Settings")); // a fold click writes its setting there
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
            org.helioviewer.jhv.gui.UIGlobals.setLaf(); // the fonts every section header reads; needs a display
            ImageLayer a = layer(uri);
            ImageLayerRenderingPanel rendering = new ImageLayerRenderingPanel(a, () -> {});
            ImageLayerGeometryPanel geometry = new ImageLayerGeometryPanel(a, () -> {});
            ImageLayerManagePanel manage = new ImageLayerManagePanel(a);

            for (String name : new String[]{"Display", "Intensity"}) {
                CollapsiblePane p = section(rendering, name);
                expect(name + " is a section", p != null);
                if (p == null)
                    continue;
                expect(name + " is foldable, not pinned open", !p.isPinned());
                expect(name + " starts open", contentShown(p));
                click(p);
                expect("a click on " + name + " hides its rows", !contentShown(p));
                click(p);
                expect("and a second click shows them again", contentShown(p));
            }
            CollapsiblePane geo = section(geometry, "Geometry");
            expect("Geometry is a section", geo != null);
            if (geo != null) {
                expect("Geometry is foldable, not pinned open", !geo.isPinned());
                boolean was = contentShown(geo);
                click(geo);
                expect("a click on Geometry flips it", contentShown(geo) != was);
            }

            CollapsiblePane fitsFold = section(rendering, "FITS clipping");
            expect("the FITS clipping and scaling rows have a fold of their own", fitsFold != null);
            if (fitsFold != null) {
                expect("inside Intensity", section(fitsFold.getParent(), "Intensity") == null
                        && all(section(rendering, "Intensity"), CollapsiblePane.class).contains(fitsFold));
                expect("holding the FITS settings", !all(fitsFold, FITSSettings.class).isEmpty());
            }

            CollapsiblePane obs = section(manage, "Observation");
            expect("the readout has a fold of its own", obs != null);
            if (obs != null) {
                expect("it starts open", contentShown(obs));
                click(obs);
                manage.updateReadout();
                expect("folded, its header keeps the first line (" + obs.getTitle() + ")", obs.getTitle().startsWith("Observation: "));
                click(obs);
                manage.updateReadout();
                expect("open, the header is the title alone", obs.getTitle().equals("Observation"));
            }

            // The fold is the section's, not the layer's.
            CollapsiblePane intensityA = section(rendering, "Intensity");
            if (intensityA != null)
                click(intensityA); // fold Intensity on layer a
            ImageLayer b = layer(uri);
            ImageLayerRenderingPanel renderingB = new ImageLayerRenderingPanel(b, () -> {});
            CollapsiblePane intensityB = section(renderingB, "Intensity");
            expect("a panel built after the click starts with Intensity folded", intensityB != null && !contentShown(intensityB));
            if (intensityA != null)
                click(intensityA); // open it again on a: b's panel is now stale
            if (intensityB != null) {
                CollapsiblePane.followRemembered(renderingB);
                expect("a cached panel follows the last click when it is shown again", contentShown(intensityB));
            }

            // Reopening a section must not resurrect rows its content hid on purpose.
            // The plane picker hides itself for a file with one image, as this fixture is.
            CollapsiblePane display = section(rendering, "Display");
            Component planeRow = null;
            try {
                java.lang.reflect.Field f = ImageLayerRenderingPanel.class.getDeclaredField("planePanel");
                f.setAccessible(true);
                planeRow = ((org.helioviewer.jhv.layers.filters.PlanePanel) f.get(rendering)).getSecond();
            } catch (ReflectiveOperationException e) {
                expect("the rendering panel has its plane row (" + e + ")", false);
            }
            if (planeRow != null && display != null) {
                boolean before = planeRow.isVisible();
                click(display);
                click(display);
                expect("reopening Display leaves the plane row as it decided (" + before + ")", planeRow.isVisible() == before);
                expect("which for this one-image file is hidden", !before);
            }

            // #332: nothing in the options may keep the wheel from the sidebar.
            for (Component root : new Component[]{rendering, geometry, manage}) {
                for (JScrollPane sp : all(root, JScrollPane.class))
                    expect("no scroll pane traps the wheel in " + root.getClass().getSimpleName() + " (" + sp.getClass().getSimpleName() + ")",
                            sp instanceof PassWheelScrollPane); // setWheelScrollingEnabled(false) does not pass it up
            }
        });

        System.out.println(failures == 0 ? "ImageLayerFoldsCheck: all ok" : "ImageLayerFoldsCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private ImageLayerFoldsCheck() {}
}

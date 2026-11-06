package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.plugins.PluginManager;
import org.helioviewer.jhv.plugins.pfss.PfssPlugin;
import org.helioviewer.jhv.plugins.swek.SWEKPlugin;

import java.util.List;

/**
 * A session state with no SWEK or PFSS entry (saved with the plugins off, or by an older build)
 * must not leave the overlay list without those rows while the plugins are active.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.PluginLayersRestoreCheck
 */
public final class PluginLayersRestoreCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static long count(String simpleName) {
        return Layers.getLayers().stream().filter(l -> l.getClass().getSimpleName().equals(simpleName)).count();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-plugin-layers").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        Directories.createPersistentDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice(); // the default layers reach SPICE

        PluginManager.addPlugin(new SWEKPlugin());
        PluginManager.addPlugin(new PfssPlugin());
        expect("plugins add their layers on install", count("SWEKLayer") == 1 && count("PfssLayer") == 1);

        Layers.restore(List.of());
        expect("restore of a state without them keeps SWEK", count("SWEKLayer") == 1);
        expect("restore of a state without them keeps PFSS", count("PfssLayer") == 1);

        Layers.restore(List.of());
        expect("and again, with no duplicates", count("SWEKLayer") == 1 && count("PfssLayer") == 1);

        Layers.restore(List.of(new org.helioviewer.jhv.plugins.pfss.PfssLayer(new org.json.JSONObject().put("detail", 1))));
        expect("a state that has PFSS replaces it, not duplicates it", count("PfssLayer") == 1);
        expect("and SWEK is still there", count("SWEKLayer") == 1);
        if (failures > 0)
            System.exit(1);
    }
}

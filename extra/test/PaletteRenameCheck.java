package org.helioviewer.jhv.gui.component;

import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.io.Directories;

/**
 * Renaming a palette keeps everything the user set on it.
 *
 * <p>A palette's state is keyed by its title. "Fourier filter" became "Filters" when RHEF moved in
 * beside the Fourier filter, and without carrying its keys across it would have come back undocked,
 * unfolded, and out of its place in the right sidebar: the dashboard forgetting how Gilly set it up,
 * which is the complaint this whole run of settings work began with.
 *
 * <p>The settings here are the relevant lines of his own file on 2026-09-14.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PaletteRenameCheck
 */
public final class PaletteRenameCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("jhv-palette-rename").toString());
        Path file = Path.of(Directories.SETTINGS.getPath(), "user.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n",
                "ui.palette.Fourier_filter=false",
                "ui.palette.Fourier_filter.shown=true",
                "ui.palette.Fourier_filter.sidebar=right",
                "ui.rightSidebarOrder=Projection|Track CME|HDR|Fourier filter|Grid|Annotation",
                "ui.section.rightSidebar.Fourier_filter=true", ""));
        org.helioviewer.jhv.io.DataSources.initSources();
        Settings.load();

        Palette.renameStored("Fourier filter", "Filters");
        expect("it still lives in the right sidebar", "right".equals(Settings.getProperty("ui.palette.Filters.sidebar")));
        expect("still shown", "true".equals(Settings.getProperty("ui.palette.Filters.shown")));
        expect("still not a floating window", "false".equals(Settings.getProperty("ui.palette.Filters")));
        expect("its section still unfolded", "true".equals(Settings.getProperty("ui.section.rightSidebar.Filters")));
        expect("and in the same place in the sidebar, between HDR and Grid",
                "Projection|Track CME|HDR|Filters|Grid|Annotation".equals(Settings.getProperty("ui.rightSidebarOrder")));

        // Once the new title has state of its own, a later launch must not put the old state back over it.
        Settings.setProperty("ui.palette.Filters.sidebar", "left");
        Palette.renameStored("Fourier filter", "Filters");
        expect("a move made after the rename survives the next launch", "left".equals(Settings.getProperty("ui.palette.Filters.sidebar")));

        System.out.println(failures == 0 ? "PaletteRenameCheck: PASS" : "PaletteRenameCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PaletteRenameCheck() {}
}

package org.helioviewer.jhv.gui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.Action;

import org.helioviewer.jhv.io.Directories;

/**
 * "Open Exports Folder" is a catalogued, searchable File menu command, and it resolves the folder
 * every export is written to, creating it when it is missing (issue #12).
 *
 * <p>Opening a file manager cannot be judged headless; this pins what can: the catalog entry, its
 * menu path, and the folder it would open. user.home is a temp directory, so nothing touches Gilly's
 * ~/HFStudio. The catalog reads the menu shortcut mask, so run-checks.sh runs this with a display.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.ExportsFolderCheck
 */
public final class ExportsFolderCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-exports-folder");
        System.setProperty("user.home", home.toString()); // before Directories loads: never the real ~/HFStudio
        org.helioviewer.jhv.app.Platform.init();

        File expected = home.resolve("HFStudio").resolve("Exports").toFile();
        expect("Directories.EXPORTS is under the temp home, got " + Directories.EXPORTS.getPath(),
                new File(Directories.EXPORTS.getPath()).getCanonicalFile().equals(expected.getCanonicalFile()));
        expect("the exports folder does not exist yet", !expected.exists());

        File got = ExportsFolder.ensure();
        expect("ensure() resolves the exports folder, got " + got,
                got != null && got.getCanonicalFile().equals(expected.getCanonicalFile()));
        expect("and creates it when it is missing", expected.isDirectory());
        File again = ExportsFolder.ensure();
        expect("a second call, with the folder there, returns the same folder",
                again != null && again.getCanonicalFile().equals(expected.getCanonicalFile()));

        ActionCatalog.Entry entry = ActionCatalog.get("openExportsFolder");
        expect("the action is catalogued as openExportsFolder", entry != null);
        if (entry != null) {
            expect("in the File menu, got \"" + entry.menuPath() + '"', entry.menuPath().equals("File/Open Exports Folder"));
            expect("named Open Exports Folder", "Open Exports Folder".equals(entry.action().getValue(Action.NAME)));
            expect("with a description search can show", !entry.description().isBlank());
        }

        System.out.println(failures == 0 ? "ExportsFolderCheck: ok" : "ExportsFolderCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}

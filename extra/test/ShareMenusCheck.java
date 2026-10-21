package org.helioviewer.jhv.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HS-16 handoffs are reachable from the menu bar, not only from Search Controls: every catalogued
 * command whose menu path starts with File/ or Edit/ is added to that menu by MenuBar, and the
 * dataset folders (DatasetLinks.rebuild) have a catalogued File menu entry of their own.
 *
 * <p>Building the actions reads the menu shortcut mask, which throws HeadlessException without a
 * display; run-checks.sh then reruns this with one (xvfb on CI), as for ActionCatalogCheck.
 *
 * <p>Run from the repository root, with a display (xvfb-run -a): java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.gui.ShareMenusCheck
 */
public final class ShareMenusCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path menuBar = Path.of("src/org/helioviewer/jhv/gui/component/MenuBar.java");
        if (!Files.isRegularFile(menuBar)) {
            System.out.println("SKIP: MenuBar.java not readable; run from the repository root");
            return;
        }
        System.setProperty("user.home", Files.createTempDirectory("hfs-share-menus").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();
        String src = Files.readString(menuBar);
        Set<String> inFile = asked(src, "fileMenu");
        Set<String> inEdit = asked(src, "editMenu");

        ActionCatalog.Entry folders = ActionCatalog.get("datasetFolders");
        expect("datasetFolders is catalogued under File", folders != null && folders.menuPath().startsWith("File/"));

        Set<String> anywhere = asked(src, "\\w+"); // submenus too, such as Open Recent
        Set<String> missing = new LinkedHashSet<>();
        for (ActionCatalog.Entry e : ActionCatalog.all())
            if ((e.menuPath().startsWith("File/") || e.menuPath().startsWith("Edit/")) && !anywhere.contains(e.id()))
                missing.add(e.id());
        expect("every File/ and Edit/ command is in its menu, missing " + missing, missing.isEmpty());
        for (String id : new String[]{"shareScene", "quickShare", "datasetFolders"})
            expect(id + " is in the File menu", inFile.contains(id));
        expect("copyFrame is in the Edit menu", inEdit.contains("copyFrame"));

        System.out.println(failures == 0 ? "ShareMenusCheck: ok" : "ShareMenusCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Set<String> asked(String src, String menu) {
        Matcher m = Pattern.compile("catalogItem\\(" + menu + ", \"(\\w+)\"\\)").matcher(src);
        Set<String> ids = new LinkedHashSet<>();
        while (m.find())
            ids.add(m.group(1));
        return ids;
    }
}

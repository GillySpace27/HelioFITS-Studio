package org.helioviewer.jhv.app;

import java.awt.Color;
import java.util.Map;

import org.json.JSONObject;

/**
 * Themes that ship in the jar: resources/themes/bundled.json reads, every entry it holds is listed as a
 * built-in under a non-user id, and a user theme turned into an entry loses its "user-" and keeps its colours.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.ThemeBundledCheck
 */
public final class ThemeBundledCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        expect("the bundled file is on the classpath", ThemeBundledCheck.class.getResource("/themes/bundled.json") != null);
        for (Theme t : Theme.all())
            if (!t.id().startsWith("user-") && t.parentId() != null)
                expect("bundled " + t.id() + " counts as built-in", t.builtIn());
        Theme parent = Theme.builtIns().getFirst();
        Theme mine = Theme.userTheme("user-quiet-dusk", "Quiet dusk", parent, Map.of(Theme.Token.Accent, new Color(0x123456)));
        JSONObject entry = new JSONObject(mine.toBundledJson());
        expect("an entry drops the user- prefix: " + entry.getString("id"), entry.getString("id").equals("quiet-dusk"));
        expect("an entry names its built-in and keeps the changed colour",
                entry.getString("base").equals(parent.id()) && entry.getJSONObject("colors").getString("Accent").equals("#123456"));
        expect("the user theme is still not a built-in", !mine.builtIn());
        System.out.println(failures == 0 ? "ThemeBundledCheck: all ok" : "ThemeBundledCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}

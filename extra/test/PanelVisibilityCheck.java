package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.io.Directories;

/**
 * Easier to find panels: on by default, legible in every built-in theme in both modes, numbered in
 * the order sections are laid out, and exactly the old look when switched off.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PanelVisibilityCheck
 */
public final class PanelVisibilityCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void legibleIn(Theme theme) {
        String n = theme.name() + (theme.base().dark ? " (dark)" : " (light)");
        Color panel = theme.get(Theme.Token.Background);
        Color base = theme.get(Theme.Token.HeaderFill);
        Color fill = PanelVisibility.headerFill(theme, false);
        Color child = PanelVisibility.headerFill(theme, true);
        Color text = PanelVisibility.headerText(theme, fill);
        expect(n + ": band at least as strong as the theme's own", Theme.contrast(fill, panel) >= Theme.contrast(base, panel) - 1e-9);
        expect(n + ": band clears 3:1 on the panel", Theme.contrast(fill, panel) >= 3);
        expect(n + ": title keeps 4.5:1 on the band", Theme.contrast(text, fill) >= 4.5);
        expect(n + ": nested band clears 3:1 and differs from its parent", Theme.contrast(child, panel) >= 3 && !child.equals(fill));
        expect(n + ": title keeps 4.5:1 on the nested band", Theme.contrast(PanelVisibility.headerText(theme, child), child) >= 4.5);
        expect(n + ": stripe clears 3:1 on the band", Theme.contrast(PanelVisibility.stripeColor(theme, fill), fill) >= 3);
        expect(n + ": hovered band keeps the title at 4.5:1", Theme.contrast(text, PanelVisibility.hoverFill(theme, fill)) >= 4.5);
    }

    private static CollapsiblePane section(String title) {
        return new CollapsiblePane(title, new JLabel(title), true);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-panel-visibility").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();

        // On by default, with nothing stored.
        expect("master is on by default", PanelVisibility.enabled());
        expect("every option is on by default", PanelVisibility.headers() && PanelVisibility.stripe()
                && PanelVisibility.numbers() && PanelVisibility.hover());

        List<Theme> themes = Theme.builtIns();
        expect("both a dark and a light built-in theme are covered",
                themes.stream().anyMatch(t -> t.base().dark) && themes.stream().anyMatch(t -> !t.base().dark));
        for (Theme theme : themes)
            legibleIn(theme);

        // The band is actually louder than the theme's own in at least the stock default.
        Theme def = Theme.byIdOrDefault(Theme.DEFAULT_ID);
        expect("default theme: band is stronger than before",
                Theme.contrast(PanelVisibility.headerFill(def, false), def.get(Theme.Token.Background))
                        > Theme.contrast(def.get(Theme.Token.HeaderFill), def.get(Theme.Token.Background)) + 0.2);
        expect("section gap and rule are larger", PanelVisibility.sectionGap() > 5 && PanelVisibility.separatorThickness() == 2);

        // A theme derived from an accent is held to the same rules.
        Theme parent = themes.getFirst();
        Map<Theme.Token, Color> derived = new EnumMap<>(Theme.derived(parent, new Color(0x2E8B57), null));
        legibleIn(Theme.userTheme("derived-check", "Derived", parent, derived));

        // Numbers follow the visible order, not creation order, and never touch the title.
        JPanel stack = new JPanel();
        CollapsiblePane a = section("Alpha"), b = section("Beta"), c = section("Gamma");
        stack.add(a);
        stack.add(b);
        stack.add(c);
        expect("sections are numbered 1, 2, 3", a.number() == 1 && b.number() == 2 && c.number() == 3);
        expect("getTitle is unchanged by numbering", a.getTitle().equals("Alpha"));
        b.setVisible(false);
        javax.swing.SwingUtilities.invokeAndWait(() -> { }); // component events are queued, not immediate
        expect("a hidden section drops out and the rest close ranks", a.number() == 1 && b.number() == 0 && c.number() == 2);
        b.setVisible(true);
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        stack.remove(c);
        stack.add(c, 0);
        expect("numbers follow layout order after a move", c.number() == 1 && a.number() == 2 && b.number() == 3);
        JPanel lone = new JPanel();
        CollapsiblePane only = section("Only");
        lone.add(only);
        expect("a section on its own is not numbered", only.number() == 0);
        CollapsiblePane nested = new CollapsiblePane("Nested", new JLabel("n"), true, true);
        expect("a nested section has no number", nested.number() == 0);

        // Off is the old look, exactly.
        Settings.setProperty(PanelVisibility.MASTER, "false");
        for (Theme theme : themes) {
            expect(theme.name() + ": off restores the theme's own band", PanelVisibility.headerFill(theme, false).equals(theme.get(Theme.Token.HeaderFill))
                    && PanelVisibility.headerFill(theme, true).equals(theme.get(Theme.Token.ChildHeaderFill)));
        }
        expect("off: 1px rule, 5px gap, no stripe, no number, no hover",
                PanelVisibility.separatorThickness() == 1 && PanelVisibility.sectionGap() == 5
                        && PanelVisibility.stripeWidth() == 0 && PanelVisibility.numberPrefix(2).isEmpty() && !PanelVisibility.hover());
        Settings.setProperty(PanelVisibility.MASTER, "true");
        Settings.setProperty(PanelVisibility.NUMBERS, "false");
        expect("one option off leaves the others on", !PanelVisibility.numbers() && PanelVisibility.headers() && PanelVisibility.stripe());
        Settings.setProperty(PanelVisibility.NUMBERS, "true");

        System.out.println(failures == 0 ? "PanelVisibilityCheck: PASS" : "PanelVisibilityCheck: " + failures + " FAIL");
        if (failures != 0)
            System.exit(1);
    }

}

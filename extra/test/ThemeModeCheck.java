package org.helioviewer.jhv.app;

/**
 * The appearance mode picks a theme of the kind it names, and an upgrade keeps the theme you had.
 *
 * <p>Dark and Light are a theme each rather than one theme and a switch, so the two settings can
 * disagree with the mode in ways nothing on screen could undo: a light theme recorded as the dark
 * choice would make Dark light, and there is no control that would let you fix it. Hence the kind
 * assertions here, and the refusal in Theme.choice() they pin.
 *
 * <p>The migration is the other half. An install from before this control has one theme recorded
 * and no mode, and the theme is the whole of what its user asked for: it must stay put rather than
 * be handed the desktop's preference on upgrade.
 *
 * <p>Reads settings, never writes them: a check must not edit the settings file of the application
 * it is checking.
 *
 * <p>Run: java -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.app.ThemeModeCheck
 */
public final class ThemeModeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        // Migration: no mode recorded, so the one theme that is recorded decides.
        expect("an install that was dark stays dark", Theme.parseMode(null, true) == Theme.Mode.Dark);
        expect("an install that was light stays light", Theme.parseMode(null, false) == Theme.Mode.Light);
        expect("and neither is quietly handed the desktop's preference",
                Theme.parseMode(null, true) != Theme.Mode.System && Theme.parseMode(null, false) != Theme.Mode.System);

        // A stated mode wins, whatever the theme is.
        expect("System is read back", Theme.parseMode("System", true) == Theme.Mode.System);
        expect("case does not matter", Theme.parseMode("system", false) == Theme.Mode.System);
        expect("nor does whitespace", Theme.parseMode("  Dark  ", false) == Theme.Mode.Dark);
        expect("Light over a dark theme is still Light", Theme.parseMode("Light", true) == Theme.Mode.Light);
        expect("an unreadable value falls back to the theme, not to a crash",
                Theme.parseMode("chartreuse", true) == Theme.Mode.Dark);

        // The kind invariant: whatever the settings say, each half is of its own kind.
        expect("the dark choice is a dark theme", Theme.darkChoice().dark());
        expect("the light choice is a light theme", !Theme.lightChoice().dark());
        expect("and they are not the same theme", !Theme.darkChoice().id().equals(Theme.lightChoice().id()));

        // Both defaults have to exist, or the fallbacks above fall through to whatever is first.
        expect("the default dark theme exists", Theme.byId(Theme.DEFAULT_ID) != null);
        expect("the default light theme exists", Theme.byId(Theme.DEFAULT_LIGHT_ID) != null);
        expect("the default dark theme is dark", Theme.byIdOrDefault(Theme.DEFAULT_ID).dark());
        expect("the default light theme is light", !Theme.byIdOrDefault(Theme.DEFAULT_LIGHT_ID).dark());

        // Every mode resolves to something, and the two fixed ones to their own kind.
        expect("effective() answers", Theme.effective() != null);

        if (System.getProperty("os.name", "").toLowerCase().contains("mac os")) {
            // The whole point of Follow system: it must be answerable here, or the option is a lie.
            expect("macOS reports its appearance", SystemAppearance.probe() != null);
            expect("so Follow system is offered", SystemAppearance.available());
        } else
            System.out.println("  --   not macOS: the desktop probe is not exercised here");

        System.out.println(failures == 0 ? "ThemeModeCheck: PASS" : "ThemeModeCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private ThemeModeCheck() {}

}

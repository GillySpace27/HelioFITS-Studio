package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JPanel;

import org.helioviewer.jhv.gui.ActionCatalog;

/**
 * The Welcome window is a launcher with a signpost to the help, not a page of text.
 *
 * <p>Model (no display): every help item names a catalogued command, a tour list or a docs link;
 * names are plain text; captions are short; every item has a full description; the state and the
 * primary action follow the rules in WelcomeModel.
 *
 * <p>Window (needs a display; run-checks.sh reruns it with one on HeadlessException): built for
 * each state, every button has a plain-text accessible name and a description, nothing visible is
 * HTML-only, the primary button is the right one, and the window holds far fewer words than the
 * wall of text it replaced.
 */
public final class WelcomeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** Commands a help item may name before they reach master: the tile is left out until then. */
    private static final Set<String> OPTIONAL = Set.of("keyboardShortcuts");

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-welcome").toString());
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        // ---- the model ----
        long help = WelcomeModel.ITEMS.stream().filter(it -> it.tier() == WelcomeModel.Tier.HELP).count();
        expect("four help tiles on screen, got " + help, help == 4);
        expect("User Manual is one of them, by that name",
                WelcomeModel.ITEMS.stream().anyMatch(it -> it.tier() == WelcomeModel.Tier.HELP && it.name().equals("User Manual")));
        for (WelcomeModel.Item it : WelcomeModel.ITEMS) {
            expect(it.name() + ": a plain-text name", !it.name().isBlank() && !it.name().contains("<"));
            expect(it.name() + ": a caption of at most " + WelcomeModel.CAPTION_MAX + " characters",
                    !it.caption().isBlank() && it.caption().length() <= WelcomeModel.CAPTION_MAX);
            expect(it.name() + ": a full description, longer than its caption", it.description().length() > it.caption().length());
            expect(it.name() + ": no em dash", !(it.name() + it.caption() + it.description()).contains(String.valueOf((char) 0x2014)));
        }
        expect("the footnote names tooltips and the Help menu",
                WelcomeModel.FOOTNOTE.contains("tooltip") && WelcomeModel.FOOTNOTE.contains("Help menu"));

        expect("never opened: the first run", WelcomeModel.state(null, null, "0.9.2") == WelcomeModel.State.FIRST_RUN);
        expect("opened in this version: returning", WelcomeModel.state("true", "0.9.2", "0.9.2") == WelcomeModel.State.RETURNING);
        expect("opened in an older version: updated", WelcomeModel.state("true", "0.9.1", "0.9.2") == WelcomeModel.State.UPDATED);
        expect("opened before the version was recorded: updated", WelcomeModel.state("true", null, "0.9.2") == WelcomeModel.State.UPDATED);

        expect("first run leads with the tour, whatever is open",
                WelcomeModel.primary(WelcomeModel.State.FIRST_RUN, false, true) == WelcomeModel.Primary.TOUR);
        expect("something on the canvas: Continue",
                WelcomeModel.primary(WelcomeModel.State.RETURNING, false, true) == WelcomeModel.Primary.CONTINUE);
        expect("empty canvas with a recent session: Reopen it",
                WelcomeModel.primary(WelcomeModel.State.UPDATED, true, true) == WelcomeModel.Primary.REOPEN);
        expect("empty canvas, no recents: Open Session",
                WelcomeModel.primary(WelcomeModel.State.RETURNING, true, false) == WelcomeModel.Primary.OPEN_SESSION);
        expect("captions on the first run only",
                WelcomeModel.showCaptions(WelcomeModel.State.FIRST_RUN) && !WelcomeModel.showCaptions(WelcomeModel.State.RETURNING));

        ZoneId utc = ZoneId.of("UTC");
        Instant now = Instant.parse("2026-11-10T20:00:00Z");
        expect("today", "today".equals(WelcomeDialog.when(Instant.parse("2026-11-10T01:00:00Z"), now, utc)));
        expect("yesterday", "yesterday".equals(WelcomeDialog.when(Instant.parse("2026-11-09T23:00:00Z"), now, utc)));
        expect("this year: Nov 6", "Nov 6".equals(WelcomeDialog.when(Instant.parse("2026-11-06T12:00:00Z"), now, utc)));
        expect("another year carries it", "Dec 30, 2025".equals(WelcomeDialog.when(Instant.parse("2025-12-30T12:00:00Z"), now, utc)));

        // ---- the catalog (throws HeadlessException without a display) ----
        for (WelcomeModel.Item it : WelcomeModel.ITEMS) {
            if (WelcomeModel.TOURS.equals(it.id()) || it.isLink() || OPTIONAL.contains(it.id()))
                continue;
            expect(it.name() + ": catalogued as " + it.id(), ActionCatalog.get(it.id()) != null);
        }

        // ---- the window, built for each state ----
        File recent = File.createTempFile("Recent ", ".jhv");
        File older = File.createTempFile("Older ", ".jhv");
        JDialog owner = new JDialog();
        for (WelcomeModel.State state : WelcomeModel.State.values()) {
            for (boolean empty : new boolean[] {true, false}) {
                String tag = state + (empty ? ", empty canvas" : ", something open");
                WelcomeDialog.Built built = WelcomeDialog.build(owner, state, empty, List.of(recent, older));
                List<AbstractButton> buttons = new ArrayList<>();
                StringBuilder words = new StringBuilder();
                walk(built.content(), buttons, words);
                for (AbstractButton b : buttons) {
                    String name = b.getAccessibleContext().getAccessibleName();
                    String desc = b.getAccessibleContext().getAccessibleDescription();
                    expect(tag + ": [" + name + "] has a plain accessible name", name != null && !name.isBlank() && !name.contains("<"));
                    expect(tag + ": [" + name + "] has a description", desc != null && !desc.isBlank());
                }
                String primary = built.primary().getAccessibleContext().getAccessibleName();
                WelcomeModel.Primary want = WelcomeModel.primary(state, empty, true);
                boolean right = switch (want) {
                    case TOUR -> primary.startsWith("Take the tour");
                    case CONTINUE -> primary.startsWith("Continue");
                    case REOPEN -> primary.equals("Reopen " + recent.getName());
                    case OPEN_SESSION -> primary.equals("Open Session");
                };
                expect(tag + ": primary is " + want + " (" + primary + ")", right);
                expect(tag + ": the recent behind Reopen is not listed twice",
                        want != WelcomeModel.Primary.REOPEN || buttons.stream().noneMatch(b -> ("Open " + recent.getName()).equals(b.getAccessibleContext().getAccessibleName())));
                int count = words.toString().trim().split("\\s+").length;
                // The window this replaced showed about 150 words of help text alone (0.9.2).
                expect(tag + ": at most 110 words on screen, got " + count, count <= 110);
                expect(tag + ": no Close button (Esc and the close box do it)",
                        buttons.stream().noneMatch(b -> "Close".equals(b.getText())));
            }
        }
        owner.dispose();

        System.out.println(failures == 0 ? "WelcomeCheck: ok" : "WelcomeCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Every button, and the words a sighted person sees (visible components only, HTML tags removed). */
    private static void walk(Component c, List<AbstractButton> buttons, StringBuilder words) {
        if (!c.isVisible())
            return;
        String text = null;
        if (c instanceof AbstractButton b) {
            buttons.add(b);
            text = b.getText();
        } else if (c instanceof javax.swing.JLabel l)
            text = l.getText();
        if (text != null)
            words.append(' ').append(text.replaceAll("<[^>]*>", " ").replace("&nbsp;", " "));
        if (c instanceof Container k && !(c instanceof javax.swing.JComboBox))
            for (Component child : k.getComponents())
                walk(child, buttons, words);
    }

    private WelcomeCheck() {}

}

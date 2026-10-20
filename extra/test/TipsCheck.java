package org.helioviewer.jhv.gui.dialog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.search.Tour;

/**
 * Tip of the Day (HS-10). Every tip in resources/data/tips.json reads from the classpath, has a
 * unique id, a title and text with no em dash. When it names an ActionCatalog id, that id is
 * catalogued and the text spells out its menu path ("File > Copy Provenance"), so a renamed menu
 * item fails here instead of leaving a tip that points at nothing. Its Show Me target is a name the
 * code assigns, its reveal is catalogued, and its tour is one of Tour.IDS. New-user tips come
 * first; a seen tip is not offered again until all have been; the startup switch, the once-a-day
 * rule and the snooze each hold. The Welcome window no longer carries a tip and links to this one;
 * the demo scene is catalogued under Help and MenuBar adds it.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.dialog.TipsCheck
 */
public final class TipsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-tips").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();

        List<Tips.Tip> tips = Tips.load();
        expect("tips.json reads from the classpath and holds at least ten tips (" + tips.size() + ")", tips.size() >= 10);
        Set<String> names = org.helioviewer.jhv.gui.search.TourCheck.assignedNames();
        Set<String> ids = new HashSet<>();
        for (Tips.Tip tip : tips) {
            String head = tip.id();
            expect("tip " + head + " has a unique id", ids.add(tip.id()) && tip.id().matches("[a-z0-9-]+"));
            expect("tip " + head + " has a title and text, and no em dash", !tip.title().isBlank() && !tip.text().isBlank()
                    && (tip.title() + tip.text()).indexOf(0x2014) < 0);
            if (tip.target() != null)
                expect("  its Show Me target " + tip.target() + " is a name the code assigns", names.contains(tip.target()));
            if (tip.reveal() != null)
                expect("  its reveal " + tip.reveal() + " is catalogued", ActionCatalog.get(tip.reveal()) != null);
            if (tip.tour() != null)
                expect("  its tour " + tip.tour() + " is one of Tour.IDS", Tour.IDS.contains(tip.tour()));
            if (tip.action() == null)
                continue;
            ActionCatalog.Entry entry = ActionCatalog.get(tip.action());
            expect("  its action " + tip.action() + " is catalogued", entry != null);
            if (entry != null && !entry.menuPath().isEmpty()) {
                String path = entry.menuPath().replace("/", " > ");
                // The whole item: "View > Present" must not pass inside "View > Presentation".
                boolean named = Pattern.compile(Pattern.quote(path) + "(?![\\p{L}\\p{N}])").matcher(tip.text()).find();
                expect("  and its text names the menu path \"" + path + "\"", named);
            }
        }
        long newUser = tips.stream().filter(Tips.Tip::newUser).count();
        expect("there are new-user tips (" + newUser + ")", newUser >= 5);
        boolean firstNew = true;
        for (int i = 0; i < tips.size(); i++)
            if (tips.get(i).newUser() != (i < newUser))
                firstNew = false;
        expect("load() puts every new-user tip ahead of the rest", firstNew);

        // Seen tracking, against a throwaway home's settings
        List<Tips.Tip> three = List.of(tip("a", false), tip("b", true), tip("c", false));
        List<Tips.Tip> order = Tips.ordered(three);
        expect("ordered: new-user first, then file order: " + order.stream().map(Tips.Tip::id).toList(),
                order.stream().map(Tips.Tip::id).toList().equals(List.of("b", "a", "c")));
        Settings.setProperty(Tips.SEEN_KEY, "");
        expect("nothing seen: the first tip", Tips.next(order) == 0);
        Tips.markSeen(order.get(0));
        expect("one seen: the next one", Tips.next(order) == 1);
        expect("what is seen is stored: " + Settings.getProperty(Tips.SEEN_KEY), "b".equals(Settings.getProperty(Tips.SEEN_KEY)));
        Tips.markSeen(order.get(1));
        Tips.markSeen(order.get(1));
        expect("seeing a tip twice stores it once", "b,a".equals(Settings.getProperty(Tips.SEEN_KEY)));
        expect("unseen count", Tips.unseenCount(order, Tips.seen()) == 1);
        Tips.markSeen(order.get(2));
        expect("all seen: a new round from the first", Tips.next(order) == 0 && Tips.seen().isEmpty());
        expect("a stored id no longer in the file is ignored", Tips.nextUnseen(order, Tips.parseSeen("zz, b ,,")) == 1);

        // Startup: on by default, once a day, not while snoozed, not when switched off
        LocalDate today = LocalDate.of(2026, 10, 20);
        expect("due at startup by default", Tips.dueAtStartup(today));
        Settings.setProperty(Tips.LAST_KEY, today.toString());
        expect("not twice in one day", !Tips.dueAtStartup(today));
        expect("due again the next day", Tips.dueAtStartup(today.plusDays(1)));
        Settings.setProperty(Tips.SNOOZE_KEY, today.plusDays(7).toString());
        expect("not while snoozed", !Tips.dueAtStartup(today.plusDays(3)));
        expect("due once the snooze ends", Tips.dueAtStartup(today.plusDays(7)));
        Settings.setProperty(Tips.SNOOZE_KEY, "garbage");
        expect("an unreadable snooze date is no snooze", Tips.dueAtStartup(today.plusDays(1)));
        Settings.setProperty(Tips.SHOW_KEY, "false");
        expect("not when switched off", !Tips.dueAtStartup(today.plusDays(30)));
        expect("snooze choices: tomorrow, a week, a month",
                Tips.SNOOZES.stream().map(Tips.Snooze::days).toList().equals(List.of(1, 7, 30)));

        ActionCatalog.Entry tipEntry = ActionCatalog.get("tipOfTheDay");
        expect("tipOfTheDay is catalogued under Help", tipEntry != null && tipEntry.menuPath().equals("Help/Tip of the Day..."));
        String menuBar = Files.readString(Path.of("src/org/helioviewer/jhv/gui/component/MenuBar.java"));
        expect("MenuBar adds tipOfTheDay", menuBar.contains("catalogItem(helpMenu, \"tipOfTheDay\")"));
        String welcome = Files.readString(Path.of("src/org/helioviewer/jhv/gui/dialog/WelcomeDialog.java"));
        expect("the Welcome window carries no tip of its own", !welcome.contains("Tips.next") && !welcome.contains("<b>Tip:</b>"));
        expect("the Welcome window links to Tip of the Day and brings it on after itself at startup",
                welcome.contains("ActionCatalog.get(\"tipOfTheDay\")") && welcome.contains("TipOfTheDay.atStartup()"));

        ActionCatalog.Entry demo = ActionCatalog.get("openDemo");
        expect("openDemo is catalogued under Help", demo != null && demo.menuPath().startsWith("Help/"));
        expect("MenuBar adds openDemo", menuBar.contains("catalogItem(helpMenu, \"openDemo\")"));
        expect("the Welcome window has the demo card", welcome.contains("run(\"openDemo\")"));

        System.out.println(failures == 0 ? "TipsCheck: ok" : "TipsCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Tips.Tip tip(String id, boolean newUser) {
        return new Tips.Tip(id, id, id, null, null, null, null, newUser);
    }

    private TipsCheck() {}

}

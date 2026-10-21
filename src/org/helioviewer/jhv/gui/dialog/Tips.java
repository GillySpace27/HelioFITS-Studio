package org.helioviewer.jhv.gui.dialog;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The tips Tip of the Day shows, from resources/data/tips.json, and which of them this person has
 * already seen.
 *
 * <p>Each tip has a stable {@code id}, a {@code title} and its {@code text}. It may also name:
 * {@code action}, the ActionCatalog id whose menu path its text spells out (TipsCheck holds the
 * text to that path, so a renamed menu item cannot leave a tip pointing at nothing);
 * {@code target}, a component to spotlight with Show Me, as a tour step does, and {@code reveal},
 * a catalog id run first to bring it on screen; {@code tour}, a tour to offer; and {@code newUser},
 * true for the tips a first-time user should see first.
 *
 * <p>What has been seen lives in this computer's settings for this account (the HFStudio folder in
 * the home folder), so it is per person and per machine, and it survives updates. The order is
 * the new-user tips first, then the rest, each in file order; a tip seen once is not shown again
 * until every tip has been, and then the round starts over.
 */
final class Tips {

    /** The ids already seen, comma separated, in the order they were seen. */
    static final String SEEN_KEY = "tips.seen";
    /** "false" keeps Tip of the Day from opening at startup; Help > Tip of the Day still opens it. */
    static final String SHOW_KEY = "tips.atStartup";
    /** The last date (ISO) a tip opened at startup: at most one a day. */
    static final String LAST_KEY = "tips.lastShown";
    /** A date (ISO) before which no tip opens at startup. */
    static final String SNOOZE_KEY = "tips.snoozeUntil";

    /** The snooze choices: a label and how many days each puts the next startup tip off. */
    record Snooze(String label, int days) {}

    static final List<Snooze> SNOOZES = List.of(new Snooze("Until tomorrow", 1), new Snooze("For a week", 7), new Snooze("For a month", 30));

    record Tip(String id, String title, String text, @Nullable String action, @Nullable String target,
               @Nullable String reveal, @Nullable String tour, boolean newUser) {}

    static List<Tip> parse(JSONObject jo) {
        JSONArray array = jo.getJSONArray("tips");
        List<Tip> tips = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject t = array.getJSONObject(i);
            tips.add(new Tip(t.getString("id"), t.getString("title"), t.getString("text"),
                    opt(t, "action"), opt(t, "target"), opt(t, "reveal"), opt(t, "tour"), t.optBoolean("newUser")));
        }
        return tips;
    }

    @Nullable
    private static String opt(JSONObject jo, String key) {
        return jo.has(key) ? jo.getString(key) : null;
    }

    static List<Tip> load() {
        try (InputStream is = FileUtils.getResource("/data/tips.json")) {
            return ordered(parse(JSONUtils.get(is)));
        } catch (IOException | RuntimeException e) { // TipsCheck keeps the file readable; never a crash here
            Log.warn("Tips could not be read", e);
            return List.of();
        }
    }

    /** The tips in the order they are offered: new-user tips first, then the rest, each in file order. */
    static List<Tip> ordered(List<Tip> tips) {
        List<Tip> out = new ArrayList<>(tips.size());
        for (Tip t : tips)
            if (t.newUser())
                out.add(t);
        for (Tip t : tips)
            if (!t.newUser())
                out.add(t);
        return out;
    }

    static Set<String> parseSeen(@Nullable String stored) {
        Set<String> seen = new LinkedHashSet<>();
        if (stored != null)
            for (String id : stored.split(","))
                if (!id.isBlank())
                    seen.add(id.strip());
        return seen;
    }

    /** The index in {@code ordered} of the first tip not in {@code seen}; -1 when all have been seen or there are none. */
    static int nextUnseen(List<Tip> ordered, Set<String> seen) {
        for (int i = 0; i < ordered.size(); i++)
            if (!seen.contains(ordered.get(i).id()))
                return i;
        return -1;
    }

    /** How many of {@code ordered} are not in {@code seen}. */
    static int unseenCount(List<Tip> ordered, Set<String> seen) {
        int n = 0;
        for (Tip t : ordered)
            if (!seen.contains(t.id()))
                n++;
        return n;
    }

    static Set<String> seen() {
        return parseSeen(Settings.getProperty(SEEN_KEY));
    }

    /** The index of the next tip to show, starting a new round when every tip has been seen; -1 when there are none. */
    static int next(List<Tip> ordered) {
        if (ordered.isEmpty())
            return -1;
        int i = nextUnseen(ordered, seen());
        if (i < 0) { // every tip seen: a new round, from the first
            Settings.setProperty(SEEN_KEY, "");
            i = 0;
        }
        return i;
    }

    /** Remember {@code tip} as seen by this person on this computer. */
    static void markSeen(Tip tip) {
        Set<String> seen = seen();
        if (seen.add(tip.id()))
            Settings.setProperty(SEEN_KEY, String.join(",", seen));
    }

    /** Whether a tip may open by itself at startup on {@code today}: switched on, not snoozed, not already shown that day. */
    static boolean dueAtStartup(LocalDate today) {
        if ("false".equals(Settings.getProperty(SHOW_KEY)))
            return false;
        LocalDate snooze = date(Settings.getProperty(SNOOZE_KEY));
        if (snooze != null && today.isBefore(snooze))
            return false;
        LocalDate last = date(Settings.getProperty(LAST_KEY));
        return last == null || last.isBefore(today);
    }

    @Nullable
    static LocalDate date(@Nullable String iso) {
        if (iso == null || iso.isBlank())
            return null;
        try {
            return LocalDate.parse(iso.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Tips() {}

}

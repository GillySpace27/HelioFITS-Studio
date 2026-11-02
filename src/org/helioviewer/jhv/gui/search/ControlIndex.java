package org.helioviewer.jhv.gui.search;

import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.AbstractButton;
import javax.swing.JLabel;

import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.registry.ControlRegistry;

/**
 * What Help > Search Controls can find, and how a query ranks it.
 *
 * <p>Two kinds of result: every command that takes no arguments (choosing one runs it), and every
 * named control the tour can spotlight (choosing one reveals and spotlights it). Both come from
 * {@link ControlRegistry}; the controls are listed in resources/data/controls.json with a title,
 * where they live and a few words to match; when the control has
 * been built, its live text, tooltip and accessible name are added to what it matches.
 * ControlSearchCheck holds every listed id to a
 * name the code assigns and every assigned name to a listing, so a new named control is either
 * listed or shown failing.
 *
 * <p>v1 covers the main window, the sidebar sections and the palettes; not dialogs that are
 * closed, and not controls the code does not name (projection mode radio buttons, layer rows).
 */
public final class ControlIndex {

    /** One search result. {@code reveal} is an ActionCatalog id run before a control is spotlit. */
    record Item(boolean action, String id, String title, String where, String words, @Nullable String reveal, String shortcut) {
        Item(boolean action, String id, String title, String where, String words, @Nullable String reveal) {
            this(action, id, title, where, words, reveal, "");
        }
    }

    /** The ids of the listed controls (resources/data/controls.json), in order. */
    static List<String> controlIds() {
        List<String> ids = new ArrayList<>();
        for (ControlRegistry.Entry e : ControlRegistry.all())
            if (e.kind() == ControlRegistry.Kind.CONTROL)
                ids.add(e.id());
        return ids;
    }

    /**
     * Every catalogued command that runs without arguments, then every listed control; nothing read from the
     * window. Commands that need arguments (CommandRegistry) are for programs, not for a click.
     */
    static List<Item> base() {
        List<Item> items = new ArrayList<>();
        for (ControlRegistry.Entry e : ControlRegistry.all()) {
            if (e.kind() == ControlRegistry.Kind.COMMAND) {
                if (e.id().equals(CommandPalette.ID) || e.command().needsArguments() || ActionCatalog.get(e.id()) == null)
                    continue; // the search does not offer itself, nor a command for programs (arguments, or no menu or button behind it)
                items.add(new Item(true, e.id(), e.title(), menu(e.where(), e.title()), e.words(), null, e.shortcut()));
            }
        }
        for (ControlRegistry.Entry e : ControlRegistry.all())
            if (e.kind() == ControlRegistry.Kind.CONTROL)
                items.add(new Item(false, e.id(), e.title(), e.where(), e.words(), e.reveal(), ""));
        return items;
    }

    /** base(), with each built control's own text and tooltip added to what it matches. Rebuilt per open. */
    static List<Item> build() {
        List<Item> items = new ArrayList<>();
        for (Item it : base()) {
            if (it.action()) {
                items.add(it);
                continue;
            }
            Component c = anywhere(it.id());
            String live = c == null ? "" : liveText(c);
            items.add(live.isEmpty() ? it : new Item(false, it.id(), it.title(), it.where(), it.words() + " " + live, it.reveal(), it.shortcut()));
        }
        return items;
    }

    @Nullable
    private static Component anywhere(String id) {
        for (Window w : Window.getWindows()) {
            Component hit = ActionCatalog.find(w, id);
            if (hit != null)
                return hit;
        }
        return null;
    }

    /** The built control's tooltip, as plain text, or "". */
    static String tip(String id) {
        Component c = anywhere(id);
        String t = c instanceof JComponent j ? j.getToolTipText() : null;
        return t == null ? "" : t.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    private static String liveText(Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof AbstractButton b && b.getText() != null)
            sb.append(b.getText()).append(' ');
        if (c instanceof JLabel l && l.getText() != null)
            sb.append(l.getText()).append(' ');
        if (c instanceof JComponent j && j.getToolTipText() != null)
            sb.append(j.getToolTipText()).append(' ');
        if (c.getAccessibleContext() != null && c.getAccessibleContext().getAccessibleName() != null)
            sb.append(c.getAccessibleContext().getAccessibleName());
        return sb.toString().replaceAll("<[^>]*>", " ").trim(); // tooltips may be HTML
    }

    /** "Movie/Play/Pause Movie" with title "Play/Pause Movie" reads "Movie"; no menu reads "Command". */
    static String menu(String menuPath, String title) {
        if (menuPath.isEmpty())
            return "Command";
        String parent = menuPath;
        int cut = menuPath.lastIndexOf('/' + title);
        if (cut > 0 && cut + 1 + title.length() == menuPath.length())
            parent = menuPath.substring(0, cut);
        else {
            int slash = menuPath.indexOf('/');
            parent = slash > 0 ? menuPath.substring(0, slash) : menuPath;
        }
        return parent.replace("/", " > ") + " menu";
    }

    /** A menu label without its trailing ellipsis. */
    static String plain(String label) {
        String s = label.strip();
        if (s.endsWith("..."))
            s = s.substring(0, s.length() - 3);
        else if (s.endsWith("…"))
            s = s.substring(0, s.length() - 1);
        return s.strip();
    }

    /**
     * The items matching {@code query}, best first. Every word of the query must match the title,
     * where, words or id of an item. A whole-title match ranks first, then a title that starts with
     * the query, then words matched at the start of a title word, then inside the title, then the
     * same in the other fields. Ties keep index order: commands before controls. An empty query
     * returns everything.
     */
    static List<Item> search(String query, List<Item> items) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty())
            return List.copyOf(items);
        String[] tokens = q.split("\\s+");
        List<Item> hits = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Item it : items) {
            int s = score(q, tokens, it);
            if (s > 0) {
                hits.add(it);
                scores.add(s);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++)
            order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> -scores.get(i)).thenComparingInt(i -> i)); // stable
        List<Item> out = new ArrayList<>(hits.size());
        for (int i : order)
            out.add(hits.get(i));
        return out;
    }

    static int score(String q, String[] tokens, Item it) {
        String title = it.title().toLowerCase(Locale.ROOT);
        String rest = (it.where() + ' ' + it.words() + ' ' + it.id()).toLowerCase(Locale.ROOT);
        int total = 0;
        for (String t : tokens) {
            int s;
            if (wordStart(title, t))
                s = 40;
            else if (title.contains(t))
                s = 25;
            else if (wordStart(rest, t))
                s = 15;
            else if (rest.contains(t))
                s = 8;
            else
                return 0; // every word must match somewhere
            total += s;
        }
        if (title.equals(q))
            total += 100;
        else if (title.startsWith(q))
            total += 50;
        return total;
    }

    /** {@code t} begins {@code s} or a word of it (after a space or punctuation). */
    static boolean wordStart(String s, String t) {
        for (int i = s.indexOf(t); i >= 0; i = s.indexOf(t, i + 1))
            if (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))
                return true;
        return false;
    }

    private ControlIndex() {}
}

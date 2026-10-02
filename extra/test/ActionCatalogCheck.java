package org.helioviewer.jhv.gui;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;

/**
 * The action catalog names every command once, and every name it hands out resolves.
 *
 * <p>Help search, guided tours, undo and VoiceOver key on these ids (HS-8, HS-15), and the toolbar's
 * ids are persisted in user settings, so a duplicate, a command left out, or a menu asking for an
 * id nobody registered is a break somebody else finds later. Constructing the actions reads the
 * platform's menu shortcut mask, which throws HeadlessException without a display; run-checks.sh
 * then reruns this with one (xvfb on CI).
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.gui.ActionCatalogCheck
 */
public final class ActionCatalogCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String constant(Class<?> owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-action-catalog").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();

        int first = ActionCatalog.all().size();
        ActionCatalog.register();
        expect("register() is idempotent: " + first + " entries, then " + ActionCatalog.all().size(),
                first > 0 && ActionCatalog.all().size() == first);

        Set<String> ids = new LinkedHashSet<>();
        Set<String> badIds = new LinkedHashSet<>();
        Set<Action> instances = new HashSet<>();
        Set<Class<?>> catalogued = new HashSet<>();
        for (ActionCatalog.Entry e : ActionCatalog.all()) {
            ids.add(e.id());
            if (!e.id().matches("[a-z][A-Za-z0-9]*"))
                badIds.add(e.id());
            instances.add(e.action());
            catalogued.add(e.action().getClass());
        }
        expect("ids are camelCase, got " + badIds, badIds.isEmpty());
        expect("one Action instance per id: " + ids.size() + " ids, " + instances.size() + " instances",
                instances.size() == ids.size());
        boolean refused = false;
        try {
            ActionCatalog.put("zoomIn", new Actions.ZoomIn(), "", "");
        } catch (IllegalStateException e) {
            refused = true;
        }
        expect("a duplicate id is refused", refused);

        // Every public command class in Actions is catalogued, except the per-use ones.
        Set<String> missing = new LinkedHashSet<>();
        Set<String> classes = new HashSet<>();
        for (Class<?> c : Actions.class.getDeclaredClasses()) {
            int m = c.getModifiers();
            if (!Modifier.isPublic(m) || Modifier.isAbstract(m) || !Action.class.isAssignableFrom(c))
                continue;
            classes.add(c.getSimpleName());
            if (!ActionCatalog.PER_USE.contains(c.getSimpleName()) && !catalogued.contains(c))
                missing.add(c.getSimpleName());
        }
        expect("every public Actions class is catalogued, missing " + missing, missing.isEmpty());
        expect("PER_USE names classes that exist: " + ActionCatalog.PER_USE, classes.containsAll(ActionCatalog.PER_USE));

        // Every toolbar id is a catalogued command or a named toolbar control, never both, never stale.
        // ToolbarOrder is package-private to gui.component, so its constants are read reflectively.
        Class<?> toolbarOrder = Class.forName("org.helioviewer.jhv.gui.component.ToolbarOrder");
        List<String> gaps = List.of(constant(toolbarOrder, "SEPARATOR"), constant(toolbarOrder, "MORE_DIVIDER"));
        Set<String> order = new LinkedHashSet<>(List.of(constant(toolbarOrder, "DEFAULT_ORDER").split("\\|")));
        order.removeAll(gaps);
        Set<String> unresolved = new LinkedHashSet<>();
        for (String id : order)
            if (!ids.contains(id) && !ActionCatalog.TOOLBAR_CONTROLS.contains(id))
                unresolved.add(id);
        expect("every DEFAULT_ORDER id resolves, unresolved " + unresolved, unresolved.isEmpty());
        Set<String> both = new LinkedHashSet<>(ActionCatalog.TOOLBAR_CONTROLS);
        both.retainAll(ids);
        expect("no toolbar control is also a command id, both " + both, both.isEmpty());
        Set<String> stale = new LinkedHashSet<>(ActionCatalog.TOOLBAR_CONTROLS);
        stale.removeAll(order);
        expect("every TOOLBAR_CONTROLS id is in DEFAULT_ORDER, stale " + stale, stale.isEmpty());

        // Every id MenuBar asks for is registered (it throws at startup otherwise).
        Path menuBar = Path.of("src/org/helioviewer/jhv/gui/component/MenuBar.java");
        if (!Files.isRegularFile(menuBar)) {
            expect("MenuBar.java readable from the repository root (run from there)", false);
        } else {
            Matcher m = Pattern.compile("catalog(?:Item\\(\\w+, |Action\\()\"(\\w+)\"").matcher(Files.readString(menuBar));
            Set<String> asked = new LinkedHashSet<>();
            while (m.find())
                asked.add(m.group(1));
            Set<String> unknown = new LinkedHashSet<>(asked);
            unknown.removeAll(ids);
            expect("MenuBar names " + asked.size() + " catalog ids, unknown " + unknown, unknown.isEmpty());
        }

        // find() walks panels and menus by component name.
        JPanel root = new JPanel();
        JPanel inner = new JPanel();
        root.add(inner);
        JMenuBar bar = new JMenuBar();
        inner.add(bar);
        JMenu menu = new JMenu("File");
        bar.add(menu);
        JMenuItem item = new JMenuItem("Start New Session");
        item.setName("newSession");
        menu.add(item);
        expect("find() reaches a menu item by its id", ActionCatalog.find(root, "newSession") == item);
        expect("find() answers null for an id nothing carries", ActionCatalog.find(root, "noSuchId") == null);

        System.out.println(failures == 0 ? "ActionCatalogCheck: ok" : "ActionCatalogCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}

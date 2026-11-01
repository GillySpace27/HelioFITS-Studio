package org.helioviewer.jhv.gui.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.helioviewer.jhv.gui.ActionCatalog;

import org.json.JSONObject;

/**
 * The command registry: every catalogued action is a command, a call is checked against its schema
 * before it runs, Dispatch answers rather than throws, the controls file resolves, and
 * docs/commands.json (the registry as an MCP tools list) is current. Building the catalog needs a
 * display; run-checks.sh reruns this with one.
 */
public final class RegistryCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JSONObject o(String json) {
        return new JSONObject(json);
    }

    public static void main(String[] args) throws Exception {
        // Schema, on made-up schemas
        JSONObject s = o("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9},"
                + "\"axis\":{\"type\":\"string\",\"enum\":[\"X\",\"Y\"]},\"f\":{\"type\":\"number\"},\"b\":{\"type\":\"boolean\"}},"
                + "\"required\":[\"n\"],\"additionalProperties\":false}");
        expect("schema: a good call has no problems", Schema.validate(s, o("{\"n\":3,\"axis\":\"X\",\"f\":1.5,\"b\":true}")).isEmpty());
        expect("schema: a missing required argument is named", Schema.validate(s, o("{}")).toString().contains("'n'"));
        expect("schema: a string where an integer belongs", !Schema.validate(s, o("{\"n\":\"3\"}")).isEmpty());
        expect("schema: a fraction where an integer belongs", !Schema.validate(s, o("{\"n\":3.5}")).isEmpty());
        expect("schema: below the minimum", !Schema.validate(s, o("{\"n\":-1}")).isEmpty());
        expect("schema: above the maximum", !Schema.validate(s, o("{\"n\":10}")).isEmpty());
        expect("schema: outside the enum", !Schema.validate(s, o("{\"n\":1,\"axis\":\"Z\"}")).isEmpty());
        expect("schema: an unknown argument when additionalProperties is false", !Schema.validate(s, o("{\"n\":1,\"zz\":1}")).isEmpty());
        expect("schema: grammar of the type message: " + Schema.validate(s, o("{\"n\":\"3\"}")), Schema.validate(s, o("{\"n\":\"3\"}")).toString().contains("an integer"));
        expect("schema: a bad boolean", !Schema.validate(s, o("{\"n\":1,\"b\":\"yes\"}")).isEmpty());

        // The real registry (a display is needed from here)
        org.helioviewer.jhv.app.Platform.init();
        Set<String> actionIds = new LinkedHashSet<>();
        for (ActionCatalog.Entry e : ActionCatalog.all())
            actionIds.add(e.id());
        Set<String> commandIds = new LinkedHashSet<>();
        for (CommandSpec c : CommandRegistry.all())
            commandIds.add(c.id());
        expect("every catalogued action is a command, missing " + diff(actionIds, commandIds), commandIds.containsAll(actionIds));
        Set<String> blank = new LinkedHashSet<>();
        for (CommandSpec c : CommandRegistry.all())
            if (c.description().isBlank() || c.input() == null || c.output() == null)
                blank.add(c.id());
        expect("every command has a description and both schemas, without " + blank, blank.isEmpty());
        Set<String> classified = new LinkedHashSet<>(CommandRegistry.EFFECTS.keySet());
        classified.addAll(CommandRegistry.UI_ONLY);
        Set<String> unclassified = new LinkedHashSet<>(actionIds);
        unclassified.removeAll(classified);
        expect("every catalogued action has an effect (add it to EFFECTS or UI_ONLY), missing " + unclassified, unclassified.isEmpty());
        expect("no id is in both EFFECTS and UI_ONLY", java.util.Collections.disjoint(CommandRegistry.EFFECTS.keySet(), CommandRegistry.UI_ONLY));
        Set<String> unknownEffects = new LinkedHashSet<>(classified);
        unknownEffects.removeAll(actionIds);
        expect("every id in the effect tables is a catalogued action, stale " + unknownEffects, unknownEffects.isEmpty());

        // Dispatch
        expect("dispatch: an unknown id", "unknown-command".equals(Dispatch.invoke("noSuchCommand", null).error()));
        Dispatch.Result missing = Dispatch.invoke("seekFrame", o("{}"));
        expect("dispatch: a missing argument is refused before the handler: " + missing.message(), "invalid-arguments".equals(missing.error()));
        expect("dispatch: a negative frame is refused", "invalid-arguments".equals(Dispatch.invoke("seekFrame", o("{\"frame\":-2}")).error()));
        expect("dispatch: an unknown argument is refused", "invalid-arguments".equals(Dispatch.invoke("playPause", o("{\"x\":1}")).error()));
        expect("dispatch: a bad axis is refused", "invalid-arguments".equals(Dispatch.invoke("rotateView90", o("{\"axis\":\"Q\"}")).error()));
        Dispatch.Result badTime = Dispatch.invoke("seekTime", o("{\"time\":\"not a time\"}"));
        expect("dispatch: an unparsable time is invalid-arguments, not a throw: " + badTime.error() + " " + badTime.message(),
                "invalid-arguments".equals(badTime.error()));
        Dispatch.Result ok = Dispatch.invoke("describeControl", o("{\"id\":\"searchControls\"}"));
        expect("dispatch: a good call runs and returns its result: " + ok.error() + " " + ok.value(),
                ok.ok() && ok.value().optString("title").equals("Search Controls") && !ok.value().optString("shortcut").isEmpty());
        expect("dispatch: describeControl on an unknown id is invalid-arguments", "invalid-arguments".equals(Dispatch.invoke("describeControl", o("{\"id\":\"nope\"}")).error()));
        Dispatch.Result listed = Dispatch.invoke("listCommands", null);
        expect("dispatch: listCommands returns every command: " + listed.value().optJSONArray("commands"),
                listed.ok() && listed.value().getJSONArray("commands").length() == CommandRegistry.all().size());
        String disabled = null;
        for (ActionCatalog.Entry e : ActionCatalog.all())
            if (!e.action().isEnabled() && disabled == null)
                disabled = e.id();
        if (disabled == null)
            System.out.println("  note  no catalogued action is disabled at start, so the disabled answer is not exercised");
        else
            expect("dispatch: a disabled action answers disabled (" + disabled + ")", "disabled".equals(Dispatch.invoke(disabled, null).error()));

        // ControlRegistry
        ControlRegistry.Entry layers = ControlRegistry.get("imageLayersNew");
        expect("controls.json: imageLayersNew is a control with a tooltip", layers != null && layers.kind() == ControlRegistry.Kind.CONTROL && !layers.tooltip().isEmpty());
        expect("a tour that points at a control is listed on it: " + (layers == null ? null : layers.tours()),
                layers != null && layers.tours().contains("getting-started"));
        ControlRegistry.Entry search = ControlRegistry.get("searchControls");
        expect("a command carries its shortcut as text: " + (search == null ? null : search.shortcut()), search != null && !search.shortcut().isEmpty());
        javax.swing.Action zoomAction = ActionCatalog.get("zoomOut").action();
        Object oldKey = zoomAction.getValue(javax.swing.Action.ACCELERATOR_KEY);
        zoomAction.putValue(javax.swing.Action.ACCELERATOR_KEY, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F8, 0));
        String live = ControlRegistry.get("zoomOut").shortcut();
        zoomAction.putValue(javax.swing.Action.ACCELERATOR_KEY, oldKey);
        expect("the shortcut text follows a change made while running: " + live, live.equals("F8"));
        expect("a command carries its spec", search != null && search.command() != null && search.command().id().equals("searchControls"));
        Set<String> dashes = new LinkedHashSet<>();
        for (ControlRegistry.Entry e : ControlRegistry.all())
            if ((e.title() + e.where() + e.words() + e.tooltip()).indexOf(0x2014) >= 0) // U+2014
                dashes.add(e.id());
        expect("no em dash in a title, place, words or tooltip: " + dashes, dashes.isEmpty());
        int withDoc = 0;
        for (ControlRegistry.Entry e : ControlRegistry.all())
            if (e.doc() != null)
                withDoc++;
        System.out.println("  note  " + withDoc + " of " + ControlRegistry.all().size() + " entries link a doc page (not yet required)");

        // docs/commands.json is the registry
        Path file = Path.of("docs/commands.json");
        if (!Files.exists(file))
            expect("docs/commands.json exists; make it with: java ... org.helioviewer.jhv.gui.registry.CommandRegistry docs/commands.json", false);
        else
            expect("docs/commands.json is current (regenerate with CommandRegistry.main)",
                    CommandRegistry.manifest().similar(new JSONObject(Files.readString(file))));

        System.out.println(failures == 0 ? "RegistryCheck: ok" : "RegistryCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new LinkedHashSet<>(a);
        d.removeAll(b);
        return d;
    }
}

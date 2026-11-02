package org.helioviewer.jhv.gui.registry;

import java.util.function.BooleanSupplier;

import org.json.JSONObject;

/**
 * One command, as an outside caller sees it: a stable id, a plain description, what it does to
 * the user's scene or files ({@link Effect}), the JSON Schema of its arguments and of its result,
 * and the handler. The menus, the search bar, the tours and tips, and (later) an MCP server, an
 * API and a command line all go through {@link Dispatch#invoke}, so there is one place that checks
 * the arguments and one handler per command. The shape follows an MCP tool: name, description,
 * inputSchema, outputSchema, annotations.
 *
 * <p>Ids are API: never renamed. A command is added by one line in {@link CommandRegistry}.
 */
public record CommandSpec(String id, String description, Effect effect, JSONObject input, JSONObject output,
                          Handler handler, BooleanSupplier enabled) {

    /**
     * READ changes nothing; WRITE changes the scene or a setting and can be undone or repeated;
     * DESTRUCTIVE discards work or data (a front end asks first); UI only opens or shows part of the
     * interface. This is a judgement per command, kept in {@link CommandRegistry#EFFECTS}.
     */
    public enum Effect { READ, WRITE, DESTRUCTIVE, UI }

    @FunctionalInterface
    public interface Handler {
        /** Runs on the event thread. Returns the result object; throws IllegalArgumentException for a value the schema cannot express. */
        JSONObject call(JSONObject args);
    }

    /** True when the command needs arguments, so a bare click or a search result cannot run it. */
    public boolean needsArguments() {
        return input.optJSONArray("required") != null && !input.getJSONArray("required").isEmpty();
    }
}

package org.helioviewer.jhv.gui.registry;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The small part of JSON Schema the command registry uses, enough to refuse a bad call before it
 * reaches a handler: an object with typed properties ({@code string}, {@code integer},
 * {@code number}, {@code boolean}), {@code required}, {@code enum}, {@code minimum},
 * {@code maximum} and {@code additionalProperties: false}. The same schema objects are published
 * in docs/commands.json, so an MCP, API or CLI front end reads exactly what is enforced here.
 */
final class Schema {

    /** Why {@code args} does not satisfy {@code schema}; empty when it does. */
    static List<String> validate(JSONObject schema, JSONObject args) {
        List<String> problems = new ArrayList<>();
        JSONObject props = schema.optJSONObject("properties");
        if (props == null)
            props = new JSONObject();
        JSONArray required = schema.optJSONArray("required");
        if (required != null)
            for (int i = 0; i < required.length(); i++)
                if (!args.has(required.getString(i)))
                    problems.add("missing required argument '" + required.getString(i) + "'");
        for (String key : args.keySet()) {
            JSONObject p = props.optJSONObject(key);
            if (p == null) {
                if (schema.optBoolean("additionalProperties", true) == false)
                    problems.add("unknown argument '" + key + "'");
                continue;
            }
            property(key, p, args.get(key), problems);
        }
        return problems;
    }

    private static void property(String key, JSONObject p, Object v, List<String> out) {
        String type = p.optString("type", "");
        boolean ok = switch (type) {
            case "string" -> v instanceof String;
            case "integer" -> v instanceof Integer || v instanceof Long;
            case "number" -> v instanceof Number;
            case "boolean" -> v instanceof Boolean;
            case "" -> true;
            default -> false;
        };
        if (!ok) {
            out.add("argument '" + key + "' must be " + (type.isEmpty() ? "a supported type" : (type.startsWith("i") ? "an " : "a ") + type));
            return;
        }
        JSONArray allowed = p.optJSONArray("enum");
        if (allowed != null) {
            boolean found = false;
            for (int i = 0; i < allowed.length(); i++)
                found |= allowed.get(i).equals(v);
            if (!found)
                out.add("argument '" + key + "' must be one of " + allowed);
        }
        if (v instanceof Number n) {
            if (p.has("minimum") && n.doubleValue() < p.getDouble("minimum"))
                out.add("argument '" + key + "' must be at least " + p.get("minimum"));
            if (p.has("maximum") && n.doubleValue() > p.getDouble("maximum"))
                out.add("argument '" + key + "' must be at most " + p.get("maximum"));
        }
    }

    private Schema() {}
}

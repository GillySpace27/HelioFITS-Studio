package org.helioviewer.jhv.gui.registry;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.helioviewer.jhv.activity.Activity;
import org.helioviewer.jhv.activity.Driver;
import org.helioviewer.jhv.activity.MemoryHealth;
import org.helioviewer.jhv.gui.dialog.ActivityWindow;
import org.helioviewer.jhv.gui.registry.CommandSpec.Effect;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The activity record over the registry: a script or assistant that drove Studio can read back
 * what Studio did (its commands' answers and timings, the downloads, Core's output, the memory
 * state), and open the Activity window for the person watching.
 */
public final class ActivityCommands {

    private static final String KINDS = "[\"cmd\",\"cpu\",\"disk\",\"net\",\"mem\",\"core\",\"log\"]";

    private ActivityCommands() {}

    static void register() {
        CommandRegistry.add(new CommandSpec("activityLog",
                "Read Studio's activity record: commands from scripts and assistants with their answers and timings, downloads, disk, "
                        + "HelioFITS Core's output, memory changes and log lines, oldest first, after since_seq",
                Effect.READ,
                CommandRegistry.object("{\"since_seq\":{\"type\":\"integer\",\"minimum\":0,\"description\":\"Return events after this one; 0 (the default) for all still held, up to 5000\"},"
                        + "\"kinds\":{\"type\":\"array\",\"items\":{\"enum\":" + KINDS + "},\"description\":\"Only these kinds; default all\"},"
                        + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5000,\"description\":\"At most this many events; default 500\"}}"),
                new JSONObject().put("type", "object").put("properties", new JSONObject(
                        "{\"events\":{\"type\":\"array\"},\"next_seq\":{\"type\":\"integer\"},\"driving\":{\"type\":[\"object\",\"null\"]},\"memory\":{\"type\":[\"object\",\"null\"]}}")),
                ActivityCommands::log, () -> true));
        CommandRegistry.add(new CommandSpec("activityShow",
                "Open the Activity window, on one kind of line if light is given, so the person can watch what Studio does",
                Effect.UI,
                CommandRegistry.object("{\"light\":{\"enum\":" + KINDS + ",\"description\":\"Show only this kind of line; default all\"}}"),
                new JSONObject("{\"type\":\"object\",\"properties\":{}}"),
                args -> {
                    ActivityWindow.open(args.has("light") ? kind(args.getString("light")) : null);
                    return new JSONObject();
                }, () -> true));
    }

    private static Activity.Kind kind(String name) {
        return Activity.Kind.valueOf(name.toUpperCase(Locale.ROOT));
    }

    public static JSONObject log(JSONObject args) {
        Set<Activity.Kind> kinds = EnumSet.noneOf(Activity.Kind.class);
        JSONArray k = args.optJSONArray("kinds");
        if (k != null)
            for (int i = 0; i < k.length(); i++)
                kinds.add(kind(k.getString(i)));
        int limit = args.optInt("limit", 500);
        List<Activity.Event> events = Activity.since(args.optLong("since_seq", 0), kinds, limit);
        JSONArray out = new JSONArray();
        long last = args.optLong("since_seq", 0);
        for (Activity.Event e : events) {
            out.put(new JSONObject().put("seq", e.seq()).put("time", java.time.Instant.ofEpochMilli(e.timeMillis()).toString())
                    .put("kind", e.kind().name().toLowerCase(Locale.ROOT)).put("source", e.source()).put("text", e.text()));
            last = e.seq();
        }
        // A full page answers where it stopped, so the caller asks again; otherwise everything held has been seen.
        long next = events.size() >= limit ? last : Math.max(last, Activity.nextSeq() - 1);
        JSONObject answer = new JSONObject().put("events", out).put("next_seq", next);
        Driver.State d = Driver.current(System.nanoTime());
        answer.put("driving", d == null ? JSONObject.NULL : new JSONObject().put("name", d.name()).put("kind", d.kind().name().toLowerCase(Locale.ROOT))
                .put("command", d.command()).put("in_flight", d.inFlight()));
        MemoryHealth.Reading m = MemoryHealth.latest();
        answer.put("memory", m == null ? JSONObject.NULL : new JSONObject().put("level", m.level().name().toLowerCase(Locale.ROOT))
                .put("reason", m.reason()).put("advice", m.advice()).put("summary", m.summary()));
        return answer;
    }

}

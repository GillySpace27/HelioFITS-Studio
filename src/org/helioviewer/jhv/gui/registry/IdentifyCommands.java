package org.helioviewer.jhv.gui.registry;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.identify.Bodies;
import org.helioviewer.jhv.identify.Identifier;
import org.helioviewer.jhv.identify.IdentifyPins;
import org.helioviewer.jhv.identify.IdentifySettings;
import org.helioviewer.jhv.identify.Probes;
import org.helioviewer.jhv.identify.TransitIndex;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.opengl.GLRenderer;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import static org.helioviewer.jhv.gui.registry.CommandSpec.Effect;

/**
 * The cursor identifier's commands: what is at a direction, where a body is, when bodies are in a
 * field, and the identifier's settings. Argument names are snake_case as in {@link DataCommands};
 * angles are degrees, helioprojective (Tx, Ty) in the observer's frame.
 */
final class IdentifyCommands {

    private static final String TIME = "\"time\":{\"type\":\"string\",\"description\":\"UTC time, ISO 8601; omit for the active image layer's frame (or the displayed time when no layer is loaded)\"},";
    private static final String OBSERVER = "\"observer\":{\"type\":\"string\",\"description\":\"A SPICE observer such as EARTH, SOHO, STEREO AHEAD, SOLO or PSP; omit for the active image layer's own observer\"}";

    static void register() {
        CommandRegistry.add(new CommandSpec("identifyAt",
                "Name the Sun, Moon and planets at a helioprojective direction, as an observer sees them", Effect.READ,
                CommandRegistry.object("{\"tx_deg\":{\"type\":\"number\",\"description\":\"Helioprojective longitude Tx in degrees\"},"
                        + "\"ty_deg\":{\"type\":\"number\",\"description\":\"Helioprojective latitude Ty in degrees\"},"
                        + "\"tolerance_deg\":{\"type\":\"number\",\"minimum\":0,\"description\":\"Extra radius in degrees; default 0\"}," + TIME + OBSERVER + "}",
                        "tx_deg", "ty_deg"),
                new JSONObject("{\"type\":\"object\",\"properties\":{\"observer\":{\"type\":\"object\"},\"matches\":{\"type\":\"array\"}}}"), args -> {
                    Resolved r = resolve(args);
                    Identifier.Probe probe = Probes.helioprojective(Math.toRadians(args.getDouble("tx_deg")), Math.toRadians(args.getDouble("ty_deg")));
                    Identifier.Options o = Identifier.Options.fromSettings();
                    double tol = args.optDouble("tolerance_deg", 0);
                    Identifier.Probe probe1 = tol <= 0 ? null : Probes.helioprojective(Math.toRadians(args.getDouble("tx_deg") + tol), Math.toRadians(args.getDouble("ty_deg")));
                    o = new Identifier.Options(tol > 0 ? 1 : 0, o.earthUncertaintyKm(), o.lightTime(), o.pluto());
                    JSONArray matches = new JSONArray();
                    for (Identifier.Match m : Identifier.identify(List.of(r.target()), probe, probe1, o))
                        matches.put(json(m));
                    return new JSONObject().put("observer", observerJson(r.target())).put("matches", matches);
                }, () -> true));

        CommandRegistry.add(new CommandSpec("identifyLocate",
                "Where a body is in an observer's sky: helioprojective Tx and Ty, distance, elongation, and whether the active layer's field holds it", Effect.READ,
                CommandRegistry.object("{\"body\":{\"type\":\"string\",\"description\":\"Sun, Mercury, Venus, Earth, Moon, Mars, Jupiter, Saturn, Uranus, Neptune or Pluto\"},"
                        + TIME + OBSERVER + "}", "body"),
                new JSONObject("{\"type\":\"object\",\"properties\":{\"tx_deg\":{\"type\":\"number\"},\"ty_deg\":{\"type\":\"number\"},"
                        + "\"distance_au\":{\"type\":\"number\"},\"elongation_deg\":{\"type\":\"number\"},\"in_field\":{\"type\":\"boolean\"}}}"), args -> {
                    Bodies.Body body = Bodies.byName(args.getString("body"));
                    if (body == null)
                        throw new IllegalArgumentException("no body '" + args.getString("body") + "'");
                    Resolved r = resolve(args);
                    Position obs = r.target().observer();
                    Vec3 dir = Identifier.bodyDirection(body, obs, IdentifySettings.lightTime());
                    if (dir == null)
                        throw new IllegalArgumentException("no position for " + body.name() + " at " + obs.time + " (outside the bundled ephemeris)");
                    Vec2 hp = Identifier.helioprojective(dir);
                    Vec3 bw = Bodies.world(body, obs.time);
                    Vec3 ow = org.helioviewer.jhv.layers.ModelPlacement.toWorld(obs);
                    double range = bw == null ? Double.NaN : new Vec3(bw.x - ow.x, bw.y - ow.y, bw.z - ow.z).length();
                    JSONObject out = new JSONObject().put("body", body.name()).put("observer", observerJson(r.target()))
                            .put("tx_deg", Math.toDegrees(hp.x)).put("ty_deg", Math.toDegrees(hp.y))
                            .put("distance_au", range * org.helioviewer.jhv.astronomy.Sun.MeanEarthDistanceInv)
                            .put("elongation_deg", Math.toDegrees(Math.acos(Math.clamp(-dir.z, -1, 1))));
                    if (r.target().wcs() != null && r.target().region() != null)
                        out.put("in_field", new TransitIndex.LayerField(r.target().wcs(), r.target().region(), r.target().innerRadius(), r.target().outerRadius()).contains(dir, obs));
                    return out;
                }, () -> true));

        CommandRegistry.add(new CommandSpec("identifyInField",
                "When each body is in a field of view over a time range, by body and by UTC day: find dates with or without the Moon", Effect.READ,
                CommandRegistry.object("{\"start\":{\"type\":\"string\",\"description\":\"UTC start, ISO 8601\"},"
                        + "\"end\":{\"type\":\"string\",\"description\":\"UTC end, ISO 8601\"},"
                        + "\"observer\":{\"type\":\"string\",\"description\":\"A SPICE observer; default EARTH\"},"
                        + "\"field\":{\"type\":\"string\",\"enum\":[\"punch\",\"layer\",\"custom\"],\"description\":\"punch: a 46.08 degree half-width square; layer: the active image layer's own field; custom: inner_deg, outer_deg and half_width_deg. Default punch\"},"
                        + "\"inner_deg\":{\"type\":\"number\",\"minimum\":0},\"outer_deg\":{\"type\":\"number\",\"minimum\":0},"
                        + "\"half_width_deg\":{\"type\":\"number\",\"minimum\":0,\"description\":\"0 for no square cut\"},"
                        + "\"step_minutes\":{\"type\":\"number\",\"minimum\":1,\"description\":\"Sampling step; edges are refined to a minute. Default 60\"}}",
                        "start", "end"),
                new JSONObject("{\"type\":\"object\",\"properties\":{\"bodies\":{\"type\":\"object\"},\"days\":{\"type\":\"object\"}}}"), args -> {
                    long start = time(args.getString("start")).milli, end = time(args.getString("end")).milli;
                    if (end <= start)
                        throw new IllegalArgumentException("end must be after start");
                    if (end - start > 3L * 366 * 86_400_000)
                        throw new IllegalArgumentException("at most three years at a time");
                    String observer = args.optString("observer", "EARTH");
                    TransitIndex.Field field = switch (args.optString("field", "punch")) {
                        case "punch" -> TransitIndex.Annulus.PUNCH;
                        case "custom" -> new TransitIndex.Annulus(args.optDouble("inner_deg", 0), args.optDouble("outer_deg", 180), args.optDouble("half_width_deg", 0));
                        case "layer" -> {
                            ImageLayer layer = Layers.getActiveImageLayer();
                            View.ImageData data = layer == null ? null : layer.getImageData();
                            if (data == null)
                                throw new IllegalArgumentException("field 'layer' needs an active image layer with a frame");
                            MetaData m = data.metaData();
                            yield new TransitIndex.LayerField(m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius());
                        }
                        default -> throw new IllegalArgumentException("field is punch, layer or custom");
                    };
                    long step = Math.round(args.optDouble("step_minutes", 60) * 60_000);
                    Map<String, List<TransitIndex.Interval>> m = TransitIndex.compute(TransitIndex.Observer.spice(observer), field, start, end, step, IdentifySettings.pluto());
                    JSONObject bodies = new JSONObject();
                    for (Map.Entry<String, List<TransitIndex.Interval>> e : m.entrySet()) {
                        JSONArray list = new JSONArray();
                        for (TransitIndex.Interval i : e.getValue())
                            list.put(new JSONObject().put("start", new JHVTime(i.start()).toString()).put("end", new JHVTime(i.end()).toString()));
                        bodies.put(e.getKey(), list);
                    }
                    JSONObject days = new JSONObject();
                    for (Map.Entry<LocalDate, Set<String>> e : TransitIndex.byDay(m).entrySet())
                        days.put(e.getKey().toString(), new JSONArray(e.getValue()));
                    return new JSONObject().put("observer", observer).put("bodies", bodies).put("days", days);
                }, () -> true));

        CommandRegistry.add(new CommandSpec("identifySet",
                "Turn the cursor identifier and its tooltip and stars on or off, and set its tolerances; answers with every setting", Effect.WRITE,
                new JSONObject("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"enabled\":{\"type\":\"boolean\"},\"tooltip\":{\"type\":\"boolean\"},\"stars\":{\"type\":\"boolean\"},"
                        + "\"star_names_iau\":{\"type\":\"boolean\"},\"star_names_bayer\":{\"type\":\"boolean\"},"
                        + "\"star_max_magnitude\":{\"type\":\"number\"},\"tolerance_pixels\":{\"type\":\"number\",\"minimum\":0},"
                        + "\"tooltip_dwell_ms\":{\"type\":\"integer\",\"minimum\":0},\"light_time\":{\"type\":\"boolean\"},"
                        + "\"earth_observer_uncertainty_km\":{\"type\":\"number\",\"minimum\":0},\"pluto\":{\"type\":\"boolean\"},"
                        + "\"glow\":{\"type\":\"boolean\"},\"pulse\":{\"type\":\"boolean\"}}}"),
                new JSONObject("{\"type\":\"object\"}"), args -> {
                    String[][] keys = {{"enabled", IdentifySettings.ENABLED}, {"tooltip", IdentifySettings.TOOLTIP}, {"stars", IdentifySettings.STARS},
                            {"star_names_iau", IdentifySettings.STAR_NAMES_IAU}, {"star_names_bayer", IdentifySettings.STAR_NAMES_BAYER},
                            {"star_max_magnitude", IdentifySettings.STAR_MAX_MAG}, {"tolerance_pixels", IdentifySettings.TOLERANCE_PX},
                            {"tooltip_dwell_ms", IdentifySettings.DWELL_MS}, {"light_time", IdentifySettings.LIGHT_TIME},
                            {"earth_observer_uncertainty_km", IdentifySettings.EARTH_UNCERTAINTY_KM}, {"pluto", IdentifySettings.PLUTO},
                            {"glow", IdentifySettings.GLOW}, {"pulse", IdentifySettings.PULSE}};
                    for (String[] k : keys)
                        if (args.has(k[0]))
                            IdentifySettings.set(k[1], String.valueOf(args.get(k[0])));
                    return IdentifySettings.toJson();
                }, () -> true));
        registerPins();
    }

    private static void registerPins() {
        CommandRegistry.add(new CommandSpec("identifyPin",
                "Pin a body's label to the view, or unpin it, or list the pins; pins follow their body and are saved with the session", Effect.WRITE,
                new JSONObject("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"body\":{\"type\":\"string\",\"description\":\"Sun, Mercury, Venus, Earth, Moon, Mars, Jupiter, Saturn, Uranus, Neptune or Pluto; omit to list\"},"
                        + "\"pinned\":{\"type\":\"boolean\",\"description\":\"true pins, false unpins; omit to toggle\"},"
                        + "\"clear\":{\"type\":\"boolean\",\"description\":\"true removes every pin first\"}}}"),
                new JSONObject("{\"type\":\"object\",\"properties\":{\"pins\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}"), args -> {
                    if (args.optBoolean("clear", false))
                        IdentifyPins.set(java.util.List.of());
                    if (args.has("body")) {
                        Bodies.Body body = Bodies.byName(args.getString("body"));
                        if (body == null)
                            throw new IllegalArgumentException("no body '" + args.getString("body") + "'");
                        if (!args.has("pinned"))
                            IdentifyPins.toggle(body.name());
                        else if (args.getBoolean("pinned"))
                            IdentifyPins.pin(body.name());
                        else if (IdentifyPins.isPinned(body.name()))
                            IdentifyPins.toggle(body.name());
                    }
                    return new JSONObject().put("pins", IdentifyPins.toJson());
                }, () -> true));
    }

    private record Resolved(Identifier.Target target) {}

    private static JHVTime time(String s) {
        try {
            return new JHVTime(s);
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + s + "' is not a UTC time such as 2025-09-22T05:46:30");
        }
    }

    /** The observer asked for: a SPICE body at a time, or the active image layer's own observer and field. */
    private static Resolved resolve(JSONObject args) {
        @Nullable String observer = args.has("observer") ? args.getString("observer") : null;
        @Nullable JHVTime t = args.has("time") ? time(args.getString("time")) : null;
        if (observer == null) {
            ImageLayer layer = Layers.getActiveImageLayer();
            View.ImageData data = layer == null ? null : layer.getImageData();
            if (data != null && t == null) {
                MetaData m = data.metaData();
                return new Resolved(new Identifier.Target(layer.getName(), m.getViewpoint(), m.getWcsHeader(), data.region(), m.getInnerRadius(), m.getOuterRadius()));
            }
            if (t == null)
                return new Resolved(Identifier.Target.viewpoint(GLRenderer.getDisplayedViewpoint()));
            observer = "EARTH";
        }
        JHVTime when = t != null ? t : GLRenderer.getDisplayedViewpoint().time;
        Position p = Spice.getCarrington(observer, when);
        if (p == null)
            throw new IllegalArgumentException("no position for observer '" + observer + "' at " + when);
        return new Resolved(new Identifier.Target(observer, p, null, null, 0, Double.POSITIVE_INFINITY));
    }

    private static JSONObject observerJson(Identifier.Target t) {
        Position p = t.observer();
        return new JSONObject().put("name", t.name()).put("time", p.time.toString())
                .put("distance_au", p.distance * org.helioviewer.jhv.astronomy.Sun.MeanEarthDistanceInv)
                .put("carrington_lon_deg", -Math.toDegrees(p.lon)).put("lat_deg", Math.toDegrees(p.lat));
    }

    private static JSONObject json(Identifier.Match m) {
        return new JSONObject().put("body", m.body().name()).put("label", m.label()).put("layer", m.layer())
                .put("separation_deg", m.separation()).put("angular_radius_deg", m.angularRadius())
                .put("tolerance_deg", m.tolerance()).put("distance_au", m.distanceAu())
                .put("elongation_deg", m.elongation()).put("qualifier", m.qualifier().name().toLowerCase(java.util.Locale.ROOT));
    }

    private IdentifyCommands() {}
}

package org.helioviewer.jhv.app.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.helioviewer.jhv.movie.Provenance;
import org.json.JSONObject;

/**
 * A scene fit to hand to someone else: the home folder written as "~" in every string (so the
 * sender's account name is not in the file) and a list of the local files the scene points at
 * outside the cache, which the receiver will not have. Pure; the caller shows the warning and writes.
 */
public final class PortableScene {

    public record Result(JSONObject scene, List<String> localFiles) {}

    /** Every distinct {@code file:} string in the (already home-stripped) scene that is not inside {@code cacheDir}. */
    public static Result of(JSONObject scene, String home, String cacheDir) {
        JSONObject stripped = (JSONObject) Provenance.rewrite(scene, s -> Provenance.stripHome(s, home));
        Set<String> seen = new java.util.LinkedHashSet<>();
        String cacheStripped = Provenance.stripHome(cacheDir, home);
        Provenance.rewrite(stripped, s -> {
            if (s.startsWith("file:") && !s.contains(cacheStripped))
                seen.add(s);
            return s;
        });
        return new Result(stripped, new ArrayList<>(seen));
    }

    private PortableScene() {}
}

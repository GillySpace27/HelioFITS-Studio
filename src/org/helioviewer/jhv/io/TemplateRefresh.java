package org.helioviewer.jhv.io;

import java.awt.EventQueue;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.state.TemplateLibrary;
import org.helioviewer.jhv.gui.dialog.TemplateManager;
import org.helioviewer.jhv.thread.AppThread;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The template manager's Refresh Data (HS-25): ask Heliad to bring the library up to the newest
 * frames of every Heliad dataset the selected template shows (template.refresh, docs/api.md in the
 * heliad repository). Heliad queues the downloads and shows them; opening the template afterwards
 * picks up the new frames. Layers that are not Heliad datasets (a Helioviewer request) are left to
 * the template's own time mode, which already asks for the newest data when the template opens.
 */
public final class TemplateRefresh {

    private static final Duration WAIT = Duration.ofMinutes(3); // an archive search per dataset

    private TemplateRefresh() {}

    /** Installs Refresh Data when a Heliad library is found; without one the button keeps saying "Needs Heliad". */
    public static void install() {
        if (HeliadLibrary.find() == null)
            return;
        TemplateManager.refreshDataHook.install(TemplateRefresh::refresh);
    }

    private static void refresh(TemplateLibrary.Entry entry) {
        HeliadLibrary.Location lib = HeliadLibrary.find();
        if (lib == null) {
            Message.warn("Refresh Data", "No Heliad library was found.");
            return;
        }
        String name = entry.meta().name();
        JSONObject session = entry.session();
        AppThread.create(() -> {
            try {
                JSONArray result = HeliadIngest.templateRefresh(lib.root(), session, WAIT);
                String text = result == null ? "No Heliad core was found to ask." : summary(result);
                EventQueue.invokeLater(() -> Message.info("Refresh Data: " + name, text));
            } catch (IOException e) {
                EventQueue.invokeLater(() -> Message.warn("Refresh Data: " + name, "Heliad could not refresh it: " + e.getMessage()));
            }
        }, "Heliad template refresh").start();
    }

    /** What the person is told: a count per outcome, then the datasets queued with their spans. Pure. */
    static String summary(JSONArray entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        StringBuilder queued = new StringBuilder();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null)
                continue;
            String status = e.optString("status", "unknown");
            counts.merge(status, 1, Integer::sum);
            if ("queued".equals(status) || "would_queue".equals(status))
                queued.append('\n').append(e.optString("layer", e.optString("dataset_key", "?")))
                        .append(": ").append(e.optString("start")).append(" to ").append(e.optString("end"));
        }
        if (counts.isEmpty())
            return "The template shows no image layers.";
        StringBuilder sb = new StringBuilder();
        counts.forEach((status, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(' ').append(label(status)));
        sb.append('.');
        if (!queued.isEmpty())
            sb.append("\n\nDownloading in Heliad:").append(queued).append("\n\nOpen the template again once they finish.");
        return sb.toString();
    }

    private static String label(String status) {
        return switch (status) {
            case "queued" -> "queued for download";
            case "would_queue" -> "would be downloaded";
            case "up_to_date" -> "up to date";
            case "skipped" -> "not from the Heliad library (refreshed when the template opens)";
            case "no_query" -> "with no stored search";
            case "archive_error" -> "where the archive did not answer";
            case "not_found" -> "not in the library";
            default -> status;
        };
    }
}

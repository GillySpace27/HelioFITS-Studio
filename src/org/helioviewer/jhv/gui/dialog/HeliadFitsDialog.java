package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JOptionPane;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.io.HeliadFits;
import org.helioviewer.jhv.io.HeliadLibrary;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The layer panel's FITS button: shows what Heliad holds for a Helioviewer layer's frames, asks
 * before fetching the rest, and opens the FITS files as a new layer once Heliad has them. The
 * Helioviewer layer is left as it is. Every question to the core runs off the event thread.
 */
public final class HeliadFitsDialog {

    private static final String TITLE = "Use FITS";

    private HeliadFitsDialog() {}

    private record Planned(JSONArray headers, JSONObject plan) {}

    /**
     * Start for this layer. {@code status} hears a short line while Heliad works (null when it is
     * done), for the button to show; it is called on the event thread.
     */
    public static void start(ImageLayer layer, Component parent, Consumer<String> status) {
        View view = layer.getView();
        if (!HeliadFits.applies(view))
            return;
        HeliadLibrary.Location lib = HeliadLibrary.find();
        if (lib == null) {
            Message.warn(TITLE, "Use FITS needs a Heliad library on this computer. Install Heliad and make a library first.");
            return;
        }
        Path root = lib.root();
        status.accept("Asking Heliad");
        Task.submitBackground(() -> {
            JSONArray headers = HeliadFits.headers(view);
            return new Planned(headers, HeliadFits.plan(root, headers, true));
        }, planned -> {
            status.accept(null);
            ask(root, planned, parent, status);
        }, t -> failed(t, status));
    }

    private static void ask(Path root, Planned planned, Component parent, Consumer<String> status) {
        JSONObject plan = planned.plan();
        String summary = HeliadFits.summary(plan);
        int have = HeliadFits.count(plan, "in_library"), missing = HeliadFits.count(plan, "missing");
        if (missing == 0 && have == 0) {
            Message.info(TITLE, summary);
            return;
        }
        if (missing == 0) {
            if (JOptionPane.showConfirmDialog(parent, summary + "\n\nOpen them as a new layer?", TITLE,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION)
                open(plan);
            return;
        }
        String fetch = "Fetch and open", open = "Open the " + have + " I have", cancel = "Cancel";
        Object[] options = have > 0 ? new Object[]{fetch, open, cancel} : new Object[]{fetch, cancel};
        int choice = JOptionPane.showOptionDialog(parent, summary + "\n\nHeliad fetches the missing frames into its library in the background.",
                TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, fetch);
        if (choice < 0 || options[choice] == cancel)
            return;
        if (options[choice] == open) {
            open(plan);
            return;
        }
        status.accept("Fetching");
        Task.submitBackground(() -> {
            JSONObject queued = HeliadFits.plan(root, planned.headers(), false);
            List<JSONObject> jobs = HeliadFits.waitFor(root, HeliadFits.jobs(queued),
                    line -> java.awt.EventQueue.invokeLater(() -> status.accept(line)));
            return new Fetched(jobs, HeliadFits.plan(root, planned.headers(), true));
        }, fetched -> {
            status.accept(null);
            finished(fetched);
        }, t -> failed(t, status));
    }

    private record Fetched(List<JSONObject> jobs, JSONObject plan) {}

    private static void finished(Fetched fetched) {
        StringBuilder problems = new StringBuilder();
        for (JSONObject job : fetched.jobs()) {
            String s = job.optString("status");
            if (!"done".equals(s))
                problems.append("\nJob ").append(job.optInt("job_id")).append(": ").append(s.isEmpty() ? "gone" : s)
                        .append(job.optString("error").isEmpty() ? "" : " (" + job.optString("error") + ")");
        }
        if (!problems.isEmpty())
            Message.warn(TITLE, "Heliad did not get every frame." + problems + "\n\n" + HeliadFits.summary(fetched.plan()));
        open(fetched.plan());
    }

    private static void open(JSONObject plan) {
        List<URI> uris = HeliadFits.files(plan);
        if (uris.isEmpty()) {
            Message.warn(TITLE, "The Heliad library holds no FITS file for these frames.\n\n" + HeliadFits.summary(plan));
            return;
        }
        Commands.loadImage(uris);
    }

    private static void failed(Throwable t, Consumer<String> status) {
        status.accept(null);
        Log.warn("Use FITS", t);
        Message.err(TITLE, t.getMessage(), t);
    }

}

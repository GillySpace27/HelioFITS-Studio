package org.helioviewer.jhv.io;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.state.SessionArchive;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.thread.Task;

import org.json.JSONObject;

class LoadState {

    static void submit(@Nullable Commands.OperationContext context, @Nonnull URI uri) {
        Task.submitBackground(uri.toString(), new LoadStateURI(uri), result -> onSuccess(context, result), (logContext, t) -> onFailure(context, logContext, t));
    }

    static void submit(@Nullable Commands.OperationContext context, @Nonnull String json) {
        Task.submitBackground("state", new LoadStateString(json), result -> onSuccess(context, result), (logContext, t) -> onFailure(context, logContext, t));
    }

    private static void onSuccess(@Nullable Commands.OperationContext context, JSONObject state) {
        State.load(context, state);
    }

    private static void onFailure(@Nullable Commands.OperationContext context, String logContext, Throwable error) {
        String errorMessage = "Error getting the data";
        Log.error(logContext, error);
        org.helioviewer.jhv.app.Session.fireStateLoadComplete(false); // the load is over, however badly
        Message.err(errorMessage, error.getMessage());
        String message = error.getMessage() == null || error.getMessage().isBlank() ? errorMessage : error.getMessage();
        Commands.notifyLoadStateFinished(context, false, message);
    }

    private record LoadStateURI(URI uri) implements Callable<JSONObject> {
        @Override
        public JSONObject call() throws Exception {
            Path path = "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : null;
            // A session data archive opened on its own carries its session inside it.
            boolean archive = path != null && SessionArchive.isArchiveName(path.getFileName().toString());
            JSONObject state = archive ? SessionArchive.sessionIn(path)
                    : JSONUtils.get(uri).getJSONObject("org.helioviewer.jhv.state");
            if (path != null)
                reattach(state, archive ? null : path, archive ? path : null);
            return state;
        }
    }

    // Local files the session names that are not on this machine come from beside the session or
    // from its data archive, when one travelled with it. What is still missing is reported once per
    // layer by the layer itself (ImageLayerLoader), which also loads the frames that are here, so
    // only a damaged archive is worth a dialog of its own.
    private static void reattach(JSONObject state, @Nullable Path session, @Nullable Path archive) {
        String warning;
        try {
            SessionArchive.Reattach r = SessionArchive.reattach(state, session, archive,
                    Path.of(Directories.HOME.getPath(), "SessionData"));
            if (r.missing() != r.restored())
                Log.warn((r.missing() - r.restored()) + " of " + r.missing() + " missing local file(s) not found beside the session"
                        + (r.archive() == null ? " and no " + SessionArchive.SUFFIX + " archive" : " or in " + r.archive().getFileName()));
            return;
        } catch (java.io.IOException e) {
            Log.warn("Session data archive", e);
            warning = "The session's data archive could not be used: " + e.getMessage();
        }
        String text = warning;
        java.awt.EventQueue.invokeLater(() -> Message.warn("Session data missing", text));
    }

    private record LoadStateString(String json) implements Callable<JSONObject> {
        @Override
        public JSONObject call() {
            return new JSONObject(json).getJSONObject("org.helioviewer.jhv.state");
        }
    }

}

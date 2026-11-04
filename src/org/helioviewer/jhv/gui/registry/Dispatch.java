package org.helioviewer.jhv.gui.registry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Log;

import org.json.JSONObject;

/**
 * The one way to run a command by id. Checks that the command exists and is enabled, validates the
 * arguments against its schema, runs the handler on the event thread, and returns a
 * {@link Result} that never throws: a front end maps {@code error} to its own failure form.
 *
 * <p>A handler whose answer is not known on the event thread (a layer load, an export) returns a
 * {@link Deferred}: the caller's thread, never the event thread, then waits for it, up to
 * {@link #DEFERRED_TIMEOUT_S}, so a front end's one call still gets one answer.
 */
public final class Dispatch {

    /** How long a caller waits for a deferred answer: a full-disk movie export can take minutes. */
    public static final long DEFERRED_TIMEOUT_S = 1800;

    /**
     * A handler's promise of a result: the event thread returns it at once and the caller's thread
     * waits for {@link #future}. Completing it exceptionally with an IllegalArgumentException answers
     * invalid-arguments, with anything else failed.
     */
    public static final class Deferred extends JSONObject {
        public final CompletableFuture<JSONObject> future;

        public Deferred(CompletableFuture<JSONObject> _future) {
            future = _future;
        }
    }

    /** {@code error} is null on success, else one of unknown-command, invalid-arguments, disabled, failed. */
    public record Result(@Nullable String error, String message, JSONObject value) {
        public boolean ok() {
            return error == null;
        }

        static Result fail(String error, String message) {
            return new Result(error, message, new JSONObject());
        }
    }

    public static Result invoke(String id, @Nullable JSONObject args) {
        CommandSpec spec = CommandRegistry.get(id);
        if (spec == null)
            return Result.fail("unknown-command", "no command '" + id + "'");
        JSONObject in = args == null ? new JSONObject() : args;
        List<String> problems = Schema.validate(spec.input(), in);
        if (!problems.isEmpty())
            return Result.fail("invalid-arguments", String.join("; ", problems));
        if (SwingUtilities.isEventDispatchThread()) {
            Result r = run(spec, in);
            if (r.value() instanceof Deferred)
                return Result.fail("failed", "'" + id + "' answers later and cannot be waited for on the event thread");
            return r;
        }
        Result[] box = new Result[1];
        try {
            SwingUtilities.invokeAndWait(() -> box[0] = run(spec, in));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("failed", "interrupted");
        } catch (java.lang.reflect.InvocationTargetException e) {
            return Result.fail("failed", String.valueOf(e.getCause()));
        }
        return box[0].value() instanceof Deferred d ? await(id, d) : box[0];
    }

    private static Result await(String id, Deferred d) {
        try {
            return new Result(null, "", d.future.get(DEFERRED_TIMEOUT_S, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("failed", "interrupted");
        } catch (TimeoutException e) {
            return Result.fail("failed", "'" + id + "' did not finish within " + DEFERRED_TIMEOUT_S + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IllegalArgumentException)
                return Result.fail("invalid-arguments", String.valueOf(cause.getMessage()));
            Log.warn("Command " + id + " failed", cause);
            return Result.fail("failed", String.valueOf(cause.getMessage()));
        }
    }

    private static Result run(CommandSpec spec, JSONObject in) {
        if (!spec.enabled().getAsBoolean())
            return Result.fail("disabled", "'" + spec.id() + "' is not available now");
        try {
            return new Result(null, "", spec.handler().call(in));
        } catch (IllegalArgumentException e) {
            return Result.fail("invalid-arguments", String.valueOf(e.getMessage()));
        } catch (RuntimeException e) { // a failing command is logged, as from its menu item
            Log.warn("Command " + spec.id() + " failed", e);
            return Result.fail("failed", String.valueOf(e.getMessage()));
        }
    }

    private Dispatch() {}
}

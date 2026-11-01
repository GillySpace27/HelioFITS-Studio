package org.helioviewer.jhv.gui.registry;

import java.util.List;

import javax.annotation.Nullable;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Log;

import org.json.JSONObject;

/**
 * The one way to run a command by id. Checks that the command exists and is enabled, validates the
 * arguments against its schema, runs the handler on the event thread, and returns a
 * {@link Result} that never throws: a front end maps {@code error} to its own failure form.
 */
public final class Dispatch {

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
        if (SwingUtilities.isEventDispatchThread())
            return run(spec, in);
        Result[] box = new Result[1];
        try {
            SwingUtilities.invokeAndWait(() -> box[0] = run(spec, in));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("failed", "interrupted");
        } catch (java.lang.reflect.InvocationTargetException e) {
            return Result.fail("failed", String.valueOf(e.getCause()));
        }
        return box[0];
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

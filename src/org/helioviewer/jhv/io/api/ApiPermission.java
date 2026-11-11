package org.helioviewer.jhv.io.api;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.registry.CommandSpec;

/**
 * What a script or assistant may do without asking (setting "api.permission"): everything, anything
 * that removes or discards, or any command that changes something. Plus the timed approval: when the
 * question has come up three times within two minutes, its dialog offers to stop asking for a while.
 *
 * <p>The approval is memory only (gone at quit), belongs to the caller that gave it, and never covers
 * a command that discards the whole scene. Code that asks: {@link LocalApi}; the setting: Settings > General.
 */
public final class ApiPermission {

    public static final String KEY = "api.permission";
    public static final String MINUTES_KEY = "api.approveMinutes";
    static final int DEFAULT_MINUTES = 10;
    /** Prompts in this window, the one being asked included, make the dialog offer the timed approval. */
    static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(2);
    static final int OFFER_AFTER = 3;

    public enum Level {
        ALL("Allow everything"),
        REMOVALS("Ask before removing from the scene"),
        EVERYTHING("Ask before every change");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Commands that overwrite files: asked about at "removals" as well, since a script can replace your export. */
    static final Set<String> FILE_WRITES = Set.of("exportStill", "exportMovie", "saveSession", "saveSessionAs", "exportSessionWithData");
    /** Commands that throw away the whole scene or the program: asked every time, whatever was approved. */
    static final Set<String> WHOLE_SCENE = Set.of("quit", "closeWindow", "newSession", "openSession", "sessionLoad", "revertToSaved", "openDemo");

    private record Grant(String who, long untilNanos) {}

    private static final ArrayDeque<Long> prompts = new ArrayDeque<>();
    private static final AtomicReference<Grant> grant = new AtomicReference<>();  // ponytail: ref only to keep the static final guard count

    private ApiPermission() {}

    public static Level level() {
        String v = Settings.getProperty(KEY);
        if (v != null && !v.isBlank()) {
            try {
                return Level.valueOf(v);
            } catch (IllegalArgumentException ignored) {
                // an unknown value falls through to the old switch
            }
        }
        return "false".equals(Settings.getProperty(LocalApi.CONFIRM)) ? Level.ALL : Level.REMOVALS; // before the levels: one switch
    }

    public static void setLevel(Level level) {
        Settings.setProperty(KEY, level.name());
        Settings.setProperty(LocalApi.CONFIRM, Boolean.toString(level != Level.ALL)); // an older Studio reads this one
        endTimedApproval();
    }

    public static int minutes() {
        try {
            return Math.max(1, Math.min(240, Integer.parseInt(Settings.getProperty(MINUTES_KEY))));
        } catch (NumberFormatException e) {
            return DEFAULT_MINUTES;
        }
    }

    public static void setMinutes(int m) {
        Settings.setProperty(MINUTES_KEY, Integer.toString(Math.max(1, Math.min(240, m))));
    }

    /** Whether this command is put to the person at this level (before any timed approval). */
    static boolean asks(CommandSpec spec, Level level) {
        CommandSpec.Effect e = spec.effect();
        return switch (level) {
            case ALL -> false;
            case REMOVALS -> e == CommandSpec.Effect.DESTRUCTIVE || FILE_WRITES.contains(spec.id());
            case EVERYTHING -> e == CommandSpec.Effect.DESTRUCTIVE || e == CommandSpec.Effect.WRITE;
        };
    }

    static boolean timedCanCover(CommandSpec spec) {
        return !WHOLE_SCENE.contains(spec.id());
    }

    /** Minutes of timed approval left for this caller on this command, 0 when it must ask. */
    static synchronized int approvedMinutesLeft(CommandSpec spec, String who, long nowNanos) {
        Grant g = grant.get();
        if (g == null || !timedCanCover(spec) || !g.who().equals(who))
            return 0;
        long left = g.untilNanos() - nowNanos;
        if (left <= 0) {
            grant.set(null);
            return 0;
        }
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMinutes(left + TimeUnit.MINUTES.toNanos(1) - 1));
    }

    /** Count this question as asked now; true when the dialog should offer the timed approval. */
    static synchronized boolean notePrompt(CommandSpec spec, long nowNanos) {
        prompts.addLast(nowNanos);
        while (!prompts.isEmpty() && nowNanos - prompts.peekFirst() > WINDOW_NANOS)
            prompts.removeFirst();
        return prompts.size() >= OFFER_AFTER && timedCanCover(spec);
    }

    static synchronized void approve(String who, int minutes, long nowNanos) {
        grant.set(new Grant(who, nowNanos + TimeUnit.MINUTES.toNanos(minutes)));
    }

    /** Back to asking now (also when the level is changed). */
    public static synchronized void endTimedApproval() {
        grant.set(null);
        prompts.clear();
    }

    public static synchronized boolean timedApprovalActive() {
        Grant g = grant.get();
        return g != null && g.untilNanos() > System.nanoTime();
    }
}

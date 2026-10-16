package org.helioviewer.jhv.app;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONObject;

/**
 * Crash safety for a window's session: what autosave writes, the copies it keeps, and what a launch
 * after a crash says.
 *
 * <p>Autosave compares the whole scene, not a change counter. The counter only moved when a layer was
 * added or removed, so a colour table, a Levels window, an annotation or a projection chosen since
 * then reached the disk only on quit, and a crash lost it. {@link #autosave} builds the session every
 * few seconds and writes it when anything but the playhead differs from what it last wrote.
 *
 * <p>Every ten minutes of change it also keeps a copy in States/Recovery, one per ten-minute slot
 * over the last four hours ({@link #SLOTS}), overwritten in turn and never deleted. The session file
 * is the belt; these are the braces, for a scene that went wrong and was then autosaved.
 *
 * <p>A crash is a run that never said goodbye. Each process records itself in States/running.json
 * (pid, the process's start instant, its session file and whether it is still reopening it) and
 * removes itself on a clean quit; an entry whose process is gone is a run that crashed or was killed.
 * The next launch of that session says so once. When it died while reopening the session twice in a
 * row, the session itself is the likely cause, so that launch starts empty instead and says how to
 * get the session back ({@link Prior#restoreLoop}).
 */
public final class Recovery {

    static final int SLOTS = 24;
    static final long SLOT_MS = 10 * 60_000L;
    /** Consecutive crashes while reopening a session before a launch stops reopening it. */
    static final int RESTORE_CRASH_LIMIT = 2;

    private static final String RUNNING = "running.json";
    private static final String PHASE_RESTORING = "restoring";
    private static final String PHASE_RUNNING = "running";
    // Every field below is this one object's, so nothing here is a mutable static.
    private static final Recovery recovery = new Recovery();

    @Nullable
    private String lastWritten; // the scene autosave last wrote, less the playhead
    private long lastSlot = -1;
    @Nullable
    private File ownFile;
    private int restoreCrashes;
    @Nullable
    private String notice;
    private boolean noticeSaysReopened;

    /** What the last run of this session left behind. */
    public record Prior(boolean crashed, boolean whileRestoring, int restoreCrashes, @Nullable Instant savedAt) {
        /** It died reopening its session often enough that reopening it again would likely crash too. */
        public boolean restoreLoop() {
            return restoreCrashes >= RESTORE_CRASH_LIMIT;
        }
    }

    // ---- autosave ------------------------------------------------------------------------------

    /**
     * Write this window's session if the scene changed since the last write. On the EDT. True when
     * the file holds this scene, or will once the queued write lands; false when the scene could not
     * be read.
     */
    static boolean autosave(File sessionFile) {
        JSONObject json;
        try {
            json = State.snapshot();
        } catch (RuntimeException e) { // a scene caught half-built; the next tick sees it whole
            Log.warn("Autosave could not read the scene", e);
            return false;
        }
        String compared = withoutPlayhead(json);
        if (compared.equals(recovery.lastWritten))
            return true;
        recovery.lastWritten = compared;
        State.write(json, sessionFile.getParent(), sessionFile.getName());

        long slot = System.currentTimeMillis() / SLOT_MS;
        if (slot != recovery.lastSlot) {
            recovery.lastSlot = slot;
            File dir = recoveryDir();
            if (dir.isDirectory() || dir.mkdirs())
                State.write(json, dir.getPath(), slotName(sessionFile, slot));
        }
        return true;
    }

    /** The scene as written elsewhere (quit, Quick Save, Save As): nothing new for autosave to write. */
    static void written() {
        try {
            recovery.lastWritten = withoutPlayhead(State.snapshot());
        } catch (RuntimeException e) {
            recovery.lastWritten = null; // the next tick writes it again, which is harmless
        }
    }

    // While a movie plays the playhead moves every frame; that alone is not worth a write. It is
    // saved with the next real change and on quit.
    static String withoutPlayhead(JSONObject json) {
        JSONObject main = json.optJSONObject("org.helioviewer.jhv.state");
        if (main == null || !main.has("time"))
            return json.toString();
        Object time = main.remove("time");
        try {
            return json.toString();
        } finally {
            main.put("time", time);
        }
    }

    static String slotName(File sessionFile, long slot) {
        String name = sessionFile.getName();
        String base = name.toLowerCase().endsWith(".jhv") ? name.substring(0, name.length() - 4) : name;
        String owner = Integer.toHexString(sessionFile.getAbsolutePath().hashCode()); // two "session.jhv" in two folders
        return base + " (" + owner + ") recovery " + Math.floorMod(slot, SLOTS) + ".jhv";
    }

    public static File recoveryDir() {
        return new File(Directories.STATES.getPath(), "Recovery");
    }

    // ---- crash detection -----------------------------------------------------------------------

    /**
     * Record this run against its session file and report how the last run of it ended. Called once,
     * before the startup decides whether to reopen the session.
     */
    public static Prior start(File sessionFile) {
        if (java.awt.GraphicsEnvironment.isHeadless()) // a script's run quits without the quit path, so it would read as a crash
            return new Prior(false, false, 0, null);
        recovery.ownFile = sessionFile;
        Path states = Path.of(Directories.STATES.getPath());
        Prior[] prior = {new Prior(false, false, 0, null)};
        update(states, map -> {
            prior[0] = takeDead(map, sessionFile.getAbsolutePath(), Recovery::alive);
            recovery.restoreCrashes = prior[0].restoreCrashes();
            map.put(String.valueOf(pid()), entry(sessionFile, PHASE_RUNNING));
        });
        if (prior[0].crashed() && sessionFile.isFile())
            prior[0] = new Prior(true, prior[0].whileRestoring(), prior[0].restoreCrashes(),
                    Instant.ofEpochMilli(sessionFile.lastModified()));
        return prior[0];
    }

    /**
     * The dead runs of one session file, removed from the map and summed up. {@code restoreCrashes}
     * counts back-to-back deaths while reopening: one that died after reopening resets it.
     */
    static Prior takeDead(JSONObject map, String file, java.util.function.BiPredicate<String, JSONObject> isAlive) {
        boolean crashed = false;
        boolean whileRestoring = false;
        int restoreCrashes = 0;
        for (String key : new java.util.ArrayList<>(map.keySet())) {
            JSONObject e = map.optJSONObject(key);
            if (e == null || !file.equals(e.optString("file")) || isAlive.test(key, e))
                continue;
            map.remove(key);
            crashed = true;
            boolean restoring = PHASE_RESTORING.equals(e.optString("phase"));
            whileRestoring |= restoring;
            restoreCrashes = Math.max(restoreCrashes, restoring ? e.optInt("restoreCrashes", 0) + 1 : 0);
        }
        return new Prior(crashed, whileRestoring, restoreCrashes, null);
    }

    /** This window is reopening its session; a death from here on counts against the session. */
    public static void restoring() {
        phase(PHASE_RESTORING);
    }

    /** The session finished loading, however it went: a death from here on is not the session's. */
    public static void restored() {
        recovery.restoreCrashes = 0;
        phase(PHASE_RUNNING);
    }

    /** The session file this window writes changed (Save As, Open, New Session). */
    static void follow(File sessionFile) {
        recovery.lastWritten = null;
        if (recovery.ownFile == null) // not recorded (headless, or before start)
            return;
        recovery.ownFile = sessionFile;
        phase(null);
    }

    /** A clean quit: this run leaves no trace that reads as a crash. */
    static void end() {
        if (recovery.ownFile == null)
            return;
        update(Path.of(Directories.STATES.getPath()), map -> map.remove(String.valueOf(pid())));
    }

    private static void phase(@Nullable String phase) {
        File file = recovery.ownFile;
        if (file == null)
            return;
        update(Path.of(Directories.STATES.getPath()), map -> {
            String key = String.valueOf(pid());
            String now = phase != null ? phase : map.optJSONObject(key) == null ? PHASE_RUNNING
                    : map.getJSONObject(key).optString("phase", PHASE_RUNNING);
            map.put(key, entry(file, now));
        });
    }

    private static JSONObject entry(File sessionFile, String phase) {
        return new JSONObject().put("file", sessionFile.getAbsolutePath()).put("phase", phase)
                .put("started", startInstant(ProcessHandle.current())).put("restoreCrashes", recovery.restoreCrashes);
    }

    // Alive is the same process, not just the same pid: the OS hands out a dead window's pid again.
    private static boolean alive(String pid, JSONObject e) {
        long started = e.optLong("started", -1);
        try {
            return ProcessHandle.of(Long.parseLong(pid)).filter(ProcessHandle::isAlive)
                    .map(p -> started == -1 || startInstant(p) == started).orElse(false);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static long startInstant(ProcessHandle p) {
        return p.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
    }

    private static long pid() {
        return ProcessHandle.current().pid();
    }

    // Read, change and write running.json under a file lock: windows are separate processes.
    private static void update(Path states, java.util.function.Consumer<JSONObject> change) {
        Path file = states.resolve(RUNNING);
        try (FileChannel lockChannel = FileChannel.open(states.resolve(RUNNING + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            JSONObject map;
            try {
                map = Files.isRegularFile(file) ? new JSONObject(Files.readString(file, StandardCharsets.UTF_8)) : new JSONObject();
            } catch (org.json.JSONException e) { // emptied by a crash mid-write: start over
                map = new JSONObject();
            }
            change.accept(map);
            Files.writeString(file, map.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not update the crash record", e);
        }
    }

    // ---- what the next launch says -------------------------------------------------------------

    /** Remember what to tell the user once the window is up. */
    public static void tell(Prior prior, String sessionName, boolean reopened) {
        if (!prior.crashed())
            return;
        String folder = recoveryDir().getPath();
        if (!reopened) {
            recovery.notice = "HelioFITS Studio quit unexpectedly while reopening \"" + sessionName + "\" "
                    + prior.restoreCrashes() + " times in a row, so this time it started with an empty session.\n\n"
                    + "\"" + sessionName + "\" is unchanged: open it again from File > Open Recent. "
                    + "Earlier copies are kept in\n" + folder;
            return;
        }
        String when = prior.savedAt() == null ? ""
                : ", last saved " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC).format(prior.savedAt()) + " UTC";
        recovery.noticeSaysReopened = true;
        recovery.notice = "HelioFITS Studio did not quit normally last time. \"" + sessionName
                + "\" was reopened from its autosave" + when + ".\n\n"
                + "Changes are autosaved within a few seconds. Copies from the last few hours are kept in\n" + folder;
    }

    /** Show the notice, if there is one, once. On the EDT, with the window up. */
    static void showNotice() {
        String text = recovery.notice;
        recovery.notice = null;
        // A session opened from Finder at launch takes the autosave's place, so "reopened" would be untrue.
        if (recovery.noticeSaysReopened && org.helioviewer.jhv.io.CommandLine.desktopDocumentRequested())
            return;
        if (text != null)
            Message.info("Session recovered", text);
    }

    private Recovery() {}
}

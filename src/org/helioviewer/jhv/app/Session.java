package org.helioviewer.jhv.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.JOptionPane;
import javax.swing.Timer;

import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;

import org.json.JSONArray;
import org.json.JSONTokener;

// Per-window session continuity. Every window is a document with its own autosave file and a
// display name; each reopens on relaunch. Multi-window is one process per window (JHV's scene
// is all static state); a shared registry (windows.json) lets the primary reopen the extra
// windows on a cold start. The persistent NetFileCache is shared across processes, so reopening
// re-downloads nothing.
public final class Session {

    private static final String MAIN_FILE = "session-main.jhv"; // the primary window's autosave
    private static final String REGISTRY = "windows.json";       // extra windows to reopen
    private static final int AUTOSAVE_INTERVAL_MS = 30_000;

    private static volatile long changeCounter;
    private static long savedCounter;
    private static long autosavedCounter;
    private static Timer autosaveTimer;

    private static File sessionFile;   // where THIS window autosaves
    private static boolean named;      // sessionFile is a user-named .jhv (vs an auto file)

    // A window spawned by "Open New Window" carries its assigned session file; the user-launched
    // primary has none and owns its session file (remembered in Settings) plus the reopen fan-out.
    private static boolean isExtra() {
        return System.getProperty("jhv.sessionFile") != null;
    }

    public static boolean isExtraWindow() {
        return isExtra();
    }

    // Auto files (the primary default, or a spawned window's scratch file) read as "Untitled";
    // any other path is a user-named project whose name we show.
    private static boolean isAutoFile(File f) {
        String n = f.getName();
        return n.equals(MAIN_FILE) || (n.startsWith("window-") && n.endsWith(".jhv"));
    }

    public static void init() {
        String assigned = System.getProperty("jhv.sessionFile");
        if (assigned != null) {
            sessionFile = new File(assigned);
        } else {
            String mainPath = Settings.getProperty("session.mainFile"); // primary remembers its named file
            sessionFile = (mainPath != null && !mainPath.isBlank()) ? new File(mainPath) : new File(Directories.STATES.getPath(), MAIN_FILE);
        }
        named = !isAutoFile(sessionFile);
        updateTitle();

        if (isExtra()) {
            registryAdd(sessionFile); // so a relaunch reopens this window too
        } else {
            reopenExtraWindows(); // primary fans out the previously-open extra windows
        }

        Layers.addListener(new Layers.Listener() {
            @Override public void layerAdded(int index, Layer layer) { markDirty(); }
            @Override public void layerRemoved(int index, Layer layer) { markDirty(); }
            @Override public void layersCleared() { markDirty(); }
            @Override public void nameUpdated(Layer layer) {}
            @Override public void layerUpdated(Layer layer) {}
            @Override public void timeUpdated(Layer layer) {}
        });

        autosaveTimer = new Timer(AUTOSAVE_INTERVAL_MS, e -> autosaveIfChanged());
        autosaveTimer.setRepeats(true);
        autosaveTimer.start();

        updateLive(true); // announce this window to the Window menu
    }

    // One-shot listeners fired (on the EDT) when a state load finishes — used to stop a button's
    // spinner once the reload actually completes.
    private static final List<Runnable> stateLoadListeners = new ArrayList<>();

    public static void onNextStateLoad(Runnable r) {
        stateLoadListeners.add(r);
    }

    public static void fireStateLoadComplete() {
        List<Runnable> copy = new ArrayList<>(stateLoadListeners);
        stateLoadListeners.clear();
        for (Runnable r : copy)
            r.run();
    }

    public static void markDirty() {
        changeCounter++;
    }

    public static void markSaved() {
        savedCounter = changeCounter;
        autosavedCounter = changeCounter;
    }

    public static boolean isDirty() {
        return changeCounter != savedCounter;
    }

    // The session to auto-restore on launch (this window's file), or null.
    public static File restoreCandidate() {
        return sessionFile != null && sessionFile.isFile() && sessionFile.length() > 0 ? sessionFile : null;
    }

    // Point this window at a named .jhv (after Save As / Load), so autosave follows it and the
    // name shows in the title. Keeps the reopen registry pointing at the new path.
    public static void setSessionFile(File file, boolean isNamed) {
        File old = sessionFile;
        sessionFile = file;
        named = isNamed;
        markSaved();
        updateTitle();
        updateLive(true); // reflect the new name in the Window menu
        if (isNamed)
            addRecent(file);
        if (isExtra()) {
            registryRemove(old);
            registryAdd(file);
        } else {
            Settings.setProperty("session.mainFile", file.getAbsolutePath()); // reopen this named file next launch
        }
    }

    @Nullable
    public static File currentSessionFile() {
        return sessionFile;
    }

    public static boolean isNamedSession() {
        return named;
    }

    // Quick Save (floppy icon): write the current state to the session's own file, no dialog.
    public static void quickSaveToCurrent() {
        if (sessionFile != null) {
            State.saveNow(sessionFile.getParent(), sessionFile.getName());
            markSaved();
        }
    }

    // Start New Session clears the document identity back to Untitled (auto file), so the name
    // field resets and the old named file is no longer reopened.
    public static void resetToUntitled() {
        String assigned = System.getProperty("jhv.sessionFile");
        sessionFile = assigned != null ? new File(assigned) : new File(Directories.STATES.getPath(), MAIN_FILE);
        named = false;
        if (assigned == null)
            Settings.setProperty("session.mainFile", ""); // primary: forget the named file
        markSaved();
        updateTitle();
        updateLive(true);
    }

    // ---- recent sessions (Open Recent menu) --------------------------------------------------

    private static final int MAX_RECENTS = 8;

    public static void addRecent(File f) {
        List<String> recents = recentSessions();
        String path = f.getAbsolutePath();
        recents.removeIf(p -> p.equals(path));
        recents.add(0, path);
        while (recents.size() > MAX_RECENTS)
            recents.remove(recents.size() - 1);
        Settings.setProperty("recentSessions", String.join("\n", recents));
    }

    public static List<String> recentSessions() {
        String v = Settings.getProperty("recentSessions");
        List<String> out = new ArrayList<>();
        if (v != null && !v.isBlank())
            for (String p : v.split("\n"))
                if (!p.isBlank() && new File(p).isFile())
                    out.add(p);
        return out;
    }

    public static void clearRecents() {
        Settings.setProperty("recentSessions", "");
    }

    // Inline rename from the name field: save the current state under `name` in the states dir
    // and make it this window's named project. No dialog.
    public static void renameCurrentSession(String name) {
        String clean = name.trim();
        if (clean.toLowerCase().endsWith(".jhv"))
            clean = clean.substring(0, clean.length() - 4);
        if (clean.isEmpty())
            return;
        String dir = Settings.getProperty("path.state");
        if (dir == null)
            dir = Directories.STATES.getPath();
        State.saveNow(dir, clean + ".jhv");
        setSessionFile(new File(dir, clean + ".jhv"), true);
    }

    // Default filename for the Save As dialog (the current name once one exists).
    public static String suggestedSaveName() {
        String n = displayName();
        return "Untitled".equals(n) ? "session" : n;
    }

    public static String displayName() {
        if (sessionFile == null || !named)
            return "Untitled";
        String n = sessionFile.getName();
        return n.toLowerCase().endsWith(".jhv") ? n.substring(0, n.length() - 4) : n;
    }

    private static void updateTitle() {
        MainFrame.setSessionName(displayName());
    }

    private static void autosaveIfChanged() {
        if (changeCounter == autosavedCounter || sessionFile == null)
            return;
        autosavedCounter = changeCounter;
        State.save(sessionFile.getParent(), sessionFile.getName());
    }

    // Invoked from ExitHooks on every quit path. `closingThisWindow` is true for the red close
    // button (this document is being dismissed → drop it from the reopen set) and false for
    // Cmd-Q / app quit (keep it, so relaunch brings every window back). Returns false to abort.
    public static boolean confirmExitAndAutosave(boolean closingThisWindow) {
        if (autosaveTimer != null)
            autosaveTimer.stop();

        boolean confirm = !"false".equals(Settings.getProperty("session.confirmOnExit"));
        if (confirm && isDirty() && !Layers.getImageLayers().isEmpty()) {
            Object[] options = {"Save", "Don't Save", "Cancel"};
            int choice = JOptionPane.showOptionDialog(MainFrame.get(),
                    "Save \"" + displayName() + "\" as a project before closing?\n\n" +
                            "It is auto-restored on the next launch either way.",
                    "JHelioviewer", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                if (autosaveTimer != null)
                    autosaveTimer.start(); // quit aborted: resume autosaving
                return false;
            }
            if (choice == JOptionPane.YES_OPTION)
                saveNamedNow();
        }

        if (sessionFile != null)
            State.saveNow(sessionFile.getParent(), sessionFile.getName());
        if (closingThisWindow && isExtra())
            registryRemove(sessionFile); // user dismissed this window: do not reopen it
        updateLive(false); // leave the Window menu
        return true;
    }

    private static void saveNamedNow() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String dir = Settings.getProperty("path.state");
        if (dir == null)
            dir = Directories.STATES.getPath();
        String file = "session-" + stamp + ".jhv";
        State.saveNow(dir, file);
        setSessionFile(new File(dir, file), true);
    }

    // ---- New Window + reopen fan-out (one process per window) --------------------------------

    // Launch another window as a separate process, assigned its own session file. Used both by
    // the "Open New Window" action (new empty file) and by the primary's reopen fan-out.
    public static void spawnWindow(File assignedSession) {
        try {
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            String classPath = System.getProperty("java.class.path");
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("-Djhv.sessionFile=" + assignedSession.getAbsolutePath());
            if (!classPath.contains(File.pathSeparator) && classPath.endsWith(".jar")) {
                cmd.add("-jar");
                cmd.add(classPath);
            } else {
                cmd.add("-cp");
                cmd.add(classPath);
                cmd.add("org.helioviewer.jhv.JHelioviewer");
            }
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (Exception e) {
            Log.error(e);
            Message.err("New window", "Could not open a new window: " + e.getMessage());
        }
    }

    // A fresh, unique session file for a brand-new empty window.
    public static File newWindowFile() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        return new File(Directories.STATES.getPath(), "window-" + stamp + ".jhv");
    }

    // ---- live window list (for the Window menu), keyed by process id -------------------------

    public record WindowInfo(long pid, String name, boolean current) {}

    private static long pid() {
        return ProcessHandle.current().pid();
    }

    public static long currentPid() {
        return pid();
    }

    private static File liveFile() {
        return new File(Directories.STATES.getPath(), "windows-live.json");
    }

    private static synchronized org.json.JSONObject readLive() {
        File f = liveFile();
        if (!f.isFile())
            return new org.json.JSONObject();
        try {
            return new org.json.JSONObject(new JSONTokener(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return new org.json.JSONObject();
        }
    }

    private static synchronized void writeLive(org.json.JSONObject map) {
        try {
            Files.writeString(liveFile().toPath(), map.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn(e);
        }
    }

    private static synchronized void updateLive(boolean present) {
        org.json.JSONObject map = readLive();
        String key = String.valueOf(pid());
        if (present)
            map.put(key, displayName());
        else
            map.remove(key);
        writeLive(map);
    }

    // Currently-alive windows across all processes; prunes entries whose process is gone.
    public static synchronized List<WindowInfo> liveWindows() {
        org.json.JSONObject map = readLive();
        List<WindowInfo> out = new ArrayList<>();
        boolean changed = false;
        for (String key : new ArrayList<>(map.keySet())) {
            long p;
            try {
                p = Long.parseLong(key);
            } catch (NumberFormatException e) {
                map.remove(key);
                changed = true;
                continue;
            }
            if (ProcessHandle.of(p).isPresent())
                out.add(new WindowInfo(p, map.optString(key, "Untitled"), p == pid()));
            else {
                map.remove(key);
                changed = true;
            }
        }
        if (changed)
            writeLive(map);
        out.sort((a, b) -> Long.compare(a.pid(), b.pid())); // stable order
        return out;
    }

    public static int liveWindowCount() {
        return readLive().length();
    }

    // Raise a window to the front: our own directly, another process's via System Events by pid.
    public static void raiseWindow(long targetPid) {
        if (targetPid == pid()) {
            MainFrame.toFront();
            return;
        }
        try {
            new ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to set frontmost of (first process whose unix id is " + targetPid + ") to true")
                    .start();
        } catch (Exception e) {
            Log.warn(e);
        }
    }

    private static void reopenExtraWindows() {
        for (File f : registryRead()) {
            if (f.equals(sessionFile))
                continue; // never re-spawn the primary
            spawnWindow(f);
        }
    }

    // ---- reopen registry (windows.json): the set of extra windows to bring back ---------------

    private static File registryFile() {
        return new File(Directories.STATES.getPath(), REGISTRY);
    }

    private static synchronized List<File> registryRead() {
        List<File> out = new ArrayList<>();
        File reg = registryFile();
        if (!reg.isFile())
            return out;
        try {
            JSONArray arr = new JSONArray(new JSONTokener(Files.readString(reg.toPath(), StandardCharsets.UTF_8)));
            for (Object o : arr) {
                File f = new File(o.toString());
                if (f.isFile() && f.length() > 0) // skip stale/empty entries
                    out.add(f);
            }
        } catch (Exception e) {
            Log.warn(e);
        }
        return out;
    }

    private static synchronized void registryWrite(List<File> files) {
        JSONArray arr = new JSONArray();
        for (File f : files)
            arr.put(f.getAbsolutePath());
        try {
            Files.writeString(registryFile().toPath(), arr.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn(e);
        }
    }

    private static synchronized void registryAdd(@Nullable File file) {
        if (file == null)
            return;
        List<File> files = registryRead();
        if (files.stream().noneMatch(f -> f.getAbsolutePath().equals(file.getAbsolutePath()))) {
            files.add(file);
            registryWrite(files);
        }
    }

    private static synchronized void registryRemove(@Nullable File file) {
        if (file == null)
            return;
        List<File> files = registryRead();
        files.removeIf(f -> f.getAbsolutePath().equals(file.getAbsolutePath()));
        registryWrite(files);
    }

    private Session() {}
}

package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;

/**
 * Which session a window opens at startup, for every combination that decides it.
 *
 * <p>The rule is "-state on the command line, else the pinned default (startup.loadState), else the
 * last autosave", and a spawned extra window only ever reopens its own file. It used to live inline
 * in CommandLine.setArguments, where nothing could see it drift. The first half of this check pins
 * the pure resolver against a written-out table, not against a copy of its own logic; the second
 * half runs the real setArguments and reads back which -state the window would load first.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.StartupPrecedenceCheck
 */
public final class StartupPrecedenceCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static File restore;
    private static File pinned;

    private static String name(@javax.annotation.Nullable Object o) {
        if (o == null)
            return "none";
        if (o == restore)
            return "autosave";
        if (o == pinned)
            return "pinned";
        return o instanceof String s && s.equals(pinned.getPath()) ? "<pinned path>" : String.valueOf(o);
    }

    private static void row(boolean extraWindow, @javax.annotation.Nullable File restoreFile,
                            @javax.annotation.Nullable String loadState,
                            CommandLine.StartupState.Source want, @javax.annotation.Nullable File wantFile) {
        CommandLine.StartupState got = CommandLine.resolveStartup(extraWindow, restoreFile, loadState, "last"); // the precedence below the blank rung
        File gotFile = got.uri() == null ? null : new File(got.uri());
        boolean ok = got.source() == want
                && (wantFile == null ? gotFile == null : wantFile.getAbsoluteFile().equals(gotFile));
        expect((extraWindow ? "extra window" : "primary") + ", restore " + name(restoreFile)
                + ", startup.loadState " + name(loadState) + ": want " + want + " " + name(wantFile)
                + ", got " + got.source() + " " + (gotFile == null ? "none" : gotFile.getName()), ok);
    }

    private static void modeRow(boolean extraWindow, @javax.annotation.Nullable File restoreFile,
                                @javax.annotation.Nullable String loadState, String mode,
                                CommandLine.StartupState.Source want, @javax.annotation.Nullable File wantFile) {
        CommandLine.StartupState got = CommandLine.resolveStartup(extraWindow, restoreFile, loadState, mode);
        File gotFile = got.uri() == null ? null : new File(got.uri());
        boolean ok = got.source() == want
                && (wantFile == null ? gotFile == null : wantFile.getAbsoluteFile().equals(gotFile));
        expect((extraWindow ? "extra window" : "primary") + ", restore " + name(restoreFile)
                + ", startup.loadState " + name(loadState) + ", startup.mode '" + mode + "': want " + want + " "
                + name(wantFile) + ", got " + got.source() + " " + (gotFile == null ? "none" : gotFile.getName()), ok);
    }

    private static File fileOf(String value) {
        return value.startsWith("file:") ? new File(URI.create(value)) : new File(value).getAbsoluteFile();
    }

    // setArguments appends the resolved session after whatever the user typed; loadRequest loads the first.
    private static void launch(String what, String[] args, File wantFirst, File wantAppended) {
        CommandLine.setArguments(args);
        List<String> states = CommandLine.getOptionValues("-state");
        boolean ok = !states.isEmpty()
                && wantFirst.getAbsoluteFile().equals(fileOf(states.get(0)))
                && wantAppended.getAbsoluteFile().equals(fileOf(states.get(states.size() - 1)));
        expect(what + ": -state values " + states, ok);
    }

    public static void main(String[] args) throws Exception {
        // Settings is written below; a check must never reach the real user.properties (Settings.java:30-42).
        System.setProperty("user.home", Files.createTempDirectory("hfs-startup-precedence").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        Directories.STATES.getFile().mkdirs();

        File states = Directories.STATES.getFile();
        restore = new File(states, "session-main.jhv");
        pinned = new File(states, "default.jhv");
        File explicit = new File(states, "Explicit.jhv");
        for (File f : new File[] {restore, pinned, explicit})
            Files.writeString(f.toPath(), "{}"); // readable and non-empty, so each one counts

        CommandLine.StartupState.Source NONE = CommandLine.StartupState.Source.NONE;
        CommandLine.StartupState.Source EXTRA = CommandLine.StartupState.Source.EXTRA_WINDOW;
        CommandLine.StartupState.Source PINNED = CommandLine.StartupState.Source.PINNED;
        CommandLine.StartupState.Source AUTOSAVE = CommandLine.StartupState.Source.AUTOSAVE;
        String pin = pinned.getPath();

        // The resolver, every combination: extra window, restore candidate, startup.loadState.
        // "true" and "false" are the Settings checkbox's values, not paths: both fall through.
        row(false, null, null, NONE, null);
        row(false, null, "true", NONE, null);
        row(false, null, "false", NONE, null);
        row(false, null, pin, PINNED, pinned);
        row(false, restore, null, AUTOSAVE, restore);
        row(false, restore, "true", AUTOSAVE, restore);
        row(false, restore, "false", AUTOSAVE, restore);
        row(false, restore, pin, PINNED, pinned);
        row(true, null, null, NONE, null);
        row(true, null, "true", NONE, null);
        row(true, null, "false", NONE, null);
        row(true, null, pin, NONE, null);
        row(true, restore, null, EXTRA, restore);
        row(true, restore, "true", EXTRA, restore);
        row(true, restore, "false", EXTRA, restore);
        row(true, restore, pin, EXTRA, restore);

        // startup.mode (HS-10): "blank" starts on the fresh-install scene instead of the autosave.
        // It sits below the pinned default and an extra window's own file, so it changes only the
        // last rung; any other value keeps the old answer.
        CommandLine.StartupState.Source BLANK = CommandLine.StartupState.Source.BLANK;
        modeRow(false, restore, null, "blank", BLANK, null);
        modeRow(false, restore, "true", "blank", BLANK, null);
        modeRow(false, restore, "false", "blank", BLANK, null);
        modeRow(false, null, null, "blank", BLANK, null);
        modeRow(false, restore, pin, "blank", PINNED, pinned);
        modeRow(true, restore, null, "blank", EXTRA, restore);
        modeRow(true, null, null, "blank", NONE, null);
        modeRow(false, restore, null, "last", AUTOSAVE, restore);
        modeRow(false, restore, null, "", BLANK, null);
        // The default: nothing set means blank, so a plain launch opens nothing.
        modeRow(false, restore, null, null, BLANK, null);
        modeRow(false, restore, pin, null, PINNED, pinned);
        modeRow(true, restore, null, null, EXTRA, restore);

        // The real startup path, primary window, with an autosave on disk. The first -state is the
        // one loadRequest loads, so an explicit one has to come first whatever is pinned.
        Settings.setProperty("startup.mode", "last"); // these rows are about what is pinned, not the blank default
        launch("no -state, nothing pinned: the autosave", new String[0], restore, restore);
        launch("explicit -state over the autosave", new String[] {"-state", explicit.getPath()}, explicit, restore);
        Settings.setProperty("startup.loadState", "true");
        launch("startup.loadState true: the autosave", new String[0], restore, restore);
        Settings.setProperty("startup.loadState", "false");
        launch("startup.loadState false: the autosave", new String[0], restore, restore);
        launch("explicit -state with startup.loadState false", new String[] {"-state", explicit.getPath()}, explicit, restore);
        Settings.setProperty("startup.loadState", pin);
        launch("pinned default over the autosave", new String[0], pinned, pinned);
        launch("explicit -state over the pinned default", new String[] {"-state", explicit.getPath()}, explicit, pinned);

        // A blank start loads no session, and an explicit -state still wins over it. The untitled
        // autosave it would otherwise reopen is copied aside first, since the blank session will
        // autosave over that file; the copy goes into Open Recent so it is one click away.
        Settings.setProperty("startup.loadState", "false");
        Settings.setProperty("startup.mode", "blank");
        String[] before = states.list();
        CommandLine.setArguments(new String[0]);
        expect("blank start: no -state, got " + CommandLine.getOptionValues("-state"),
                CommandLine.getOptionValues("-state").isEmpty());
        String[] after = states.list();
        java.util.List<String> added = new java.util.ArrayList<>(java.util.Arrays.asList(after));
        added.removeAll(java.util.Arrays.asList(before));
        File kept = added.size() == 1 ? new File(states, added.get(0)) : null;
        expect("blank start: the untitled autosave is copied aside, added " + added,
                kept != null && Files.readString(kept.toPath()).equals(Files.readString(restore.toPath())));
        expect("blank start: the copy is first in Open Recent",
                kept != null && org.helioviewer.jhv.app.Session.recentSessions().indexOf(kept.getAbsolutePath()) == 0);
        launch("explicit -state over a blank start", new String[] {"-state", explicit.getPath()}, explicit, explicit);
        // Blank is the default: with startup.mode never set, a plain launch loads nothing.
        Settings.setProperty("startup.mode", "");
        CommandLine.setArguments(new String[0]);
        expect("default mode: no -state, got " + CommandLine.getOptionValues("-state"), CommandLine.getOptionValues("-state").isEmpty());
        Settings.setProperty("startup.mode", "last");

        // A blank scene is not worth a recent: only an autosave with image layers is kept aside.
        File blankScene = new File(states, "blank-scene.jhv");
        Files.writeString(blankScene.toPath(), "{\"org.helioviewer.jhv.state\":{\"imageLayers\":[]}}");
        File fullScene = new File(states, "full-scene.jhv");
        Files.writeString(fullScene.toPath(), "{\"org.helioviewer.jhv.state\":{\"imageLayers\":[{}]}}");
        expect("a blank scene is not kept aside", !org.helioviewer.jhv.app.Session.hasImageLayers(blankScene));
        expect("a scene with image layers is", org.helioviewer.jhv.app.Session.hasImageLayers(fullScene));

        // Cancel in the download review: the window leaves the session being opened before anything is
        // cleared, so no autosave can land on it, and the Untitled autosave it replaces is kept aside.
        File opened = new File(states, "Opened.jhv");
        Files.writeString(opened.toPath(), "{\"org.helioviewer.jhv.state\":{\"imageLayers\":[{}]}}");
        Files.writeString(restore.toPath(), "{\"org.helioviewer.jhv.state\":{\"imageLayers\":[{}],\"keep\":1}}");
        org.helioviewer.jhv.app.Session.setSessionFile(opened, true);
        org.helioviewer.jhv.app.Session.expectStateLoad();
        org.helioviewer.jhv.app.Session.abandonLoad();
        File now = org.helioviewer.jhv.app.Session.currentSessionFile();
        expect("after Cancel the window no longer writes to the opened session, got " + now, now != null && !now.equals(opened) && restore.equals(now));
        expect("after Cancel the opened session is untouched", Files.readString(opened.toPath()).contains("imageLayers"));
        expect("after Cancel the window is Untitled", !org.helioviewer.jhv.app.Session.isNamedSession());
        boolean keptCopy = false;
        for (String n : states.list())
            if (n.startsWith("Untitled before blank start") && Files.readString(new File(states, n).toPath()).contains("\"keep\":1"))
                keptCopy = true;
        expect("after Cancel the Untitled autosave it will overwrite is kept aside", keptCopy);

        System.out.println(failures == 0 ? "StartupPrecedenceCheck: ok" : "StartupPrecedenceCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}

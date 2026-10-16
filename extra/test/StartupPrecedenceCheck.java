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
        CommandLine.StartupState got = CommandLine.resolveStartup(extraWindow, restoreFile, loadState, null);
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
        modeRow(false, restore, null, "", AUTOSAVE, restore);

        // The real startup path, primary window, with an autosave on disk. The first -state is the
        // one loadRequest loads, so an explicit one has to come first whatever is pinned.
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
        Settings.setProperty("startup.mode", "last");

        System.out.println(failures == 0 ? "StartupPrecedenceCheck: ok" : "StartupPrecedenceCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}

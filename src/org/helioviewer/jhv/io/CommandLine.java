package org.helioviewer.jhv.io;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;

public class CommandLine {

    /** One command-line option: its single-dash spelling, how many values follow it, and its help line. */
    public record Option(String name, int arity, String help) {}

    /** Process exit codes of the command line (docs/cli.md). */
    public enum Exit {
        OK(0), RUNTIME(1), USAGE(2), INPUT_NOT_FOUND(3), EXPORT_FAILED(4);

        public final int code;

        Exit(int _code) {
            code = _code;
        }
    }

    // Every option keeps its single-dash spelling; a double-dash form is an alias (--state is -state).
    private static final List<Option> OPTIONS = List.of(
            new Option("-load", 1, "Load or request a supported file at program start. The option can be used multiple times."),
            new Option("-request", 1, "Load a request file and issue a request at program start. The option can be used multiple times."),
            new Option("-state", 1, "Load state file. The window then belongs to that session, so autosave and quit write back to it."),
            new Option("-export", 1, "Export mode: png writes the current view as one PNG with the session stamped in, then exits. Needs -out. Never writes autosave."),
            new Option("-out", 1, "Where -export writes its file."),
            new Option("-dataset", 1, "Load a Heliad library dataset, named by its dataset_uid, its dataset_key or a former key, as one image layer, from its files between -start and -end. The option can be used multiple times."),
            new Option("-start", 1, "With -dataset: the first observation time to load, ISO 8601 UTC, Z and fractional seconds optional (2025-09-21T16:00:00.000)."),
            new Option("-end", 1, "With -dataset: the last observation time to load, ISO 8601 UTC."),
            new Option("-h", 0, "Show this help and exit."));

    private static final List<String> OPTION_NAMES = OPTIONS.stream().map(Option::name).toList();

    private static final List<String> EXPORT_FORMATS = List.of("png");

    private static final Set<String> uriSchemes = Set.of("jpip", "jpips", "http", "https", "file");

    private static String[] arguments;

    // A session file the desktop asked this app to open: a double-click in Finder, Open With, or a
    // drop on the Dock icon. macOS delivers that as an open-documents event, never as an argument,
    // so a launch from Finder looked exactly like a bare launch: the Welcome window came up, the
    // startup rung reopened the last session, and the file that was clicked never loaded. Until the
    // startup session has been opened, the clicked file takes its place (see foldIntoStartup).
    // One final holder rather than three mutable statics; every access holds CommandLine.class.
    private static final DesktopOpen desktop = new DesktopOpen();

    private static final class DesktopOpen {
        @Nullable
        URI document;
        boolean startupStateOpened;
        boolean requested;
    }

    /** The session a window opens at startup when the command line names none, and why. */
    public record StartupState(Source source, @Nullable URI uri) {
        public enum Source { NONE, EXTRA_WINDOW, PINNED, AUTOSAVE, BLANK }
    }

    /**
     * Precedence: an explicit -state on the command line, else a GUI-pinned default session
     * (startup.loadState), else the auto-restored last session. The command line is not an input
     * here because it always wins: setArguments appends this answer after it and loadRequest()
     * honors the first -state it sees. Pure, so StartupPrecedenceCheck can pin every combination.
     *
     * <p>A spawned window restores only its assigned session file (empty if brand-new); the pinned
     * default is a primary-window concept. "true" and "false" are the Settings checkbox's values,
     * not paths, and fall through to the autosave. {@code mode} is startup.mode: "blank" (HS-10)
     * replaces the autosave rung with the fresh-install scene; anything else keeps the autosave.
     */
    public static StartupState resolveStartup(boolean extraWindow, @Nullable java.io.File restore,
                                              @Nullable String loadState, @Nullable String mode) {
        if (extraWindow)
            return restore == null ? new StartupState(StartupState.Source.NONE, null)
                    : new StartupState(StartupState.Source.EXTRA_WINDOW, restore.toURI());
        if (loadState != null && !"false".equals(loadState) && !"true".equals(loadState))
            return new StartupState(StartupState.Source.PINNED, Path.of(loadState).toUri());
        if (BLANK_MODE.equals(mode))
            return new StartupState(StartupState.Source.BLANK, null);
        if (restore != null)
            return new StartupState(StartupState.Source.AUTOSAVE, restore.toURI());
        return new StartupState(StartupState.Source.NONE, null);
    }

    /** The startup.mode value for starting on the fresh-install scene (Settings, Startup). */
    public static final String BLANK_MODE = "blank";

    /**
     * A lone session path on the command line, as Windows and Linux pass a double-clicked file,
     * becomes {@code -state <path>}. A value that follows an option is that option's, so
     * {@code -load a.jhv} is left alone. Pure, for DesktopDocumentCheck.
     */
    static String[] promoteSessionArgument(String[] args) {
        List<String> out = new ArrayList<>(args.length + 1);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            boolean optionValue = i > 0 && args[i - 1].startsWith("-");
            if (!optionValue && !a.startsWith("-") && a.toLowerCase(java.util.Locale.ROOT).endsWith(".jhv"))
                out.add("-state");
            out.add(a);
        }
        return out.toArray(String[]::new);
    }

    /**
     * The desktop asked to open {@code uri} (a .jhv). Before the startup session has been opened
     * it replaces that session and returns true, so nothing more is to be done; afterwards it
     * returns false and the caller opens it the way Open Recent does. Either way the Welcome
     * window and the tour offer stand down (desktopDocumentRequested).
     */
    public static synchronized boolean foldIntoStartup(URI uri) {
        desktop.requested = true;
        if (desktop.startupStateOpened)
            return false;
        desktop.document = uri;
        return true;
    }

    /** True once the desktop has asked this app to open a session, at launch or later. */
    public static synchronized boolean desktopDocumentRequested() {
        return desktop.requested;
    }

    public static void setArguments(String[] rawArgs) {
        String[] args = promoteSessionArgument(normalize(rawArgs));
        arguments = args;
        // An export is a batch job: no reopened session, no pinned default, no crash-loop record, and
        // no -state appended; the -state it names (if any) is only read.
        if (isExportMode())
            return;
        java.io.File restore = org.helioviewer.jhv.app.Session.restoreCandidate();
        String sessionName = org.helioviewer.jhv.app.Session.displayName();
        // How the last run of this window's session ended, recorded before this one decides anything.
        org.helioviewer.jhv.app.Recovery.Prior prior = org.helioviewer.jhv.app.Recovery.start(
                org.helioviewer.jhv.app.Session.currentSessionFile());
        StartupState start = resolveStartup(org.helioviewer.jhv.app.Session.isExtraWindow(),
                restore, Settings.getProperty("startup.loadState"), Settings.getProperty("startup.mode"));
        boolean explicit = Arrays.asList(args).contains("-state");
        // Reopening the session crashed the last runs: start empty rather than crash again. Only the
        // automatic reopen; a session asked for on the command line or from Finder is still opened.
        // The primary window's own session only: an extra window has no blank start of its own.
        boolean reopens = !explicit && start.source() == StartupState.Source.AUTOSAVE;
        boolean skip = reopens && prior.restoreLoop();
        if (skip)
            start = new StartupState(StartupState.Source.BLANK, null);
        if (reopens)
            org.helioviewer.jhv.app.Recovery.tell(prior, sessionName, !skip);
        if (start.source() == StartupState.Source.BLANK && !explicit)
            org.helioviewer.jhv.app.Session.startBlank(); // keeps the session it would have reopened
        URI stateArg = start.uri();
        if (stateArg != null) {
            org.helioviewer.jhv.app.Session.expectStateLoad(); // hold the automatic saves until it lands
            arguments = Arrays.copyOf(args, args.length + 2);
            arguments[args.length] = "-state";
            arguments[args.length + 1] = stateArg.toString();
        }
    }

    public static void load() {
        // -load
        for (URI uri : getURIOptionValues("-load")) {
            Commands.loadImage(uri);
        }
        // -dataset [-start] [-end]: one range for every dataset on the line
        List<String> start = getOptionValues("-start"), end = getOptionValues("-end");
        for (String dataset : getOptionValues("-dataset"))
            HeliadDatasetLoad.load(dataset, start.isEmpty() ? null : start.get(0), end.isEmpty() ? null : end.get(0))
                    .exceptionally(t -> {
                        Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
                        Log.warn("-dataset " + dataset + ": " + cause.getMessage());
                        datasetFailed.set(true);
                        return null;
                    });
    }

    // Set when a -dataset could not be loaded, so an -export waiting for an image ends with
    // INPUT_NOT_FOUND instead of timing out. Written from the loader's callback.
    private static final java.util.concurrent.atomic.AtomicBoolean datasetFailed = new java.util.concurrent.atomic.AtomicBoolean();

    /** Whether a -dataset on this line failed to load. */
    public static boolean datasetFailed() {
        return datasetFailed.get();
    }

    /** Whether the line names a -state, which an -export must wait for before it shoots. */
    public static boolean hasStateArgument() {
        return !getOptionValues("-state").isEmpty();
    }

    // after DataSources is loaded
    public static void loadRequest() {
        // -request: works only for default server
        for (URI uri : getURIOptionValues("-request")) {
            Commands.loadRequest(uri);
        }
        openStateArgument(Commands::loadState);
    }

    /**
     * -state: the first one on the line, which is the user's when there is one. The window loads it
     * and then belongs to it, so autosave and quit write back to the file that was opened. Returns
     * what was handed to {@code load}, or null; {@code load} is a parameter so SessionStateArgCheck
     * can see which file would load and which file the window then saves to, without loading it.
     */
    @Nullable
    public static URI openStateArgument(java.util.function.Consumer<URI> load) {
        URI uri;
        synchronized (CommandLine.class) {
            desktop.startupStateOpened = true; // from here on a desktop open loads directly
            List<URI> states = getURIOptionValues("-state");
            // A file opened from the desktop during startup is the newest thing the user asked for.
            uri = desktop.document != null ? desktop.document : states.isEmpty() ? null : states.get(0);
        }
        if (uri == null)
            return null;
        // Hold the automatic saves until the scene really is this session's, and keep holding them
        // if the load fails: a restore that never happened must not be written over anything.
        org.helioviewer.jhv.app.Session.expectStateLoad();
        org.helioviewer.jhv.app.Session.onNextStateLoad(success -> {
            if (!success)
                org.helioviewer.jhv.app.Session.expectStateLoad();
        });
        load.accept(uri);
        // Never adopt a session data archive: autosave would write a .jhv over the zip and its data.
        if (!isExportMode() && "file".equals(uri.getScheme()) && !org.helioviewer.jhv.app.state.SessionArchive.isArchiveName(uri.getPath()))
            org.helioviewer.jhv.app.Session.adoptSessionFile(new java.io.File(uri));
        return uri;
    }

    private static List<URI> getURIOptionValues(String param) {
        List<URI> uris = new ArrayList<>();
        for (String value : getOptionValues(param)) {
            try {
                URI uri = resolveLocation(value);
                if (uri != null)
                    uris.add(uri);
            } catch (Exception e) {
                Log.warn(e);
            }
        }
        return uris;
    }

    @Nullable
    private static URI resolveLocation(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme != null && uriSchemes.contains(scheme.toLowerCase()))
                return uri;
        } catch (URISyntaxException ignored) {
            // The argument may still be a valid local path.
        }

        Path path = Path.of(value);
        if (Files.isReadable(path))
            return path.toUri();

        Log.warn("File not found: " + value);
        return null;
    }

    /**
     * Method that looks for options in the command line.
     *
     * @param param name of the option.
     * @return the values associated to the option.
     */
    static List<String> getOptionValues(String param) { // package-private for StartupPrecedenceCheck
        List<String> values = new ArrayList<>();
        if (arguments == null)
            return values;

        for (int i = 0; i < arguments.length; i++) {
            if (!param.equals(arguments[i]))
                continue;

            if (i + 1 == arguments.length || arguments[i + 1].startsWith("-")) {
                Log.warn("Missing value for command line option: " + param);
                continue;
            }
            values.add(arguments[++i]);
        }
        return values;
    }

    /** The help text, generated from the option table. */
    public static String getUsageMessage() {
        StringBuilder sb = new StringBuilder("The following command-line options are available:\n");
        for (Option o : OPTIONS) {
            sb.append("\n").append(o.name());
            if (o.arity() > 0)
                sb.append(" <value>");
            sb.append("\n       ").append(o.help()).append('\n');
        }
        sb.append("\nA double-dash spelling of any option (--state, --export, --help) is an alias.\n")
                .append("Exit codes: 0 ok, 1 runtime error, 2 usage error, 3 input not found, 4 export failed.");
        return sb.toString();
    }

    /** The one place the command line writes to a terminal: help, usage errors and export failures. */
    public static void emit(java.io.PrintStream to, String text) {
        to.println(text);
    }

    /** -h or --help anywhere on the line. */
    public static boolean wantsHelp(String[] args) {
        return Arrays.stream(args).anyMatch(a -> "-h".equals(a) || "--help".equals(a));
    }

    /** A double-dash alias becomes its single-dash option; anything else is unchanged. Pure. */
    static String[] normalize(String[] args) {
        String[] out = args.clone();
        for (int i = 0; i < out.length; i++)
            if (out[i].startsWith("--") && OPTION_NAMES.contains(out[i].substring(1)))
                out[i] = out[i].substring(1);
        return out;
    }

    /**
     * The problem with a command line, as an exit code and a message, or null when it is fine. Pure
     * apart from reading the file system for the input files, so CommandLineOptionsCheck can drive it.
     * An unknown option is a usage error (macOS' -psn_ launch argument is not one); {@code -load} and
     * {@code -request} values may be URLs and are not looked up.
     */
    @Nullable
    public static Problem validate(String[] rawArgs) {
        String[] args = normalize(rawArgs);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("-") || a.startsWith("-psn_"))
                continue;
            if (!OPTION_NAMES.contains(a))
                return new Problem(Exit.USAGE, "Unknown option: " + a);
            if (OPTIONS.stream().filter(o -> o.name().equals(a)).findFirst().orElseThrow().arity() > 0
                    && (i + 1 == args.length || args[i + 1].startsWith("-")))
                return new Problem(Exit.USAGE, "Missing value for command line option: " + a);
            if (a.equals("-state") && "file".equals(schemeOf(args[i + 1])) && !Files.isReadable(Path.of(args[i + 1])))
                return new Problem(Exit.INPUT_NOT_FOUND, "File not found: " + args[i + 1]);
        }
        for (String time : valuesOf(args, "-start"))
            if (!isTime(time))
                return new Problem(Exit.USAGE, "-start is not an ISO 8601 time: " + time);
        for (String time : valuesOf(args, "-end"))
            if (!isTime(time))
                return new Problem(Exit.USAGE, "-end is not an ISO 8601 time: " + time);
        if (valuesOf(args, "-dataset").isEmpty() && !(valuesOf(args, "-start").isEmpty() && valuesOf(args, "-end").isEmpty()))
            return new Problem(Exit.USAGE, "-start and -end need -dataset");
        List<String> formats = valuesOf(args, "-export");
        if (!formats.isEmpty()) {
            if (!EXPORT_FORMATS.contains(formats.get(0)))
                return new Problem(Exit.USAGE, "Unknown export format: " + formats.get(0) + " (png is the first slice)");
            if (valuesOf(args, "-out").isEmpty())
                return new Problem(Exit.USAGE, "-export needs -out <path>");
        }
        return null;
    }

    public record Problem(Exit exit, String message) {}

    private static boolean isTime(String value) {
        try {
            HeliadDatasetLoad.parse(value);
            return true;
        } catch (HeliadDatasetLoad.Failed e) {
            return false;
        }
    }

    private static String schemeOf(String value) {
        try {
            String scheme = new URI(value).getScheme();
            return scheme == null || scheme.length() == 1 ? "file" : scheme.toLowerCase(java.util.Locale.ROOT); // a drive letter is a path
        } catch (URISyntaxException e) {
            return "file";
        }
    }

    private static List<String> valuesOf(String[] args, String param) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i + 1 < args.length; i++)
            if (param.equals(args[i]) && !args[i + 1].startsWith("-"))
                values.add(args[++i]);
        return values;
    }

    /** True when the line asks for -export: the window is a batch job, not a session. */
    public static boolean isExportMode() {
        return arguments != null && !valuesOf(arguments, "-export").isEmpty();
    }

    @Nullable
    public static String exportOut() {
        List<String> v = arguments == null ? List.of() : valuesOf(arguments, "-out");
        return v.isEmpty() ? null : v.get(0);
    }

}

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

    private static final String usageMessage = """
            The following command-line options are available:
            
            -load    file location
                   Load or request a supported file at program start. The option can be used multiple times.
            
            -request request file location
                   Load a request file and issue a request at program start. The option can be used multiple times.
            
            -state   state file
                   Load state file. The window then belongs to that session, so autosave and quit
                   write back to it.""";

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
        String[] args = promoteSessionArgument(rawArgs);
        arguments = args;
        StartupState start = resolveStartup(org.helioviewer.jhv.app.Session.isExtraWindow(),
                org.helioviewer.jhv.app.Session.restoreCandidate(),
                Settings.getProperty("startup.loadState"), Settings.getProperty("startup.mode"));
        if (start.source() == StartupState.Source.BLANK && !Arrays.asList(args).contains("-state"))
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
        if ("file".equals(uri.getScheme()))
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

    public static String getUsageMessage() {
        return usageMessage;
    }

}

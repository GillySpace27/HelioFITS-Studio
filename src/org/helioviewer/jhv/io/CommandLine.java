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

    public static void setArguments(String[] args) {
        arguments = args;
        // Precedence: an explicit -state on the command line, else a GUI-pinned default session
        // (startup.loadState), else the auto-restored last session. Command line always wins
        // because it is appended last and loadRequest() honors the first -state it sees.
        String stateArg = null;
        java.io.File restore = org.helioviewer.jhv.app.Session.restoreCandidate();
        String propState = Settings.getProperty("startup.loadState");
        if (org.helioviewer.jhv.app.Session.isExtraWindow()) {
            // A spawned window restores only its assigned session file (empty if brand-new);
            // the pinned default is a primary-window concept.
            if (restore != null)
                stateArg = restore.toURI().toString();
        } else if (propState != null && !"false".equals(propState) && !"true".equals(propState)) {
            stateArg = Path.of(propState).toUri().toString();
        } else if (restore != null) {
            stateArg = restore.toURI().toString();
        }
        if (stateArg != null) {
            org.helioviewer.jhv.app.Session.expectStateLoad(); // hold the automatic saves until it lands
            arguments = Arrays.copyOf(args, args.length + 2);
            arguments[args.length] = "-state";
            arguments[args.length + 1] = stateArg;
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
        // -state
        for (URI uri : getURIOptionValues("-state")) {
            // Hold the automatic saves until the scene really is this session's, and keep holding them
            // if the load fails: a restore that never happened must not be written over anything.
            org.helioviewer.jhv.app.Session.expectStateLoad();
            org.helioviewer.jhv.app.Session.onNextStateLoad(success -> {
                if (!success)
                    org.helioviewer.jhv.app.Session.expectStateLoad();
            });
            Commands.loadState(uri);
            if ("file".equals(uri.getScheme()))
                org.helioviewer.jhv.app.Session.adoptSessionFile(new java.io.File(uri));
            break;
        }
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
    private static List<String> getOptionValues(String param) {
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

package org.helioviewer.jhv.io;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The command line as a table: unknown options and bad values are usage errors with their own exit
 * code, double-dash spellings are aliases, the help text is generated from the table, and -export
 * switches the window to a batch job that appends no -state and reopens nothing.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.CommandLineOptionsCheck
 */
public final class CommandLineOptionsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static CommandLine.Exit exit(String... args) {
        CommandLine.Problem p = CommandLine.validate(args);
        return p == null ? null : p.exit();
    }

    public static void main(String[] args) throws Exception {
        // A throwaway home first: openStateArgument below reaches Recovery and Settings (see SessionStateArgCheck).
        System.setProperty("user.home", Files.createTempDirectory("hfs-cli-options").toString());
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        Directories.STATES.getFile().mkdirs();
        Path state = Files.createTempFile("cli-check", ".jhv");
        String s = state.toString();

        expect("a plain line is fine", exit("-load", "http://example.org/a.fits") == null);
        expect("an unknown option is a usage error", exit("--bogus", "--export", "png", "--out", "x.png") == CommandLine.Exit.USAGE);
        expect("a macOS -psn_ launch argument is not an unknown option", exit("-psn_0_12345") == null);
        expect("an option without its value is a usage error", exit("-state") == CommandLine.Exit.USAGE);
        expect("an unreadable -state is input-not-found", exit("--state", s + ".missing") == CommandLine.Exit.INPUT_NOT_FOUND);
        expect("a readable -state passes, in either spelling", exit("-state", s) == null && exit("--state", s) == null);
        expect("-export needs -out", exit("--export", "png") == CommandLine.Exit.USAGE);
        expect("-export knows png only", exit("--export", "mp4", "--out", "x") == CommandLine.Exit.USAGE);
        expect("-export png -out is fine", exit("--export", "png", "--out", "x.png", "--state", s) == null);

        expect("-dataset with a range is fine", exit("-dataset", "sdo/aia/-/-/1/193", "-start", "2025-09-21T16:00:00", "--end", "2025-09-21T18:00:00Z") == null);
        expect("-dataset alone is fine", exit("--dataset", "ds-7f3a") == null);
        expect("a -start that is not a time is a usage error", exit("-dataset", "x", "-start", "yesterday") == CommandLine.Exit.USAGE);
        expect("-end without -dataset is a usage error", exit("-end", "2025-09-21T18:00:00") == CommandLine.Exit.USAGE);
        expect("--state is an alias of -state", CommandLine.normalize(new String[]{"--state", s})[0].equals("-state"));
        expect("a value that merely starts with -- is left alone", CommandLine.normalize(new String[]{"-load", "--x"})[1].equals("--x"));

        String help = CommandLine.getUsageMessage();
        expect("the help lists every option of the table", help.contains("-load") && help.contains("-request")
                && help.contains("-state") && help.contains("-export") && help.contains("-out") && help.contains("-h")
                && help.contains("-dataset") && help.contains("-start") && help.contains("-end"));
        expect("the help names the exit codes", help.contains("3 input not found") && help.contains("4 export failed"));
        expect("--help and -h ask for help", CommandLine.wantsHelp(new String[]{"--help"}) && CommandLine.wantsHelp(new String[]{"-h"}));

        CommandLine.setArguments(new String[]{"--export", "png", "--out", "/tmp/o.png", "--state", s});
        expect("-export is export mode", CommandLine.isExportMode());
        expect("-out is read back", "/tmp/o.png".equals(CommandLine.exportOut()));
        expect("export mode appends no -state", CommandLine.getOptionValues("-state").size() == 1);
        CommandLine.openStateArgument(u -> { });
        expect("export mode adopts no session file", !state.toFile().equals(org.helioviewer.jhv.app.Session.currentSessionFile()));

        Files.deleteIfExists(state);
        if (failures > 0) {
            System.out.println("CommandLineOptionsCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("CommandLineOptionsCheck: all passed");
    }
}

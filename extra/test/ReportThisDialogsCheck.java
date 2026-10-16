package org.helioviewer.jhv.gui.dialog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every error and warning dialog offers {@link FeedbackDialog#REPORT_THIS}.
 *
 * <p>The button lives in one place: Message.err and Message.warn (gui/MessageHandler) add it. A
 * direct JOptionPane.showMessageDialog with ERROR_MESSAGE or WARNING_MESSAGE bypasses that and
 * shows no button (vault projects/jhelioviewer.md, item I: "About 12 direct JOptionPane dialogs
 * still lack the button"). This scans src/ for such calls, and for a hand-built JOptionPane given
 * an error or warning type whose options do not name REPORT_THIS, and fails on any that is not in
 * the short list below with its reason.
 *
 * <p>Questions are out of scope: showConfirmDialog and showOptionDialog ask the user to choose
 * (often with a warning icon, as "Discard changes?" does), and a Report button there would be a
 * third answer to a yes-or-no question.
 */
public final class ReportThisDialogsCheck {

    // path under src/ -> how many direct error or warning dialogs it may keep, and why.
    private static final Map<String, Integer> ALLOWED = Map.of(
            // fatalErr: Message.fatalErr calls System.exit(-1) as soon as the dialog closes, and
            // FeedbackDialog is not modal, so a Report button would open a window that dies at once.
            "org/helioviewer/jhv/gui/MessageHandler.java", 1,
            // The console handler's fatal error: runs before the GUI handler is installed (SPICE
            // loads before MainFrame), then the process exits.
            "org/helioviewer/jhv/app/Message.java", 1);

    private static final Pattern SHOW_MESSAGE = Pattern.compile("\\.showMessageDialog\\s*\\(");
    private static final Pattern SET_TYPE = Pattern.compile("\\.setMessageType\\s*\\(");
    private static final Pattern ERROR_OR_WARNING = Pattern.compile("JOptionPane\\.(ERROR|WARNING)_MESSAGE");

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path src = Path.of("src");
        if (!Files.isDirectory(src)) {
            System.out.println("SKIP: run from the repository root (no src/ here)");
            return;
        }
        Map<String, Integer> found = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(src)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String rel = src.relativize(file).toString().replace('\\', '/');
            int n = 0;
            // showMessageDialog has no way to add a button: an error or warning type is a miss.
            Matcher m = SHOW_MESSAGE.matcher(text);
            while (m.find())
                if (ERROR_OR_WARNING.matcher(statement(text, m.end())).find())
                    n++;
            // A hand-built pane is fine when its options name the button.
            m = SET_TYPE.matcher(text);
            while (m.find()) {
                String call = statement(text, m.end());
                if (ERROR_OR_WARNING.matcher(call).find()
                        && !text.substring(m.end(), Math.min(text.length(), m.end() + 400)).contains("REPORT_THIS"))
                    n++;
            }
            if (n > 0)
                found.put(rel, n);
        }

        for (Map.Entry<String, Integer> e : found.entrySet()) {
            int allowed = ALLOWED.getOrDefault(e.getKey(), 0);
            expect(e.getValue() <= allowed, e.getKey() + ": " + e.getValue() + " direct error or warning dialog(s) without "
                    + FeedbackDialog.REPORT_THIS + " (allowed " + allowed + "); use Message.err or Message.warn");
        }
        // The list must not go stale: an allowance nobody uses is one a new dialog could hide behind.
        for (Map.Entry<String, Integer> e : ALLOWED.entrySet())
            expect(found.getOrDefault(e.getKey(), 0).equals(e.getValue()),
                    e.getKey() + ": allowed " + e.getValue() + ", found " + found.getOrDefault(e.getKey(), 0));
        // And the scan finds what it is meant to: the allowed sites are real hits.
        expect(found.keySet().containsAll(ALLOWED.keySet()), "the scan sees the known fatal-error dialogs");

        if (failures != 0) {
            System.out.println("ReportThisDialogsCheck: " + failures + " failure(s)");
            System.exit(1);
        }
    }

    /** The text from {@code from} to the end of the statement (the next semicolon). */
    private static String statement(String text, int from) {
        int end = text.indexOf(';', from);
        return text.substring(from, end < 0 ? text.length() : end);
    }

    private static void expect(boolean ok, String what) {
        if (ok)
            System.out.println("  ok   " + what);
        else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

}

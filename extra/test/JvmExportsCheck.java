package org.helioviewer.jhv.gui.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Selecting an image layer builds a JHVRangeSlider, whose JIDE UI needs java.desktop/sun.swing.
 * The jar manifest exports it only for java -jar; a -cp launcher needs the flags. Starts child
 * JVMs with -cp: with the flags the slider builds, without them it fails and
 * JvmExports.missingMessage() says so.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.gui.component.JvmExportsCheck
 */
public final class JvmExportsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static int child(boolean flags, String mode) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-Djava.awt.headless=true");
        if (flags) {
            cmd.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
            cmd.add("--add-exports=java.desktop/sun.swing=ALL-UNNAMED");
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), JvmExportsCheck.class.getName(), mode));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        return p.waitFor();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) { // the child
            if (args[0].equals("slider"))
                new JHVRangeSlider(0, 10, 2, 8);
            else
                System.exit(org.helioviewer.jhv.app.JvmExports.missingMessage() == null ? 0 : 3);
            return;
        }
        expect("with the flags, -cp builds a JHVRangeSlider", child(true, "slider") == 0);
        expect("without them, -cp cannot (the check can fail)", child(false, "slider") != 0);
        expect("with the flags, nothing is reported missing", child(true, "message") == 0);
        expect("without them, the missing export is reported", child(false, "message") == 3);
        if (failures > 0)
            System.exit(1);
    }
}

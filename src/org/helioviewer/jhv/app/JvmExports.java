package org.helioviewer.jhv.app;

import java.util.ArrayList;
import java.util.List;

/**
 * JIDE's range slider reaches into java.desktop internals. The jar manifest exports them, but the
 * JVM honours that only for {@code java -jar}; a launcher that starts the main class with -cp
 * gets an IllegalAccessError on the first layer selection instead. This names the flags.
 */
public final class JvmExports {

    private static final String[] PACKAGES = {"sun.awt", "sun.swing"};

    /** Null when java.desktop exports what Studio needs to this code; otherwise the text to show. */
    public static String missingMessage() {
        Module desktop = ModuleLayer.boot().findModule("java.desktop").orElse(null);
        if (desktop == null)
            return null;
        Module self = JvmExports.class.getModule();
        List<String> flags = new ArrayList<>();
        for (String pkg : PACKAGES)
            if (!desktop.isExported(pkg, self))
                flags.add("--add-exports=java.desktop/" + pkg + "=ALL-UNNAMED");
        return flags.isEmpty() ? null
                : "HelioFITS Studio was started without a Java option it needs, so selecting an image layer will fail. "
                        + "Start it with java -jar HFStudio.jar, or add " + String.join(" ", flags) + " to the Java command.";
    }

    private JvmExports() {}
}

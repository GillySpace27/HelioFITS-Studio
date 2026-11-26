package org.helioviewer.jhv.gui;

import java.awt.Color;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.display.Display;

/**
 * Export friendly timelines: the pane and header colours are the canvas's background (black, or white) with a
 * contrasting ink, follow the white-background switch, and the theme's own come back when it is turned off.
 */
public final class TimelineExportCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-tl-export").toString());
        System.setProperty("java.awt.headless", "true");
        Theme theme = Theme.current();

        UIGlobals.timelineColors(theme);
        Color themed = UIGlobals.TL_SELECTED_INTERVAL_BACKGROUND_COLOR, themedHeader = UIGlobals.TL_TEXT_BACKGROUND_COLOR;
        expect("off by default, with no export background", !UIGlobals.isTimelineExport() && UIGlobals.timelineExportBackground() == null);

        Display.whiteBackground = false;
        UIGlobals.setTimelineExport(true);
        expect("on, over a black canvas: the pane and the header are black", Color.BLACK.equals(UIGlobals.TL_SELECTED_INTERVAL_BACKGROUND_COLOR)
                && Color.BLACK.equals(UIGlobals.TL_AVAILABLE_INTERVAL_BACKGROUND_COLOR) && Color.BLACK.equals(UIGlobals.TL_TEXT_BACKGROUND_COLOR));
        expect("with white ink", Color.WHITE.equals(UIGlobals.TL_LABEL_TEXT_COLOR) && Color.WHITE.equals(UIGlobals.TL_TEXT_COLOR));

        Display.whiteBackground = true;
        UIGlobals.timelineColors(theme);
        expect("over a white canvas: white, with black ink", Color.WHITE.equals(UIGlobals.TL_SELECTED_INTERVAL_BACKGROUND_COLOR)
                && Color.BLACK.equals(UIGlobals.TL_LABEL_TEXT_COLOR) && Color.WHITE.equals(UIGlobals.timelineExportBackground()));

        UIGlobals.setTimelineExport(false);
        expect("off again: the theme's colours are back", themed.equals(UIGlobals.TL_SELECTED_INTERVAL_BACKGROUND_COLOR)
                && themedHeader.equals(UIGlobals.TL_TEXT_BACKGROUND_COLOR) && UIGlobals.timelineExportBackground() == null);

        System.out.println(failures == 0 ? "TimelineExportCheck: ok" : "TimelineExportCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}

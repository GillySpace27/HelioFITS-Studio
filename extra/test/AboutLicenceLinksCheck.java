package org.helioviewer.jhv.gui.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every licence the About dialog links is in the jar, and the FFmpeg credit links the notices the live
 * updater (extra/ffmpeg/update_ffmpeg.py) maintains: FFmpeg-Notices.txt and GPL-3.0.txt. FFmpeg.txt and
 * FFmpeg-GPL.txt were written only by the retired root updater and say nothing about the shipped build.
 *
 * <p>Run: CP="bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')"
 * java -Djava.awt.headless=true -cp "$CP" org.helioviewer.jhv.gui.dialog.AboutLicenceLinksCheck
 */
public final class AboutLicenceLinksCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String html = AboutDialog.credits();
        Matcher m = Pattern.compile("href=['\"](/licenses/[^'\"]+)['\"]").matcher(html);
        List<String> links = new ArrayList<>();
        while (m.find())
            links.add(m.group(1));

        expect("the credits link at least one bundled licence (found " + links.size() + ")", !links.isEmpty());
        for (String link : links)
            expect(link + " is in the jar", AboutLicenceLinksCheck.class.getResource(link) != null);
        expect("the FFmpeg credit links /licenses/FFmpeg-Notices.txt", links.contains("/licenses/FFmpeg-Notices.txt"));
        expect("the FFmpeg credit links /licenses/GPL-3.0.txt", links.contains("/licenses/GPL-3.0.txt"));
        expect("no link to the superseded /licenses/FFmpeg.txt", !links.contains("/licenses/FFmpeg.txt"));
        expect("no link to the superseded /licenses/FFmpeg-GPL.txt", !links.contains("/licenses/FFmpeg-GPL.txt"));

        if (failures > 0)
            System.exit(1);
    }
}

package org.helioviewer.jhv.timelines.band;

import java.awt.Color;
import java.nio.file.Files;

import org.json.JSONObject;

/**
 * Colour levels of a timeline row: an IMAP speed row starts with slow, intermediate and fast levels and
 * a field component does not; an edited set is saved and restored, an unedited one is not written; a
 * level can be added to a row that came with none.
 */
public final class BandLevelsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static BandType type(String baseUrl) {
        for (BandDataset[] sets : ImapCatalog.catalogData().datasets().values())
            for (BandDataset set : sets)
                for (BandType t : set.bandTypes())
                    if (t.getBaseUrl().equals(baseUrl))
                        return t;
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-band-levels").toString());
        System.setProperty("java.awt.headless", "true");

        BandType speedType = type("imap:swapi-proton#proton_sw_speed_sun");
        BandType fieldType = type("imap:coho-hr#B_R");
        expect("the IMAP catalog has the rows this check needs", speedType != null && fieldType != null);
        if (speedType == null || fieldType == null) {
            System.exit(1);
            return;
        }
        Band speed = new Band(speedType, false);
        Band field = new Band(fieldType, false);
        expect("an IMAP speed row starts with colour levels, on", speed.hasLevelColors() && speed.isMulticolor());
        expect("a field component starts with none", !field.hasLevelColors() && !field.isMulticolor());
        Color slow = speed.levelColor(300), mid = speed.levelColor(500), fast = speed.levelColor(900);
        expect("300, 500 and 900 km/s get three different colours", slow != null && mid != null && fast != null
                && !slow.equals(mid) && !mid.equals(fast) && !slow.equals(fast));

        JSONObject plain = new JSONObject();
        speed.serialize(plain);
        expect("levels the row came with are not written to the session", !plain.has("levels"));

        speed.setLevels(new BandType.Level[]{new BandType.Level(-Double.MAX_VALUE, 450, Color.BLUE),
                new BandType.Level(450, Double.MAX_VALUE, Color.RED)});
        JSONObject edited = new JSONObject();
        speed.serialize(edited);
        expect("an edited set is written", edited.has("levels") && edited.getJSONArray("levels").length() == 2);
        Band back = (Band) Band.deserialize(new JSONObject(edited.toString()));
        expect("and comes back: 400 is blue, 500 red", Color.BLUE.equals(back.levelColor(400)) && Color.RED.equals(back.levelColor(500)));
        expect("the edge of an open level reads back as open (1e6 is red)", Color.RED.equals(back.levelColor(1e6)));

        field.setLevels(new BandType.Level[]{new BandType.Level(0, 5, Color.GREEN)});
        expect("a level can be added to a row that came with none, and turns the colouring on", field.hasLevelColors() && field.isMulticolor()
                && Color.GREEN.equals(field.levelColor(3)) && field.levelColor(-3) == null);
        field.setLevels(new BandType.Level[0]);
        expect("clearing the levels turns it off", !field.hasLevelColors() && !field.isMulticolor());

        System.out.println(failures == 0 ? "BandLevelsCheck: ok" : "BandLevelsCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}

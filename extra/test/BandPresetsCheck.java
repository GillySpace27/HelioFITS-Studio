package org.helioviewer.jhv.timelines.band;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;

/**
 * The Timeline Manager's presets: the rows of the stack are saved with their colour levels and come back; a
 * missing or damaged file is no presets; a deleted preset is kept in the retired file.
 */
public final class BandPresetsCheck {

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
        Path home = Files.createTempDirectory("hfs-band-presets");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.awt.headless", "true");
        Path file = BandPresets.file(home.resolve("Timelines"));

        expect("no file is no presets", BandPresets.load(file).isEmpty());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
        expect("a damaged file is no presets, not a crash", BandPresets.load(file).isEmpty());

        Band speed = new Band(type("imap:swapi-proton#proton_sw_speed_sun"), false);
        Band field = new Band(type("imap:coho-hr#B_R"), false);
        speed.setDataColor(Color.MAGENTA);
        JSONArray rows = BandPresets.capture(List.of(speed, field));
        expect("both rows are captured", rows.length() == 2);

        Map<String, JSONArray> presets = new LinkedHashMap<>();
        presets.put("Wind and field", rows);
        expect("a preset is saved", BandPresets.save(file, presets));
        Map<String, JSONArray> back = BandPresets.load(file);
        expect("and read back by name", back.size() == 1 && back.containsKey("Wind and field"));
        List<Band> bands = BandPresets.restore(back.get("Wind and field"));
        expect("its rows come back, in order", bands.size() == 2 && bands.get(0).getBandType().equals(speed.getBandType()) && bands.get(1).getBandType().equals(field.getBandType()));
        expect("with the colour the user set", Color.MAGENTA.equals(bands.get(0).getDataColor()));

        expect("a damaged row is left out and the rest come back", BandPresets.restore(new JSONArray("[1, {\"x\":2}, " + rows.getJSONObject(1) + "]")).size() == 1);

        BandPresets.retire(file, "Wind and field", rows);
        BandPresets.retire(file, "Wind and field", rows);
        Map<String, JSONArray> retired = BandPresets.load(file.resolveSibling("presets-retired.json"));
        expect("a deleted preset is kept, a second one of the same name beside it", retired.size() == 2 && retired.containsKey("Wind and field (2)"));

        System.out.println(failures == 0 ? "BandPresetsCheck: ok" : "BandPresetsCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}

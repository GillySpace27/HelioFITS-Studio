package org.helioviewer.jhv.timelines.band;

import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Against the real SPDF: one SWAPI proton listing and the newest published day, through the default fetcher
 * and the real CDF reader. Network, so it only runs when asked (IMAP_LIVE=1); offline checks cover the logic.
 */
public final class ImapLiveProbeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("IMAP_LIVE"))) {
            System.out.println("SKIP: set IMAP_LIVE=1");
            return;
        }
        System.setProperty("user.home", Files.createTempDirectory("hfs-imap-live").toString());
        System.setProperty("java.awt.headless", "true");
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();

        ImapCatalog.Product proton = ImapCatalog.product("swapi-proton");
        int year = LocalDate.now().getYear();
        NavigableMap<LocalDate, ImapListing.Entry> listing = ImapSource.listing(proton, year);
        if (listing.isEmpty())
            listing = ImapSource.listing(proton, year - 1);
        expect("SPDF lists SWAPI proton days (" + listing.size() + ")", !listing.isEmpty());
        LocalDate last = listing.lastKey();
        System.out.println("  last day listed: " + last + " " + listing.get(last).name());

        BandType speed = null;
        for (BandDataset[] sets : ImapCatalog.catalogData().datasets().values())
            for (BandDataset set : sets)
                for (BandType t : set.bandTypes())
                    if (t.getBaseUrl().equals("imap:swapi-proton#proton_sw_speed_sun"))
                        speed = t;
        long start = last.toEpochDay() * 86_400_000L;
        BandData data = ImapSource.dataRequest(Map.of(speed, false), start, start + 86_400_000L).call().getFirst();
        expect("the newest day gives minute samples (" + data.dates().length + ")", data.dates().length > 0);
        boolean sane = true;
        for (float v : data.values())
            sane &= Float.isFinite(v) && v >= 0 && v < 10_000;
        expect("speeds are finite and inside the file's stated 0 to 10000 km/s", sane);

        BandType br = null;
        for (BandDataset[] sets : ImapCatalog.catalogData().datasets().values())
            for (BandDataset set : sets)
                for (BandType t : set.bandTypes())
                    if (t.getBaseUrl().equals("imap:coho-hr#B_R"))
                        br = t;
        BandData hourly = ImapSource.dataRequest(Map.of(br, false), start - 86_400_000L, start + 86_400_000L).call().getFirst();
        expect("two days of merged hourly B_R give whole hours of one-minute points (" + hourly.dates().length + ")",
                hourly.dates().length > 0 && hourly.dates().length % 60 == 0);
        System.exit(failures > 0 ? 1 : 0);
    }

}

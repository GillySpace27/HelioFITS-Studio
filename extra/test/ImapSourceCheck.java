package org.helioviewer.jhv.timelines.band;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.YAxis;

import org.json.JSONObject;

/**
 * IMAP source, offline (an injected fetcher and day reader): days are joined with a missing day and a bad day
 * and the gap stays blank; an hourly COHO row covers [h, h+1) with no blank minute inside it or at a seam
 * between two requests; the MAG span cap refuses and reserves nothing; a saved session restores the base URL;
 * a failed listing falls back to the saved one and is not asked again at once.
 */
public final class ImapSourceCheck {

    private static final long DAY = 86_400_000L;
    private static final long MINUTE = 60_000L;
    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final String SPEED = "proton_sw_speed_sun";
    private static final int GAP_FROM = 600;
    private static final int GAP_TO = 610;

    private static final Set<String> badFiles = ConcurrentHashMap.newKeySet();
    private static final List<String> asked = new CopyOnWriteArrayList<>(); // appended by download threads
    private static final AtomicInteger listingCalls = new AtomicInteger();
    private static boolean listingDown;
    private static String cohoText = "";

    private static String row(String name, String date, String size) {
        return "<tr><td><a href=\"" + name + "\">" + name + "</a></td><td align=\"right\">" + date
                + "  </td><td align=\"right\">" + size + "</td></tr>\n";
    }

    private static String cdfName(LocalDate d) {
        return "imap_swapi_l3a_proton-sw_" + d.toString().replace("-", "") + "_v002.0011.cdf";
    }

    private static ImapSource.Env env(File dir, AtomicLong clock) {
        ImapSource.Fetcher fetcher = new ImapSource.Fetcher() {
            @Override
            public String text(URI uri) throws IOException {
                listingCalls.incrementAndGet();
                if (listingDown)
                    throw new IOException("offline");
                String u = uri.toString();
                if (u.contains("proton-sw/2026/")) {
                    // 2026-01-24 is not published; 2026-01-25 is published but its file is corrupt
                    StringBuilder sb = new StringBuilder("<table>\n");
                    for (int d : new int[]{23, 25, 26})
                        sb.append(row(cdfName(LocalDate.of(2026, 1, d)), "2026-01-" + d + " 10:00", "174K"));
                    sb.append(row("imap_swapi_l3a_proton-sw_20260126_v001.cdf", "2026-01-26 09:00", "100K"));
                    sb.append(row("imap_swapi_l3a_proton-sw-ialirt_20260127_v001.cdf", "2026-01-27 09:00", "1K"));
                    return sb.append("</table>").toString();
                }
                if (u.contains("coho1hr")) {
                    return "<table>\n" + row("imap_daily.txt", "2026-11-25 10:00", "10K")
                            + row("imap_merged_hr2026.txt", "2026-11-25 10:00", "561K") + "</table>";
                }
                if (u.contains("norm-rtn")) // MAG: nothing published
                    return "<table>\n</table>";
                throw new IOException("404 " + u);
            }

            @Override
            public File file(URI uri, LongConsumer onBytes) throws IOException {
                asked.add(uri.toString());
                String path = uri.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                File f = File.createTempFile("imap", ".dat", dir);
                f.deleteOnExit();
                String body = name.startsWith("imap_merged") ? cohoText : badFiles.contains(name) ? "BAD" : name;
                Files.writeString(f.toPath(), body, StandardCharsets.UTF_8);
                return f;
            }
        };
        ImapSource.DayReader reader = file -> {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if ("BAD".equals(text))
                throw new IOException("corrupt CDF");
            LocalDate day = LocalDate.parse(text.substring("imap_swapi_l3a_proton-sw_".length(),
                    "imap_swapi_l3a_proton-sw_".length() + 8).replaceAll("(\\d{4})(\\d{2})(\\d{2})", "$1-$2-$3"));
            float[] speed = new float[ImapCdfReader.MINUTES_PER_DAY];
            for (int i = 0; i < speed.length; i++)
                speed[i] = i >= GAP_FROM && i < GAP_TO ? YAxis.BLANK : 400 + day.getDayOfMonth();
            Map<String, float[]> minutes = new LinkedHashMap<>();
            minutes.put(SPEED, speed);
            return new ImapCdfReader.DayMinutes("swapi-proton", day.toEpochDay() * DAY, minutes, Map.of(), "Please acknowledge SWAPI.");
        };
        return new ImapSource.Env(fetcher, reader, dir, clock::get);
    }

    private static BandType type(String baseUrl) {
        for (BandDataset[] sets : ImapCatalog.catalogData().datasets().values())
            for (BandDataset set : sets)
                for (BandType t : set.bandTypes())
                    if (t.getBaseUrl().equals(baseUrl))
                        return t;
        return null;
    }

    private static List<BandData> run(BandType t, long start, long end) throws Exception {
        return ImapSource.dataRequest(Map.of(t, false), start, end).call();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-imap-source").toString());
        System.setProperty("java.awt.headless", "true");
        File dir = Files.createTempDirectory("hfs-imap-listing").toFile();
        AtomicLong clock = new AtomicLong(LocalDate.of(2026, 2, 1).toEpochDay() * DAY);
        ImapSource.useEnv(env(dir, clock));

        // session: the base URL alone restores a band, with no catalog merged yet
        JSONObject saved = new JSONObject().put("bandType", new JSONObject().put("baseUrl", "imap:coho-hr#B_R")
                .put("name", "x").put("label", "x").put("unitLabel", "nT").put("range", new org.json.JSONArray().put(-15).put(15)));
        Band restored = (Band) Band.deserialize(new JSONObject().put("bandType", saved.getJSONObject("bandType")));
        JSONObject again = new JSONObject();
        restored.serialize(again);
        Band twice = (Band) Band.deserialize(again);
        expect("session round trip keeps the IMAP base URL", twice.getBandType().getBaseUrl().equals("imap:coho-hr#B_R")
                && twice.getBandType().equals(restored.getBandType()));
        expect("a restored IMAP band finds its source without the catalog", BandDownloads.hasCatalog(twice));
        expect("an unknown IMAP series has no catalog", !ImapSource.hasCatalog("imap:coho-hr#nope")
                && !ImapSource.hasCatalog("imap:nope#B_R"));

        // CDF days: 23 good, 24 not published, 25 corrupt, 26 good (highest version wins), 27 not published
        badFiles.add(cdfName(LocalDate.of(2026, 1, 25)));
        BandType speed = type("imap:swapi-proton#" + SPEED);
        expect("the catalog lists the proton speed series", speed != null);
        long d23 = LocalDate.of(2026, 1, 23).toEpochDay() * DAY;
        List<BandData> out = run(speed, d23, d23 + 5 * DAY);
        BandData bd = out.getFirst();
        int perDay = ImapCdfReader.MINUTES_PER_DAY - (GAP_TO - GAP_FROM);
        expect("two good days join, the missing and the corrupt day are skipped", bd.dates().length == 2 * perDay);
        boolean gapBlank = true, onMinutes = true;
        for (long t : bd.dates()) {
            long inDay = t % DAY / MINUTE;
            onMinutes &= t % MINUTE == 0;
            gapBlank &= !(inDay >= GAP_FROM && inDay < GAP_TO);
        }
        expect("a gap inside a day stays blank and every point is on a minute", gapBlank && onMinutes);
        expect("days carry their own values (23rd and 26th)", bd.values()[0] == 423 && bd.values()[bd.values().length - 1] == 426);
        expect("nothing is written for the unpublished 24th", java.util.Arrays.stream(bd.dates())
                .noneMatch(t -> t >= d23 + DAY && t < d23 + 3 * DAY));
        long before = asked.size();
        run(speed, d23, d23 + DAY);
        expect("a parsed day is kept in memory, not parsed again", asked.size() == before);
        expect("the 26th uses the higher version and i-ALiRT names are ignored", asked.stream()
                .anyMatch(u -> u.endsWith("20260126_v002.0011.cdf")) && asked.stream().noneMatch(u -> u.contains("ialirt")));
        expect("the CDF acknowledgement reaches the attribution footer",
                ImapCatalog.attribution(speed).contains("Please acknowledge SWAPI."));
        expect("the footer names NASA GSFC/SPDF", ImapCatalog.attribution(speed).startsWith("IMAP data: NASA GSFC/SPDF"));

        // listing: persisted, negative-cached
        clock.addAndGet(2 * 3_600_000L);
        listingDown = true;
        int calls = listingCalls.get();
        run(speed, d23, d23 + DAY);
        expect("a failed listing falls back to the copy already held", listingCalls.get() == calls + 1);
        clock.addAndGet(60_000L);
        run(speed, d23, d23 + DAY);
        expect("a failed listing is not asked again for a few minutes", listingCalls.get() == calls + 1);
        ImapSource.useEnv(env(dir, clock)); // a fresh launch, still offline
        listingDown = true;
        List<BandData> offline = run(speed, d23, d23 + DAY);
        expect("offline, the listing saved on disk still resolves the day", offline.getFirst().dates().length == perDay);
        File other = Files.createTempDirectory("hfs-imap-empty").toFile();
        ImapSource.useEnv(env(other, clock));
        boolean threw = false;
        try {
            run(speed, d23, d23 + DAY);
        } catch (IOException e) {
            threw = true;
        }
        expect("offline with no saved listing the request fails (and is not reserved)", threw);
        listingDown = false;
        ImapSource.useEnv(env(dir, clock));

        // COHO: each row covers [h, h+1) as 60 minutes; hour 1 has a fill B_R
        long jan1 = LocalDate.of(2026, 1, 1).toEpochDay() * DAY;
        StringBuilder coho = new StringBuilder();
        for (int doy : new int[]{1, 2})
            for (int h = 0; h < 24; h++) {
                String br = doy == 1 && h == 1 ? "9999.99" : Float.toString(doy * 100 + h);
                coho.append(String.format("%4d %3d %2d   0.99   10.0   20.0 %8s    4.00    5.00    6.00   400.0   390.0    -5.0     3.0   5.00 100000.%n",
                        2026, doy, h, br));
            }
        cohoText = coho.toString();
        BandType br = type("imap:coho-hr#B_R");
        BandData day1 = run(br, jan1, jan1 + DAY).getFirst();
        expect("24 hourly rows, one fill row: 23 hours of one-minute points", day1.dates().length == 23 * 60);
        boolean hourCovered = true;
        for (int i = 0; i < 60; i++)
            hourCovered &= has(day1, jan1 + 2 * 3_600_000L + i * MINUTE, 102f);
        expect("hour 2 is written for [02:00, 03:00) with its own value", hourCovered && !has(day1, jan1 + 3 * 3_600_000L, 102f));
        expect("the fill hour is blank, not interpolated", java.util.Arrays.stream(day1.dates())
                .noneMatch(t -> t >= jan1 + 3_600_000L && t < jan1 + 2 * 3_600_000L));
        expect("the yearly file is asked for with the listing mtime in its key", asked.stream()
                .anyMatch(u -> u.contains("imap_merged_hr2026.txt?lm=")));

        // seam between two requests: through the real minute cache, no blank minute at 23:59 -> 00:00
        BandData day2 = run(br, jan1 + DAY, jan1 + 2 * DAY).getFirst();
        Band band = new Band(br, false);
        band.addToCache(day1.values(), day1.dates());
        band.addToCache(day2.values(), day2.dates());
        boolean seamOk = !"--".equals(band.getStringValue(jan1 + DAY - MINUTE)) && !"--".equals(band.getStringValue(jan1 + DAY))
                && !"--".equals(band.getStringValue(jan1 + DAY + 30 * MINUTE));
        expect("no blank minute at the seam between two requests", seamOk);
        expect("the fill hour is blank in the cache too", "--".equals(band.getStringValue(jan1 + 90 * MINUTE)));

        // MAG cap: refused and not reserved; zooming in loads
        BandType mag = type("imap:mag-rtn#b_rtn_R");
        long span14 = 14 * DAY;
        expect("MAG allows exactly 14 days", ImapSource.refusal(mag.getBaseUrl(), jan1, jan1 + span14) == null);
        expect("MAG refuses a wider view with the zoom-in message", "Zoom in to load IMAP MAG (max 14 days)"
                .equals(ImapSource.refusal(mag.getBaseUrl(), jan1, jan1 + span14 + 1)));
        expect("proton, alpha and merged files have no cap", ImapSource.refusal("imap:swapi-proton#" + SPEED, jan1, jan1 + 400 * DAY) == null
                && ImapSource.refusal("imap:coho-hr#B_R", jan1, jan1 + 400 * DAY) == null);
        List<String> statuses = new CopyOnWriteArrayList<>(); // the status sink runs on download threads
        ImapCatalog.setStatusSink(statuses::add);
        Band magBand = new Band(mag, false);
        DrawController.setSelectedInterval(jan1, jan1 + 30 * DAY);
        onEdt(() -> magBand.fetchData(DrawController.selectedAxis));
        expect("a 30-day MAG view posts the zoom-in status", statuses.contains("Zoom in to load IMAP MAG (max 14 days)"));
        expect("a refused view starts no download", !downloading(magBand));
        DrawController.setSelectedInterval(jan1, jan1 + 5 * DAY);
        int listingsBefore = listingCalls.get();
        onEdt(() -> magBand.fetchData(DrawController.selectedAxis));
        // the offline fetchers finish in microseconds, so isDownloading() alone can already be false:
        // the request counts as started if it was active or reached the MAG listing, which only a reservation can do
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!downloading(magBand) && listingCalls.get() == listingsBefore && System.nanoTime() < deadline)
            Thread.sleep(10);
        expect("zooming in afterwards loads: the refused view had reserved nothing",
                downloading(magBand) || listingCalls.get() > listingsBefore);
        expect("zooming in clears the status", !statuses.isEmpty() && statuses.getLast().isEmpty());

        System.exit(failures > 0 ? 1 : 0);
    }

    // BandDownloads keeps plain collections that the app touches only on the event thread; so does this check
    private static void onEdt(Runnable r) throws Exception {
        EventQueue.invokeAndWait(r);
    }

    private static boolean downloading(Band band) throws Exception {
        boolean[] ret = new boolean[1];
        EventQueue.invokeAndWait(() -> ret[0] = band.isDownloading());
        return ret[0];
    }

    private static boolean has(BandData d, long t, float v) {
        for (int i = 0; i < d.dates().length; i++)
            if (d.dates()[i] == t)
                return d.values()[i] == v;
        return false;
    }

}

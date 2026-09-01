package org.helioviewer.jhv.io;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * Native LASCO level-0.5 FITS from NRL's LZ archive, which is the route to recent data: the
 * VSO's LASCO catalog simply stops in early 2025 (measured 2026-08-31: 27 records for six hours
 * of 2025-01-01, zero from 2025-06-01 on), while the LZ archive is current. Same autoindex
 * pattern the PUNCH client parses, one directory per UTC day per detector:
 * {@code level_05/YYMMDD/c2/NNNNNNNN.fts}.
 *
 * <p>Filenames are sequence numbers, not timestamps, so cadence thinning here is by index
 * within each day: the files sort in time order, and the layer keys frames by their FITS
 * DATE-OBS once decoded, so day granularity costs at most a few frames outside the exact hours
 * asked for.
 */
public final class LascoClient {

    private static final String BASE_URL = "https://lasco-www.nrl.navy.mil/lz/level_05";
    private static final Pattern FILE_PATTERN = Pattern.compile("href=\"(\\d+\\.fts)\"");

    public static void submitResolve(@Nonnull FitsRequest request, @Nonnull Consumer<List<URI>> receiver) {
        Task.submit("lasco", new Resolve(request), receiver::accept, "Error listing the LASCO archive");
    }

    private record Resolve(FitsRequest request) implements Callable<List<URI>> {
        @Override
        public List<URI> call() throws Exception {
            return query(request);
        }
    }

    static List<URI> query(FitsRequest request) throws Exception {
        return filterToSynoptic(list(request));
    }

    private static List<URI> list(FitsRequest request) throws Exception {
        String detector = request.product().toLowerCase(Locale.ROOT); // "c2" / "c3"
        long cadence = request.cadence();
        int perDay = cadence <= 0 ? Integer.MAX_VALUE
                : (int) Math.max(1, TimeUtils.DAY_IN_MILLIS / cadence);

        List<URI> out = new ArrayList<>();
        for (long day = TimeUtils.floorDay(request.startTime()); day <= request.endTime(); day += TimeUtils.DAY_IN_MILLIS) {
            LocalDateTime date = LocalDateTime.ofEpochSecond(day / 1000, 0, ZoneOffset.UTC);
            String dirUrl = String.format("%s/%02d%02d%02d/%s/",
                    BASE_URL, date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(), detector);
            String html = readIndex(dirUrl);
            if (html == null) // a day the archive does not have is a gap, not an error
                continue;

            List<String> files = new ArrayList<>();
            Matcher m = FILE_PATTERN.matcher(html);
            while (m.find())
                files.add(m.group(1));
            files.sort(null); // fixed-width numeric names, lexicographic == chronological

            int step = perDay == Integer.MAX_VALUE ? 1 : Math.max(1, (int) Math.ceil(files.size() / (double) perDay));
            int kept = 0;
            for (int i = 0; i < files.size(); i += step) {
                out.add(URI.create(dirUrl + files.get(i)));
                kept++;
            }
            Log.info("LASCO " + detector + " " + dirUrl + " -> " + files.size() + " files, kept " + kept);
        }
        return out;
    }

    /** Enough for any LASCO primary header; 180 cards. */
    private static final int HEADER_BYTES = 14400;
    private static final int PROBE_THREADS = 6;

    /**
     * Drop the frames that are not part of the synoptic programme.
     *
     * <p>Once a day LASCO runs a filter sequence, and those frames land in the same directory as
     * everything else: on 2025-09-20 the C3 day held 108 Clear full-frame images plus a Blue, an
     * Orange, a DeepRd and an IR at 60 to 300 s against the usual 17.6 s, and two 512 subframes
     * binned two by two. In a movie they read as noise, because they are a different instrument
     * configuration rather than a different moment.
     *
     * <p>The keeper is whichever (filter, polarizer, width) combination is most common among the
     * frames asked for, which needs no per-detector lore and follows the programme if it changes.
     * Headers are read by taking the first few kilobytes of each file and closing the stream, so a
     * rejected frame never costs its two megabytes.
     */
    private static List<URI> filterToSynoptic(List<URI> candidates) throws Exception {
        if (candidates.size() < 4) // too few to have a majority worth trusting
            return candidates;

        List<Config> configs;
        ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS);
        try {
            List<Future<Config>> futures = new ArrayList<>(candidates.size());
            for (URI uri : candidates)
                futures.add(pool.submit(() -> readConfig(uri)));
            configs = new ArrayList<>(candidates.size());
            for (Future<Config> f : futures)
                configs.add(f.get());
        } finally {
            pool.shutdown();
        }

        Map<Config, Long> counts = configs.stream()
                .filter(c -> c != Config.UNKNOWN)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        if (counts.isEmpty()) // nothing readable: keep everything rather than empty the layer
            return candidates;
        Config keep = Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();

        List<URI> out = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Config c = configs.get(i);
            // An unreadable header is kept: a probe that failed is not evidence about the frame.
            if (c == Config.UNKNOWN || c.equals(keep))
                out.add(candidates.get(i));
        }
        Log.info("LASCO keeping " + keep + ": " + out.size() + " of " + candidates.size() + " frames");
        return out;
    }

    /** The parts of a LASCO header that decide whether two frames belong in the same movie. */
    private record Config(String filter, String polar, int width) {
        private static final Config UNKNOWN = new Config("?", "?", 0);

        @Override
        public String toString() {
            return filter + '/' + polar + ' ' + width + "px";
        }
    }

    private static Config readConfig(URI uri) {
        try (NetClient nc = NetClient.prefix(uri, HEADER_BYTES)) {
            if (!nc.isSuccessful())
                return Config.UNKNOWN;
            // A 206 gives exactly the prefix; a server ignoring Range gives 200 and the whole file,
            // so take what is there rather than insisting on the full count.
            okio.BufferedSource source = nc.getSource();
            source.request(HEADER_BYTES);
            byte[] head = source.getBuffer().readByteArray(Math.min(HEADER_BYTES, source.getBuffer().size()));
            String filter = null, polar = null;
            int width = 0;
            for (int i = 0; i + 80 <= head.length; i += 80) {
                String card = new String(head, i, 80, StandardCharsets.ISO_8859_1);
                if (card.startsWith("END "))
                    break;
                String key = card.substring(0, Math.min(8, card.length())).trim();
                switch (key) {
                    case "FILTER" -> filter = cardValue(card);
                    case "POLAR" -> polar = cardValue(card);
                    case "NAXIS1" -> {
                        try {
                            width = Integer.parseInt(cardValue(card));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                    default -> {
                    }
                }
            }
            return filter == null || polar == null || width == 0
                    ? Config.UNKNOWN : new Config(filter, polar, width);
        } catch (Exception e) {
            return Config.UNKNOWN;
        }
    }

    private static String cardValue(String card) {
        String v = card.length() > 10 ? card.substring(10) : "";
        int slash = v.indexOf('/');
        if (slash >= 0)
            v = v.substring(0, slash);
        return v.trim().replace("'", "").trim();
    }

    private static String readIndex(String url) throws Exception {
        try (NetClient nc = NetClient.of(new URI(url), true, NetClient.NetCache.NETWORK)) {
            if (!nc.isSuccessful()) {
                Log.info("LASCO " + url + " -> not ok");
                return null;
            }
            return nc.getSource().readUtf8();
        }
    }

    private LascoClient() {
    }

}

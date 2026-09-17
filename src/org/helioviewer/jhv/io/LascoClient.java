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
import java.util.Objects;
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
import org.helioviewer.jhv.metadata.LascoPointing;
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
        Task.submitBackground("lasco", new Resolve(request), receiver::accept, "Error listing the LASCO archive");
    }

    /**
     * Lend pointing to a URI list that was not produced by {@link #query}, then hand the same list back.
     *
     * <p>A session restored from its cached URI list reloads the frames directly and never re-runs the
     * query, so the header probe that fills {@link LascoPointing}'s table never happened and every
     * placeholder frame came back unrotated: a movie spanning the 2025-08 C2 gap flipped 178 degrees
     * partway through. The probe is the same one the query does, over the restored URIs rather than a
     * fresh listing.
     */
    public static void submitLend(@Nonnull FitsRequest request, @Nonnull List<URI> uris, @Nonnull Consumer<List<URI>> receiver) {
        Task.submitBackground("lasco", new Lend(request, uris), receiver::accept, "Error reading LASCO headers");
    }

    private record Lend(FitsRequest request, List<URI> uris) implements Callable<List<URI>> {
        @Override
        public List<URI> call() {
            // The list is handed back whatever happens: a probe that could not reach the archive is a
            // reason to show the frames unrotated, not a reason to leave the layer at "Loading..."
            // for the rest of the session.
            try {
                List<LascoPointing.Frame> frames = probe(uris).stream().map(Probe::frame).filter(Objects::nonNull).toList();
                LascoPointing.lend(frames, otherTelescope(request, frames));
            } catch (Exception e) {
                Log.error("Could not read LASCO headers to lend pointing; frames with none will show unrotated", e);
            }
            return uris;
        }
    }

    private record Resolve(FitsRequest request) implements Callable<List<URI>> {
        @Override
        public List<URI> call() throws Exception {
            return query(request);
        }
    }

    static List<URI> query(FitsRequest request) throws Exception {
        return filterToSynoptic(request, list(request));
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
     * rejected frame never costs its two megabytes. The same read lends pointing to frames whose
     * header has none; see {@link LascoPointing}.
     */
    private static List<URI> filterToSynoptic(FitsRequest request, List<URI> candidates) throws Exception {
        if (candidates.isEmpty())
            return candidates;

        List<Probe> probes = probe(candidates);
        List<LascoPointing.Frame> frames = probes.stream().map(Probe::frame).filter(Objects::nonNull).toList();
        LascoPointing.lend(frames, otherTelescope(request, frames));

        if (candidates.size() < 4) // too few to have a majority worth trusting
            return candidates;

        Map<Config, Long> counts = probes.stream()
                .map(Probe::config)
                .filter(c -> c != Config.UNKNOWN)
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        if (counts.isEmpty()) // nothing readable: keep everything rather than empty the layer
            return candidates;
        Config keep = Collections.max(counts.entrySet(), Map.Entry.comparingByValue()).getKey();

        List<URI> out = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Config c = probes.get(i).config();
            // An unreadable header is kept: a probe that failed is not evidence about the frame.
            if (c == Config.UNKNOWN || c.equals(keep))
                out.add(candidates.get(i));
        }
        Log.info("LASCO keeping " + keep + ": " + out.size() + " of " + candidates.size() + " frames");
        return out;
    }

    /**
     * Headers of the other telescope over the stretch where this request's frames have no pointing, or
     * nothing when they all have some. C2 and C3 ride the same spacecraft, and in the 2025-08 C2 gap every
     * C3 header kept its CROTA.
     */
    private static List<LascoPointing.Frame> otherTelescope(FitsRequest request, List<LascoPointing.Frame> frames) throws Exception {
        String other = switch (request.product().toUpperCase(Locale.ROOT)) {
            case "C2" -> "C3";
            case "C3" -> "C2";
            default -> null;
        };
        List<LascoPointing.Frame> gaps = frames.stream().filter(LascoPointing.Frame::placeholder).toList();
        if (other == null || gaps.isEmpty())
            return List.of();

        long start = gaps.stream().mapToLong(LascoPointing.Frame::milli).min().orElseThrow();
        long end = gaps.stream().mapToLong(LascoPointing.Frame::milli).max().orElseThrow();
        Log.info("LASCO " + gaps.size() + " " + request.product() + " frames have no pointing; reading " + other + " headers from "
                + TimeUtils.format(start) + " to " + TimeUtils.format(end));
        FitsRequest otherRequest = new FitsRequest(request.archive(), request.level(), other, request.version(), request.cadence(), start, end);
        return probe(list(otherRequest)).stream().map(Probe::frame).filter(Objects::nonNull).toList();
    }

    private static List<Probe> probe(List<URI> uris) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS);
        try {
            List<Future<Probe>> futures = new ArrayList<>(uris.size());
            for (URI uri : uris)
                futures.add(pool.submit(() -> readProbe(uri)));
            List<Probe> probes = new ArrayList<>(uris.size());
            for (Future<Probe> f : futures)
                probes.add(f.get());
            return probes;
        } finally {
            pool.shutdown();
        }
    }

    /** The parts of a LASCO header that decide whether two frames belong in the same movie. */
    private record Config(String filter, String polar, int width) {
        private static final Config UNKNOWN = new Config("?", "?", 0);

        @Override
        public String toString() {
            return filter + '/' + polar + ' ' + width + "px";
        }
    }

    private record Probe(Config config, LascoPointing.Frame frame) {
        private static final Probe UNKNOWN = new Probe(Config.UNKNOWN, null);
    }

    private static Probe readProbe(URI uri) {
        try (NetClient nc = NetClient.prefix(uri, HEADER_BYTES)) {
            if (!nc.isSuccessful())
                return Probe.UNKNOWN;
            // A 206 gives exactly the prefix; a server ignoring Range gives 200 and the whole file,
            // so take what is there rather than insisting on the full count.
            okio.BufferedSource source = nc.getSource();
            source.request(HEADER_BYTES);
            byte[] head = source.getBuffer().readByteArray(Math.min(HEADER_BYTES, source.getBuffer().size()));
            String filter = null, polar = null, detector = null, date = null, time = null;
            int width = 0, height = 0;
            double crota1 = Double.NaN, crota2 = Double.NaN, crpix1 = Double.NaN, crpix2 = Double.NaN;
            for (int i = 0; i + 80 <= head.length; i += 80) {
                String card = new String(head, i, 80, StandardCharsets.ISO_8859_1);
                if (card.startsWith("END "))
                    break;
                String key = card.substring(0, Math.min(8, card.length())).trim();
                switch (key) {
                    case "FILTER" -> filter = cardValue(card);
                    case "POLAR" -> polar = cardValue(card);
                    case "DETECTOR" -> detector = cardValue(card);
                    case "DATE-OBS" -> date = cardValue(card);
                    case "TIME-OBS" -> time = cardValue(card);
                    case "CROTA1" -> crota1 = cardDouble(card);
                    case "CROTA2" -> crota2 = cardDouble(card);
                    case "CRPIX1" -> crpix1 = cardDouble(card);
                    case "CRPIX2" -> crpix2 = cardDouble(card);
                    case "NAXIS1" -> width = cardInt(card);
                    case "NAXIS2" -> height = cardInt(card);
                    default -> {
                    }
                }
            }
            Config config = filter == null || polar == null || width == 0
                    ? Config.UNKNOWN : new Config(filter, polar, width);
            return new Probe(config, frame(uri, detector, date, time, width, height, crota1, crota2, crpix1, crpix2));
        } catch (Exception e) {
            return Probe.UNKNOWN;
        }
    }

    private static LascoPointing.Frame frame(URI uri, String detector, String date, String time, int width, int height,
                                             double crota1, double crota2, double crpix1, double crpix2) {
        if (detector == null || date == null || time == null || width == 0 || height == 0)
            return null;
        long milli;
        try {
            milli = TimeUtils.parse(date.replace('/', '-') + 'T' + (time.length() > 8 ? time.substring(0, 8) : time));
        } catch (RuntimeException e) {
            return null;
        }
        String path = uri.getPath();
        return new LascoPointing.Frame(path.substring(path.lastIndexOf('/') + 1), detector, date, time, milli, width, height,
                crota1, crpix1, crpix2, LascoPointing.isPlaceholder(crota1, crota2, crpix1, crpix2, width, height));
    }

    static String cardValue(String card) {
        String v = card.length() > 10 ? card.substring(10).trim() : "";
        if (v.startsWith("'")) { // a quoted value may hold a slash, as DATE-OBS = '2025/08/31' does
            int end = v.indexOf('\'', 1);
            return (end > 0 ? v.substring(1, end) : v.substring(1)).trim();
        }
        int slash = v.indexOf('/');
        return (slash >= 0 ? v.substring(0, slash) : v).trim();
    }

    private static double cardDouble(String card) {
        try {
            return Double.parseDouble(cardValue(card));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int cardInt(String card) {
        try {
            return Integer.parseInt(cardValue(card));
        } catch (NumberFormatException e) {
            return 0;
        }
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

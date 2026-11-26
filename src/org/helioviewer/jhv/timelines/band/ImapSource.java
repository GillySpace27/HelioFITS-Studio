package org.helioviewer.jhv.timelines.band;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.NetClient;
import org.helioviewer.jhv.io.NetFileCache;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.timelines.draw.YAxis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import uk.ac.bristol.star.cdf.CdfContent;
import uk.ac.bristol.star.cdf.CdfReader;

/**
 * Loads IMAP bands (base URLs {@code imap:<product>#<series>}) from NASA SPDF. Nothing here rebins: SWAPI and
 * MAG days arrive as per-minute arrays from ImapCdfReader and are joined as they are (a minute with no sample
 * is not handed on, so it stays blank); each hourly COHO row covers [h, h+1) and is written as 60 one-minute
 * points, so there is no blank minute inside an hour and none at a request seam. A missing or bad day is
 * skipped and logged once; the other days still load.
 */
final class ImapSource {

    /** The network and the CDF parser, replaceable so a check runs offline. */
    interface Fetcher {
        String text(URI uri) throws IOException;

        File file(URI uri, LongConsumer onBytes) throws IOException;
    }

    interface DayReader {
        ImapCdfReader.DayMinutes read(File file) throws IOException;
    }

    /** dir: where persisted listings live (null: Studio's file cache). */
    record Env(Fetcher fetcher, DayReader reader, File dir, LongSupplier clock) {}

    private record Listed(NavigableMap<LocalDate, ImapListing.Entry> map, long at, boolean failed) {}

    private static final long DAY = TimeUtils.DAY_IN_MILLIS;
    private static final long MINUTE = TimeUtils.MINUTE_IN_MILLIS;
    private static final long HOUR = 60 * MINUTE;
    private static final long LISTING_TTL_CURRENT_YEAR = HOUR;
    private static final long LISTING_TTL_PAST_YEAR = 24 * HOUR;
    private static final long LISTING_FAILURE_TTL = 5 * MINUTE;
    private static final Pattern COHO_ROW = Pattern.compile(
            "<a href=\"(imap_merged_hr(\\d{4})\\.txt)\">[^<]*</a></td>\\s*<td[^>]*>\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})?");

    private static final AtomicReference<Env> ENV = new AtomicReference<>(defaultEnv());
    private static final ConcurrentHashMap<String, Listed> LISTINGS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Cache<String, ImapCdfReader.DayMinutes> DAYS = Caffeine.newBuilder().softValues().build();
    private static final Cache<String, ImapCoho.Columns> COHO = Caffeine.newBuilder().softValues().build();

    private ImapSource() {}

    // ---- environment

    private static Env defaultEnv() {
        return new Env(new Fetcher() {
            @Override
            public String text(URI uri) throws IOException {
                try (NetClient nc = NetClient.of(uri, false, NetClient.NetCache.NETWORK)) {
                    return new String(nc.getStream().readAllBytes(), StandardCharsets.UTF_8);
                }
            }

            @Override
            public File file(URI uri, LongConsumer onBytes) throws IOException {
                return NetFileCache.get(uri, onBytes).file();
            }
        }, file -> {
            try {
                CdfContent cdf = new CdfContent(new CdfReader(file));
                return ImapCdfReader.readDay(cdf, file.toURI());
            } catch (RuntimeException e) { // jcdf reports a corrupt file in many ways
                throw new IOException("Cannot read " + file.getName(), e);
            }
        }, null, System::currentTimeMillis);
    }

    /** For checks: swap the environment and forget every cached listing, day and warning. */
    static void useEnv(Env env) {
        ENV.set(env);
        LISTINGS.clear();
        WARNED.clear();
        DAYS.invalidateAll();
        COHO.invalidateAll();
    }

    private static File listingDir() {
        File dir = ENV.get().dir;
        return dir != null ? dir : Directories.FILECACHE.getFile();
    }

    // ---- what BandDownloads and Band ask

    static boolean handles(String baseUrl) {
        return ImapCatalog.handles(baseUrl);
    }

    static boolean hasCatalog(String baseUrl) {
        return ImapCatalog.series(baseUrl) != null;
    }

    static BandReaderHapi.DatasetRef dataset(String baseUrl) {
        ImapCatalog.Product p = product(baseUrl);
        return new BandReaderHapi.DatasetRef(ImapCatalog.SCHEME + p.id(), p.title());
    }

    /** null when the visible span may be loaded; else the message to show, and nothing is to be reserved. */
    static String refusal(String baseUrl, long start, long end) {
        ImapCatalog.Series s = ImapCatalog.series(baseUrl);
        if (s == null)
            return null;
        ImapCatalog.Product p = ImapCatalog.product(s.product());
        if (p.maxSpanDays() > 0 && end - start > p.maxSpanDays() * DAY)
            return "Zoom in to load IMAP " + p.instrument().toUpperCase(java.util.Locale.ROOT)
                    + " (max " + p.maxSpanDays() + " days)";
        return null;
    }

    private static ImapCatalog.Product product(String baseUrl) {
        ImapCatalog.Product p = ImapCatalog.product(ImapCatalog.productOf(baseUrl));
        if (p == null)
            throw new IllegalArgumentException("Unknown IMAP product: " + baseUrl);
        return p;
    }

    static Callable<List<BandData>> dataRequest(Map<BandType, Boolean> resolutions, long start, long end) {
        if (resolutions.isEmpty())
            throw new IllegalArgumentException("No IMAP series requested");
        ImapCatalog.Product p = null;
        List<BandType> types = new ArrayList<>();
        List<ImapCatalog.Series> series = new ArrayList<>();
        for (BandType type : resolutions.keySet()) {
            ImapCatalog.Series s = ImapCatalog.series(type.getBaseUrl());
            if (s == null)
                throw new IllegalArgumentException("Unknown IMAP series: " + type.getBaseUrl());
            ImapCatalog.Product sp = ImapCatalog.product(s.product());
            if (p != null && !p.id().equals(sp.id()))
                throw new IllegalArgumentException("IMAP series do not belong to one product");
            p = sp;
            types.add(type);
            series.add(s);
        }
        ImapCatalog.Product product = p;
        return () -> product.isCoho() ? readCoho(product, types, series, start, end)
                : readCdf(product, types, series, start, end);
    }

    // ---- CDF products (SWAPI, MAG): per-day minute arrays joined

    private static final class Accumulator {
        long[] dates = new long[1440];
        float[] values = new float[1440];
        int n;

        void add(long date, float value) {
            if (n == dates.length) {
                dates = Arrays.copyOf(dates, n * 2);
                values = Arrays.copyOf(values, n * 2);
            }
            dates[n] = date;
            values[n++] = value;
        }

        BandData data(BandType type) {
            return new BandData(type, Arrays.copyOf(dates, n), Arrays.copyOf(values, n));
        }
    }

    private static List<BandData> readCdf(ImapCatalog.Product p, List<BandType> types, List<ImapCatalog.Series> series,
                                          long start, long end) throws Exception {
        List<Accumulator> acc = new ArrayList<>();
        types.forEach(t -> acc.add(new Accumulator()));
        if (end > start) {
            LocalDate last = LocalDate.ofEpochDay(Math.floorDiv(end - 1, DAY));
            for (LocalDate day = LocalDate.ofEpochDay(Math.floorDiv(start, DAY)); !day.isAfter(last); day = day.plusDays(1)) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                ImapListing.Entry entry = listing(p, day.getYear()).get(day);
                if (entry == null)
                    continue; // nothing published for that day
                ImapCdfReader.DayMinutes dm = day(p, day, entry);
                if (dm == null)
                    continue;
                long dayStart = day.toEpochDay() * DAY;
                for (int i = 0; i < series.size(); i++) {
                    float[] minutes = dm.minutes().get(series.get(i).var());
                    if (minutes == null || minutes.length != ImapCdfReader.MINUTES_PER_DAY)
                        continue;
                    for (int m = 0; m < minutes.length; m++) {
                        long t = dayStart + m * MINUTE;
                        if (minutes[m] != YAxis.BLANK && t >= start && t < end)
                            acc.get(i).add(t, minutes[m]);
                    }
                }
            }
        }
        List<BandData> ret = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++)
            ret.add(acc.get(i).data(types.get(i)));
        return ret;
    }

    private static ImapCdfReader.DayMinutes day(ImapCatalog.Product p, LocalDate day, ImapListing.Entry entry)
            throws InterruptedException {
        URI uri = URI.create(p.dir() + day.getYear() + '/' + entry.name());
        String key = uri + "|" + entry.mtimeMs();
        ImapCdfReader.DayMinutes cached = DAYS.getIfPresent(key);
        if (cached != null)
            return cached;
        try {
            File file = ENV.get().fetcher().file(uri, n -> {});
            ImapCdfReader.DayMinutes dm = ENV.get().reader().read(file);
            if (dm == null)
                return null;
            DAYS.put(key, dm);
            ImapCatalog.acknowledgement(p.id(), dm.acknowledgement());
            return dm;
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();
            warnOnce(key, "IMAP " + day + " " + p.id() + " skipped: " + e.getMessage());
            return null;
        }
    }

    // ---- COHO hourly files: each row covers [h, h+1), written as 60 one-minute points

    private static List<BandData> readCoho(ImapCatalog.Product p, List<BandType> types, List<ImapCatalog.Series> series,
                                           long start, long end) throws Exception {
        List<Accumulator> acc = new ArrayList<>();
        types.forEach(t -> acc.add(new Accumulator()));
        if (end > start) {
            NavigableMap<LocalDate, ImapListing.Entry> files = listing(p, 0);
            int y0 = LocalDate.ofEpochDay(Math.floorDiv(start, DAY)).getYear();
            int y1 = LocalDate.ofEpochDay(Math.floorDiv(end - 1, DAY)).getYear();
            for (int year = y0; year <= y1; year++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                ImapListing.Entry entry = files.get(LocalDate.of(year, 1, 1));
                if (entry == null)
                    continue;
                ImapCoho.Columns cols = coho(p, entry);
                if (cols == null)
                    continue;
                for (int i = 0; i < series.size(); i++) {
                    int col = ImapCoho.column(series.get(i).var());
                    if (col < 0)
                        continue;
                    float[] v = cols.values()[col];
                    for (int row = 0; row < v.length; row++) {
                        long h = cols.hourStartMs()[row];
                        if (v[row] == YAxis.BLANK || h + HOUR <= start || h >= end)
                            continue;
                        for (int k = 0; k < 60; k++) {
                            long t = h + k * MINUTE;
                            if (t >= start && t < end)
                                acc.get(i).add(t, v[row]);
                        }
                    }
                }
            }
        }
        List<BandData> ret = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++)
            ret.add(acc.get(i).data(types.get(i)));
        return ret;
    }

    /** The yearly file grows, so its cache key carries the listing's mtime (or size): old copies stay. */
    static URI cohoUri(ImapCatalog.Product p, ImapListing.Entry entry) {
        long lm = entry.mtimeMs() != 0 ? entry.mtimeMs() : entry.bytes();
        return URI.create(p.dir() + entry.name() + "?lm=" + lm);
    }

    private static ImapCoho.Columns coho(ImapCatalog.Product p, ImapListing.Entry entry) throws InterruptedException {
        URI uri = cohoUri(p, entry);
        String key = uri.toString();
        ImapCoho.Columns cached = COHO.getIfPresent(key);
        if (cached != null)
            return cached;
        try {
            File file = ENV.get().fetcher().file(uri, n -> {});
            try (InputStream in = new FileInputStream(file)) {
                ImapCoho.Columns cols = ImapCoho.parse(in);
                COHO.put(key, cols);
                return cols;
            }
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();
            warnOnce(key, "IMAP " + entry.name() + " skipped: " + e.getMessage());
            return null;
        }
    }

    // ---- listings: in memory, persisted, negative-cached

    /** Year of the product's directory, or for COHO (one directory, a file per year) key 0 with Jan 1 per year. */
    static NavigableMap<LocalDate, ImapListing.Entry> listing(ImapCatalog.Product p, int year) throws IOException {
        Env env = ENV.get();
        String key = p.id() + '|' + year;
        long now = env.clock().getAsLong();
        Listed cur = LISTINGS.get(key);
        if (cur != null) {
            if (!cur.failed && now - cur.at < ttl(year, now))
                return cur.map;
            if (cur.failed && now - cur.at < LISTING_FAILURE_TTL) {
                if (cur.map != null)
                    return cur.map;
                throw new IOException("IMAP listing unavailable for " + p.id() + (year == 0 ? "" : " " + year));
            }
        }
        URI uri = URI.create(year == 0 ? p.dir() : p.dir() + year + '/');
        try {
            String html = env.fetcher().text(uri);
            NavigableMap<LocalDate, ImapListing.Entry> map = p.isCoho() ? parseCoho(html)
                    : ImapListing.parse(html, ImapListing.pattern(p.instrument(), p.level(), p.descriptor()));
            LISTINGS.put(key, new Listed(map, now, false));
            persist(p, year, map);
            return map;
        } catch (IOException e) {
            NavigableMap<LocalDate, ImapListing.Entry> old = cur != null && cur.map != null ? cur.map : readPersisted(p, year);
            LISTINGS.put(key, new Listed(old, now, true));
            warnOnce("listing|" + key, "IMAP listing " + uri + " failed" + (old == null ? "" : ", using the saved copy")
                    + ": " + e.getMessage());
            if (old != null)
                return old;
            throw e;
        }
    }

    private static long ttl(int year, long now) {
        return year == Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).getYear() ? LISTING_TTL_CURRENT_YEAR
                : LISTING_TTL_PAST_YEAR;
    }

    static NavigableMap<LocalDate, ImapListing.Entry> parseCoho(String html) {
        TreeMap<LocalDate, ImapListing.Entry> ret = new TreeMap<>();
        Matcher m = COHO_ROW.matcher(html);
        while (m.find()) {
            long mtime = 0;
            if (m.group(3) != null) {
                try {
                    mtime = LocalDateTime.parse(m.group(3).replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (java.time.DateTimeException e) {
                    mtime = 0;
                }
            }
            ret.put(LocalDate.of(Integer.parseInt(m.group(2)), 1, 1), new ImapListing.Entry(m.group(1), mtime, 0));
        }
        return ret;
    }

    private static File listingFile(ImapCatalog.Product p, int year) {
        return new File(listingDir(), "imap-listing-" + p.id() + '-' + year + ".tsv");
    }

    private static void persist(ImapCatalog.Product p, int year, NavigableMap<LocalDate, ImapListing.Entry> map) {
        if (map.isEmpty())
            return;
        try {
            File target = listingFile(p, year);
            File tmp = new File(target.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), ImapListing.serialize(map), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            warnOnce("persist|" + p.id() + year, "IMAP listing not saved: " + e.getMessage());
        }
    }

    private static NavigableMap<LocalDate, ImapListing.Entry> readPersisted(ImapCatalog.Product p, int year) {
        File f = listingFile(p, year);
        if (!f.isFile())
            return null;
        try {
            NavigableMap<LocalDate, ImapListing.Entry> map = ImapListing.deserialize(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            return map.isEmpty() ? null : map;
        } catch (IOException e) {
            return null;
        }
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key))
            Log.warn(message);
    }

}

package org.helioviewer.jhv.activity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.metadata.Region;
import org.json.JSONObject;

/**
 * The activity record: lights lit by pulses and levels and dark after the hold, the ring's cursor,
 * filter and cap, argument cutting, who is driving and for how long, the memory rule at its
 * thresholds, Core output followed line by line, the OS counters rising after a known write, and
 * the activityLog command's answer.
 */
public final class ActivityCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // Settings captures user.home when first loaded: point it at a throwaway directory first.
        System.setProperty("user.home", Files.createTempDirectory("hfs-activity").toString());
        org.helioviewer.jhv.app.Platform.init();

        lights();
        ring();
        driver();
        memory();
        tail();
        counters();
        command();
        freeMemory();

        System.out.println(failures == 0 ? "ActivityCheck: ok" : "ActivityCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void lights() throws Exception {
        long t = System.nanoTime();
        expect("a light nobody has pulsed is dark", !Activity.lit(Activity.Kind.NET, t));
        Activity.pulse(Activity.Kind.NET);
        expect("a pulse lights it", Activity.lit(Activity.Kind.NET, System.nanoTime()));
        expect("it is dark once the hold has passed", !Activity.lit(Activity.Kind.NET, System.nanoTime() + Activity.HOLD_NANOS + 1));
        Activity.begin(Activity.Kind.CPU);
        expect("a level keeps it lit past the hold", Activity.lit(Activity.Kind.CPU, System.nanoTime() + 10 * Activity.HOLD_NANOS));
        Activity.end(Activity.Kind.CPU);
        expect("and releases it at the end", !Activity.lit(Activity.Kind.CPU, System.nanoTime() + Activity.HOLD_NANOS + 1) && Activity.level(Activity.Kind.CPU) == 0);
        Activity.end(Activity.Kind.CPU);
        expect("an unmatched end does not leave a negative level", Activity.level(Activity.Kind.CPU) == 0);
        long before = Activity.pulses(Activity.Kind.DISK);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10_000; j++)
                    Activity.pulse(Activity.Kind.DISK);
            });
            threads[i].start();
        }
        for (Thread th : threads)
            th.join();
        expect("pulses from eight threads are all counted", Activity.pulses(Activity.Kind.DISK) - before == 80_000);
        long net = Activity.netBytesTotal();
        Activity.netBytes(1000);
        Activity.netBytes(-5);
        expect("byte counts add and ignore nonsense", Activity.netBytesTotal() - net == 1000);
        byte[] data = new byte[4096];
        try (var in = Activity.countingNet(new java.io.ByteArrayInputStream(data))) {
            in.readAllBytes();
        }
        expect("a counted stream adds what it reads", Activity.netBytesTotal() - net == 1000 + 4096);
    }

    private static void ring() {
        long first = Activity.nextSeq();
        for (int i = 0; i < Activity.CAPACITY + 50; i++)
            Activity.event(i % 2 == 0 ? Activity.Kind.CMD : Activity.Kind.CORE, "t", "line " + i);
        List<Activity.Event> all = Activity.since(0, null, Activity.CAPACITY + 100);
        expect("the ring holds the last " + Activity.CAPACITY + ": " + all.size(), all.size() == Activity.CAPACITY);
        expect("oldest first, in order", all.get(0).seq() < all.get(1).seq() && all.get(all.size() - 1).text().equals("line " + (Activity.CAPACITY + 49)));
        long mid = all.get(100).seq();
        List<Activity.Event> after = Activity.since(mid, null, 10);
        expect("since_seq returns what is after it and respects the limit", after.size() == 10 && after.get(0).seq() == mid + 1);
        Set<Activity.Kind> cmd = EnumSet.of(Activity.Kind.CMD);
        expect("a kind filter keeps only that kind", Activity.since(0, cmd, 100).stream().allMatch(e -> e.kind() == Activity.Kind.CMD));
        expect("events before the cursor are not repeated", Activity.since(Activity.nextSeq() - 1, null, 10).isEmpty());
        Activity.Event cut = Activity.event(Activity.Kind.LOG, "t", "x".repeat(2000));
        expect("long text is cut to 500 characters: " + cut.text().length(), cut.text().length() == 500 && cut.text().endsWith("…"));
        expect("sequence numbers never repeat", Activity.nextSeq() > first + Activity.CAPACITY + 50);
    }

    private static void driver() throws Exception {
        Driver.reset();
        expect("nobody drives at first", Driver.current(System.nanoTime()) == null);
        expect("heliofits-mcp is MCP", Driver.classify("heliofits-mcp/1.2; Claude Desktop") == Driver.Kind.MCP);
        expect("pyheliofits is Python", Driver.classify("pyheliofits/1.0") == Driver.Kind.PYTHON);
        expect("anything else, and no header, is other", Driver.classify("curl/8") == Driver.Kind.OTHER && Driver.classify(null) == Driver.Kind.OTHER);
        expect("the name carries the MCP client: " + Driver.name("heliofits-mcp/1.2; Claude Desktop", ""), Driver.name("heliofits-mcp/1.2; Claude Desktop", "").equals("Claude Desktop via heliofits-mcp"));
        expect("a bare product drops its version", Driver.name("pyheliofits/1.0", "").equals("pyheliofits") && Driver.name(null, "").equals("a script"));
        expect("control characters are stripped", !Driver.name("evil\u0007\u001b[31m/1", "").contains("\u0007"));
        Driver.begin(Driver.Kind.MCP, "Claude Desktop via heliofits-mcp", "layerAdd");
        Driver.State s = Driver.current(System.nanoTime());
        expect("driving is on while a command runs, with who and what", s != null && s.inFlight() && s.kind() == Driver.Kind.MCP && s.command().equals("layerAdd"));
        expect("a long command holds it well past the linger", Driver.current(System.nanoTime() + 3600L * 1_000_000_000L) != null);
        Driver.end();
        expect("it lingers after the answer", Driver.current(System.nanoTime()) != null && !Driver.current(System.nanoTime()).inFlight());
        expect("and is over once the linger has passed", Driver.current(System.nanoTime() + (Driver.lingerSeconds() + 1) * 1_000_000_000L) == null);
        Driver.end();
        expect("an unmatched end changes nothing", Driver.current(System.nanoTime() + (Driver.lingerSeconds() + 1) * 1_000_000_000L) == null);
    }

    private static void memory() {
        long g = 1L << 30;
        MemoryHealth.Reading ok = MemoryHealth.assess(0.4, 8 * g, 0.01, 4 * g, 8 * g, 8 * g, 16 * g, 0);
        expect("a half-empty heap with little collecting is OK", ok.level() == MemoryHealth.Level.OK && ok.reason().isEmpty());
        expect("a full decoded-frame cache alone is not a problem", MemoryHealth.assess(0.4, 8 * g, 0.01, 8 * g, 8 * g, 8 * g, 16 * g, 0).level() == MemoryHealth.Level.OK);
        expect("heap 80% after GC is tight", MemoryHealth.assess(0.80, 8 * g, 0.01, 0, g, g, 16 * g, 0).level() == MemoryHealth.Level.TIGHT);
        expect("heap 92% after GC is critical", MemoryHealth.assess(0.92, 8 * g, 0.01, 0, g, g, 16 * g, 0).level() == MemoryHealth.Level.CRITICAL);
        expect("collector 10% of the time is tight", MemoryHealth.assess(0.3, 8 * g, 0.10, 0, g, g, 16 * g, 0).level() == MemoryHealth.Level.TIGHT);
        expect("collector 25% of the time is critical", MemoryHealth.assess(0.3, 8 * g, 0.25, 0, g, g, 16 * g, 0).level() == MemoryHealth.Level.CRITICAL);
        MemoryHealth.Reading machine = MemoryHealth.assess(0.3, 8 * g, 0.0, 0, g, g / 40, 16 * g, 0);
        expect("under 5% of the machine free is tight, and says to lower the share: " + machine.advice(),
                machine.level() == MemoryHealth.Level.TIGHT && machine.advice().contains("Memory share"));
        MemoryHealth.Reading bad = MemoryHealth.assess(0.95, 8 * g, 0.3, 0, g, g, 16 * g, 0);
        expect("a critical reading explains itself and what to do: " + bad.reason(), bad.reason().contains("95%") && bad.advice().contains("save your session"));
        MemoryHealth.Reading live = MemoryHealth.sample(0, 1L << 30, 0);
        expect("a live reading is a fraction of a real heap: " + live.summary(), live.heapAfterGc() >= 0 && live.heapAfterGc() <= 1.5 && live.heapMax() > 0);
    }

    private static void tail() throws Exception {
        Path f = Files.createTempFile("hfs-tail", ".txt");
        OutputTail t = new OutputTail(f, "core");
        long seq = Activity.nextSeq();
        Files.writeString(f, "first\nsecond\npart");
        t.drain(false);
        List<Activity.Event> got = Activity.since(seq - 1, EnumSet.of(Activity.Kind.CORE), 10);
        expect("finished lines appear, the unfinished one waits: " + got.size(), got.size() == 2 && got.get(1).text().equals("second"));
        Files.writeString(f, "ial\nlast", java.nio.file.StandardOpenOption.APPEND);
        t.drain(true);
        got = Activity.since(seq - 1, EnumSet.of(Activity.Kind.CORE), 10);
        expect("a line finished later joins its start, and the end flushes the rest: " + got.size(),
                got.size() == 4 && got.get(2).text().equals("partial") && got.get(3).text().equals("last"));
        Files.delete(f);
    }

    private static void counters() throws Exception {
        if (!IoCounters.supported()) {
            System.out.println("  note  no per-process I/O counters on this system; the OS counter check is not run");
            return;
        }
        IoCounters.Sample a = IoCounters.sample();
        Path f = Files.createTempFile("hfs-io", ".bin");
        try (var out = new java.io.FileOutputStream(f.toFile())) {
            byte[] block = new byte[1 << 20];
            new java.util.Random(1).nextBytes(block);
            for (int i = 0; i < 64; i++)
                out.write(block);
            out.getFD().sync();
        }
        IoCounters.Sample b = IoCounters.sample();
        Files.delete(f);
        expect("written bytes rise by about what was written (64 MiB): " + (b.written() - a.written()) + " B", b.written() - a.written() >= 32L << 20);
    }

    private static void command() {
        Activity.event(Activity.Kind.CMD, "test", "> layerAdd");
        JSONObject r = org.helioviewer.jhv.gui.registry.ActivityCommands.log(new JSONObject().put("kinds", new org.json.JSONArray().put("cmd")).put("limit", 5));
        expect("activityLog answers events of the kind asked, with a cursor: " + r.getJSONArray("events").length(),
                r.getJSONArray("events").length() == 5 && r.getLong("next_seq") > 0
                        && r.getJSONArray("events").getJSONObject(0).getString("kind").equals("cmd"));
        JSONObject empty = org.helioviewer.jhv.gui.registry.ActivityCommands.log(new JSONObject().put("since_seq", r.getLong("next_seq") + 1_000_000));
        expect("asking past the end gives nothing new", empty.getJSONArray("events").isEmpty());
    }

    private static void freeMemory() {
        short[] half = new short[40 * 24];
        ImageBuffer buffer = ImageBuffer.fromShorts(40, 24, ImageBuffer.Format.Gray16F, half, ImageFilter.NONE);
        ImageBufferCache.put("activity-check", new DecodedImage(buffer, new Region(-1.5, -0.75, 3, 1.5)));
        expect("a decoded frame is held before Free up memory", ImageBufferCache.get("activity-check") != null);
        ImageBufferCache.clear();
        expect("Free up memory drops every decoded frame", ImageBufferCache.get("activity-check") == null);
    }

}

# Performance plan: memory, CPU and latency

Status: the pre-implementation plan, kept as written on 2026-10-25. WP1 to WP4 have since been
implemented on `claude/perf-memory-latency` (PR #102); the changelog says what shipped.
Branch when work starts: `claude/perf-memory-latency-<slug>` (one initiative per branch).
Tag the start before any multi-file change: `git tag pre/perf-memory-latency`.

## Evidence

Source: live diagnosis of one running HFStudio process (PID 97210, 28 minutes up, a 1291-frame
Helioviewer layer loaded as two JPIP pieces, 10 image layers decoding). Tools: `ps`, `footprint`,
`vmmap -summary`, `jcmd` (VM.flags, GC.heap_info, GC.class_histogram, Thread.print), and a 45 s
JFR recording (`profile` settings). Native memory tracking was off. One session, one recording:
rare stalls may not show, and the allocation weights from JFR are estimates (use the ranking).

| Finding | Number | How obtained |
|---|---|---|
| Physical footprint | 88 GB (peak 89 GB); 70% of writable memory swapped | `footprint`, `vmmap -summary` |
| Heap | 30 GB max (JVM default, no `-Xmx`), 26.5 to 28.3 GB used | `jcmd GC.heap_info`, JFR |
| Live heap in `DataBinCache` | about 27 GB: 130.8M `byte[]` (18.4 GB), 130.6M `TreeMap$Entry` (5.2 GB), 18.0M `Bin`, `TreeMap`, `BinId` | `GC.class_histogram` (runs a full GC first) |
| Full GCs | 23 in 45 s, about 1.08 s each; stop-the-world 26.6 s of 45 s (59%) | JFR `GarbageCollection`, `GCPhasePause` |
| GC cause | 86 collections from "G1 Humongous Allocation" (per-decode `int[]`, 4 B/pixel) | JFR |
| Machine CPU | `jvmUser` 70 to 76%, `machineTotal` 92 to 97% | JFR `CPULoad` |
| Decoder threads | 10 `View-Decoder` (one per layer), all in native `OpenJpeg.decode`; each asks OpenJPEG for up to 4 threads | `Thread.print`, `OpjDecoder.DECODE_THREADS` |
| Hot Java line | `OpjDecoder.java:51-52` takes 414 of 686 execution samples (60%) | JFR `ExecutionSample` |
| UI thread | 48 of 73 `AWT-EventQueue-0` samples in `ImageBuffer.countLevels()` (66%), called from `ImageLayer.java:949` | JFR |

The screenshot Gilly sent (581% CPU, 139 threads, 3:31 CPU time) agrees with these numbers.

## Causes, ranked

1. `DataBinCache` (`src/org/helioviewer/jhv/view/j2k/opj/DataBinCache.java`) keeps every JPIP piece
   forever, one small `byte[]` plus a `TreeMap` entry plus a boxed `Long` key per piece (average piece
   about 140 bytes, overhead larger than the payload). `bytesHeld()` exists and nothing calls it;
   `clear()` has no caller. `BinId` uses the default record hash, which collides (17.9M
   `ConcurrentHashMap$TreeNode`).
2. A near-full heap makes each humongous `int[]` allocation a full GC, so the app is stopped most of
   the time.
3. The decode path copies pixels four times (bins to codestream, to an `Arena`, OpenJPEG plane to
   `int[]`, `int[]` to the Gray8 buffer), and the last copy is a per-pixel `ByteBuffer.put`
   (`OpjDecoder.java:51-52`).
4. Ten independent decoders, no priority between layers, no cancellation of a running decode
   (`LatestWorker.invalidate()` only marks results stale).
5. Work that repeats: `OpjSource.codestream()` rebuilds from all bins on every decode and every
   `resolutionSet` call; `JPIPCache.scan` sorts all bins for every frame stored (O(frames x bins)).
6. UI thread does pixel work (`countLevels`, item 1 below).

## Plan

Each step is a separate commit on the branch. Before a step is called done: show its check failing
once, then passing (`extra/test/<Name>Check.java`), run `ant check-all`, and re-profile where noted.
No step deletes or renames anything that is tracked, changes a data contract, or touches the
technical names in `CLAUDE.md`.

### Step 0. Try a smaller heap today (no code)
The heap is the lesser of the two memory budgets (see Review corrections, item 1). For an
experiment only, relaunch the dev build with `JAVA_TOOL_OPTIONS="-Xmx12g -Duser.home=$(mktemp -d)"`
and watch footprint, full-GC count and stop-the-world time against the table above. Do not ship a
fixed `-Xmx`: `run.sh` passes no JVM options and `release/deploy_release.sh` passes only
`--enable-native-access` and `--add-exports` (lines 861 to 863), and a fixed 12g would hurt a small
machine. The shipped fix is the budget in Step 2. `-XX:G1HeapRegionSize=32m` does not help: the
humongous threshold is half a region (16 MB), and a full-size plane is larger than that.

### Step 1. Move `countLevels` off the UI thread (a few lines)
`ImageLayer.java:949` calls `measuredLevels()` on the EDT per displayed frame. Compute it where the
frame is decoded (decode thread) or only when the readout in `ImageLayerManagePanel.java:561` is
visible. Smallest change that removes the work from the EDT wins.
Check: a unit check that `measuredLevels()` result is unchanged; JFR shows no `countLevels` samples
on `AWT-EventQueue-0`.

### Step 2. Bound `DataBinCache`
- Merge contiguous pieces in `Bin.put`, or when a bin completes, so one bin is one array.
- Count real cost (payload plus per-entry overhead) in `bytesHeld()`.
- LRU eviction by frame against a byte budget, shared by all layers (one budget, not one per
  layer, since there is one `DataBinCache` per `J2KSource.Remote`); never evict the target frame or
  one being decoded.
- Eviction must also reset the frame's complete flag. `J2KReader.restoreFrame`
  (`J2KReader.java:91-106`) already does the rest: when the flag is false it reloads the frame from
  `JPIPCacheManager` and puts it back. If the flag stays true after the bins are gone,
  `OpjSource.codestream` returns null and every decode of that frame fails with "has not arrived
  yet". Only frames the disk cache stored (`J2KReader.java:116`, completed frames) are safe to evict;
  keep partial frames.
- Give `BinId` an explicit `hashCode`.
- Coalesce at the source too: `JPIPCache.put` receives one `JPIPSegment` per server message, which
  is why pieces average 140 bytes. Append to the bin's tail run instead of storing a new array.
Check: a `DataBinCacheCheck` that fills past the budget and asserts the held bytes stay under it and
that a completed bin still decodes after pieces were merged (show it failing against today's code).
Re-profile: live heap in `DataBinCache` and full-GC count.

### Step 3. Decode without the extra copies
- `OpenJpeg.read` and `OpjDecoder.call`: read the native `MemorySegment` straight into the Gray8
  destination with shift and clamp in one loop over the segment; do not build the `int[]`.
- `OpjDecoder.call` rebuilds the codestream per decode (`OpjSource.java:94-96`, `Codestream.build`).
  Keep a small LRU of built codestreams (a handful of frames, bounded by bytes), not one per frame:
  1291 built codestreams would recreate the memory problem. The resolution set is already cached
  per frame (`J2KSource.Remote.resolutionSet` returns `resolutionSet[frame]`).
Check: a check that decodes a fixture frame the old way and the new way and compares bytes
(fixture in `extra/test/data/` with a README row). JFR: no `OpjDecoder.call` line-51 samples, no
humongous `int[]` allocations.

### Step 2b. One memory budget for heap plus decoded frames
`ImageBufferCache` (`src/org/helioviewer/jhv/image/ImageBufferCache.java:23-30`) bounds decoded
frames, which live in native memory, at 40% of physical RAM and never under 4 GiB. On this 128 GB
machine that is about 51 GB, and the process showed 52 GB of `MALLOC_LARGE`. Together with a 30 GB
heap that is more than 80 GB, so swap is the design, not an accident. The comment there explains
why it is large (a 983-frame PUNCH movie of 32 MiB frames should loop without redecoding), and
that goal is sound; the flaw is that the two budgets are independent and ignore what else the
machine is doing. Make one budget: heap cache plus decoded-frame cache under a single share of
physical memory (a fraction to agree with Gilly), and shrink the decoded-frame budget when
`ImageBufferCache`'s own eviction rate shows thrash. The JFR allocation profile shows Caffeine
`evictEntry` among the top allocators, which suggests that cache was already full and churning;
measure its hit rate before choosing numbers.
Check: a check on the budget arithmetic (heap plus native stays under the share for several RAM
sizes), plus a before and after footprint measurement.

### Step 4. One shared, prioritised decode pool
Replace the per-layer `LatestWorker<DecodedImage>("View-Decoder")` (`ImageLayerLoader.java:41`) with
one pool sized from the core count: visible target frame first, neighbours next, prefetch last,
hidden layers not at all. Scale `DECODE_THREADS` (`OpjDecoder.java`) by how many decodes run at once.
`LatestWorker` semantics (latest request wins, callbacks on the EDT) stay for each layer.
Check: a check that two layers cannot exceed the pool width and that a newer request overtakes a
queued older one. JFR: native decode threads and `machineTotal` drop at equal throughput.

### Step 5. Fix the quadratic store
`JPIPCache.scan` (`JPIPCache.java:44`) must not copy and sort every bin per frame. Index bins by
frame (or iterate only that frame's keys).
Check: a check with many frames asserting `scan(frame)` returns the same bins as today and that its
cost does not grow with unrelated frames.

### Step 6. Perceived speed
Show a coarse resolution level at once and refine, so scrubbing always paints within one frame time.
Throttle the layer panel readout rebuild on time updates (`LayerOptionSections.timeUpdated` to
`lazyRepaint`). Optionally start the second 900-frame query while the first piece downloads
(`ImageLayerLoader.loadChunks`, currently sequential by design to spare the server; decide with
Gilly before changing).

### Step 7. Native buffers
Pool decode and output buffers per size instead of allocate and free per frame
(`ImageBuffer.java:361`, `OpenJpeg.java:167-168`). Do this last, after re-measuring.

## Order and effort

| Order | Step | Size | Expected effect (inference, to be measured) |
|---|---|---|---|
| 1 | 0 flags | none | fewer full GCs, no swap growth |
| 2 | 1 countLevels | a few lines | UI thread relieved |
| 3 | 2 and 2b budgets | medium | removes most of the 27 GB heap and caps the 52 GB native, the main causes |
| 4 | 3 decode copies | medium | about 60% of Java CPU, no humongous allocation |
| 5 | 4 shared pool | medium | CPU and scrub latency |
| 6 | 5 scan | small | disk-cache store cost |
| 7 | 6 and 7 | small to medium | feel |

Steps 2 and 3 are independent (different files) and can be done in parallel sessions.

## Review corrections (second pass over this plan)

Found by re-reading the code paths the plan relies on. Each is fixed in the steps above.

1. Missing: the decoded-frame cache (Step 2b). It is the likely source of the 52 GB of native
   memory, and the plan had treated native memory as a side effect of decode copies.
2. Wrong: "`G1HeapRegionSize=32m` stops humongous allocations". It does not; the threshold is half a
   region. Only removing the `int[]` (Step 3) does. Also unverified: the 64 MB plane figure assumed a
   4096 x 4096 decode. The recording has no allocation-size events, so the real plane sizes are
   unknown; add `jdk.ObjectAllocationOutsideTLAB` to the next recording.
3. Wrong: the earlier review said `submitDecode` rebuilds the codestream through `resolutionSet`.
   For a JPIP layer it reads a cached array; the rebuild happens once per decode in
   `OpjDecoder.call`, on the decoder thread, not the UI thread.
4. Wrong: "an evicted frame is simply re-requested". It needs its complete flag reset, or decodes
   fail (Step 2).
5. Wrong: "cache the built codestream per frame" (memory). Now a small bounded LRU (Step 3).
6. Wrong: `-Xmx12g` as a shipped flag. Now an experiment; the budget in code is the fix.
7. Missing: a repeatable baseline. Before Step 1, record one fixed protocol so every step is
   compared to the same thing: throwaway home, the same layer and frame range, 60 s of idle then
   60 s of scrubbing, JFR `profile` settings plus `ObjectAllocationOutsideTLAB`, and record
   `footprint`, full-GC count, stop-the-world seconds and EDT samples. Keep the numbers in
   `docs/` next to this file.
8. Missing: confirm memory is released when a layer is removed. `DataBinCache.clear()` has no
   caller and release relies on the `Cleaner` in `J2KView` (`J2KAbolisher`); test it by loading,
   removing the layer, forcing a GC and reading the histogram.
9. Missing risk: step 4 must keep `LatestWorker`'s "latest request wins" per layer, and a shared pool
   must not let a flood of prefetch starve the target frame; the priorities need a test.
10. Missing: the disk cache (`JPIPCacheManager`) already stores every completed frame, so the
    data is held twice (disk and heap). Check its size limit and write cost per frame before
    relying on it for restores; if it is slow, eviction will move the stall to the reader thread.

## Not covered, needs separate work

- GL upload, texture and FBO allocation, and the render loop. Not examined.
- "Sun pizza" and "template" features: no code by those names exists in this tree; the running jar
  may be a different build. Tell Gilly which build was profiled before judging them.
- Native memory breakdown (NMT was off): re-run with `-XX:NativeMemoryTracking=summary` in a
  throwaway session after step 2.
- Whether `OpjSource.resolutionSet` runs on the EDT (`J2KView.java:301`): not confirmed.

## Decisions so far (Gilly, 2026-10-25)

1. Memory: heap plus decoded frames share 50% of physical RAM combined, roughly 1/4 heap and 3/4
   decoded frames. The share is adjustable in Settings.
2. Principle: anything that makes the program faster but less precise, or trades one cost for
   another, must be configurable and able to be turned off in Settings. Every step here that adds
   such a tradeoff (cache budget, coarse-first display, lower decode level, prefetch, eviction)
   gets a Settings entry with the precise or conservative behaviour available.
3. Movie larger than the cache budget: ask each time. The prompt offers the choices (re-decode
   evicted frames at full precision, or lower the cached resolution so the whole movie fits) and
   a "remember this" checkbox. The remembered choice is a Settings entry that can be reset.
   The same prompt also offers to raise the memory share (Decision 1) and shows how much the movie
   needs against what the budget allows, so the third choice is "allocate more memory".

## Open questions for Gilly

1. One shared budget: what fraction of physical RAM for heap plus decoded frames (the current
   decoded-frame cache alone takes 40%)? And bin cache: fixed size or share of that?
2. Should the PUNCH looping goal (whole 983-frame movie decoded and cached) win over staying under
   RAM on a 16 GB laptop, or should the cache degrade to re-decoding?
3. Should the second 900-frame piece be prefetched, given the server cost note in `loadChunks`?
4. Which build was profiled, and where are the sun pizza and template features?

## Rules that apply (from CLAUDE.md)

Run `ant check-all` before each commit. No push, release or workflow run without Gilly's yes per
action. Do not edit upstream-derived files beyond small hunks (`git log upstream/master -1 -- <path>`
first). `extra/ci/guards.py count` must stay green (no new broad catches, prints, non-final statics).
No em dashes. Do not commit a changed `lib/natives-macos/libjhvmetalhost.dylib`.

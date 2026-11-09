# Load guard and hang hardening: scope

Status: scope only, no code. Written 2026-11-08 against `origin/master` at d26c436b8. Each piece below
waits for Gilly's yes.

## What happened

Gilly opened a `.jhv` in the debug build. Studio began downloading every layer for a month at high
cadence, asked nothing, and hung.

## What the code does on opening such a file (read, not run)

1. `LoadState.submit` reads the JSON on the shared "Worker" pool, then `State.load` runs on the EDT
   (Task callbacks always do) and calls `loadLayers`.
2. `loadLayers` calls `ImageLayer.createDetached` per image layer. Each one starts its own
   `ImageLayerLoader.load(APIRequest)`. Nothing looks at layers x frames x size first. The request is
   whatever the file says: start, end and `cadence` come straight from the JSON (`APIRequest.fromJson`);
   only a file with no cadence gets `TimeUtils.defaultCadence`, which caps at 96 frames.
3. `APIRequest.chunks()` cuts the span into pieces of at most 900 frames. A month at 12 s is about
   216,000 frames, so 240 pieces per layer. Each piece is a server-built movie plus a JPIP view.
4. Per layer, frame fetches are capped at 4 (`FRAME_FETCHERS`) and chunk queries are sequential in the
   default `OPEN` mode. But the caps are per layer, not global: 10 layers is 40 fetch streams. In
   `ALL` mode (a Setting) every piece is queried at once with a pool sized `parts.size()`.
5. The "Worker" pool behind all of it is `Executors.newCachedThreadPool`: unbounded. There is no
   global concurrency cap and no queue, so nothing waits its turn.
6. Frames land in `FrameBatch`, which every 150 ms hands the EDT a batch. `ManyView.addFrames` then
   copies the whole frame map and rebuilds its index: O(N) per batch, O(N squared) over the load, on
   the EDT.

## The likely hang cause (inferred, not reproduced)

Item 6. With tens of thousands of frames per layer and several layers, each 150 ms batch costs a full
copy and re-index of the growing map on the EDT, plus `putDates` per frame, plus the listener and
panel refreshes the batch triggers. The EDT falls behind and never catches up, which looks like a
hang. Item 5 amplifies it: the pool lets thread, socket and heap use grow with the request.

Unverified, and what would settle each:

- That the EDT is the stall. Needs one `jstack` or a sample of the hung process. The logs in
  `~/HFStudio/Logs` for 2026-11-08 do not capture the hang itself (no stack, no OOM line).
- Memory as a co-cause. Not seen in a log; the same logs do show repeated "No space left on device"
  from 15:19, so the disk was full during that session. Free space on `~/HFStudio` may matter too.
- The size of the cost. A headless check that feeds `ManyView.addFrames` 200k fake frames in 150 ms
  batches would give the number. That check is the first thing PR 1 should add.

## Recommendation, in four PRs

Guard first, because it removes the trigger. The rest make a bad load survivable.

| PR | What | Size | Needs |
|----|------|------|-------|
| 1 | Pre-flight confirm | small | none |
| 2 | Global fetch cap and bounded Worker pool | small to medium | none |
| 3 | Make `addFrames` incremental (append, not rebuild) | medium, touches upstream-derived `ManyView` | PR 1's measurement |
| 4 | Watchdog and emergency stop | medium | Gilly's call on the open question below |

### PR 1: pre-flight confirm

- `LoadEstimate`: from the state JSON before any layer is built (in `LoadState`, before
  `State.load`), sum over image layers `frames = (end - start) / cadence`, chunks = `ceil(frames/900)`,
  and a rough size. Size per frame is not known up front; use a labelled assumption (a per-source
  constant, shown as "about") rather than a measured number.
- Setting `load.confirmFrames`, default 5,000 frames total. Above it, a dialog: **Load anyway**,
  **Load with fewer frames** (raises the cadence until under the threshold, edits the in-memory JSON
  only, never the file), **Cancel**. Says how many layers, frames, pieces and the assumed MB.
- Same estimate and dialog for the other entry points that build an `APIRequest` in bulk (the data
  dialog's Load, the local API's load command). Local API callers get a refusal with the numbers
  unless they pass an explicit override, so a script cannot hit it silently. Cached frames are free:
  the estimate should subtract what `NetFileCache` already holds if that is cheap to ask; if not,
  ship without it and say so in the dialog.
- Check: `LoadEstimateCheck` with a fixture session, shown failing first.

### PR 2: caps and queue

- Replace `newCachedThreadPool` for load work with a bounded pool, and make `FRAME_FETCHERS` a global
  permit (a `Semaphore`) so total concurrent fetches stay at 4 (a Setting, in line with the existing
  rule that speed-for-precision tradeoffs are Settings). Layers queue instead of competing.
- Per-host politeness: the same permit doubles as the rate limit; add a minimum gap only if the archive
  pushes back (unverified that it does).
- `ChunkLoading.ALL` stays available but is clamped to the same cap.

### PR 3: incremental `addFrames`

Append to the existing sorted structure when frames arrive in order, rebuild only on an out-of-order
insert. Needs care because `ManyView` readers are lock-free by design (published whole, replaced
whole). Do this only after PR 1's measurement shows the cost is real; PR 1 may make it unnecessary for
the common case.

### PR 4: watchdog and emergency stop

Java limits, stated plainly:

- A watchdog thread in the same JVM can detect a stuck EDT (post a tiny `invokeLater`, check it ran
  within N seconds) and can cancel futures and shut pools, since none of that needs the EDT.
- It cannot rescue a JVM out of memory or in a long GC pause, and it cannot draw UI if the toolkit
  itself is wedged. A same-JVM prompt is a Swing dialog and Swing is exactly what is stuck.
- A truly independent control needs another process.

Recommended shape, lazy first:

1. **In-JVM watchdog, no new window.** Daemon thread, 1 s heartbeat. At 10 s of EDT silence: pause all
   loads (drain the global permit to zero, which stops new fetches) and log a thread dump to
   `~/HFStudio/Logs` so the next hang is diagnosable. At 30 s: cancel every `ImageLayerLoader` future
   and dispose pending work. This alone would likely have saved Gilly's session, and it needs no UI.
2. **Stop from outside the EDT.** A POST on the existing local API (`/stop`, served by `HttpServer` on
   its own thread, already running) that does the same cancel. That makes the emergency stop
   reachable by `curl` or a tiny script even when the window is dead, and the footer Stop and Task
   Manager (recent #221, #225) call the same method, so there is one stop path.
3. **Prompt** ("Studio seems stuck; pause?") only once the EDT recovers, as a normal dialog, since it
   cannot show while stuck.

Open question for Gilly: a separate always-responsive tiny process or a global hotkey (macOS needs an
accessibility grant for a global hotkey, and a child process adds packaging and notarization surface).
My recommendation is to skip both until item 1 and 2 have shipped and proved insufficient.

## Not in scope

Changing what a session saves, the server's 1000 frame cap handling, or the Task Manager UI.

## Decision wanted

Yes to PR 1 as scoped (default 5,000 frames, the three-button dialog, local API refuses without an
override)? And is 5,000 the right threshold for you?

## Update 2026-11-09: one choice per layer

The review dialog asks per layer, not once for the session: two radio columns, Postpone and
Download / load from disk, and a single Go button with a live total of what the current choices
will download. A layer starts on Postpone when it alone is over the frame limit or the size limit,
and on Download / load from disk otherwise (a fully cached, empty or unknown-count layer loads).
Closing the window postpones everything that would download. A third "Stream" column for #243 is
left out: that PR is not merged, and a column for a mode that does not exist yet would be dead.

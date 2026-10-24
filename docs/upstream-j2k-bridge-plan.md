# Shelved: adopting upstream's JPEG 2000 bridge with OpenJPEG behind it

Written 2026-10-24, shelved the same day by Gilly's decision. Nothing here is built. The policy
that applies today is in `docs/UPSTREAM.md` ("What is not taken from upstream"). This file keeps
the alternative intact so it can be picked up without redoing the analysis.

## The situation it would fix

Upstream JHelioviewer and this fork both rewrote JPEG 2000 reading in autumn 2026, in opposite
directions:

| Layer | Upstream (from October 2026) | This fork (from 2026-09-16, ece2db71) |
|---|---|---|
| Java side | `J2KView`, `J2KReader`, `J2KSource` and an FFM binding around a native bridge; much smaller | About 3,000 lines in `view/j2k`: Java JPIP data-bin cache, codestream rebuild, JP2/JPX box reader |
| Reader (JPIP protocol, cache, boxes) | esajpip C client, CDDL 1.0, the archive's own server code | This fork's Java code |
| Decoder | Kakadu (proprietary; its licence does not reach a fork) | OpenJPEG 2 (BSD-2) through FFM |

The fork can never take Kakadu. It could take the reader and the Java side, if the decoder behind
the bridge were OpenJPEG.

## Why it is feasible

Upstream's bridge (`native/jpeg2000/jhv_j2k.h`) is a clean C API: open a file or an empty JPIP
source, feed whole JPIP response bodies, query frames, XML and palette, import and export disk
cache entries, and decode a region of a resolution level with `jhv_j2k_begin_decode`,
`jhv_j2k_decode` and `jhv_j2k_end_decode`. Kakadu appears in only three places:

- `jhv_kdu_geometry.cc` (65 lines): reads level geometry from a `kdu_codestream`;
- `jhv_kdu_decode.cc` (82 lines): decodes a reduced region into Gray8 or RGBA;
- the `kdu_message_queue` error and warning plumbing in `jhv_j2k.cc`.

The esajpip client builds without Kakadu (its CMake option `ESAJPIP_CLIENT_ONLY`), and upstream's
CMake file already says "OpenJPEG is not used", meaning nothing in the reader depends on a
particular decoder.

## The plan, in order

1. **Wait for a quiet base.** Upstream made 179 commits in the 23 days to 2026-10-23, many in
   `view/j2k` and `native/jpeg2000`. Start only after about two weeks with no upstream commits
   there; otherwise the base moves under the work.
2. **The shim alone, on a branch, testable without touching Studio's layer code.** Write
   `jhv_opj_geometry.c` and `jhv_opj_decode.c` with the same signatures as the Kakadu pair, built
   with a `JHV_J2K_DECODER=openjpeg` CMake option. Hard parts:
   - "Decode only when the level is complete": the bridge reports how many levels are ready;
     OpenJPEG decodes truncated codestreams but has no equivalent of Kakadu's quality limiter,
     so progressive display may look different (coarser first frames). Measure before deciding
     it matters.
   - Region decode at a reduction level: `opj_set_decode_area` plus `cp_reduce`, already used by
     this fork's `OpjDecoder`, so the arithmetic exists.
   - Palette and RGBA output: this fork's OpenJPEG binding only handles one greyscale component
     today (see the comment in `opj/OpenJpeg.java`); the shim needs the component loop.
   - Error text into the 256-byte buffer, replacing the Kakadu message queue.
   Validate with upstream's own native check (`extra/test/j2k/check_native_build.py` and
   `J2KNativeTest`) against esajpip's fixtures, on macOS first (Gilly's Mac), then Linux and
   Windows through CI.
3. **Licensing.** esajpip is CDDL 1.0: file-level copyleft like MPL 2.0, so bundling it as a
   native library beside MPL code is the same arrangement as OpenJPEG today. Add it to
   `extra/licenses/` and the About dialog, and run `python3 extra/licenses/sync_licenses.py --check`.
   Never bring a `*kdu*` file under `lib/` (CLAUDE.md).
4. **One sync, not cherry-picks.** Merge upstream's J2K Java side together with its layer refactor
   (`Frames`, `FrameDecoder`, `Source`, the layer loader), because the Java side assumes it. Expect
   the conflicts in `ImageLayer.java` (46 fork commits against 30 upstream since 2026-09-14) and
   `MoviePanel.java` (30 against 11). Keep the fork's behaviour; move fork-only logic into new
   classes with small hooks, per "Before a large refactor" in `docs/UPSTREAM.md`.
5. **Retire this fork's reader.** `git mv` `view/j2k/opj/` and the Java JPIP cache into `attic/`
   with one line in `attic/README.md`. Nothing is deleted.
6. **Regression campaign against the live Helioviewer JPIP servers, on real hardware:**
   progressive decode of a single image, a 1,000-frame JPX movie (speed and memory against the
   current OpenJPEG build), disk-cache restoration across restarts, Callisto radio, many-file
   local layers, and the PUNCH, RHEF and warp paths that sit on top of image layers.

## Cost, honestly

The code is a few sessions of agent work. The calendar time is set by native builds on three
platforms, three to six build-test-fix rounds that need Gilly running Studio, and the layer-refactor
merge, where every conflict resolution is a guess until the app runs. Roughly a focused week of
Gilly's attention if he is available daily.

## Payoff

The largest permanent divergence from upstream becomes a decoder shim of a few hundred lines.
Upstream's reader fixes (retry accounting, cache keys, cancellation, byte-bounded caches) then
arrive as ordinary cherry-picks, and this fork stops maintaining its own JPIP implementation.

## When to reopen

- This fork's JPIP code starts costing real bugs, or
- upstream adopts a pluggable decoder (ask; the design document `docs/ddf3.md` line 170 says Kakadu
  "will not be replaced on the client side", so expect no), or
- a large upstream sync is happening anyway for other reasons.

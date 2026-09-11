# Load From Cache

A View-menu dialog that lists what is already on disk, says how it lines up with the master
time range, and loads it as a layer instead of downloading it again.

The problem it solves, in Gilly's words: stop re-downloading cached datasets just because one of
the parameters got bumped.

Companion page: https://claude.ai/code/artifact/6ac6af25-6c73-4ecd-8b1c-caa79d094fc1

## What is actually on disk

`NetFileCache` is content-addressed: one FITS frame per file, named `sha256(uri)`. It is one-way,
there is no index, and the source URL cannot be recovered from a cached file. So there is no
dataset on disk to list, and the only description of what is cached lives in the FITS headers.

That is the better source anyway. A URL is authoritative about where data came from; the header is
authoritative about what it is.

Measured on a real cache, 2026-09-10: 754 files, 13 GB, a full header pass in 2.4 s, nothing
unreadable, roughly a third of the frames tile-compressed. Grouped by identity that is six
datasets, of which two PUNCH product types account for 12.3 of the 13 GB.

| Frames | On disk | Span | Dataset |
|---|---|---|---|
| 268 | 6.0 GB | 2025-09-19 → 09-25 | PUNCH 1-2-3-4 · WFI+NFI Mosaic · L3 · CA · 0l |
| 154 | 325 MB | 2025-09-19 → 09-25 | GOES-18 · SUVI · L1b |
| 107 | 215 MB | see note | SOHO · LASCO · C3 |
| 104 | 209 MB | see note | SOHO · LASCO · C2 |
| 88 | 6.3 GB | 2025-09-20 → 09-25 | PUNCH 1-2-3-4 · WFI+NFI Mosaic · L3 · PA · 0l |
| 33 | 528 MB | 2025-07-16 → 09-20 | Proba-3 · ASPIICS · L3 |

## Decisions

### Reuse the application's FITS reader, do not write a second one

`FitsMetaData` takes a `MetaDataContainer`, which is a small key-to-value interface, so
`FitsHeaderContainer` adapts a nom-tam `Header` in about twenty lines and the scanner then reads
headers exactly the way the application does.

This is not tidiness. The first throwaway scan reported no observation time for either LASCO
dataset, because SOHO writes `DATE-OBS = '2025/09/19'` and puts the time in a separate `TIME-OBS`
card. `FitsMetaData.retrieveTime` has known that for years, along with `DATE_OBS` for MDI and early
EIT, and date-only headers meaning midnight. A second reader is a second place for all of it to be
wrong.

Two consequences, both discovered by building it:

- `MetaDataContainer` was package-private while `FitsMetaData`'s constructor taking one was public,
  so the constructor was unusable from outside the package. The interface is public now.
- A full `FitsMetaData` cannot be built outside a running application: `retrievePosition` asks
  SPICE where the observer was. Worse, `CommonMetaData` holds `Sun.StartEarth` as a static field,
  so merely loading the metadata classes initialises the ephemeris. `FitsMetaData.observation()`
  returns identity and time only, which is everything read before that line and all the scanner
  needs. The scan still cannot run in a check, because that class-init reaches SPICE regardless;
  in the application it is settled long before, since `AppInit.loadSpice()` runs before the window
  is built.

### The index

2.4 s is fine once and much too long on every dialog open. `Settings/cacheIndex.json` holds one
record per frame keyed on `name + length + modification time`; a later pass re-reads only files
whose key changed. An unreadable or version-stale index means a full rescan, never an error.

The first scan runs in the background with a progress row. A four-second freeze on a menu item is
the kind of thing that gets a feature blamed for the wrong problem.

### Grouping

A dataset is the frames sharing an identity: the application's own display name, which carries
mission, instrument and detector and is what the layer will be called, plus `LEVEL`, `TYPECODE` and
`FILEVRSN` where a file has them.

The display name is not enough alone. PUNCH's clear and polarized mosaics are both
"WFI+NFI Mosaic". And the file version is the whole point: `0k` and `0l` are two cache entries for
one observation, and seeing them as two rows a version apart is what stops the re-download.

### Colouring: coverage, not containment

Against the master range from `TimeSelectorPanel`:

- **covers** — the dataset spans the whole master range. Load it and nothing is missing.
- **partial** — the two overlap. The row says by how much.
- **no overlap** — disjoint. Still listed, still loadable, greyed rather than hidden, because
  loading one is also how you move the master range to it.

Containment would paint a dataset green for being *small*, which is the opposite of the question
being asked.

Three edges, all found by the check rather than by thinking:

- A master range with no duration reports no overlap for everything, not covering for everything.
  Both are defensible in the abstract; only one is safe on screen, because a row of green chips
  says "you already have all of this".
- Two real spans meeting at a single instant do not overlap. A dataset ending exactly when the
  range begins contributes one frame at the boundary and nothing that plays.
- A dataset that *is* a single instant is the exception to that rule. It has no span to share, so
  the same test would grey out a lone cached frame sitting squarely inside the range.

### Naming, which only a real scan could settle

`LEVEL` is not a standard card and instruments use it however they like. The first scan of the
real cache produced three names that were wrong in three different ways: PUNCH writes `'3'` and
wants an L in front of it; Proba-3 writes `'L3'` and got a second one, reading `ASPIICS · LL3`;
GOES SUVI writes the whole of "National Aeronautics and Space Administration (NASA) L1b", which is
a provenance statement rather than a level, and turned a dataset name into a paragraph.

So an L is added only when one is missing, and anything longer than six characters is not treated
as a level at all. Dropping it costs nothing: the display name it would have joined already carries
the mission and instrument.

## What a row shows

Columns mirror the manage readout, so a cached dataset is described in the same words as a loaded
one: status chip, dataset name, frames, span, cadence, size on disk, native size, and level, type
and version as their own sortable columns.

Actions on a selected row: **Load as layer**, handing the group's files to `Commands.loadImage`
sorted by time; **Reveal in Finder**; **Delete**, which is the only deliberate way to get 6 GB
back short of clearing everything.

Filters above the table: text over the dataset name, and a three-way toggle over the status chips.

## Build order

1. **Scanner and index.** `FitsHeaderContainer`, `CacheIndex`, the grouping, the overlap
   arithmetic. No UI. Checked by `CacheIndexCheck`. Done.
2. **The dialog.** Table, chips, filters, background first scan. Read-only, and already useful: it
   answers "what have I got" without touching anything. Done; measured on the real cache at
   2.8 s cold and 21 ms warm, which is the index earning its place at 133 times over.
3. **Load and delete.** The two actions that change something, once the reading half has been seen
   on screen.

### The chips are the one thing not taken from the theme

Good, caution and nothing are a separate axis from the interface's accent: a theme is free to be
purple or orange, and green still has to mean "you already have this". `CacheChipContrastCheck`
holds all six colours at 4.5:1 against the list background of every built-in theme, and it caught
the grey failing at 4.26 and 3.90 in the two dark ones.

Its first version measured whether the three could be told apart using the contrast ratio, which
was the wrong instrument. Contrast is a luminance difference, and a semantic palette deliberately
keeps its good and caution colours at similar lightness so neither shouts over the other; asking
for contrast between them would have forced green and amber apart in brightness for no reader's
benefit. What separates them is hue, 106 degrees of it, and what separates both from the third is
that the third has almost no colour at all.

## Open, with recommendations

- **Should a long gap split a dataset?** ASPIICS is 33 frames over two months, almost certainly two
  campaigns. Recommendation: not yet. One row per identity with the span and frame count visible so
  the hole is obvious. Splitting invents a boundary the data does not state.
- **Does the JP2 store belong here?** JPIP layers save a whole movie as one `.jpx` under
  `Downloads/`: one file, already a movie, already readably named. Recommendation: not in the first
  version. Mixing one-file-per-movie with one-file-per-frame in one table explains itself badly.

## Limits

- A layer loaded from the cache has no remembered query, so the PUNCH refresh button will not
  appear on it. It is a set of local files and the application cannot know what search produced
  them.
- The cache never evicts. This dialog is the first thing that makes 13 GB visible, which is why
  Delete is in scope rather than deferred.
- Frames whose headers carry no instrument identity land in one unidentified group. There are none
  in the measured cache; grouping them together still beats hiding them.
- The dialog reads what is on disk, not what is loadable. A truncated file is listed and fails at
  load, reported as one failed frame, which is the existing behaviour for any bad file.

# Indexed Carrington Synoptic Map Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `ptmc_compo_sm_*.fits`-style hand-annotated Carrington synoptic maps (a 20-category
index-coded chart: coronal-hole boundaries, polarity, neutral line, filaments, sunspots, plage)
load via the existing `File > Open Image Layer…` flow and render draped on the full solar sphere
with exact, un-blended legend colors.

**Architecture:** No new rendering machinery — JHV's existing CAR/CEA surface-map shader path
(`solarCommon.frag`/`solarOrtho.frag`) and `ColorRule`/LUT auto-selection system
(`LUT.java`/`colors.js`) already do everything needed once a file is (a) recognized as this map
type and (b) given a categorical (not auto-stretched, not linearly-blended) rendering path. This
plan adds one metadata-detection branch, one data-only LUT resource, and two small, targeted
rendering fixes (nearest-neighbor texture filtering + a hidden levels slider) — all gated on a
single new boolean, `MetaData.isIndexedSurfaceMap()`.

**Tech Stack:** Java 25, OpenGL ES 3.0 (via `GLES30`), Swing (JIDE components), `nom.tam.fits`.
No test framework exists in this repo — verification follows the repo's own convention, a
`main`-guarded self-check class under `extra/test/` (see `extra/test/JHVMetadataDump.java` for
precedent), compiled and run exactly as `extra/fits/run-fast-rice-verifier.sh` does.

## Global Constraints

- Full design/architecture rationale lives in `docs/pr-design-carrington-sphere.md` — read it
  before starting if anything below is unclear on *why*, not just *what*.
- Detection signature: FITS header `ORIGIN` starting with `ptmc_compo` (the IDL pipeline's existing,
  stable convention — confirmed present on every file of this kind, zero pipeline changes needed).
- Render `PRIMARY` HDU only (4013×2011, `BITPIX=8`, already the correctly-scaled 2:1 equirectangular
  product). `FULL`/`LONG`/`LAT`/`POL` extensions are out of scope.
- The categorical LUT ("CH/Polarity Legend") is index→RGB exactly per `20_in_Color_Table.docx`:
  indices 0–13 and 16–19 defined, 14–15 explicitly unused per the docx, everything else (20–255)
  falls back to loud magenta (`R=255,G=0,B=255`) so any stray/unexpected value is obviously wrong
  rather than silently miscolored.
- No new UI surface, no new menu item, no manual "treat as indexed" override — auto-detection only,
  per the approved design.
- No `.ggr` file — the LUT is added as a 256-entry block appended to `resources/luts/standard-luts.txt`
  (already read by `LUT.loadLuts()`), which requires **zero Java code changes** for the LUT itself.

---

### Task 1: `isIndexedSurfaceMap` accessor on `MetaData`

**Files:**
- Modify: `src/org/helioviewer/jhv/metadata/MetaData.java`
- Modify: `src/org/helioviewer/jhv/metadata/CommonMetaData.java`

**Interfaces:**
- Produces: `MetaData.isIndexedSurfaceMap(): boolean` — every later task (Task 3's detection, Task 5's
  texture filter, Task 6's panel hiding) calls this exact method on a `MetaData` instance.

- [ ] **Step 1: Add the interface method**

In `src/org/helioviewer/jhv/metadata/MetaData.java`, the interface currently ends with:

```java
    @Nonnull
    DetectorMask getDetectorMask();

}
```

Change it to:

```java
    @Nonnull
    DetectorMask getDetectorMask();

    boolean isIndexedSurfaceMap();

}
```

- [ ] **Step 2: Add the default-false field + implementation to `CommonMetaData`**

In `src/org/helioviewer/jhv/metadata/CommonMetaData.java`, the field block currently reads:

```java
    protected Region region = Region.DEFAULT;
    protected String displayName = "unknown";
    protected URI sourceUri = MetaData.UNKNOWN_SOURCE_URI;
    protected DetectorMask detectorMask = DetectorMask.NONE;
```

Change it to:

```java
    protected Region region = Region.DEFAULT;
    protected String displayName = "unknown";
    protected URI sourceUri = MetaData.UNKNOWN_SOURCE_URI;
    protected DetectorMask detectorMask = DetectorMask.NONE;
    protected boolean isIndexedSurfaceMap = false;
```

And the `getDetectorMask()` implementation currently reads:

```java
    @Nonnull
    @Override
    public DetectorMask getDetectorMask() {
        return detectorMask;
    }

}
```

Change it to:

```java
    @Nonnull
    @Override
    public DetectorMask getDetectorMask() {
        return detectorMask;
    }

    @Override
    public boolean isIndexedSurfaceMap() {
        return isIndexedSurfaceMap;
    }

}
```

- [ ] **Step 3: Compile to verify no other `MetaData` implementer breaks**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
```

Expected: `BUILD SUCCESSFUL`. (Interface additions with a default in the only production
implementer, `CommonMetaData`, cannot break other implementers — `CommonMetaData` is the base class
every other metadata type, including `FitsMetaData`, extends. If `BUILD FAILED` names a different
class implementing `MetaData` directly, add the same two-line accessor there too before proceeding.)

- [ ] **Step 4: Commit**

```bash
git add src/org/helioviewer/jhv/metadata/MetaData.java src/org/helioviewer/jhv/metadata/CommonMetaData.java
git commit -m "Add MetaData.isIndexedSurfaceMap(), default false"
```

---

### Task 2: `WcsInterpreter` forced-projection overload

**Files:**
- Modify: `src/org/helioviewer/jhv/metadata/WcsInterpreter.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `WcsInterpreter.read(MetaDataContainer m, @Nullable WcsHeader.Projection forcedProjection): Result`
  — Task 3 calls this with `WcsHeader.Projection.CAR` when `isIndexedSurfaceMap` is true, `null`
  otherwise. The existing 1-arg `read(MetaDataContainer m)` keeps working unchanged (delegates with
  `null`) so every other call site is untouched.

This is the one real architectural gap the naive design missed: `WcsInterpreter.read()` computes
`unitPerPixelX/Y` and `crvalX/Y` differently depending on its own *internally derived*
`isSurfaceMap` (from `fromCtype`). Reassigning `FitsMetaData.wcsProjection` *after* calling `read()`
would leave those geometry fields wrong (the non-surface-map branch sets `unitPerPixelX/Y = 0`).
The projection must be forced *before* `read()`'s internal branch runs.

- [ ] **Step 1: Write the failing self-check**

Create `extra/test/WcsInterpreterForcedProjectionCheck.java`:

```java
package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.wcs.WcsHeader;

// Standalone self-check (no test framework in this repo — see extra/test/JHVMetadataDump.java for
// the established pattern). Confirms forcing CAR routes WcsInterpreter through the surface-map
// geometry branch even when CTYPE1/CTYPE2 don't literally end in "CAR".
public final class WcsInterpreterForcedProjectionCheck {

    public static void main(String[] args) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("CTYPE1", "Longitude");
        headers.put("CTYPE2", "Latitude");
        headers.put("CDELT1", "0.0897247426998");
        headers.put("CDELT2", "0.0895816823006");
        headers.put("CRVAL1", "0.0");
        headers.put("CRVAL2", "-90.0");
        MetaDataContainer m = new MapMetaDataContainer(headers);

        WcsInterpreter.Result unforced = WcsInterpreter.read(m);
        assertTrue(unforced.projection() == WcsHeader.Projection.TAN,
                "unforced: expected TAN (literal 'Longitude'/'Latitude' CTYPE doesn't match fromCtype), got " + unforced.projection());
        assertTrue(unforced.unitPerPixelX() == 0,
                "unforced non-surface-map branch should leave unitPerPixelX at 0, got " + unforced.unitPerPixelX());

        WcsInterpreter.Result forced = WcsInterpreter.read(m, WcsHeader.Projection.CAR);
        assertTrue(forced.projection() == WcsHeader.Projection.CAR,
                "forced: expected CAR, got " + forced.projection());
        assertTrue(forced.unitPerPixelX() > 0,
                "forced surface-map branch should compute a nonzero unitPerPixelX, got " + forced.unitPerPixelX());
        assertTrue(forced.unitPerPixelY() > 0,
                "forced surface-map branch should compute a nonzero unitPerPixelY, got " + forced.unitPerPixelY());

        System.out.println("WcsInterpreterForcedProjectionCheck: PASS");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }
}
```

This references `MapMetaDataContainer`, a small test-only `MetaDataContainer` implementation that
doesn't exist yet — create it alongside:

Create `extra/test/MapMetaDataContainer.java`:

```java
package org.helioviewer.jhv.metadata;

import java.util.Map;
import java.util.Optional;

// Minimal MetaDataContainer backed by a plain Map, for standalone self-checks that construct
// synthetic FITS headers without opening a real file.
final class MapMetaDataContainer implements MetaDataContainer {

    private final Map<String, String> headers;

    MapMetaDataContainer(Map<String, String> _headers) {
        headers = _headers;
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(headers.get(key));
    }

    @Override
    public Optional<Double> getDouble(String key) {
        return getString(key).map(Double::parseDouble);
    }

    @Override
    public Optional<Long> getLong(String key) {
        return getString(key).map(Long::parseLong);
    }

    @Override
    public String getRequiredString(String key) {
        return getString(key).orElseThrow(() -> new RuntimeException("Missing required key: " + key));
    }

    @Override
    public double getRequiredDouble(String key) {
        return getDouble(key).orElseThrow(() -> new RuntimeException("Missing required key: " + key));
    }

    @Override
    public long getRequiredLong(String key) {
        return getLong(key).orElseThrow(() -> new RuntimeException("Missing required key: " + key));
    }
}
```

`MetaDataContainer` (confirmed, full interface, `src/org/helioviewer/jhv/metadata/MetaDataContainer.java`)
has exactly six methods — `getString`, `getLong`, `getDouble`, `getRequiredString`, `getRequiredLong`,
`getRequiredDouble` — matching `MapMetaDataContainer` above exactly; no additional stub methods
needed.

- [ ] **Step 2: Run the check to verify it fails to compile**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
mkdir -p /tmp/jhv-carrington-check
CP="bin:resources"
while IFS= read -r jar; do CP="$CP:$jar"; done < <(find lib -type f -name '*.jar' | sort)
javac --release 25 -cp "$CP" -d /tmp/jhv-carrington-check extra/test/MapMetaDataContainer.java extra/test/WcsInterpreterForcedProjectionCheck.java
```

Expected: **compile error** — `WcsInterpreter.read(MetaDataContainer, WcsHeader.Projection)` does
not exist yet (only the 1-arg overload does). This is the "red" state.

- [ ] **Step 3: Add the forced-projection overload**

In `src/org/helioviewer/jhv/metadata/WcsInterpreter.java`, the current `read` method starts:

```java
    static Result read(MetaDataContainer m) {
        String ctype1 = m.getString("CTYPE1").orElse("");
        String ctype2 = m.getString("CTYPE2").orElse("");
        WcsHeader.Projection projection = WcsHeader.Projection.fromCtype(ctype1, ctype2);
        boolean isSurfaceMap = projection.isSurfaceMap();
```

Change it to:

```java
    static Result read(MetaDataContainer m) {
        return read(m, null);
    }

    static Result read(MetaDataContainer m, WcsHeader.Projection forcedProjection) {
        String ctype1 = m.getString("CTYPE1").orElse("");
        String ctype2 = m.getString("CTYPE2").orElse("");
        WcsHeader.Projection projection = forcedProjection != null ? forcedProjection : WcsHeader.Projection.fromCtype(ctype1, ctype2);
        boolean isSurfaceMap = projection.isSurfaceMap();
```

Everything after this line (`WcsInput wcs = readWcsInput(m); ...`) is unchanged — the rest of the
method already branches purely on the local `isSurfaceMap`/`projection` variables, which now honor
the forced value.

- [ ] **Step 4: Run the check again to verify it passes**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
rm -rf /tmp/jhv-carrington-check && mkdir -p /tmp/jhv-carrington-check
CP="bin:resources"
while IFS= read -r jar; do CP="$CP:$jar"; done < <(find lib -type f -name '*.jar' | sort)
javac --release 25 -cp "$CP" -d /tmp/jhv-carrington-check extra/test/MapMetaDataContainer.java extra/test/WcsInterpreterForcedProjectionCheck.java
java -cp "/tmp/jhv-carrington-check:$CP" org.helioviewer.jhv.metadata.WcsInterpreterForcedProjectionCheck
```

Expected: `WcsInterpreterForcedProjectionCheck: PASS`

- [ ] **Step 5: Commit**

```bash
git add src/org/helioviewer/jhv/metadata/WcsInterpreter.java extra/test/WcsInterpreterForcedProjectionCheck.java extra/test/MapMetaDataContainer.java
git commit -m "WcsInterpreter: add forced-projection overload for non-standard CTYPE surface maps"
```

---

### Task 3: `FitsMetaData` detection branch

**Files:**
- Modify: `src/org/helioviewer/jhv/metadata/FitsMetaData.java`

**Interfaces:**
- Consumes: `MetaData.isIndexedSurfaceMap()` (Task 1), `WcsInterpreter.read(m, forcedProjection)` (Task 2).
- Produces: any `FitsMetaData` built from a header with `ORIGIN` starting with `ptmc_compo` now has
  `isIndexedSurfaceMap() == true`, `getDetector() == "CHPOL"`, and `getWcsHeader().projection ==
  WcsHeader.Projection.CAR` with correct surface-map geometry. Task 4's `colors.js` rule matches on
  `detector == "CHPOL"`. Task 5/6 call `isIndexedSurfaceMap()`.

- [ ] **Step 1: Write the failing self-check**

Create `extra/test/FitsMetaDataChpolarityCheck.java`:

```java
package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.wcs.WcsHeader;

// Standalone self-check (no test framework in this repo). Confirms the ORIGIN-prefix detection
// branch: files from the ptmc_compo pipeline are recognized as indexed surface maps with a
// synthetic "CHPOL" detector; anything else is unaffected.
public final class FitsMetaDataChpolarityCheck {

    public static void main(String[] args) throws Exception {
        FitsMetaData indexed = build("ptmc_compo_sm_20250909_041922_cr2302DO_l3");
        assertTrue(indexed.isIndexedSurfaceMap(), "ptmc_compo origin should set isIndexedSurfaceMap");
        assertTrue("CHPOL".equals(indexed.getDetector()), "ptmc_compo origin should set detector=CHPOL, got " + indexed.getDetector());
        assertTrue(indexed.getWcsHeader().projection == WcsHeader.Projection.CAR,
                "ptmc_compo origin should force CAR projection, got " + indexed.getWcsHeader().projection);

        FitsMetaData unrelated = build("some_other_pipeline_output");
        assertTrue(!unrelated.isIndexedSurfaceMap(), "non-ptmc_compo origin must NOT set isIndexedSurfaceMap");
        assertTrue(!"CHPOL".equals(unrelated.getDetector()), "non-ptmc_compo origin must NOT set detector=CHPOL");

        System.out.println("FitsMetaDataChpolarityCheck: PASS");
    }

    // package-visible (not private): Task 4's ChpolarityLutRegistrationCheck reuses this builder
    static FitsMetaData build(String origin) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("ORIGIN", origin);
        headers.put("NAXIS1", "4013");
        headers.put("NAXIS2", "2011");
        headers.put("CTYPE1", "Longitude");
        headers.put("CTYPE2", "Latitude");
        headers.put("CDELT1", "0.0897247426998");
        headers.put("CDELT2", "0.0895816823006");
        headers.put("CRVAL1", "0.0");
        headers.put("CRVAL2", "-90.0");
        headers.put("DATE-OBS", "2025-09-09T04:19:22.127");
        MetaDataContainer m = new MapMetaDataContainer(headers);
        return new FitsMetaData(m, MetaData.UNKNOWN_SOURCE_URI);
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }
}
```

`MapMetaDataContainer` (Task 2) will need every header key `FitsMetaData`'s constructor path
touches for this input, or its unimplemented-method stubs will throw. Before running, re-check
which optional headers `identifyObservation`/`retrievePosition`/`retrieveOcculterRadii`/etc. read
via `getString(...).orElse(...)` (safe, returns empty) versus `getRequiredString`/`getRequiredDouble`
(throws if absent) — add any additionally-required keys to the `headers` map above. `DATE-OBS` is
included because `retrieveTime` requires one of `DATE-AVG`/`DATE_AVG`/`DATE_OBS`/`DATE-OBS`.

- [ ] **Step 2: Run the check to verify it fails**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
rm -rf /tmp/jhv-carrington-check && mkdir -p /tmp/jhv-carrington-check
CP="bin:resources"
while IFS= read -r jar; do CP="$CP:$jar"; done < <(find lib -type f -name '*.jar' | sort)
javac --release 25 -cp "$CP" -d /tmp/jhv-carrington-check extra/test/MapMetaDataContainer.java extra/test/FitsMetaDataChpolarityCheck.java
java -cp "/tmp/jhv-carrington-check:$CP" org.helioviewer.jhv.metadata.FitsMetaDataChpolarityCheck
```

Expected: `AssertionError: ptmc_compo origin should set isIndexedSurfaceMap` (the branch doesn't
exist yet, so `isIndexedSurfaceMap()` is still the Task-1 default `false`). This is the "red" state.

- [ ] **Step 3: Add the detection branch**

In `src/org/helioviewer/jhv/metadata/FitsMetaData.java`, `identifyObservation` currently reads:

```java
    private void identifyObservation(MetaDataContainer m) {
        observatory = m.getString("TELESCOP").orElse("");
        instrument = m.getString("INSTRUME").orElse("");
        int instrumentSuffix = instrument.indexOf('_');
        if (instrumentSuffix != -1)
            instrument = instrument.substring(0, instrumentSuffix);
        detector = m.getString("DETECTOR").orElse("");
        measurement = m.getString("WAVELNTH").orElse("");
```

Change it to:

```java
    private void identifyObservation(MetaDataContainer m) {
        observatory = m.getString("TELESCOP").orElse("");
        instrument = m.getString("INSTRUME").orElse("");
        int instrumentSuffix = instrument.indexOf('_');
        if (instrumentSuffix != -1)
            instrument = instrument.substring(0, instrumentSuffix);
        detector = m.getString("DETECTOR").orElse("");
        measurement = m.getString("WAVELNTH").orElse("");

        String origin = m.getString("ORIGIN").orElse("");
        if (origin.startsWith("ptmc_compo")) {
            detector = "CHPOL";
            isIndexedSurfaceMap = true;
        }
```

And the tail of the same method, currently:

```java
        } else if (detector.equals("demregpy")) {
            displayName = "DEM " + instrument;
        } else {
            displayName = instrument + ' ' + measurement;
        }
    }
```

Change to:

```java
        } else if (detector.equals("demregpy")) {
            displayName = "DEM " + instrument;
        } else if (isIndexedSurfaceMap) {
            displayName = "CH/Polarity Legend " + origin;
        } else {
            displayName = instrument + ' ' + measurement;
        }
    }
```

`origin` is a local variable in this method (declared in the block just added above), so it's in
scope at this later `else if` — no field promotion needed since both uses are within the same method
body.

Now `retrievePixelParameters`, currently:

```java
        if (instrument.equals("CALLISTO")) {
            region = new Region(0, 0, pixelW, pixelH);
        } else {
            WcsInterpreter.Result wcs = WcsInterpreter.read(m);
            wcsProjection = wcs.projection();
            boolean isSurfaceMap = wcsProjection.isSurfaceMap();
```

Change to:

```java
        if (instrument.equals("CALLISTO")) {
            region = new Region(0, 0, pixelW, pixelH);
        } else {
            WcsInterpreter.Result wcs = WcsInterpreter.read(m, isIndexedSurfaceMap ? WcsHeader.Projection.CAR : null);
            wcsProjection = wcs.projection();
            boolean isSurfaceMap = wcsProjection.isSurfaceMap();
```

(`WcsHeader` is already imported in this file — confirmed at the top of `FitsMetaData.java`.)

- [ ] **Step 4: Run the check again to verify it passes**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
rm -rf /tmp/jhv-carrington-check && mkdir -p /tmp/jhv-carrington-check
CP="bin:resources"
while IFS= read -r jar; do CP="$CP:$jar"; done < <(find lib -type f -name '*.jar' | sort)
javac --release 25 -cp "$CP" -d /tmp/jhv-carrington-check extra/test/MapMetaDataContainer.java extra/test/FitsMetaDataChpolarityCheck.java
java -cp "/tmp/jhv-carrington-check:$CP" org.helioviewer.jhv.metadata.FitsMetaDataChpolarityCheck
```

Expected: `FitsMetaDataChpolarityCheck: PASS`

- [ ] **Step 5: Commit**

```bash
git add src/org/helioviewer/jhv/metadata/FitsMetaData.java extra/test/FitsMetaDataChpolarityCheck.java
git commit -m "FitsMetaData: detect ptmc_compo-origin indexed synoptic maps, force CAR projection"
```

---

### Task 4: CH/Polarity LUT resource + color rule

**Files:**
- Modify: `resources/luts/standard-luts.txt`
- Modify: `resources/settings/colors.js`

**Interfaces:**
- Produces: a LUT registered under the exact name `CH/Polarity Legend`, auto-selected by
  `LUT`'s existing `ColorRule` matcher whenever a layer's `detector == "CHPOL"` (set by Task 3).

No Java changes in this task — `LUT.loadLuts()` already reads `standard-luts.txt` unconditionally,
and `readColorRules()` already reads `colors.js` unconditionally, both at `LUT`'s static-init time.

- [ ] **Step 1: Append the LUT block to `standard-luts.txt`**

The file currently ends with the `PUNCH` and `HMI magnetogram` blocks (256 comma-separated signed
ints each), each block separated by exactly one blank line, e.g.:

```
PUNCH
-16777216, -16645888, ... (256 total)

HMI magnetogram
-7798764, -7601903, ... (256 total)
```

Append a new block, `CH/Polarity Legend`, after the last existing block, preceded by one blank line
(matching the file's existing block-separator convention) and followed by a trailing newline:

```
CH/Polarity Legend
-1, -16777216, -16777088, -16776961, -2031617, -65281, -65536, -4144960, -13346289, -16711936, -23296, -256, -16384, -10496, -65281, -65281, -9239281, -3644416, -11008753, -3650816, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281, -65281
```

This block is exactly 256 comma-separated signed-int32 values, one entry per raw byte index (index
`N`'s color is entry `N`), packed as `0xAARRGGBB` per `LUT.packArgbToRgba` — entries 0/1 are the
docx's unnumbered White/Black (an inferred convention, since 0/1 never appear as pixel values in the
sample file — low-risk if wrong, a one-line fix later), 2–13 and 16–19 are the docx's numbered
legend verbatim, 14–15 (docx-confirmed unused) and 20–255 all fall back to loud magenta
(`R=255,G=0,B=255`) so any unexpected raw value is visually obvious rather than silently wrong.

The exact index→color source table (for reference / future edits):

| Index | Meaning | RGB |
|---|---|---|
| 0 | White (background) | 255,255,255 |
| 1 | Black | 0,0,0 |
| 2 | + CH Boundary Navy | 0,0,128 |
| 3 | + CH Blue | 0,0,255 |
| 4 | + Polarity Light Cyan | 224,255,255 |
| 5 | - CH Boundary Magenta | 255,0,255 |
| 6 | - CH Red | 255,0,0 |
| 7 | - Polarity Silver | 192,192,192 |
| 8 | Neutral line Dark Green | 52,90,15 |
| 9 | Filaments Green | 0,255,0 |
| 10 | Sun Spots Orange | 255,165,0 |
| 11 | Missing Yellow | 255,255,0 |
| 12 | Border Tangerine | 255,192,0 |
| 13 | Plage centers Gold | 255,215,0 |
| 16 | AIA+CH 304 Red | 115,5,15 |
| 17 | AIA+CH 193 Orange | 200,100,0 |
| 18 | AIA-CH 304 Red | 88,5,15 |
| 19 | AIA-CH 193 Orange | 200,75,0 |
| 14, 15, 20–255 | (unused / fallback) | 255,0,255 |

- [ ] **Step 2: Append the color rule to `colors.js`**

The file currently ends:

```json
 {"instrument":"NFI-0",
  "color":"PUNCH"},

]
```

Change the closing to add a new rule before the `]`:

```json
 {"instrument":"NFI-0",
  "color":"PUNCH"},
 {"detector":"CHPOL",
  "color":"CH/Polarity Legend"},

]
```

- [ ] **Step 3: Write and run a self-check confirming both resources parse and link correctly**

Create `extra/test/ChpolarityLutRegistrationCheck.java`:

```java
package org.helioviewer.jhv.metadata;

import org.helioviewer.jhv.image.lut.LUT;

// Standalone self-check (no test framework in this repo). Confirms the new LUT resource parses,
// has exactly 256 entries, and the colors.js rule matching detector=CHPOL resolves to it.
public final class ChpolarityLutRegistrationCheck {

    public static void main(String[] args) {
        LUT byName = LUT.get("CH/Polarity Legend");
        assertTrue(byName != null, "LUT 'CH/Polarity Legend' should be registered from standard-luts.txt");
        int entries = byName.rgba().remaining() / 4;
        assertTrue(entries == 256, "expected 256 LUT entries, got " + entries);

        FitsMetaData indexed = FitsMetaDataChpolarityCheck.build("ptmc_compo_sm_20250909_041922_cr2302DO_l3");
        LUT byRule = LUT.get(indexed);
        assertTrue(byRule != null && "CH/Polarity Legend".equals(byRule.name()),
                "colors.js rule for detector=CHPOL should resolve to CH/Polarity Legend, got " + (byRule == null ? "null" : byRule.name()));

        System.out.println("ChpolarityLutRegistrationCheck: PASS");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond)
            throw new AssertionError(message);
    }
}
```

Reuses `LUT.get(String name)` and `LUT.get(FitsMetaData meta)` — both confirmed public static
accessors on `LUT` (`src/org/helioviewer/jhv/image/lut/LUT.java`) — and calls
`FitsMetaDataChpolarityCheck.build(...)` (already package-visible per Task 3) rather than
duplicating a second synthetic `FitsMetaData` constructor.

Compile and run:

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
rm -rf /tmp/jhv-carrington-check && mkdir -p /tmp/jhv-carrington-check
CP="bin:resources"
while IFS= read -r jar; do CP="$CP:$jar"; done < <(find lib -type f -name '*.jar' | sort)
javac --release 25 -cp "$CP" -d /tmp/jhv-carrington-check extra/test/MapMetaDataContainer.java extra/test/FitsMetaDataChpolarityCheck.java extra/test/ChpolarityLutRegistrationCheck.java
java -cp "/tmp/jhv-carrington-check:$CP" org.helioviewer.jhv.metadata.ChpolarityLutRegistrationCheck
```

Expected: `ChpolarityLutRegistrationCheck: PASS`

- [ ] **Step 4: Commit**

```bash
git add resources/luts/standard-luts.txt resources/settings/colors.js extra/test/ChpolarityLutRegistrationCheck.java
git commit -m "Add CH/Polarity Legend LUT + color rule for indexed synoptic maps"
```

---

### Task 5: `GLImage` nearest-neighbor filtering for indexed layers

**Files:**
- Modify: `src/org/helioviewer/jhv/opengl/GLImage.java`

**Interfaces:**
- Consumes: `View.ImageData.metaData().isIndexedSurfaceMap()` (Task 1).
- Produces: no new public API — internal rendering behavior change only.

- [ ] **Step 1: Change the texture filter in `streamImage`**

In `src/org/helioviewer/jhv/opengl/GLImage.java`, `streamImage` currently reads:

```java
    public void streamImage(View.ImageData imageData, View.ImageData prevImageData, View.ImageData baseImageData) {
        if (uploadedImageData != imageData) {
            tex.bind();
            tex.copyImageBuffer(imageData.imageBuffer(), GL.LINEAR);
            uploadedImageData = imageData;
        }
```

Change to:

```java
    public void streamImage(View.ImageData imageData, View.ImageData prevImageData, View.ImageData baseImageData) {
        if (uploadedImageData != imageData) {
            tex.bind();
            int filter = imageData.metaData().isIndexedSurfaceMap() ? GL.NEAREST : GL.LINEAR;
            tex.copyImageBuffer(imageData.imageBuffer(), filter);
            uploadedImageData = imageData;
        }
```

(`diffTex`'s `copyImageBuffer` call two lines below stays `GL.LINEAR` — a difference/movie-compare
mode against an indexed categorical layer isn't a real use case this plan covers, and leaving it
untouched keeps this diff minimal per the design's non-goals.)

- [ ] **Step 2: Compile**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Manual regression check — an ordinary continuous-data layer still renders unchanged**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant jar
java -jar bin-dist/JHelioviewer.jar   # or the repo's normal run command if different, e.g. ./run-demo.sh
```

In the running app: `File > New Image Layer…`, load any ordinary AIA layer. Expected: renders
exactly as before this change (smooth/linear-filtered) — `isIndexedSurfaceMap()` is `false` for
every layer type except the one this plan adds, so this code path is unreachable for existing
layers. This is a regression check, not new functionality.

- [ ] **Step 4: Commit**

```bash
git add src/org/helioviewer/jhv/opengl/GLImage.java
git commit -m "GLImage: nearest-neighbor texture filtering for indexed surface maps"
```

---

### Task 6: Hide the levels (brightness/contrast) slider for indexed layers

**Files:**
- Modify: `src/org/helioviewer/jhv/layers/selector/ImageLayerOptions.java`

**Interfaces:**
- Consumes: `ImageLayer.getMetaData().isIndexedSurfaceMap()` (Task 1; `ImageLayer.getMetaData()`
  already exists — confirmed at `ImageLayer.java:353-354`).
- Produces: no new public API — the levels panel becomes invisible (not removed, so layout doesn't
  jump) whenever the active layer is an indexed surface map. Combined with Task 5, this guarantees
  `GLImage.brightOffset=0, brightScale=1` (their construction-time defaults — confirmed no other
  code path auto-computes an initial stretch) are never disturbed for this layer type, since the
  only caller of `setBrightness` is this panel's slider callback.

- [ ] **Step 1: Promote `levelsPanel` from local variable to field**

In `src/org/helioviewer/jhv/layers/selector/ImageLayerOptions.java`, the field declarations
currently read:

```java
    private final LUTPanel lutPanel;
    private final SlitPanel slitPanel;
    private final InnerMaskPanel innerMaskPanel;
    private final SliderFilterPanel.DeltaCROTA deltaCROTAPanel;
    private final SliderFilterPanel.DeltaCRVAL1 deltaCRVAL1Panel;
    private final SliderFilterPanel.DeltaCRVAL2 deltaCRVAL2Panel;
```

Change to:

```java
    private final LUTPanel lutPanel;
    private final FilterDetails levelsPanel;
    private final SlitPanel slitPanel;
    private final InnerMaskPanel innerMaskPanel;
    private final SliderFilterPanel.DeltaCROTA deltaCROTAPanel;
    private final SliderFilterPanel.DeltaCRVAL1 deltaCRVAL1Panel;
    private final SliderFilterPanel.DeltaCRVAL2 deltaCRVAL2Panel;
```

And in the constructor, the local declaration:

```java
        lutPanel = new LUTPanel(layer);
        FilterDetails levelsPanel = new LevelsPanel(layer);
```

Change to (drop the local `FilterDetails` type — it's now assigning the field):

```java
        lutPanel = new LUTPanel(layer);
        levelsPanel = new LevelsPanel(layer);
```

- [ ] **Step 2: Hide the panel in `refresh()` when indexed**

`FilterDetails` (`src/org/helioviewer/jhv/layers/filters/FilterDetails.java`, confirmed full
interface) already provides exactly the method needed:

```java
public interface FilterDetails {
    Component getFirst();
    Component getSecond();
    Component getThird();

    default void setVisible(boolean visible) {
        getFirst().setVisible(visible);
        getSecond().setVisible(visible);
        getThird().setVisible(visible);
    }
}
```

`refresh()` currently reads:

```java
    public void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        downloadButton.setVisible(!imageLayer.isLocal());
        lutPanel.setLUT(imageLayer.getView().getDefaultLUT());
    }
```

Change to:

```java
    public void refresh(Layer layer) {
        ImageLayer imageLayer = (ImageLayer) layer;
        downloadButton.setVisible(!imageLayer.isLocal());
        lutPanel.setLUT(imageLayer.getView().getDefaultLUT());

        levelsPanel.setVisible(!imageLayer.getMetaData().isIndexedSurfaceMap());
    }
```

- [ ] **Step 3: Compile**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant compile
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manual verification — regression + new behavior**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant jar
java -jar bin-dist/JHelioviewer.jar
```

Load an ordinary AIA layer: confirm the levels slider is visible and works exactly as before
(regression check). This step's actual new-behavior verification (levels hidden for an indexed
layer) happens in Task 7, once a real indexed file can be loaded end-to-end.

- [ ] **Step 5: Commit**

```bash
git add src/org/helioviewer/jhv/layers/selector/ImageLayerOptions.java
git commit -m "ImageLayerOptions: hide the levels slider for indexed surface map layers"
```

---

### Task 7: End-to-end verification with the real file

**Files:** none (verification only).

**Interfaces:** none — this task consumes everything Tasks 1–6 produced, together.

- [ ] **Step 1: Confirm the real file's HDU order has no tile-compressed extension**

Already confirmed empirically during planning (`BITPIX=8` on `PRIMARY`, no `ZIMAGE`/`ZCMPTYPE` on
any of the 5 HDUs) — re-run as a sanity check before the live load, since `findHDU`
(`FITSImage.java:57-71`) picks the first `CompressedImageHDU` anywhere in the file, unconditionally,
before falling back to first-`ImageHDU`-with-axes:

```bash
python3 -c "
from astropy.io import fits
with fits.open('/Users/gilly/Documents/NWRA/PUNCH_Science/carrington_maps_handmade/ptmc_compo_sm_20250909_041922_cr2302DO_l3.fits') as hdul:
    for i, h in enumerate(hdul):
        hdr = h.header
        print(i, hdr.get('EXTNAME','PRIMARY'), 'BITPIX='+str(hdr.get('BITPIX')), 'ZIMAGE='+str(hdr.get('ZIMAGE')))
"
```

Expected: `ZIMAGE=None` on every line (already true — this just re-confirms nothing changed).

- [ ] **Step 2: Build and launch**

```bash
cd /Users/gilly/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant jar
java -jar bin-dist/JHelioviewer.jar
```

- [ ] **Step 3: Load the file**

`File > Open Image Layer…` → select
`/Users/gilly/Documents/NWRA/PUNCH_Science/carrington_maps_handmade/ptmc_compo_sm_20250909_041922_cr2302DO_l3.fits`.

- [ ] **Step 4: Verify wraparound and pole behavior**

Rotate the view continuously in one direction. Confirm:
- No visible seam where longitude wraps 360°→0°.
- Both poles are reachable and show a single coherent (if visually compressed, as expected for an
  equirectangular pole) region — not a discontinuity or missing wedge.
- The far side (180° from the initial view) is visible after rotating, with the same map content
  that was there originally (proving the texture is glued to the Sun's frame, not the camera).

- [ ] **Step 5: Verify colors against the legend**

Open `20_in_Color_Table.docx` side by side. Spot-check at least 4 distinct regions of different
colors in the rendered map against the legend table in Task 4 — confirm exact color match (no
blending/washing at region boundaries; check specifically at a boundary between two adjacent
categories, e.g. where a coronal-hole boundary line meets a polarity region, for any smearing that
would indicate `GL.NEAREST` isn't actually taking effect).

- [ ] **Step 6: Verify the levels slider is hidden**

Select the loaded layer in the layer list, open its options panel. Confirm the levels
(brightness/contrast) slider is not visible. Confirm the LUT dropdown shows "CH/Polarity Legend"
selected by default.

- [ ] **Step 7: Verify orientation (north/south)**

Compare the rendered map's polarity pattern near the visible pole against the source data's known
orientation (`CRVAL2=-90` means row 0 of the FITS array is the south pole). If the render shows the
map upside-down (north/south swapped from the real Sun), note this as a bug to fix in
`solarCommon.frag`'s texcoord-y convention for surface maps — out of scope for this plan's code
changes (which only added detection/color/filtering, not shader math) but must be flagged, not
silently accepted, if wrong.

- [ ] **Step 8: If all checks pass, this feature is complete.** If any check fails, do not proceed
  to opening a PR — return to the relevant task above, fix, and re-run the full Task 7 sequence.

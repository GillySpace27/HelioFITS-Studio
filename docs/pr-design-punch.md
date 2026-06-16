# PR design: punch

## Target architecture
HOW REMOTE SOURCES / LAYERS ARE ADDED ON CURRENT UPSTREAM (all paths under /Users/gilly/Documents/NWRA/PUNCH_Science/jhv-baseline):

The canonical analogue is the SOAR source — it is the exact template to copy. It is a pair: io/SoarClient.java (network + query logic, runs off-EDT) and gui/dialog/SoarDialog.java (Swing dialog implementing the client's Receiver interfaces). The PUNCH source mirrors this pair 1:1.

1. MENU + ACTION WIRING:
- src/org/helioviewer/jhv/gui/Actions.java — holds nested static AbstractAction subclasses. Existing pattern at Actions.java:96-116:
    `public static class NewSoarLayer extends AbstractKeyAction { public NewSoarLayer() { super("New SOAR Layer...", KeyStroke.getKeyStroke(...)); } @Override public void actionPerformed(ActionEvent e) { SoarDialog.getInstance().showDialog(); } }`
  and `NewSynopticLayer extends AbstractAction` (no accelerator) at :107. Dialog imports are at Actions.java:22-26 (`import org.helioviewer.jhv.gui.dialog.SoarDialog;` etc). NOTE: package is `gui.dialog` (singular) on upstream — the POC used `gui.dialogs` (plural), which no longer exists.
- src/org/helioviewer/jhv/gui/component/MenuBar.java — builds the File menu. Lines 27-31:
    `fileMenu.add(new Actions.NewLayer()); fileMenu.add(new Actions.NewSoarLayer()); fileMenu.add(new Actions.NewSynopticLayer()); fileMenu.add(new Actions.OpenLocalFile()); fileMenu.addSeparator();`
  NOTE: package is `gui.component` (singular) — the POC used `gui.components` (plural).

2. DIALOG INFRASTRUCTURE (SoarDialog.java is the template):
- Extends `com.jidesoft.dialog.StandardDialog`, `@SuppressWarnings("serial")`, singleton via `getInstance()` -> `new SoarDialog(MainFrame.get())` (NOTE: `org.helioviewer.jhv.gui.MainFrame.get()`, NOT the POC's `JHVFrame.getFrame()`).
- Implements the client's Receiver interfaces (e.g. `implements SoarClient.ReceiverItems, SoarClient.ReceiverSoops`).
- Overrides `createButtonPanel()` (Add/Cancel via `com.jidesoft.dialog.ButtonPanel`), `createContentPanel()` (the body), `createBannerPanel()` (returns null). Has a public `showDialog()` that calls `pack(); setLocationRelativeTo(MainFrame.get()); setVisible(true);`.
- Uses `org.helioviewer.jhv.gui.time.TimeSelectorPanel` for the time range (NOTE: package `gui.time`, NOT the POC's `gui.components.timeselector`). API confirmed: `new TimeSelectorPanel()`, `getStartTime()`/`getEndTime()` (long ms), `setTime(long start, long end)`, `addListener(TimeListener.Selection)`.
- Result list is a `JList<DataItem>` with `com.jidesoft.swing.SearchableUtils.installSearchable(listPane)` and a JScrollPane sized `new Dimension(500, 350)`. A `JLabel foundLabel` shows "N found".
- Error/confirm dialogs via `org.helioviewer.jhv.app.Message` — `Message.err(title, msg)` (Message.java:19) and `Message.warn(title, msg)` (Message.java:23). NOTE: package `app.Message`.

3. THE CLIENT / IO LAYER (SoarClient.java is the template; everything under src/org/helioviewer/jhv/io/):
- Async pattern: `org.helioviewer.jhv.thread.Task.submit(String logContext, Callable<T> task, Consumer<T> onSuccess, String errorMessage)` runs the Callable off-EDT and delivers the result on the EDT (Task.java:24). NOTE: class is `thread.Task` with static `submit`, NOT the POC's `threads.Tasks`. Receiver interfaces are nested in the client (e.g. `public interface ReceiverItems { void setSoarResponseItems(List<DataItem> list); }`), and `submitXxx` methods are `Task.submit("soar", new QueryXxx(...), receiver::setXxx, "Error ...")`.
- Query bodies are private `record QueryXxx(...) implements Callable<List<...>>`.
- HTTP: `org.helioviewer.jhv.io.NetClient` — `try (NetClient nc = NetClient.of(URI uri, boolean allowError, NetClient.NetCache cache)) { ... }`. `NetCache` enum = {CACHE, NETWORK, BYPASS} (NetClient.java:32-34, unchanged from POC). `nc.isSuccessful()` -> boolean; `nc.getSource()` -> `okio.BufferedSource` with `.readUtf8()` (NetClient.java:15,25 — unchanged). For JSON sources Soar uses `JSONUtils.get(uri)`, but PUNCH parses HTML directory listings so it uses the raw `readUtf8()` path.
- Logging: `org.helioviewer.jhv.app.Log` with `Log.info(...)`/`Log.warn(...)`. NOTE: package `app.Log`, NOT the POC's top-level `org.helioviewer.jhv.Log`.

4. HOW A LAYER + VIEW IS CONSTRUCTED FROM URIs (the load path — UNCHANGED, the keystone of why this PR is cheap):
- `org.helioviewer.jhv.app.Commands.loadImage(List<URI> uris)` returns `CompletableFuture<ImageLayer>` (Commands.java:158). It resolves URIs off-EDT via `FileUtils.resolveURIListOffEDT`, then `ImageLayer.create(null); layer.load(resolved);` and completes the future (Commands.java:162-186). Empty list -> completes null. So the POC's `Commands.loadImage(uris).thenAccept(layer -> ...)` ports verbatim.
- FITS routing is automatic: `view/uri/URIView.java:38` -> `reader = dataUri.format() == DataUri.Format.Image.FITS ? new FITSImage() : new GenericImage();`. `io/DataUri.java` detects FITS by content-type `application/fits` (DataUri.java:26) and by the `.fits.gz` extension hack (DataUri.java:16); `Format.Image` enum includes FITS (DataUri.java:42). PUNCH metadata is read by `metadata/FitsMetaData.java` which already handles observer keywords DSUN_OBS, HGLN_OBS/CRLN_OBS, HGLT_OBS/CRLT_OBS and a WCS projection (FitsMetaData.java:234-265). => No new view, format, or metadata code is required for frames to load.

5. TIME-RANGE / CADENCE / MOVIE INTEGRATION:
- The global movie span is `org.helioviewer.jhv.movie.Player.getStartTime()` / `Player.getEndTime()` (Player.java:104,108). NOTE: class is `movie.Player`, NOT the POC's `layers.Movie`.
- The "no movie loaded" sentinel: POC compared against `TimeUtils.START.milli`. On upstream `org.helioviewer.jhv.time.TimeUtils.START` is a `JHVTime` (TimeUtils.java:31) with `.milli`. `TimeUtils.format(long)` (TimeUtils.java:63) and `TimeUtils.DAY_IN_MILLIS = 86400000` (TimeUtils.java:28) both exist, so the POC's hardcoded `DAY_MILLI = 86400_000L` can be replaced with `TimeUtils.DAY_IN_MILLIS`.

6. PER-LAYER REFRESH BUTTON HOST (optional sub-feature):
- src/org/helioviewer/jhv/layers/filters/DifferencePanel.java (unchanged from POC base) — constructor `DifferencePanel(ImageLayer layer)` builds a difference-mode radio group and a single `JideButton syncButton` added `buttonPanel.add(syncButton, BorderLayout.LINE_END)` (DifferencePanel.java:39-42). Icon strings live in `gui.component.Buttons` (`Buttons.sync` = Buttons.java:46, `Buttons.refresh` = Buttons.java:76). Spinner UI is `gui.component.CircularProgressUI` (confirmed present). NOTE both are `gui.component` (singular). `ImageLayer.getStartTime()/getEndTime()` (ImageLayer.java:397/402) and `getGLImage()` (ImageLayer.java:384) exist.

## POC logic to preserve
The algorithm to preserve lives entirely in POC branch `punch-source`, file io/PunchClient.java. Read it with `git show punch-source:src/org/helioviewer/jhv/io/PunchClient.java`. The load-bearing pieces:

A) ARCHIVE LAYOUT + URL PATTERNS (must be preserved exactly — this is the data contract):
    BASE_URL = "https://umbra.nascom.nasa.gov/punch"
  The archive is a plain Apache directory tree:
    {BASE}/{level}/{product}/{YYYY}/{MM}/{DD}/PUNCH_L{lvl}_{code}_{YYYYMMDDhhmmss}_v{ver}.fits
  Three regexes drive parsing of the HTML index pages:
    FILE_PATTERN = Pattern.compile("PUNCH_L[0-9A-Z]_[A-Z0-9]{3}_(\\d{14})_v[0-9A-Za-z]+\\.fits")   // group(1) = yyyyMMddHHmmss
    DIR_PATTERN  = Pattern.compile("href=\"([A-Z0-9]{2,4})/\"")   // product subdirs under a level
    NUM_DIR_PATTERN = Pattern.compile("href=\"(\\d{2,4})/\"")     // numeric YYYY/MM/DD subdirs (for coverage probe)
    FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
  The per-day directory URL is built as:
    String.format("%s/%s/%s/%04d/%02d/%02d/", BASE_URL, level, product, year, month, day)

B) PRODUCT LISTING (QueryProducts): GET {BASE}/{level}/ , scrape DIR_PATTERN, return List<String> of product codes.

C) COVERAGE PROBE (QueryCoverage): walk year -> month -> day, at each level pick the lexicographically largest numeric subdir (`latestNumeric`), and return that day's UTC midnight as epoch ms (0 if nothing). Used to seed the time range to the archive's newest day when no movie is loaded — important because PUNCH's public archive ends in the past, so JHV's default "now" range returns nothing.

D) FRAME QUERY + CADENCE FILTER (QueryItems) — the trickiest logic, includes the bug fix in commit 36af4cce6:
    // iterate each UTC day from floor(start) to end, GET the dir index, parse FILE_PATTERN into a TreeMap<Long,DataItem> keyed by epoch-ms (newer file versions overwrite same timestamp because the index is name-sorted)
    TreeMap<Long, DataItem> found = new TreeMap<>();
    for (long day = Math.floorDiv(start, DAY_MILLI) * DAY_MILLI; day <= end; day += DAY_MILLI) { listDay(found, day); }
    // cadence decimation. lastKept is seeded just below the first in-range item so item.milli>=start always passes on the first iteration. Long.MIN_VALUE would overflow (item.milli - lastKept):
    List<DataItem> result = new ArrayList<>(found.size());
    long lastKept = start - Math.max(1, cadence) - 1;
    for (DataItem item : found.values()) {
        if (item.milli >= start && item.milli <= end && item.milli - lastKept >= cadence) {
            result.add(item);
            lastKept = item.milli;
        }
    }
    return result;
  ^ THE OVERFLOW FIX (commit 36af4cce6) is the `long lastKept = start - Math.max(1, cadence) - 1;` seed. The original buggy seed was Long.MIN_VALUE, which made `item.milli - lastKept` overflow negative and reject every frame, so search always returned empty. This MUST be carried over.

E) DataItem record (the JList element + load unit):
    public record DataItem(String file, URI uri, long milli) { @Override public String toString() { return TimeUtils.format(milli) + "  " + file; } }

F) LOAD: `submitLoad` maps items -> `List<URI> uris = items.stream().map(DataItem::uri).toList();` then `Commands.loadImage(uris).thenAccept(layer -> { if (layer != null) rememberQuery(...); });`

G) REFRESH (optional): a `WeakHashMap<ImageLayer, QueryState>` (weak so removed layers aren't pinned) records each layer's original (level, product, start, end, cadence, Set<URI> loadedUris). `submitRefresh` re-runs QueryItems, diffs against loadedUris, and loads only the new URIs as a fresh layer; returns RefreshResult(existingCount, newCount, newLayer). Records: `QueryState(String level, String product, long start, long end, long cadence, Set<URI> loadedUris)`, `RefreshResult(int existingCount, int newCount, ImageLayer newLayer)`, interface `RefreshReceiver { void onRefreshComplete(RefreshResult result); }`.

H) SEARCH CAP (PunchDialog, commit 36af4cce6): MAX_FILES=5000 hard cap (Message.err past it), CONFIRM_FILES=200 soft prompt (JOptionPane OK_CANCEL warning) — because Commands.loadImage downloads frames serially so the layer is usable while later frames stream in.

I) DIALOG empty-state + movie-prefill (PunchDialog): inherit Player movie span if a movie is loaded; else coverage probe seeds the latest archived day; empty result shows "0 found — archive may not cover this period; see umbra.nascom.nasa.gov/punch". Product list defaults to "PTM" if present. Product tooltip map (ProductInfo) maps codes PTM/CTM/PAM/CAM/PNN/CNN/PFM/CFM/PIM/CIM/PSM/CSM/CQM to descriptions. Cadence presets: native(0), 10min, 30min, 1h, 3h, 6h, 1day. Levels: {"3","Q","2","1","0"}.

## File plan

### [new] src/org/helioviewer/jhv/io/PunchClient.java
Port POC punch-source:src/org/helioviewer/jhv/io/PunchClient.java verbatim in logic, with ONLY these mechanical adaptations to current upstream: (1) `import org.helioviewer.jhv.app.Log;` instead of `org.helioviewer.jhv.Log`. (2) Replace `org.helioviewer.jhv.threads.Tasks.submit(...)` with `org.helioviewer.jhv.thread.Task.submit(...)` (same 4-arg shape: logContext, Callable, Consumer, errorMessage — Task.java:24). (3) `import org.helioviewer.jhv.app.Commands;` (unchanged path). (4) `import org.helioviewer.jhv.layers.ImageLayer;` (unchanged). (5) `import org.helioviewer.jhv.time.TimeUtils;` and replace the local `private static final long DAY_MILLI = 86400_000L;` with `TimeUtils.DAY_IN_MILLIS` at use sites (or keep a private constant — Bogdan tolerates both; smaller diff = reuse TimeUtils.DAY_IN_MILLIS). Keep: BASE_URL, FILE_PATTERN, DIR_PATTERN, NUM_DIR_PATTERN, FILE_TIME constants; record DataItem(String file, URI uri, long milli); interfaces ReceiverItems/ReceiverProducts/ReceiverCoverage; static submitSearchTime/submitGetProducts/submitGetCoverage/submitLoad with the same signatures; private records QueryItems/QueryProducts/QueryCoverage implementing Callable; readIndex(String) using `try (NetClient nc = NetClient.of(new URI(url), true, NetClient.NetCache.NETWORK))` then `nc.isSuccessful()`/`nc.getSource().readUtf8()`. Keep the cadence-overflow-safe seed `long lastKept = start - Math.max(1, cadence) - 1;`. SCOPE DECISION: include the refresh machinery (RefreshResult/RefreshReceiver/QueryState/layerQueries WeakHashMap/rememberQuery/hasRememberedQuery/submitRefresh) ONLY if also wiring DifferencePanel; if dropping refresh to minimize diff, delete all of part-G code AND the DifferencePanel edit. Class is `public final class PunchClient` with private ctor, like SoarClient (SoarClient.java:18,155).

### [new] src/org/helioviewer/jhv/gui/dialog/PunchDialog.java
Port POC punch-source:src/org/helioviewer/jhv/gui/dialogs/PunchDialog.java with these mechanical fixes: (1) package `org.helioviewer.jhv.gui.dialog` (singular). (2) `import org.helioviewer.jhv.gui.MainFrame;` and replace every `JHVFrame.getFrame()` with `MainFrame.get()` (SoarDialog.java:70,226). (3) `import org.helioviewer.jhv.gui.time.TimeSelectorPanel;` (not gui.components.timeselector). (4) `import org.helioviewer.jhv.app.Message;` (not top-level Message). (5) `import org.helioviewer.jhv.movie.Player;` and replace `Movie.getStartTime()/Movie.getEndTime()` with `Player.getStartTime()/Player.getEndTime()`. (6) `import org.helioviewer.jhv.time.TimeUtils;` keep TimeUtils.START.milli sentinel and TimeUtils.format(...). Keep all dialog structure exactly: extends StandardDialog implements PunchClient.ReceiverItems/ReceiverProducts/ReceiverCoverage; singleton getInstance(); MAX_FILES=5000, CONFIRM_FILES=200, resultSize Dimension(500,350); String[] Level={"3","Q","2","1","0"}; ProductInfo Map.ofEntries(...) tooltip map; record Cadence(String label, long milli) + Cadences[] presets; levelCombo/productCombo/cadenceCombo/timeSelectorPanel/listPane/foundLabel/coverageLabel fields; productsDownloaded + rangeUserChanged flags; createButtonPanel (Add/Cancel), getLoadButton (with MAX_FILES Message.err + CONFIRM_FILES JOptionPane), createContentPanel, getSearchButton, createBannerPanel returns null, showDialog (movie-prefill + coverage probe), and the three setPunchResponseXxx callbacks. Title "New PUNCH Layer". Mirror SoarDialog idioms (it is the sibling on disk).

### [edit] src/org/helioviewer/jhv/gui/Actions.java
Add `import org.helioviewer.jhv.gui.dialog.PunchDialog;` alongside the other dialog imports (Actions.java:22-26). Add a nested action class mirroring NewSynopticLayer (no accelerator, since N+Shift is taken by SOAR): `public static class NewPunchLayer extends AbstractAction { public NewPunchLayer() { super("New PUNCH Layer..."); } @Override public void actionPerformed(ActionEvent e) { PunchDialog.getInstance().showDialog(); } }`. Place it right after NewSynopticLayer (~Actions.java:117).

### [edit] src/org/helioviewer/jhv/gui/component/MenuBar.java
One line: insert `fileMenu.add(new Actions.NewPunchLayer());` after `fileMenu.add(new Actions.NewSynopticLayer());` (MenuBar.java:29), before `fileMenu.add(new Actions.OpenLocalFile());`.

### [edit] src/org/helioviewer/jhv/layers/filters/DifferencePanel.java
OPTIONAL — include only if shipping the per-layer refresh feature (recommend SPLITTING this out; see risks). Port the POC diff: add imports java.awt.Dimension, javax.swing.JProgressBar, `org.helioviewer.jhv.app.Message`, `org.helioviewer.jhv.gui.component.CircularProgressUI` (singular package; POC used gui.components.base), `org.helioviewer.jhv.io.PunchClient`. In the constructor, before the syncButton, build a JideButton refreshButton(Buttons.refresh) enabled by `PunchClient.hasRememberedQuery(layer)`, with a CircularProgressUI indeterminate JProgressBar spinner (20x20, hidden); on click disable+show-spinner, call `PunchClient.submitRefresh(layer, result -> { restore button; Message.warn(...) with newCount })`. Wrap refreshButton(LINE_START)+syncButton(LINE_END) in a `JPanel rightCluster = new JPanel(new BorderLayout())` added to `buttonPanel.add(rightCluster, BorderLayout.LINE_END)`. NOTE: POC imported Buttons from gui.components — upstream is gui.component (already imported in the file). If refresh is dropped, do NOT touch this file at all.

## Style notes
Bogdan idioms to follow exactly (all verified against SoarClient/SoarDialog on disk): K&R braces, 4-space indent, no tabs, UTF-8, newline at EOF (CONTRIBUTING.md). Client = `public final class` with private no-arg constructor at the bottom of the file (SoarClient.java:155). Off-EDT work via `Task.submit(\"<context>\", new QueryXxx(...), receiver::setter, \"Error ...\")`; query bodies as private `record QueryXxx(...) implements Callable<...>`. Receiver interfaces nested in the client, named `ReceiverItems`/`ReceiverProducts`/etc with setter methods named `set<Source>ResponseItems(...)`. Dialog = singleton with static `getInstance()` and a private constructor taking `JFrame mainFrame`; `@SuppressWarnings(\"serial\")`; extend `com.jidesoft.dialog.StandardDialog`; override createButtonPanel/createContentPanel/createBannerPanel(returns null); public `showDialog()` does pack/setLocationRelativeTo(MainFrame.get())/setVisible(true). Use `MainFrame.get()` everywhere (never the old JHVFrame). Use `Message.err`/`Message.warn` for user dialogs, `Log.info`/`Log.warn` for logging, both from `org.helioviewer.jhv.app`. Combo/list idioms: `new DefaultComboBoxModel<>(list.toArray(String[]::new))`, `SearchableUtils.installSearchable(listPane)`, `listPane.setListData(list.toArray(DataItem[]::new))`. Pattern-match instanceof in listeners (`if (combo.getSelectedItem() instanceof String s)`), exactly as SoarDialog and the POC already do. Prefer reusing `TimeUtils.DAY_IN_MILLIS` over a local DAY_MILLI constant. Keep `// mark loaded only after successful callback` style of short explanatory comments — Bogdan keeps terse intent comments. Mirror the SOAR pattern over inventing anything; the smaller and more SOAR-shaped the diff, the more likely the merge.

## Risks
- POLICY/SCOPE (the big one — see open question): Bogdan merges only his own work and is selective. He may not want to host a NASA-mission-specific source hardcoded to one URL. If he prefers a generic remote-archive abstraction, the dialog/client structure changes substantially. Land as a clean SOAR-shaped pair first and let him decide.
- WCS RENDERING UNVERIFIED on post-refactor code. PUNCH L2/L3 mosaics are large-FOV (out to ~45 deg) helioprojective products; FitsMetaData reads observer keywords (DSUN_OBS, HGLN/CRLN, HGLT/CRLT) and a wcs.projection() (FitsMetaData.java:234-265), but #314 (unify non-ortho coordinate handling) / #316 (surface map WCS projections) reworked projection handling and were NOT exercised against PUNCH. Frames will LOAD as FITS layers regardless (load path is unchanged and confirmed), but correct on-sphere placement/scale is unproven. This is a render concern, orthogonal to this PR's data-access scope — flag it, don't try to fix it here.
- DifferencePanel is the wrong host for a PUNCH-refresh button conceptually (it is the difference-mode panel, reused only because it already owns the per-layer button cluster). It also creates an io->layers.filters dependency. Strongly recommend SHIPPING THE REFRESH BUTTON AS A SEPARATE PR (or dropping it) to keep PR #2 to 4 files / pure additive (PunchClient + PunchDialog + 2 one-liners). Smaller diff = higher merge odds.
- MAX_FILES=5000 serial download could be a long unbounded operation; Bogdan may object to the cap size or want a hard time-span limit. The CONFIRM_FILES=200 prompt mitigates but he may want it lower.
- HTML directory-listing scraping (regex over Apache autoindex) is brittle if SDAC changes its index format or fronts it with a different server. SOAR uses a real TAP/ADQL API; PUNCH has no query API, so scraping is the only option — worth a sentence in the PR description so Bogdan knows it is deliberate, not lazy.
- Coverage probe + product list issue several sequential network GETs on dialog open; on a slow link the dialog populates with a delay. POC handles this with async callbacks and 'Checking archive coverage...' placeholder text — preserve that UX.

## Open questions
- SCOPE/POLICY (ask Bogdan first): Do you want JHelioviewer to host a PUNCH-specific source (a NewPunchLayer action + PunchDialog hardcoded to umbra.nascom.nasa.gov/punch), or would you prefer a GENERIC remote-archive/directory-tree source of which PUNCH is one configured instance (e.g. a small registry of {name, baseUrl, file-regex, layout-template})? The POC is PUNCH-specific; a generic design is more reusable but a bigger, more speculative diff.
- Should the per-layer 'refresh for new frames' button ship in this PR (touching DifferencePanel, adding an io->layers.filters edge), be its own follow-up PR, or be dropped? It is the only part that reaches outside the IO+dialog boundary.
- Is scraping the SDAC Apache directory index acceptable, or do you require/know of a proper query API for the PUNCH archive? (There is no TAP-like endpoint as there is for SOAR.)
- Acceptable bounds for a single load: is MAX_FILES=5000 with a 200-file confirmation prompt OK, or do you want a hard cap / max time-span instead?
- Where should the menu item live and what label/accelerator — 'New PUNCH Layer...' in the File menu right after 'New Synoptic Layer...', no accelerator (since Cmd/Ctrl+Shift+N is taken by SOAR)? Confirm naming/placement preference.
- Will PUNCH L2/L3 large-FOV helioprojective WCS render correctly after #314/#316, or is additional projection/metadata work needed before a PUNCH source is useful? (Frames load either way; this is about correct placement.)
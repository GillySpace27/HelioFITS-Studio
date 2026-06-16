# PR design: style-guide

## Target architecture
BOGDAN NICULA STYLE GUIDE for JHelioviewer-SWHV (derived from `upstream/master` HEAD 525e4c59a and 9 recent commits: 8eca622a0, 065a048b0, d8162e380, 18f1b2ec0, 3d97d3bac, 23f0bc4f5, plus baseline reads of FilterMGN/FilterWOW/ImageFilter/MapMode/GLSLSolarShader/GLSLShader/ImageLayerOptions/ImageFilterPanel/SliderFilterPanel/FilterDetails/LUT/EDTCallbackExecutor/Log). All conclusions are evidenced from quoted source below. CONTRIBUTING.md is short: "K&R style, IntelliJ defaults OK; no tabs; 4-space indent; no DOS EOL, newline at EOF; UTF-8 only." Everything below is the unwritten remainder that IntelliJ-default formatting does NOT enforce but Bogdan consistently does.

=== 1. FILE / CLASS LAYOUT & ORDERING ===
Package statement, blank line, then imports (see import block rule). Class body order is consistently: (a) nested type declarations that are pure constants first if tiny (e.g. `UBO` holder class in GLSLShader.java:8-18), then (b) `static final` constants, then (c) instance fields, then (d) constructor(s), then (e) methods grouped by lifecycle (init/dispose/use), then (f) nested helper classes LAST. Example GLSLSolarShader.java: all the `public static final GLSLSolarShader sphere=...` instances at top (lines 14-19), then private instance fields, then private constructor, then static UBO fields, then `init()`/`dispose()`, then the `bindXxx` methods. FilterMGN.java puts the inner `GaussFilter` class FIRST (it is the algorithm core), then the file-level constants `K`, `MIX_FACTOR`, `sigmas`, `weights`, then the static helper `gaussNormAccumulate`, then the public `filter()` override LAST. Pattern: the `@Override` entry-point method tends to come AFTER the private helpers it calls, not before.
- Visibility is minimized aggressively. Top-level helper classes are package-private (no modifier): `class FilterMGN implements ImageFilter.Algorithm` (no `public`), `class FilterWOW`, `abstract class GLSLShader`. Only types used outside their package get `public`. Many panels are `final class ImageLayerOptions extends JPanel` (package-private + final), `final class LayersTableModel`. Make new classes package-private + final unless cross-package use is required.
- One blank line separates methods; NO blank line is wasted between tightly-coupled field declarations. Bogdan inserts blank lines to mark logical paragraphs inside methods (see commit 8eca622a0 which ADDED blank lines around a 3-statement block to group it).

=== 2. NAMING CONVENTIONS ===
- Constants: ALL_CAPS_SNAKE for true magic numbers used as config: `SCALES`, `MIX_FACTOR`, `ONE_MINUS_MIX_FACTOR`, `SIGMA_E0`, `NOISE_THRESH`, `BOOST`, `K`, `BDIV`, `WCS_SIZE`, `MIN_DCROTA`. BUT lowercase camelCase for `static final` arrays/objects that are conceptually data tables, not scalars: `sigmas`, `weights`, `radii0`, `weights0`, `sigma0`, `latiGridBuf`, `loggedLevel`, `root`, `filename`. So: scalar tunable -> UPPER; data table / object handle -> lowerCamel even when static final.
- Fields/locals/methods: lowerCamelCase. Boolean fields read as predicates: `hasCommon`, `removed`, `diffMode`.
- GL reference-location ints get the `Ref` suffix: `pv0Ref`, `pv1Ref`, `latiGridRef`. GL buffer-object handles get `BO` suffix: `wcsBO`, `projectionBO`, `screenBO`. Backing FloatBuffers get `Buf` suffix: `wcsBuf`, `projectionBuf`. Sizes get `_SIZE` suffix.
- Column-index constants in table models: `NAME_COL`, `TIME_COL`, `NUMBER_COLUMNS` (commit 8eca622a0 added `static final int NAME_COL = 1;`).
- CONSTRUCTOR PARAMETER CONVENTION (very distinctive, imitate exactly): constructor params that shadow a field are prefixed with underscore, assigned bare to the field. From MapMode.java: `MapMode(GLSLSolarShader _shader, Kind _kind) { shader = _shader; kind = _kind; }`. From ImageFilter.Type: `Type(String _description, Algorithm _algorithm) { description = _description; algorithm = _algorithm; }`. From GLSLShader: `GLSLShader(String _vertex, String _fragment) { vertex = _vertex; fragment = _fragment; }`. From GLSLSolarShader: `GLSLSolarShader(String vertex, String fragment, boolean _hasCommon)` — note he only underscores the param that collides (`_hasCommon`) while `vertex`/`fragment` pass straight to `super(...)`. RULE: underscore-prefix a ctor param IFF it is stored into a same-named field; otherwise leave bare. NO `this.x = x`.

=== 3. RECORDS vs CLASSES ===
- Records are used freely for immutable value carriers AND for small service singletons. `public record LUT(String name, ByteBuffer rgba)` with a compact canonical constructor that defensively copies: `public LUT { rgba = rgba.asReadOnlyBuffer(); }`. Nested `private record ColorRule(@Nullable String observatory, ... LUT lut)` with compact ctor normalizing fields.
- Records even hold an executor: `record EDTCallbackExecutor(ExecutorService delegate)` with a `static final EDTCallbackExecutor pool = new EDTCallbackExecutor(createCachedPool());` singleton and instance method `<T> Future<T> submit(...)`. So a record is his default for "value + a couple methods, no mutable state."
- Use a plain `class` when there is mutable instance state (filters hold no instance state so they could be records but are classes implementing an interface) or it extends a framework base (JPanel, AbstractTableModel, GLSLShader).

=== 4. FINAL / IMMUTABILITY ===
- `final class` is the default for leaf classes (ImageLayerOptions, LayersTableModel, Layers). `private final` for fields set once in ctor (ImageLayerOptions panels: `private final LUTPanel lutPanel;` etc., assigned in ctor). Method params/locals are generally NOT marked final (IntelliJ default), so do not over-annotate `final` on locals.
- Static lambdas/handlers captured into `final` fields. Enum fields are `final` (`public final String description; final Algorithm algorithm;` — note: only `public` where needed, package-private otherwise, both `final`).

=== 5. HOW HE ADDS AN ENUM ENTRY ===
Canonical from ImageFilter.Type and commit 065a048b0. (a) Add the entry inline in the enum list, keeping entries in a sensible (often alphabetical) order — commit 065a048b0 inserted `FLARE_TRIGGER("Flare Trigger", "FL"),` right after `FLARE`. (b) Each entry carries constructor args. (c) Fields declared below the entries with a terse `//` comment each: `// The abbreviation of the HEKEvent` / `// The name of the SWEK Event`. (d) Constructor uses the `_param` convention. For ImageFilter.Type the algorithm instance is constructed inline in the entry: `MGN("Multi-scale Gaussian normalization", new FilterMGN())`, `WOW("Wavelet-optimized whitening", new FilterWOW())`, and `None("No filter", null)` passes `null` for the no-op case (he dispatches `type == Type.None ? data : filter(...)` rather than giving None a real algorithm). TO ADD RHEF: add `RHEF("Radial histogram equalizing filter", new FilterRHEF())` after WOW; the public `description` String and the `static byte[] filter(..., Type type)` dispatch need no change. To add a new MapMode (e.g. a disk projection) add `LogDisk(GLSLSolarShader.logdisk, Kind.POLAR)` with a matching `GLSLSolarShader.logdisk` static instance.

=== 6. HOW HE WIRES A SWING COMPONENT ===
- Layout managers actually used: `GridBagLayout` for the stacked options panel (ImageLayerOptions), `BorderLayout` and `FlowLayout(FlowLayout.LEADING, 0, 0)` for small sub-panels (ImageFilterPanel). He does NOT use external layout libs except JIDE widgets.
- The reusable row abstraction is the `FilterDetails` interface (three components: `getFirst()` = right-aligned `JLabel` title, `getSecond()` = the control, `getThird()` = trailing button area). Every adjustable image control implements it. New per-layer controls (RHEF strength slider, disk-projection toggle) MUST implement `FilterDetails` and be added in ImageLayerOptions via `addToGridBag(c, panel)` with `c.gridy++;` between rows. Study `ImageLayerOptions.addToGridBag` (3 columns: label gridx=0 LINE_END NONE; control gridx=1 weightx=1 HORIZONTAL; trailing gridx=2 LINE_END NONE).
- GridBagConstraints idiom: one `GridBagConstraints c = new GridBagConstraints();` reused, mutated between `add(...)` calls, `c.gridy++` to advance. He sets `c.anchor`/`c.fill`/`c.weightx` explicitly per cell.
- Listeners are lambdas, never anonymous classes: `item.addActionListener(e -> { layer.getView().clearCache(); layer.getView().setFilter(type); DisplayController.render(1); });` and `slider.addChangeListener(e -> { double value = slider.getValue()/10.; layer.getGLImage().setEnhanced(value); label.setText(formatLabel(value)); DisplayController.display(); });`. Pattern inside every UI listener: mutate model -> update the label text -> trigger a redraw via `DisplayController.display()` (cheap) or `DisplayController.render(1)` (forces decode/cache rebuild). Sliders are the in-house `JHVSlider`, toggle/split buttons are JIDE `JideToggleButton`/`JideSplitButton`/`JideButton` with glyph icons from `Buttons` (e.g. `Buttons.corona`, `Buttons.download`, `Buttons.info`).
- Radio groups: `ButtonGroup g = new ButtonGroup();` loop over `enum.values()`, build a `JRadioButton(type.toString())`, set tooltip from a `description` field, preselect matching current state, add listener, `g.add(item); panel.add(item);` — copy ImageFilterPanel's loop verbatim for the RHEF entry (it auto-appears once RHEF is in the enum, no panel edit needed for the radio itself).
- Tooltips set on nearly every control via `setToolTipText("...")`, sometimes tiny HTML: `label.setToolTipText("<html><body>pixel⋅R<sup>v");`.
- A redraw after model mutation is mandatory; forgetting `DisplayController.display()` is the classic bug.

=== 7. EVENT / LISTENER BROADCAST PATTERN ===
When adding a cross-cutting notification (commit 8eca622a0 is the template), follow all four steps: (1) add a method to the `Layers.Listener` interface (`void nameUpdated(Layer layer);`); (2) add a static `fireXxx` that fans out: `public static void fireNameUpdated(Layer layer){ listeners.forEach(listener -> listener.nameUpdated(layer)); }`; (3) implement the new method as an empty body `@Override public void nameUpdated(Layer layer) {}` in EVERY existing `Layers.Listener` implementor that doesn't care (LayerOptions did `{}`); (4) implement it meaningfully where it matters (LayersTableModel fired a targeted `fireTableCellUpdated(row, NAME_COL)`). Note he prefers FIRING A NARROW EVENT over a broad repaint.

=== 8. COMMENT DENSITY & STYLE ===
- Sparse. Code is expected to read itself. When he comments it is to (a) cite an algorithm source: `// derived from https://dev.ipol.im/~getreuer/code/` (FilterMGN), (b) explain a non-obvious numeric/physics rationale: `// avoid denoising very low noise or blank images`, `// can be faster than serial quickSelect`, `// restore some crispness after denoising`, (c) name a stage in a pipeline: `// A trous transform`, `// Denoise stage`, `// Coefficients`, `// Horizontal pass`. Trailing same-line comments are common: `convolveHorizontal(...); // Horizontal pass`.
- Field-purpose comments are single-line `//` ABOVE the field, terse, capitalized: `// Box weights`, `// Box radii`, `// The abbreviation of the HEKEvent`.
- He leaves COMMENTED-OUT code intentionally as a known-future hook: `// private final SectorPanel sectorPanel;` and the matching `// addToGridBag(c, sectorPanel);` lines in ImageLayerOptions, and `//import com.google.common.base.Stopwatch;` in ImageFilter. This is idiomatic for him but a contributor should NOT add new dead code in a PR he must review — keep diffs clean.
- Almost no Javadoc (`/** */`) in app code. Do not add Javadoc.
- GLSL gets explanatory block comments tying the shader basis to the Java side (solarLogPolar.frag: "// Effective polar map convention is 0 at north and increasing anti-clockwise. // This basis must stay consistent with the Java-side non-ortho projection...").

=== 9. IMPORT STYLE ===
- NO wildcard imports anywhere. Every class explicitly imported (IntelliJ default for JHV).
- Grouped with blank lines between groups, in this order: (1) `java.*` and `javax.*` together (LUT.java shows java.* then javax.annotation grouped); actually two sub-blocks observed — `java.*` first, then a blank, then `javax.*`; (2) blank line; `org.helioviewer.jhv.*` project imports; (3) blank line; third-party `com.jidesoft.swing.*`, `org.json.*` LAST. See ImageLayerOptions (java.awt/javax.swing, then org.helioviewer..., then `import com.jidesoft.swing.JideButton;`) and LUT.java (`org.json.*` after a blank line at the very end). `@Nullable`/`@Nonnull` come from `javax.annotation`.

=== 10. GLSL + SHADER REGISTRATION ===
- Shaders live as resources under `resources/glsl/` as `.vert`/`.frag` pairs. Non-ortho map fragments share `/glsl/solarCommon.frag` which is PREPENDED at load time when the `common` flag is true (GLSLShader._init: `if (common) fragmentText = streamToString(getResource(COMMON_FRAGMENT)) + fragmentText;`).
- To register a new map-projection shader: (1) add `resources/glsl/solarLogDisk.frag` reusing the existing fragment skeleton (a `sampleXxxTexcoord(...)` helper + a `main()` that branches on `diffMode = display.isDiff != NODIFFERENCE` and calls `getColor(texCoord, diffTexCoord, enhancementFactor)`), and reuse the shared `solar.vert`; (2) add a `public static final GLSLSolarShader logdisk = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarLogDisk.frag", true);` instance (the `true` = hasCommon); (3) add `logdisk._init(logdisk.hasCommon);` to `GLSLSolarShader.init()` AND `logdisk._dispose();` to `dispose()` — both static methods list every shader explicitly, so the new one must be added to BOTH; (4) wire it into a MapMode enum entry. The shader reads per-frame state from UBOs (`wcs[0].crval`, `display.radii`, `display.cutOff`, `screen.yStart/yStop`); new tunables ride in the existing `DisplayBlock`/`WCSBlock` via the `bindDisplay(...)`/`bindWCS(...)` packed-FloatBuffer methods (note `setBufferDataIfChanged` vs `setBufferData // always changes`). Adding a brand-new uniform means extending the packed buffer layout (capacity in the `BufferUtils.newFloatBuffer(...)` and the `put(...)` order) on both Java and GLSL `layout(std140)` block — high-risk, see Risks.
- GLSL formatting mirrors Java: 4-space indent, K&R braces, `const` qualifiers on params, descriptive lowerCamel locals.

=== 11. ERROR / NULL HANDLING ===
- `@Nullable`/`@Nonnull` (javax.annotation) annotate API boundaries (LUT.ColorRule fields, return types). Null-guard with `expected == null || ...` short-circuits.
- Logging via the in-house `org.helioviewer.jhv.app.Log` wrapper (`Log.error(...)`, `Log.warn(...)`), never `System.out`/printStackTrace. Commit d8162e380 REMOVED a Guava dependency (`Throwables`) and replaced it with a hand-rolled `Log` helper — he is actively shedding third-party deps; do NOT introduce Guava/Apache-commons; use JDK + existing helpers.
- Wrap-and-rethrow with a domain exception: GLSLShader `catch (Exception e) { _dispose(); throw new GLException("Cannot load shader", e); }` — clean up resources in catch, then rethrow a typed exception with a human message.
- Defensive but terse guards at method tops returning early: FilterMGN `if (width < 1 || height < 1) return data;`, FilterWOW `if (width < 128 || height < 128) return data;`. A new filter should early-return the input unchanged on degenerate sizes.
- There is a literal `// satisfy coverity` guard in ImageLayerOptions (`if (margin == null) margin = new Insets(...)`) — Coverity static analysis is run (see `coverity.sh`); avoid obvious NPE-able dereferences.

=== 12. CONCURRENCY IDIOMS ===
- All UI mutation hops to the EDT via `EventQueue.invokeLater(...)` (java.awt.EventQueue, NOT SwingUtilities). Background compute -> result -> `EventQueue.invokeLater(() -> onSuccess.accept(result))` is centralized in `EDTCallbackExecutor.pool.submit(callable, onSuccess, onFailure)` (a record). New async loads (the PUNCH SDAC source) should route through that executor / existing loader plumbing rather than spawning raw threads.
- Data-parallel pixel loops use the in-house `org.helioviewer.jhv.thread.ParallelRange.run(height, (from, to) -> { for (int y = from; y < to; y++) {...} })` — EVERY hot image loop in FilterMGN/FilterWOW/ImageFilter uses this exact form, iterating rows [from,to). A new RHEF filter MUST use `ParallelRange.run` for its per-row/per-pixel passes, not parallelStream or manual threads. `Arrays.parallelSort` is used where a parallel sort is wanted (FilterWOW.median).
- Named threads via `AppThread.NamedThreadFactory("Worker")`; shutdown hooks registered with descriptive thread names ("JHV-ShutdownHook").
- `Thread.currentThread().interrupt()` on caught `InterruptedException` (EDTCallbackExecutor) — preserve interrupt status, never swallow.

=== 13. FORMATTING SPECIFICS (K&R) ===
- Opening brace on the SAME line (K&R): `class X {`, `void f() {`, `if (c) {`. `else`/`catch`/`while` (do-while) on the same line as the preceding `}`: `} else {`, `} catch (Exception e) {`.
- SINGLE-STATEMENT if/for/while bodies are written WITHOUT braces and on the NEXT line (very consistent): `if (removed) return;`, `if (row >= 0) fireTableCellUpdated(row, NAME_COL);`, `for (int k = 0; k < K; ++k) weights[k] = weights0[i][k] / sum;`. Only add braces when the body is multi-statement. (Note: when the controlled statement is itself an `if/else` chain he DOES brace to avoid the dangling-else, e.g. GaussFilter.extension.)
- 4-space indent, spaces only, no tabs; newline at EOF (commit 065a048b0 deleted a trailing blank line so the file ends with exactly one newline after `}`).
- Spacing: space after keywords (`if (`, `for (`, `while (`), space around binary operators and after commas, NO space before `(` in calls, NO space inside parens. Pre-increment `++k` preferred in counting loops. Numeric literals use trailing `f` for float and leading `.` is acceptable (`+ .5f`, `1f / 16`).
- Float constants written compactly: `1f`, `0.97f`, `1f - MIX_FACTOR`, `100 / Math.PI`.
- Ternary used liberally for short branches: `type == Type.None ? data : filter(...)`, `thrown == null ? "" : ": " + thrown.getMessage()`.

TINY BEFORE/AFTER (matching his actual edit in 8eca622a0):
  // NOT his style (over-braced, this-qualified, broad event):
  if (Objects.equals(oldName, getName())) {
      Layers.fireTimeUpdated(this);
  } else {
      Layers.fireLayerUpdated(this);
  }
  // His style (braceless, blank-line-grouped, narrow event then always-time):
  if (!Objects.equals(oldName, getName()))
      Layers.fireNameUpdated(this);
  Layers.fireTimeUpdated(this);

=== 14. CHANGELOG & COMMIT MESSAGE ===
- Every user-visible change adds ONE bullet to the top `## JHelioviewer X.Y.Z (pending)` section of `changelog.md`, imperative, terse, with backticks around literal tokens: "- Add `ARC` WCS projection support", "- Add options to choose colors and line thickness for annotations". Group under `### Display and rendering` / `### Image loading and data` / `### Application control and integration` when the pending section grows subsections.
- Commit subjects are short imperative, Title-Case-ish, optionally `(fixes #NNN)`: "Add layer name update event", "Map Flare Trigger to HEK flare events (fixes #105)", "Remove use of Guava Throwables", "Simplify EDT callback executor". No body needed for small changes. Author is Bogdan; a contributor PR should still match this subject style so he can fast-forward/cherry-pick cleanly. Keep ONE logical change per commit (he commits in very small, single-purpose increments — "Cleanup", "Reduce", "Optimize TimeMap").

## POC logic to preserve
n/a

## File plan

## Style notes
TOP DO / DON'T RULES (highest-leverage for getting a PR merged):

DO:
1. Constructor param underscore convention: `Foo(Bar _x) { x = _x; }` for any param stored into a same-named field. NEVER `this.x = x`.
2. Make new classes package-private + `final` (`final class`, no `public`) unless used cross-package. Minimize visibility everywhere.
3. Braceless single-statement if/for/while bodies on the next line. Brace only multi-statement (or dangling-else) blocks.
4. Per-layer image controls implement the `FilterDetails` 3-component interface and are added in ImageLayerOptions via `addToGridBag(c, panel); c.gridy++;`. New global filters (RHEF) only need an `ImageFilter.Type` enum entry — the radio button auto-generates.
5. Every UI listener is a lambda that mutates the model then calls `DisplayController.display()` (or `render(1)` to force re-decode). Never an anonymous class.
6. All hot image loops use `ParallelRange.run(height, (from,to) -> { for (int y=from; y<to; y++){...} })`. Background work + EDT handoff goes through `EDTCallbackExecutor.pool.submit(...)` / `EventQueue.invokeLater`.
7. New map-projection shader: add a `GLSLSolarShader` static instance, register it in BOTH `init()` and `dispose()`, reuse `solar.vert` + the prepended `solarCommon.frag`, wire a MapMode enum entry.
8. Add exactly one imperative changelog bullet under the `(pending)` section with backticked literals; commit subject is short imperative `(fixes #NNN)`.
9. Imports fully explicit, grouped java/javax -> org.helioviewer -> third-party(jidesoft/json), blank lines between groups.
10. Early-return the input unchanged on degenerate sizes (`if (width<128||height<128) return data;`). Log via `Log.error/warn`. Wrap-and-rethrow typed exceptions after cleanup.

DON'T:
1. No wildcard imports. No `System.out`/`printStackTrace`. No Javadoc in app code.
2. No new third-party dependencies — he is actively removing Guava (commit d8162e380). JDK + existing in-house helpers only.
3. No raw threads, no `parallelStream`, no `SwingUtilities.invokeLater` (use `java.awt.EventQueue`).
4. No `final` noise on locals/params; no `this.` qualification.
5. No broad repaint when a narrow event/cell-update suffices (commit 8eca622a0 added a targeted `fireNameUpdated` + `fireTableCellUpdated(row, NAME_COL)` instead of a full table refresh).
6. No new commented-out / dead code in the PR diff (he keeps his own, but a contributor's diff must be clean for review).
7. Don't forget the redraw call after a model mutation, the newline-at-EOF, and adding the new shader to BOTH init() and dispose().
8. Keep the diff SMALL and single-purpose — one logical change per commit; smaller diff = higher merge odds.

## Risks
- GLSL UBO layout coupling: adding a new shader uniform (e.g. RHEF/disk tunables not already present) requires editing the packed FloatBuffer in GLSLSolarShader.bindDisplay/bindWCS (capacity in BufferUtils.newFloatBuffer, the put() order, the *_SIZE constant) AND the matching layout(std140) block in the GLSL on both sides; mismatched offsets fail silently or corrupt rendering. Prefer reusing existing display.radii / display.cutOff / screen.yStart fields the disk shaders already expose.
- The non-ortho projection basis is shared between the GLSL fragment and a Java-side unprojection (per the solarLogPolar.frag comment 'must stay consistent with the Java-side non-ortho projection'). The #314 'unify non-ortho coordinate handling' / #316 'surface map WCS projections' refactor changed where that Java basis lives; a new disk projection must hook the current MapMode/MapView mechanism, not the pre-refactor path the POC used.
- ImageFilter.Type currently dispatches None via `type == Type.None ? data : filter(...)` and the algorithm instances are constructed eagerly at enum-init time (`new FilterMGN()`). A stateful RHEF (needs per-frame radius map) does not fit the stateless `float[] filter(float[] data,int w,int h)` signature cleanly — may need an Algorithm variant or to pass metadata; confirm the Algorithm interface contract with Bogdan before widening it.
- Coverity is run (coverity.sh); new code with reachable NPE/resource-leak patterns will trip it and block merge.
- He commits in very small single-purpose increments and merges only his own work; a large multi-feature PR (RHEF + PUNCH source + disk projections together) is likely to be rejected — split into the three separate PRs already planned (#43/#44/#45).

## Open questions
- ImageFilter.Algorithm contract: is the stateless `float[] filter(float[] data,int width,int height)` signature intended to stay metadata-free, or is it acceptable to add an overload / pass FitsMetaData (needed for RHEF's solar-radius-aware radial binning)? How would you prefer a filter that needs the disk geometry to receive it?
- For a new disk/log-polar map projection, should it be a new MapMode enum entry + new GLSLSolarShader instance (mirroring LogPolar/Polar), or do the #316 surface-map WCS projections now subsume custom radial remaps so it should be expressed as a WcsHeader.Projection instead?
- Per-layer projection override (the POC's 'Flat-in-disk' toggle): after #314 unified non-ortho coordinate handling, is per-layer MapMode override still supported, or is map mode now strictly a global display state? Where should a per-layer override hook?
- Preferred home for a new SDAC/PUNCH remote image source on current upstream — is there a registry/enum of data servers to extend (the changelog mentions IAS default server), and should it route through the existing DownloadLayer/loader async plumbing?
- Is adding a new uniform to the std140 DisplayBlock acceptable, or do you prefer overloading existing slots (radii/cutOff) to avoid touching the UBO layout?
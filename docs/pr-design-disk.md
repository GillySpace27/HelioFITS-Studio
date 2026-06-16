# PR design: disk

## Target architecture
HOW NON-ORTHO PROJECTIONS WORK ON CURRENT UPSTREAM (verified on ref upstream/master, head 525e4c59a):

The post-refactor pipeline has a clean separation the POC predates. Adding a projection touches exactly five tables, all of which are switch/enum-driven so the compiler enforces completeness.

(1) MapMode enum — src/org/helioviewer/jhv/display/MapMode.java. Each constant binds a GLSLSolarShader instance and an inner `Kind`:
    `Orthographic(GLSLSolarShader.ortho, Kind.ORTHOGRAPHIC), HPC(...,Kind.HPC), Latitudinal(...,Kind.LATITUDINAL), LogPolar(GLSLSolarShader.logpolar, Kind.POLAR), Polar(GLSLSolarShader.polar, Kind.POLAR);`
    `enum Kind { ORTHOGRAPHIC, HPC, LATITUDINAL, POLAR }`. New MapMode values auto-appear in the toolbar (see #6) and auto-persist (see #7) — no UI/state edits to add a mode.

(2) MapMode -> MapView -> ProjectedMap dispatch. MapView.create (display/MapView.java:31) branches ORTHOGRAPHIC -> OrthographicView else ProjectedView. ProjectedView.projectedKind (MapView.java:161) maps `MapMode.Kind -> ProjectedMap.Kind` via an EXHAUSTIVE switch (HPC/LATITUDINAL/POLAR; ORTHOGRAPHIC throws). ProjectedMap (display/ProjectedMap.java) is the Java half of the dual math: `enum Kind {HPC, LATITUDINAL, POLAR}` and exhaustive `project()`/`unproject()` switches. project for POLAR = `projectPolarVector`: `r=hypot(v.x,v.y); theta=PolarBasis.angle(v); return new Vec2(scale.getXValueInv(deg(theta)), scale.getYValueInv(r))`. projectToScreen multiplies pt.x by vp.aspect (ProjectedMap.java:65). mouseToGrid/mouseToScreen go through `mouseToRawGrid` using `ViewportMath.computeUpX/Y` and `scale.getInterpolated{X,Y}Value`.

(3) MapScale — display/MapScale.java. Interface with getInterpolated{X,Y}Value, getXValueInv/getYValueInv, getYstart/getYstop, getDisplayXValue. MapScaleBase holds xStart/xStop/yStart/yStop, computes yStart=scaleY(_yStart) in the ctor (so subclass scaleY() is virtually-called before subclass fields init — load-bearing constraint, see risks). Subclasses LinearMapScale, LogMapScale (scaleY=log, invScaleY=exp), LatitudinalMapScale. Factory methods: `polar(radialSize)=new LinearMapScale(0,360,0,radialSize)`, `logpolar(radialSize)=new LogMapScale(0,360,0.05,max(0.05,radialSize))`. There is NO getYParam() on upstream — that is a POC addition.

(4) Shaders — resources/glsl/. solarCommon.frag holds all shared structs+helpers as std140 UBOs: WCSBlock, ProjectionBlock, ScreenBlock, DisplayBlock. The `Screen` struct (solarCommon.frag) is: `mat4 inverseMVP; vec4 viewport; float iaspect; float xStart; float xStop; float yStart; float yStop;`. `getScrPos()` does inverse-MVP unproject then `vec2 scrpos = vec2(iaspect*up1.x, up1.y)+.5; clamp_coord(scrpos)` — the +.5 unit-square map plus clamp_coord's x-slit (display.slit) and y in [0,1]. solarPolar.frag / solarLogPolar.frag are thin: `scrpos=getScrPos(); radialCoordinate = (Polar) yStart+scrpos.y*(yStop-yStart) | (LogPolar) exp(yStart+scrpos.y*(yStop-yStart))`, then `samplePolarTexcoord(...)` builds `angle=scrpos.x*TWOPI; polarXY=vec2(-sin(angle),-cos(angle))*radialCoordinate;` does the cutOff block, then `centered=rotate_plane_inverse(crota, vec2(polarXY.x,-polarXY.y)-crval); texCoord=rect.zw*vec2(centered.x-rect.x,-centered.y-rect.y); clamp_texture(texCoord)`. solar.vert is a trivial passthrough shared by ALL solar shaders (no per-shader vertex code).

(5) Shader registration + UBO upload — opengl/GLSLSolarShader.java. Static instances `sphere/ortho/hpc/lati/polar/logpolar`. init()/dispose() list each instance explicitly. The ScreenBlock UBO is filled by `bindScreen(Viewport vp, MapScale scale)`: writes the inverse-MVP mat4, `vp.glslArray` (the viewport vec4), `(1/vp.aspect)` (iaspect), `scale.getInterpolatedXValue(0)` (xStart), `scale.getInterpolatedXValue(1)` (xStop), `scale.getYstart()`, `scale.getYstop()`. The buffer is allocated `BufferUtils.newFloatBuffer(16+4+4+4)` = 28 floats; only 16+4+5=25 are currently written, so there are 3 unused trailing floats in the std140 block already (room for 2 new floats with zero size change). bindScreen is the per-render hook called from GLRenderer.renderScene / renderSceneScale / renderMiniview.

(6) GLRenderer — opengl/GLRenderer.java:33 `createScales(MapMode mode, Viewport[])` EXHAUSTIVE switch builds the per-viewport MapScale array; Polar/LogPolar cases call `MapScale.{polar,logpolar}(ImageLayers.getLargestRadialSize())` (ImageLayers.java:52 still exists). The per-layer image shader is selected in ImageLayer.renderScale (layers/ImageLayer.java:212): `GLSLSolarShader shader = mv.mode().shader; shader.use();`.

(7) Toolbar / state / status — all auto-driven now. ToolBar (gui/component/ToolBar.java:44) `implements ViewState.ModeListener`, builds the projection menu with `for (MapMode el : MapMode.values())` (line 234) into an `EnumMap<MapMode,JRadioButtonMenuItem> projectionItems`, and rebuilds/reselects in `modeStateChanged()` (line 411) — NEW MapMode VALUES NEED NO TOOLBAR EDIT. ViewState (app/state/ViewState.java) owns projection state, persists via writeModeJson/readModeJson using `MapMode.valueOf(name)` — NEW MapMode VALUES AUTO-PERSIST. ViewState.setProjection -> Display.setMapMode. ViewState.ModeListener is a functional interface with `modeStateChanged()`; listeners register via `ViewState.addModeListener(ModeListener)`. PositionStatusPanel is now at gui/status/PositionStatusPanel.java:43-48 with `if(isHpc) ... else if(isLatitudinal) ... else if(isPolar()||isLogPolar()) setText(formatPolar(coord))`.

(8) Grid — layers/GridLayer.java renderScale calls `flatGrid.render(mv,vp,showLabels)` for all non-ortho modes; FlatGrid (layers/grid/FlatGrid.java) is the template. Helpers confirmed present and modern: GridMath.LINEWIDTH (=GLSLLine.LINEWIDTH_BASIC), GLSLLine, GLText.renderer()->SdfTextRenderer, FastFormat.rounded2, Colors.WhiteFloat/Colors.Null, BufVertex, GLSLLine.stride. FlatGrid.drawLabels sizing idiom: `worldTextHeight = TEXT_SIZE*Display.pixelScale[1]*Math.min(width,1)/vp.height; textScaleFactor=worldTextHeight/renderer.getFontSize(); labelOffset=0.1*worldTextHeight`.

(9) PolarBasis — math/PolarBasis.java: `x(r,a)=-r*sin(a); y(r,a)=r*cos(a); angle(Vec3 v)=atan2(-v.x,v.y) wrapped [0,2pi)`. Only an `angle(double,double)` overload is missing for the disk path.

PACKAGE MOVES vs POC (the POC built on pre-refactor paths; CURRENT correct paths): Region is `org.helioviewer.jhv.metadata.Region` (POC used base.Region) — but GLSLSolarShader on upstream already imports metadata.Region, so the disk shaders need no change. ImageFilter/filter package is `org.helioviewer.jhv.image` (was imagedata) — relevant only at the import line in ImageFilterPanel, which already exists upstream. Base widgets JHVSlider + TerminatedFormatterFactory are in `gui/component` (POC used gui/components/base). RangeSlider is wrapped as `gui/component/JHVRangeSlider`. ProjectionOptionsPanel must live in `gui/component` not `gui/components`. PositionStatusPanel is `gui/status`. annotation package is `annotation` (singular) and export is `movie` now — not load-bearing for this PR.

CRITICAL ARCH NOTE — what the refactor made obsolete in the POC approach: the POC's disk shaders define a private `getDiskPos()` that re-reads `screen.viewport`/`screen.inverseMVP` to get the raw world-plane offset `up1.xy` (deliberately bypassing getScrPos's unit-square clamp + x-slit). That is STILL the right move on current upstream — getScrPos and the Screen UBO are byte-for-byte the same shape. So the POC shaders port essentially verbatim; the only real integration work is (a) reconciling package/path renames, (b) using ViewState.ModeListener (functional interface) instead of the POC's Runnable-style addModeListener, (c) using JHVRangeSlider instead of raw jidesoft RangeSlider, (d) re-pointing the panel/status edits at gui/component and gui/status.

## POC logic to preserve
PROVEN ALGORITHM TO PRESERVE (from branch disk-projections; the math is verified-working and the GLSL/Java dual MUST stay consistent per docs/non-ortho-projection-note.md).

=== GLSL — getDiskPos + disk sampling (shared by solarDiskLog.frag and solarDiskPower.frag) ===
```
// Raw world-plane offset from disk center; cf getScrPos but WITHOUT the unit-square clamp + aspect.
vec2 getDiskPos(void) {
    vec2 normalizedScreenpos = 2. * (gl_FragCoord.xy - screen.viewport.xy) / screen.viewport.zw - 1.;
    vec4 up1 = screen.inverseMVP * vec4(normalizedScreenpos.x, normalizedScreenpos.y, -1., 1.);
    return up1.xy;
}
// in main():
vec2 w = getDiskPos();
float t = 2. * length(w);          // normalized display radius in [0,1]; disk rim at |w|=0.5
if (t > 1. || t == 0.) discard;
float angle = atan(-w.x, w.y);     // PolarBasis convention: 0 at north, anticlockwise
if (angle < 0.) angle += TWOPI;
clamp_coord(vec2(angle / TWOPI, t));   // reuse angular slit (display.slit) + [0,1] clamp
// LogDisk:    float radialCoordinate = exp(screen.yStart + t * (screen.yStop - screen.yStart));
// PowerDisk:  float radialCoordinate = pow(screen.yStart + t * (screen.yStop - screen.yStart), screen.yParam);  // yParam = 1/p
```
sampleDiskTexcoord (same body for both, only radialCoordinate differs):
```
if (radialCoordinate > display.radii.y || radialCoordinate < display.radii.x) discard;
vec2 polarXY = (2. * radialCoordinate / t) * vec2(w.x, -w.y);   // image-plane point at physical radius along w
// ...identical cutOff block as solarPolar.frag (displayXY = polarXY.yx; cutOffAlt; geometryFlatDist...) ...
vec2 centered = rotate_plane_inverse(crota, vec2(polarXY.x, -polarXY.y) - crval);
vec2 texCoord = rect.zw * vec2(centered.x - rect.x, -centered.y - rect.y);
clamp_texture(texCoord);
```
Note the `2.*radialCoordinate/t` factor: it rescales the screen direction w (whose length encodes t) to land at physical radius `radialCoordinate` in image-plane R_sun units — this is what differs from solarPolar's `vec2(-sin,-cos)*radialCoordinate` and is the heart of the "disk" (vs rectilinear-unwrap) view. enhancementFactor = max(1., radialCoordinate); diff path mirrors solarPolar.

=== GLSL — solarDiskFlat.frag (the flat/limb-pegged override) ===
```
if (screen.diskFlatRadius <= 0.) discard;     // limb outside radial range -> nothing to paint
vec2 w = getDiskPos();
float t = 2. * length(w);
if (t > 1.) discard;
float angle = atan(-w.x, w.y); if (angle < 0.) angle += TWOPI;
clamp_coord(vec2(angle / TWOPI, t));
// sampleFlatTexcoord:
vec2 polarXY = 2. * screen.diskFlatRadius * vec2(w.x, -w.y);   // LINEAR sky-plane scaling; rim = diskFlatRadius R_sun
float radialCoordinate = length(polarXY);
if (radialCoordinate > display.radii.y || radialCoordinate < display.radii.x) discard;
// ...same cutOff block...; same centered/texCoord/clamp_texture as above...
```
enhancementFactor = 1. (no radial enhance on a flat layer).

=== Java — ProjectedMap disk math (must mirror GLSL) ===
```
private static Vec2 projectDisk(Quat rotation, MapScale scale, Vec3 v0) {
    Vec3 v = rotation.rotateVector(v0);
    double r = Math.hypot(v.x, v.y);
    if (r == 0) return new Vec2(0, 0);
    double t = Math.max(0, scale.getYValueInv(r) + .5);   // inverse of the shader's yStart+t*(yStop-yStart)
    double f = .5 * t / r;
    return new Vec2(f * v.x, f * v.y);                     // isotropic, NOT *vp.aspect
}
// unproject(DISK) reuses unprojectPolar.
private static Vec2 mouseToDiskGrid(Camera camera, double width, Viewport vp, MapScale scale, int x, int y) {
    double upX = ViewportMath.computeUpX(vp, width, camera.getTranslationX(), x);
    double upY = ViewportMath.computeUpY(vp, width, camera.getTranslationY(), y);
    double t = 2 * Math.hypot(upX, upY);
    return new Vec2(Math.toDegrees(PolarBasis.angle(upX, upY)), scale.getInterpolatedYValue(t));  // (PA deg, r R_sun)
}
// mouseToScreen(DISK): return new Vec2(computeUpX(...), computeUpY(...));  // raw, no aspect/scale inverse
// emitMapLine(DISK)/emitMapPoints(DISK): emit pt.x,pt.y RAW (no *vp.aspect, no horizontal wrap — disk has no angular seam)
```

=== Java — MapScale PowerMapScale + getYParam + factories ===
```
default double getYParam() { return 1; }     // added to interface
static MapScale diskLog(double radialSize)   { ... return new LogMapScale(0, 360, rMin>0?rMin:0.9, Math.max(rMax>0?rMax:radialSize, 2)); }
static MapScale diskPower(double radialSize) { ... return new PowerMapScale(0, 360, rMin, Math.max(rMax>0?rMax:radialSize, 1)); }
final class PowerMapScale extends MapScaleBase {
    private static double power() { return Display.getDiskPower(); }  // read live; ctor calls scaleY() before fields init
    @Override public double getYParam() { return 1 / power(); }
    @Override protected double scaleY(double val)    { return Math.pow(val, power()); }
    @Override protected double invScaleY(double val) { return Math.pow(val, 1 / power()); }
    // scaleX/invScaleX identity
}
```

=== Java — GLSLSolarShader.bindScreen additions (compute diskFlatRadius from the limb) ===
```
double t1 = scale.getYValueInv(1) + .5;   // where r=1 (limb) lands in normalized t
screenBuf.put((float) scale.getYstart()).put((float) scale.getYstop())
         .put((float) scale.getYParam())
         .put(t1 > 1e-4 ? (float) (1 / t1) : 0);   // diskFlatRadius: rim R_sun so limb aligns with the warped layers; 0 => discard
```

=== Java — per-layer flat shader selection (ImageLayer.renderScale) ===
`GLSLSolarShader shader = mv.isDisk() && glImage.isFlatInDisk() ? GLSLSolarShader.diskFlat : mv.mode().shader;`

=== Java — Display statics (exponent dead-zone + ranges) ===
```
setDiskPower: clamp to [-2,2]; if |p|<0.05 snap to ±0.05 (p=0 degenerate — every radius maps to 1).
diskPower default 0.5; logDiskRMin default 0.9 (must be >0 for log); rMax=0 means "follow loaded layers".
```

=== Java — DiskGrid ring selection (1-2-5 decade rings, decimated) ===
`ringRho(scale,r) = .5*(scale.getYValueInv(r)+.5)`. chooseRings walks 1,2,5 x 10^k within [getInterpolatedYValue(0), getInterpolatedYValue(1)], skipping rings closer than MIN_RING_SPACING=0.04 of disk radius, cap MAX_RINGS=16. 180-segment circles + 30-degree spokes via GLSLLine, Colors.Null bracketing per polyline, labels along +y with FastFormat.rounded2 and the FlatGrid sizing idiom.

## File plan

### [edit] resources/glsl/solarCommon.frag
UNAVOIDABLE. In `struct Screen { ... float yStart; float yStop; }` append two trailing floats: `float yParam; float diskFlatRadius;`. Zero UBO size change — GLSLSolarShader already allocates 16+4+4+4 floats and writes only 25, leaving room. Touches the std140 block shared by ALL six solar shaders, so every existing mode must be re-verified to still render (driver is final arbiter). No getScrPos change.

### [new] resources/glsl/solarDiskLog.frag
Template = solarPolar.frag. Add private `vec2 getDiskPos()` (raw up1.xy, no clamp/aspect). main(): w=getDiskPos(); t=2*length(w); discard t>1||t==0; angle=atan(-w.x,w.y) wrapped [0,2pi); clamp_coord(vec2(angle/TWOPI,t)); radialCoordinate=exp(yStart+t*(yStop-yStart)); then sampleDiskTexcoord using polarXY=(2*radialCoordinate/t)*vec2(w.x,-w.y), the verbatim cutOff block, rotate_plane_inverse/rect/clamp_texture. diff path mirrors solarPolar.

### [new] resources/glsl/solarDiskPower.frag
Identical to solarDiskLog.frag except `radialCoordinate = pow(yStart + t*(yStop-yStart), screen.yParam)` (yParam=1/p).

### [new] resources/glsl/solarDiskFlat.frag
Flat/limb-pegged override shader. getDiskPos as above. main(): discard if screen.diskFlatRadius<=0; w,t,angle,clamp_coord as above (t>1 discard). sampleFlatTexcoord: polarXY=2*screen.diskFlatRadius*vec2(w.x,-w.y); radialCoordinate=length(polarXY); display.radii clamp; cutOff block; rotate_plane_inverse/rect/clamp_texture. enhancementFactor=1.

### [edit] src/org/helioviewer/jhv/math/PolarBasis.java
UNAVOIDABLE (used by both projectDisk path and DiskGrid/mouse). Add `public static double angle(double x, double y){ double t=Math.atan2(-x,y); if(t<0) t+=2*Math.PI; return t; }` and refactor existing `angle(Vec3 v)` to `return angle(v.x, v.y);`.

### [edit] src/org/helioviewer/jhv/display/MapMode.java
UNAVOIDABLE. Add enum constants `LogDisk(GLSLSolarShader.diskLog, Kind.DISK), PowerDisk(GLSLSolarShader.diskPower, Kind.DISK)` after Polar. Add `DISK` to inner `enum Kind`.

### [edit] src/org/helioviewer/jhv/display/MapView.java
UNAVOIDABLE. Add `public boolean isDisk(){ return mode.kind == MapMode.Kind.DISK; }`. Add `case DISK -> ProjectedMap.Kind.DISK;` to projectedKind switch (MapView.java:161). Change the two ProjectedView calls to pass `kind`: `ProjectedMap.mouseToGrid(kind, camera, ...)` and `ProjectedMap.mouseToScreen(kind, camera, ...)` (since those methods gain a Kind param).

### [edit] src/org/helioviewer/jhv/display/ProjectedMap.java
UNAVOIDABLE. Add `DISK` to `enum Kind`. Add `case DISK -> projectDisk(rotation, scale, v)` to project(); `case POLAR, DISK -> unprojectPolar(...)` to unproject(). Add private projectDisk (quoted in poc_logic). projectToScreen: return raw pt for DISK (skip *vp.aspect). emitMapLine/emitMapPoints: add DISK branches that emit raw pt.x/pt.y with no aspect and no horizontal wrap (new emitDiskLine helper). Add `Kind kind` first param to mouseToGrid and mouseToScreen; DISK branch -> mouseToDiskGrid / raw computeUpX/Y (quoted). mouseToSurface already passes kind via unproject. Keep the file private/final and the docs/non-ortho-projection-note.md comment.

### [edit] src/org/helioviewer/jhv/display/MapScale.java
UNAVOIDABLE. Add interface default `double getYParam(){ return 1; }`. Add static factories `diskLog(double radialSize)` -> LogMapScale(0,360, Display.getLogDiskRMin() (or 0.9), max(rMax|radialSize,2)) and `diskPower(double radialSize)` -> new PowerMapScale(0,360, Display.getPowerDiskRMin(), max(rMax|radialSize,1)). Add `final class PowerMapScale extends MapScaleBase` overriding scaleY=pow(val,power()), invScaleY=pow(val,1/power()), getYParam=1/power(), with `private static double power(){return Display.getDiskPower();}` (NOT an instance field — superclass ctor virtually calls scaleY before subclass init). MINIMIZE: if Bogdan wants p fixed, PowerMapScale can hardcode `static final double POWER=0.5` and drop the Display dependency — flag as open question.

### [edit] src/org/helioviewer/jhv/display/Display.java
Add static state + accessors only if the PowerDisk exponent / radial-range / dead-zone are tunable: diskPower (default 0.5, clamp[-2,2], dead-zone snap |p|<0.05->±0.05), logDiskRMin(0.9)/logDiskRMax(0=auto), powerDiskRMin/powerDiskRMax, with set/get. MINIMIZE: if PowerMapScale hardcodes p and the panel is dropped, this whole file edit disappears. Keep as its own commit.

### [edit] src/org/helioviewer/jhv/opengl/GLSLSolarShader.java
UNAVOIDABLE. Add static instances `diskLog`, `diskPower`, `diskFlat` (all `("/glsl/solar.vert", "/glsl/solarDiskX.frag", true)`). Add their `_init`/`_dispose` calls in init()/dispose(). In bindScreen, after putting yStart/yStop, append `.put((float)scale.getYParam())` and `.put(t1>1e-4?(float)(1/t1):0)` where `double t1 = scale.getYValueInv(1)+.5` (the diskFlatRadius limb peg). Buffer alloc unchanged (16+4+4+4).

### [edit] src/org/helioviewer/jhv/opengl/GLRenderer.java
UNAVOIDABLE (exhaustive switch). In createScales add `case LogDisk -> createConstantScales(viewports, MapScale.diskLog(ImageLayers.getLargestRadialSize())); case PowerDisk -> createConstantScales(viewports, MapScale.diskPower(ImageLayers.getLargestRadialSize()));`.

### [edit] src/org/helioviewer/jhv/opengl/GLImage.java
Add `private boolean flatInDisk = false;` with `isFlatInDisk()/setFlatInDisk(boolean)`. Add `setFlatInDisk(jo.optBoolean("flatInDisk", flatInDisk))` in fromJson and `jo.put("flatInDisk", flatInDisk)` in toJson (per-layer state persistence).

### [edit] src/org/helioviewer/jhv/layers/ImageLayer.java
UNAVOIDABLE for the flat override. In renderScale (line ~212) change `GLSLSolarShader shader = mv.mode().shader;` to `GLSLSolarShader shader = mv.isDisk() && glImage.isFlatInDisk() ? GLSLSolarShader.diskFlat : mv.mode().shader;`.

### [edit] src/org/helioviewer/jhv/layers/Layers.java
Add `private static GridLayer gridLayer;`, `public static GridLayer getGridLayer(){return gridLayer;}`, register in add() (`else if (layer instanceof GridLayer gl) gridLayer = gl;`) and null it in the dispose/reset block. ONLY needed if the grid-color picker (panel) is included; if the panel is dropped, this and the DiskGrid color accessor are unnecessary. Keep with the panel commit.

### [new] src/org/helioviewer/jhv/layers/grid/DiskGrid.java
Port verbatim from POC (already uses the modern grid/text API): rings via chooseRings (1-2-5 decade, MIN_RING_SPACING=0.04, MAX_RINGS=16) at ringRho=.5*(getYValueInv(r)+.5), 180-segment GLSLLine circles + 30-degree spokes with Colors.Null bracketing, labels along +y via SdfTextRenderer/GLText.renderer()/FastFormat.rounded2 using the FlatGrid sizing idiom. setColor/getColor only needed if the color picker ships.

### [edit] src/org/helioviewer/jhv/layers/GridLayer.java
Add `private final DiskGrid diskGrid = new DiskGrid();` (+ getDiskGrid() if color picker ships). In renderScale: `if (mv.isDisk()) diskGrid.render(mv,vp,showLabels); else flatGrid.render(...)`. Add diskGrid.init() in init() and diskGrid.dispose() in dispose().

### [edit] src/org/helioviewer/jhv/gui/status/PositionStatusPanel.java
UNAVOIDABLE for correct status readout. At line ~47 change `else if (mv.isPolar() || mv.isLogPolar())` to `else if (mv.isPolar() || mv.isLogPolar() || mv.isDisk())` so disk modes reuse formatPolar (PA deg, R_sun) — matches mouseToDiskGrid output. NOTE: file is at gui/status, NOT the POC's gui/components/statusplugin.

### [edit] src/org/helioviewer/jhv/layers/filters/ImageFilterPanel.java
Add the per-layer flat toggle: `JideToggleButton flatButton = new JideToggleButton("◯", layer.getGLImage().isFlatInDisk());` tooltip 'Render this layer flat (no radial warp) in disk projections'; actionListener sets glImage.setFlatInDisk(flatButton.isSelected()) then DisplayController.display(); add to `buttonPanel.add(flatButton, BorderLayout.LINE_START)` (enhanceButton stays LINE_END). Import com.jidesoft.swing.JideToggleButton.

### [new] src/org/helioviewer/jhv/gui/component/ProjectionOptionsPanel.java
OPTIONAL (own commit). Port POC's panel to gui/component. Singleton getInstance(); GridBagLayout. Rows: PowerDisk exponent JHVSlider [-200..200]/0.01 + numberField; radial-range JHVRangeSlider (NOT raw RangeSlider) log-spaced [0.05,1000] R_sun + two numberFields; grid-color JButton via JColorChooser (needs Layers.getGridLayer().getDiskGrid()). Implement `ViewState.ModeListener` (method modeStateChanged()) and show/hide rows by Display.mode (power row only for PowerDisk). Use gui/component JHVSlider + TerminatedFormatterFactory. Each control calls the Display setter then DisplayController.display().

### [edit] src/org/helioviewer/jhv/gui/JHVFrame.java
OPTIONAL (panel commit). Add the panel to the left pane: `ProjectionOptionsPanel projectionOptions = ProjectionOptionsPanel.getInstance(); leftPane.add("Disk projection", projectionOptions, true); ViewState.addModeListener(projectionOptions);` — note projectionOptions must implement ViewState.ModeListener (functional, modeStateChanged()), NOT the POC's method-ref `::refreshForMode` against a Runnable overload. Import gui.component.ProjectionOptionsPanel and app.state.ViewState.

## Style notes
Bogdan idioms this PR must follow (CONTRIBUTING + observed upstream patterns): K&R braces, 4-space indent, no tabs, UTF-8, newline at EOF, warning-free under -Xlint:all. Constructor params prefixed `_` (e.g. `_scales`), then assigned to unprefixed fields. Classes default-private/final where possible (ProjectedMap is `final` with a private ctor; keep it). Static utility classes have a trailing private no-arg ctor. Enum constants carry behavior via constructor args (MapMode(shader, kind)); switches over enums are EXHAUSTIVE arrow-switches with no default (the ORTHOGRAPHIC case throws IllegalArgumentException — match that pattern for any new throw). UBO uploads are hand-packed FloatBuffers in a fixed std140 order; never reorder existing fields, only append. GLSL: `#version 300 es`, `precision highp float`, helper-per-concern, `discard` for out-of-range, comments that explicitly cite the Java/GLSL dual and docs/non-ortho-projection-note.md when touching projection sign conventions. Swing widgets come from gui/component (JHVSlider, JHVRangeSlider, TerminatedFormatterFactory, Buttons) — do NOT reintroduce raw jidesoft RangeSlider. State persistence rides JSONObject opt*/put with the same key style as siblings ("flatInDisk" alongside "enhanced"). Mode/projection plumbing goes through ViewState (setProjection/ModeListener), never by poking Display.mode from the UI. Commit messages are terse one-liners; split into logical commits: (1) Screen UBO field + shaders + GLSLSolarShader instances; (2) MapMode/MapScale/ProjectedMap/MapView Java projection + PolarBasis + GLRenderer scales; (3) DiskGrid + GridLayer + PositionStatusPanel; (4) flat-in-disk override (GLImage + ImageLayer + ImageFilterPanel button); (5) OPTIONAL ProjectionOptionsPanel + Display statics + JHVFrame + Layers.getGridLayer. Smaller diff = more likely accepted: keep commit 5 cleanly separable and offer the hardcoded-p variant.

## Risks
- solarCommon.frag Screen-struct edit is shared by ALL six solar shaders (sphere/ortho/hpc/lati/polar/logpolar). Appending two floats stays within the existing 16+4+4+4 alloc and std140 padding, but a GLSL/ANGLE driver is the final arbiter — every pre-existing mode must be re-verified to render unchanged after the edit. This is the single highest-risk line in the PR.
- PowerMapScale: MapScaleBase's constructor virtually calls scaleY() before any subclass instance field is initialized (yStart=scaleY(_yStart) in the base ctor). So the exponent CANNOT be a normal instance field — it must come from a static (Display.getDiskPower()) or a static-final constant. The POC handles this with a static power() accessor; preserve that or hardcode. A naive `private final double power` field would read 0.0 during super() and break the scale.
- p=0 is degenerate (pow(x,0)=1 maps every radius to the rim) and negative p inverts the mapping; the POC clamps to [-2,2] with a dead-zone snap to ±0.05. If the exponent is exposed via the panel, that guard must live in Display.setDiskPower; if hardcoded to 0.5, the guard is moot.
- diskFlatRadius limb-peg: bindScreen computes t1 = scale.getYValueInv(1)+.5 every frame; when the limb (r=1) falls outside the radial range t1<=0 and diskFlatRadius is set 0, so solarDiskFlat.frag discards entirely (a flat layer simply vanishes rather than mis-scaling). Verify this is the intended UX — a flat AIA disk should disappear if rMin>1.
- Aspect handling: disk projection emits isotropic coordinates (no *vp.aspect). In tall-narrow windows (aspect<1) the disk is sized to map HEIGHT and overflows horizontally by design (matches Polar's map-square sizing). Confirm Bogdan is OK with that vs clamping to the smaller dimension.
- CACTus/SWEK CME placement and FOV/annotation persistence: the POC notes SWEKLayer/SWEKPopupController special-case only polar modes; disk modes fall through the generic path. Off-disk HPC unprojection is already documented as incomplete upstream — disk unproject reuses unprojectPolar (sphere intersection), so off-limb annotation drag may behave like Polar, not like a true plane. Low risk, but note it.
- Package/path drift between POC and current upstream is pervasive (gui/component vs gui/components/base, gui/status vs gui/components/statusplugin, metadata.Region vs base.Region, image vs imagedata, JHVRangeSlider wrapper). Mechanical but easy to get wrong; every import in the ported files must be re-pointed.
- ViewState.ModeListener is a functional interface (modeStateChanged()), NOT a Runnable. The POC's `ViewState.addModeListener(panel::refreshForMode)` will not compile against current upstream — the panel must implement ViewState.ModeListener.

## Open questions
- PowerDisk exponent p: ship it tunable (Display.getDiskPower + slider, range [-2,2] with a dead-zone near 0) or fix it at p=0.5 to minimize the diff (drops Display.java statics, the panel, and the slider)? The original v1 design fixed p=0.5; the POC later extended to [-2,2]. Which do you want upstream?
- Do you want the optional ProjectionOptionsPanel (left-pane: exponent / radial-range / grid-color controls) in this PR at all, or should disk modes ship with sensible defaults only and the panel land later? It is the largest, most-bikesheddable chunk (~300 LOC + JHVFrame + Layers wiring).
- diskLog inner radius default: the POC uses 0.9 R_sun (log requires >0). Is 0.9 the right floor for LogDisk, or should it derive from the innermost loaded layer / the occulter radius?
- Naming: are 'LogDisk' / 'PowerDisk' the names you want in the projection menu, or do you prefer something like 'Disk (log)' / 'Disk (power)'? The enum constant name is what persists to state JSON and shows in the toolbar.
- The flat-in-disk per-layer toggle currently lives as a small JideToggleButton ('◯') in the layer's filter buttonPanel. Is that the right home, or would you rather it sit in the layer options popup / next to the LUT controls?
- Should disk modes participate in the SWEK CME-track placement special-casing (like Polar/LogPolar), or is the generic annotation path acceptable for v1? (POC deferred this.)
- Is appending to the shared Screen UBO acceptable, or would you prefer the two disk-only floats (yParam, diskFlatRadius) packed into a separate small UBO to avoid touching the struct every solar shader includes?
# PR design: rhef

## Target architecture
FILTER PIPELINE (current upstream/master):
- src/org/helioviewer/jhv/image/ImageFilter.java — enum Type{None,MGN,WOW} each holding an Algorithm; interface is single-method: `interface Algorithm { float[] filter(float[] data, int width, int height); }`. Static entry points: `static byte[] filter(byte[] data, int w, int h, Type type)` and `static short[] filterHalfFloat(short[] data, int w, int h, Type type)` (the short path is Gray16F half-float; the design-note name "fromShorts" is on ImageBuffer, the filter method is filterHalfFloat). Conversion to/from float uses ParallelRange and Float.float16ToFloat / Float.floatToFloat16.
- src/org/helioviewer/jhv/image/FilterMGN.java, FilterWOW.java — `class FilterMGN implements ImageFilter.Algorithm`, package-private, import only `org.helioviewer.jhv.thread.ParallelRange`.
- src/org/helioviewer/jhv/image/ImageBuffer.java — `fromBytes(int,int,Format,byte[],ImageFilter.Type)`, `fromShorts(int,int,Format,short[],ImageFilter.Type)`, `createWriteBuffer(int,int,Format,ImageFilter.Type)`; inner `WriteBuffer` stores filterType and applies it in `finish()`. Format{Gray8,Gray16F,RGBA32}.
- src/org/helioviewer/jhv/image/ImageBufferCache.java + view/j2k/J2KDecodeKey.java + view/uri/URIDecodeKey.java — cache key INCLUDES ImageFilter.Type (J2KDecodeKey hashes serial+params+filter), so RHEF output caches correctly per filter; decodeParams (subImage/level/factor) are deterministic per pan/zoom => the radial Region is deterministic per key. NO cache-key change needed.
- src/org/helioviewer/jhv/thread/ParallelRange.java — `ParallelRange.run(int length, Task task)` where `Task.run(int from, int to)`. Unchanged from POC.

CONFIRMED FINDING: filters STILL receive only (float[] data, int w, int h). The WCS/UBO refactors (#295/#297/#301/#314/#316) are render-time only; the CPU decode filter has no geometry. RHEF therefore still needs geometry threaded.

DECODE CALL SITES (TARGET):
- J2K path: view/j2k/J2KView.java:292-298 builds `new J2KDecoder(source, decodeParams, numComps, filterType)` and submits it. view/j2k/J2KDecoder.java is `record J2KDecoder(J2KSource src, J2KParams.Decode params, int numComps, ImageFilter.Type filterType)`; at line 104 it calls `ImageBuffer.createWriteBuffer(actualWidth, actualHeight, format, filterType)` and `outBuffer.finish()` runs the filter. CRITICAL: the display Region is currently computed POST-decode in J2KView.sendDataToHandler (line 337): `Region r = m.roiToRegion(roi.x(), roi.y(), roi.w(), roi.h(), resolution.factorX(), resolution.factorY())`. So the decoder does NOT yet have a Region — it must be computed in J2KView from decodeParams + metaData[frame] and passed into the decoder. J2KView.getResolutionLevel(frame,level) exists (line 365). ResolutionSet.Level.factorX()/factorY() exist (record Level at ResolutionSet.java:59). J2KParams.Decode has fields frame/subImage/level/factor; J2KParams.SubImage has x()/y()/w()/h(). J2KViewCallisto builds no decoder of its own (inherits J2KView path).
- URI/FITS path: view/uri/URIView.java holds `private final Region imageRegion` computed ONCE in the ctor (line 57) as `m.roiToRegion(0,0,buffer.width,buffer.height,1,1)` (URI images decode whole — no dynamic ROI). The decode path is `Decoder(File file, URIImageReader reader, ImageFilter.Type type)` (line 93) -> `reader.readImageBuffer(file, type)`. view/uri/URIImageReader.java interface: `ImageBuffer readImageBuffer(File file, ImageFilter.Type filterType)`. view/uri/FITSImage.java implements it via private `readHDU(...)` -> `readPixels(Header,int[],Object,ImageFilter.Type)` which calls createWriteBuffer at lines 366, 393, 414 (Gray8 byte path + Gray8 blank-fallback + Gray16F path). view/uri/GenericImage.java `readBuffered(BufferedImage,ImageFilter.Type)` calls fromBytes/fromShorts at lines 107/111 (Gray8/Gray16F); RGBA32 path at 126 is filter-skipped.

METADATA / GEOMETRY (TARGET):
- metadata/MetaData.java:43 declares `Region roiToRegion(int,int,int,int,double,double)`.
- metadata/CommonMetaData.java:121 implements roiToRegion as `new Region(roiX*factorX*unitPerPixelX + region.llx, roiY*factorY*unitPerPixelY + region.lly, roiWidth*factorX*unitPerPixelX, roiHeight*factorY*unitPerPixelY)`. NullMetaData and BasicMetaData both `extends CommonMetaData`.
- metadata/FitsMetaData.java:334 overrides roiToRegion as `new Region(roiX*factorX*unitPerPixelX - referenceX, roiY*factorY*unitPerPixelY - referenceY, ...)` where (FitsMetaData.java:284-285) `referenceX = unitPerPixelX*crpix1; referenceY = unitPerPixelY*(pixelH - crpix2)` and crpix is the CRPIX reference pixel. For images where CRPIX == Sun center (CRVAL==0) roiToRegion ALREADY puts the origin at Sun center. When CRVAL!=0 the reference pixel is offset from Sun center and the origin must be shifted by the de-rotated crval. FitsMetaData stores `crval` as a Vec2 (line 289: scaled by unitPerArcsec for non-surface maps) and `crota` as a Quat (line 297: Quat.createAxisZ(wcs.crotaRad())); `isSurfaceMap = wcsProjection.isSurfaceMap()`. Quat.rotateInverseVector(Vec3) exists (Quat.java:170); Vec2.x/.y are public finals.
- metadata/Region.java — `public class Region` with public finals width,height,llx,lly,urx,ury,ulx,glslArray; ctor Region(llx,lly,width,height); static Region.scale; value-equality on (width,height,llx,lly). Lives in org.helioviewer.jhv.metadata.

RENDER-TIME PIPELINE (TARGET):
- opengl/GLImage.java:84-97 applyFilters() calls GLSLSolarShader.bindDisplay(color, 1f/w, 1f/h, -2*sharpen, diffMode.ordinal(), sector0, sector1, enhanced, cutOffX, cutOffY, cutOffVal, calcDepth, brightOffset, brightScale*responseFactor, innerRadius, outerRadius, slitLeft, slitRight). Accessors are record-style now: uploadedImageData.metaData(), .imageBuffer(). enhanced field at line 51, setEnhanced clamps [0,3]. fromJson/toJson at 306/328 round-trip "enhanced" etc.
- opengl/GLSLSolarShader.java — displayBuf is `BufferUtils.newFloatBuffer(4+4+4+4+4+4)` = 24 floats (GLSLSolarShader.java:46); DISPLAY_SIZE = capacity*4. bindDisplay (155-171) writes exactly 22 floats (color[4] + 4 + 4 + 4 + bOffset/bScale[2] + innerRadius/outerRadius/slitLeft/slitRight[4]); the buffer is sized 24, so 2 trailing std140-pad floats are currently zero. Six solar shaders are static instances (sphere, ortho, hpc, lati, polar, logpolar) all init/dispose via lists in init().
- resources/glsl/solarCommon.frag — `struct Display` ends with `vec2 slit;` (no upsilon today). getColor(...) computes value through diff/sharpen/dither then `return texture(lut, vec2(value,0.5)) * display.color;`. There is NO upsilon block on TARGET.

## POC logic to preserve
RANK KERNEL — port verbatim from rhef-filter:src/org/helioviewer/jhv/imagedata/FilterRHEF.java (only package + Region import change). The proven algorithm:

(1) Geometry from Region (origin at Sun center):
```
double pixX, pixY, llx, lly;
if (region == null || !(region.width > 0) || !(region.height > 0)) {
    pixX = 1; pixY = 1; llx = -.5 * width; lly = -.5 * height;   // image-center, pixel-unit fallback
} else {
    pixX = region.width / width; pixY = region.height / height;  // physical units per pixel
    llx = region.llx; lly = region.lly;                          // lower-left from Sun center
}
double invBinWidth = 1 / Math.min(pixX, pixY); // ~1-pixel-wide annuli
double dxMax = Math.max(Math.abs(llx), Math.abs(llx + width * pixX));
double dyMax = Math.max(Math.abs(lly), Math.abs(lly + height * pixY));
int numBins = (int) (Math.sqrt(dxMax * dxMax + dyMax * dyMax) * invBinWidth) + 1;
```
(2) Per-pixel annulus bin via parallel pass over rows; dx2[x] precomputed; `binOf[rowBase+x] = (int)(Math.sqrt(dx2[x]+dy2)*invBinWidth)`.
(3) Counting sort of pixel indices by bin: offset[] prefix sums, then scatter into order[] via a cursor copy.
(4) Per-bin parallel rank kernel (THE load-bearing inner loop):
```
float[] out = data.clone();
ParallelRange.run(numBins, (from, to) -> {
    for (int b = from; b < to; b++) {
        int lo = offset[b]; int hi = offset[b + 1];
        if (hi - lo < MIN_BIN_COUNT) continue;
        long[] packed = new long[hi - lo];
        int n = 0;
        for (int j = lo; j < hi; j++) {
            int idx = order[j]; float v = data[idx];
            if (v > 0) packed[n++] = (long) Float.floatToRawIntBits(v) << 32 | idx; // exclude 0 (occulter/pad stays black)
        }
        if (n < MIN_BIN_COUNT) continue;
        Arrays.sort(packed, 0, n);                       // non-negative float bits sort numerically
        float invRange = 1f / (n - 1);
        int i = 0;
        while (i < n) {                                  // average rank over equal-value runs (scipy rankdata "average")
            long bits = packed[i] >>> 32;
            int j = i;
            while (j + 1 < n && packed[j + 1] >>> 32 == bits) j++;
            float value = .5f * (i + j) * invRange;
            for (int k = i; k <= j; k++) out[(int) packed[k]] = value;
            i = j + 1;
        }
    }
});
return out;
```
MIN_BIN_COUNT = 5 (bins with fewer valid pixels pass input through). Output is [0,1] rank. NaN/zero handling: pixels with v<=0 are excluded and retain their cloned input (zero stays zero -> occulter/padding stays black).

UPSILON MIDTONE CURVE — port verbatim to TARGET solarCommon.frag (from rhef-filter:resources/glsl/solarCommon.frag lines 157-161). The exact double-sided gamma to preserve:
```
if (display.upsilon != 1.) {
    // Double-sided gamma around the midpoint: softens rank-equalized (RHEF) output
    value = clamp(value, 0., 1.);
    value = value < .5 ? .5 * pow(2. * value, display.upsilon) : 1. - .5 * pow(2. - 2. * value, display.upsilon);
}
```
Placed in getColor() AFTER the sharpen block and BEFORE `value += dither(texcoord);`. upsilon=1 is identity (no-op); range clamped [0.05, 1] in GLImage.setUpsilon.

SUN-CENTER FOR NONZERO CRVAL — port the POC's roiToSunRegion concept (rhef-filter CommonMetaData + FitsMetaData diffs). POC stored sunShiftX/sunShiftY on CommonMetaData (default 0) and FitsMetaData computed them from crval de-rotated by crota:
```
// POC FitsMetaData (pre-refactor): Vec3 sun = crota.rotateInverseVector(new Vec3(-crval.x, -crval.y, 0)); sunShiftX = sun.x; sunShiftY = sun.y;
// POC CommonMetaData.roiToSunRegion: r = roiToRegion(...); if (sunShiftX==0 && sunShiftY==0) return r; return new Region(r.llx - sunShiftX, r.lly + sunShiftY, r.width, r.height);
```
This is the ONLY part that must be re-expressed against TARGET fields (crval Vec2 + crota Quat already exist on TARGET FitsMetaData; see file_plan). It is load-bearing only for off-center Sun (PUNCH, some LASCO); for centered CRVAL==0 imagery roiToRegion alone already centers on the Sun and the shift is a no-op.

## File plan

### [new] src/org/helioviewer/jhv/image/FilterRHEF.java
NEW package-private class `class FilterRHEF implements ImageFilter.Algorithm`. Package org.helioviewer.jhv.image. Imports: java.util.Arrays; javax.annotation.Nullable; org.helioviewer.jhv.metadata.Region; org.helioviewer.jhv.thread.ParallelRange. Constant `private static final int MIN_BIN_COUNT = 5;`. Two methods: `@Override public float[] filter(float[] data, int width, int height) { return filter(data, width, height, null); }` and `@Override public float[] filter(float[] data, int width, int height, @Nullable Region region) { ... }` containing the verbatim kernel from poc_logic (geometry block, counting sort, per-bin sort-and-rank). Leading class comment crediting Gilly & DeForest, mirroring the POC. Guard `if (width < 1 || height < 1) return data;` at top.

### [edit] src/org/helioviewer/jhv/image/ImageFilter.java
(1) Add imports javax.annotation.Nullable and org.helioviewer.jhv.metadata.Region. (2) Add enum entry after WOW: `RHEF("Radial histogram equalization", new FilterRHEF())`. (3) Add to interface Algorithm a default method: `default float[] filter(float[] data, int width, int height, @Nullable Region region) { return filter(data, width, height); }` (MGN/WOW inherit the no-op, so they are UNCHANGED). (4) Add @Nullable Region region param to the two private workers `filter(byte[]...,Algorithm,@Nullable Region)` and `filterHalfFloat(short[]...,Algorithm,@Nullable Region)`, forwarding `algorithm.filter(data,width,height,region)`. (5) Add @Nullable Region region param to the two static entry points `filter(byte[]...,Type,@Nullable Region)` and `filterHalfFloat(short[]...,Type,@Nullable Region)`, forwarding region. Matches POC ImageFilter diff exactly.

### [edit] src/org/helioviewer/jhv/image/ImageBuffer.java
Add imports javax.annotation.Nullable, org.helioviewer.jhv.metadata.Region. Add @Nullable Region region overloads: `fromBytes(int,int,Format,byte[],ImageFilter.Type,@Nullable Region)` (keep the existing 4-arg and 5-arg fromBytes; have the 5-arg delegate with region=null OR add region directly — follow POC: keep `fromBytes(w,h,fmt,data)` delegating to `(...,Type.None,null)`, and make the filtered overload take region), `fromShorts(...,@Nullable Region)`, `createWriteBuffer(...,@Nullable Region)`. Inner WriteBuffer: add `private final Region region;` field, set in ctor `WriteBuffer(...,@Nullable Region _region)`, and forward it in finish() to fromShorts/fromBytes. RGBA32 path still skips the filter. Matches POC ImageBuffer diff.

### [edit] src/org/helioviewer/jhv/metadata/MetaData.java
Add to the interface, right after the roiToRegion declaration (line 43): `@Nonnull Region roiToSunRegion(int roiX, int roiY, int roiWidth, int roiHeight, double factorX, double factorY);`

### [edit] src/org/helioviewer/jhv/metadata/CommonMetaData.java
Add protected fields `protected double sunShiftX = 0; protected double sunShiftY = 0;` next to unitPerPixelX/Y. Implement once for all subclasses (Null/Basic/Fits inherit): `@Nonnull @Override public Region roiToSunRegion(int roiX,int roiY,int roiWidth,int roiHeight,double factorX,double factorY){ Region r = roiToRegion(roiX,roiY,roiWidth,roiHeight,factorX,factorY); if (sunShiftX==0 && sunShiftY==0) return r; return new Region(r.llx - sunShiftX, r.lly + sunShiftY, r.width, r.height); }` Matches POC CommonMetaData diff. (Default sunShift 0 => for CRVAL==0 imagery roiToSunRegion == roiToRegion.)

### [edit] src/org/helioviewer/jhv/metadata/FitsMetaData.java
In the WCS setup block (after crota is assigned, ~line 297, inside the non-CALLISTO branch), set sunShiftX/Y from crval de-rotated by crota for non-surface maps with nonzero crval. Using TARGET fields (crval is a Vec2 in the same units as the Region for non-surface maps; crota is a Quat): `if (!isSurfaceMap && (crval.x != 0 || crval.y != 0)) { Vec3 sun = crota.rotateInverseVector(new Vec3(-crval.x, -crval.y, 0)); sunShiftX = sun.x; sunShiftY = sun.y; }`. Add `import org.helioviewer.jhv.math.Vec3;`. NOTE: verify crval here is already scaled to Region units (line 289 multiplies internalCrvalX by unitPerArcsec for non-surface maps) — this matches the units of referenceX/region.llx, so the shift composes correctly. This is the ONE spot needing TARGET-specific re-expression vs the pre-refactor POC; flagged as open question for Bogdan.

### [edit] src/org/helioviewer/jhv/view/j2k/J2KDecoder.java
Add `import org.helioviewer.jhv.metadata.Region;`. Change record header to `record J2KDecoder(J2KSource src, J2KParams.Decode params, int numComps, ImageFilter.Type filterType, @Nullable Region region)` (add `import javax.annotation.Nullable;`). At the createWriteBuffer call (line 104) pass `region`: `ImageBuffer.createWriteBuffer(actualWidth, actualHeight, format, filterType, region)`. Matches POC J2KDecoder diff.

### [edit] src/org/helioviewer/jhv/view/j2k/J2KView.java
(1) Add a private helper `private Region filterRegion(J2KParams.Decode decodeParams){ int frame=decodeParams.frame; J2KParams.SubImage roi=decodeParams.subImage; ResolutionSet.Level resolution=getResolutionLevel(frame, decodeParams.level); return metaData[frame].roiToSunRegion(roi.x(),roi.y(),roi.w(),roi.h(),resolution.factorX(),resolution.factorY()); }`. (2) At submitDecode (line 296) pass it: `new J2KDecoder(source, decodeParams, numComps, filterType, filterRegion(decodeParams))`. Leave sendDataToHandler's existing `m.roiToRegion(...)` (display Region) UNCHANGED — display geometry must NOT change. Region is already imported (org.helioviewer.jhv.metadata.Region, line 23). The POC also extracted a decodeRegion() helper for the display path; SKIP that refactor here to minimize diff — only filterRegion() is needed.

### [edit] src/org/helioviewer/jhv/view/uri/URIImageReader.java
Add `import javax.annotation.Nullable; import org.helioviewer.jhv.metadata.Region;`. Change interface method to `ImageBuffer readImageBuffer(File file, ImageFilter.Type filterType, @Nullable Region region) throws Exception;`. Matches POC.

### [edit] src/org/helioviewer/jhv/view/uri/URIView.java
Add `private final Region filterRegion;` next to imageRegion. In ctor after imageRegion is set (line 57): `filterRegion = m.roiToSunRegion(0, 0, buffer.width, buffer.height, 1, 1);`. In the Decoder record (line 93) add a `@Nullable Region region` component and pass it: `reader.readImageBuffer(file, type, region)`. At submit (line 80): `new Decoder(dataUri.file(), reader, filterType, filterRegion)`. Region already imported. Matches POC URIView diff.

### [edit] src/org/helioviewer/jhv/view/uri/FITSImage.java
Add `import javax.annotation.Nullable; import org.helioviewer.jhv.metadata.Region;`. Thread @Nullable Region through: readImageBuffer(file,type) -> add region param and forward; readImage() ctor path and readImageBuffer(InputStream) pass null. readHDU(hdu,filterType) -> readHDU(hdu,filterType,@Nullable Region region). readPixels(header,axes,pixels,filterType) -> readPixels(...,@Nullable Region region). Pass region to all 3 createWriteBuffer calls (Gray8 byte path ~line366, Gray8 blank-fallback ~line393, Gray16F path ~line414). Matches POC FITSImage diff.

### [edit] src/org/helioviewer/jhv/view/uri/GenericImage.java
Add `import javax.annotation.Nullable; import org.helioviewer.jhv.metadata.Region;`. readImageBuffer(file,type) -> add @Nullable Region region; readBuffered(image,filterType) -> readBuffered(image,filterType,@Nullable Region region). Pass region to fromBytes (Gray8, ~line107) and fromShorts (Gray16F, ~line111). readImage() ctor path calls readBuffered(image, Type.None, null). RGBA32 default branch (~line126) unchanged (filter skipped). Matches POC GenericImage diff.

### [edit] resources/glsl/solarCommon.frag
(1) In `struct Display`, append `float upsilon;` AFTER `vec2 slit;` (this consumes one of the two existing trailing std140 pad floats — NO UBO size change; verify all 6 solar shaders still render). (2) In getColor(), after the sharpen `value = mix(...)` block and BEFORE `value += dither(texcoord);`, insert the verbatim upsilon block from poc_logic. Matches POC solarCommon.frag (POC put the same 2 lines; the struct upsilon field also matches).

### [edit] src/org/helioviewer/jhv/opengl/GLSLSolarShader.java
Append a `float upsilon` param to bindDisplay(...) (after slitLeft, slitRight) and add `displayBuf.put(upsilon);` after the innerRadius/outerRadius/slitLeft/slitRight put. The displayBuf capacity (4+4+4+4+4+4 = 24 floats) is UNCHANGED — upsilon fills float index 22 (the first existing pad slot). Matches POC GLSLSolarShader diff.

### [edit] src/org/helioviewer/jhv/opengl/GLImage.java
Add field `private double upsilon = 1;` after `enhanced`. In applyFilters(), append `, (float) upsilon` to the bindDisplay(...) call (after slitLeft, slitRight). Add setter `public void setUpsilon(double _upsilon){ upsilon = Math.clamp(_upsilon, 0.05, 1); }` and getter `public double getUpsilon(){ return upsilon; }`. In fromJson add `setUpsilon(jo.optDouble("upsilon", upsilon));` and in toJson add `jo.put("upsilon", upsilon);`. Matches POC GLImage diff.

### [edit] src/org/helioviewer/jhv/layers/filters/ImageFilterPanel.java
MINIMAL-DIFF CHOICE: KEEP the existing radio-button modePanel (RHEF auto-appears because the loop iterates ImageFilter.Type.values()) — do NOT port the POC's JComboBox refactor. ONLY add the Upsilon control: add `private static String formatUpsilon(double value){ return String.format("%.2f", value); }`. In the ctor, after the enhanceButton block, add a JHVSlider(5,100,(int)(layer.getGLImage().getUpsilon()*100)) with a JLabel (formatUpsilon), tooltip "Midtone softening of equalized output: 1 = none"; changeListener sets layer.getGLImage().setUpsilon(value) and calls DisplayController.display() (RENDER-time, NOT clearCache). Wrap in a JPanel(BorderLayout) and a JideSplitButton("Υ") (Greek capital Upsilon) tooltip "Soften the midtones of rank-equalized (RHEF) images", setAlwaysDropdown(true). Add it: `buttonPanel.add(upsilonButton, BorderLayout.LINE_START);` (enhanceButton stays LINE_END). Imports already present (Buttons, JHVSlider from gui.component; JideSplitButton). getSecond() still returns modePanel.

## Style notes
Bogdan idioms this PR must follow (verified against TARGET source):
- K&R braces, 4-space indent, no tabs, UTF-8, newline at EOF (CONTRIBUTING).
- Constructor/setter params use a leading underscore: `setUpsilon(double _upsilon)`, `WriteBuffer(..., @Nullable Region _region)`. (Seen throughout GLImage, ImageBuffer, FitsMetaData.)
- Filters are package-private: `class FilterRHEF implements ImageFilter.Algorithm` (NOT public) — mirror FilterMGN/FilterWOW exactly, including a single `import org.helioviewer.jhv.thread.ParallelRange;`.
- Use `Math.clamp(...)` (Java 25) not custom clamps — already used in setEnhanced and the byte conversion.
- Use `ParallelRange.run(length, (from,to) -> {...})` for all hot loops; precompute per-column arrays (dx2[]) outside the parallel body like the POC.
- @Nullable / @Nonnull from javax.annotation, applied on the new Region params exactly as roiToRegion uses @Nonnull on its return.
- Record decoders: J2KDecoder and URIView.Decoder are records — add the Region as a record COMPONENT, not a mutable field.
- JSON round-trip pattern: `setX(jo.optDouble(\"x\", x))` in fromJson, `jo.put(\"x\", x)` in toJson — upsilon follows enhanced one-for-one.
- Render vs decode refresh discipline: filter-type change => `layer.getView().clearCache(); layer.getView().setFilter(type); DisplayController.render(1);` (forces re-decode). Upsilon change => `DisplayController.display()` ONLY (GPU uniform, no re-decode). This split is the whole point of the hybrid shape.
- std140 UBO: do NOT resize displayBuf; upsilon occupies an existing trailing pad float. Keep the field at the END of struct Display (after vec2 slit) so existing offsets are untouched.
- Terse one-line commit messages; two commits (algorithm+upsilon shader; decode-chain plumbing).
- Greek capital Upsilon glyph Υ for the split-button label, matching the POC and the studio's Υ naming.
- Comments terse and lowercase-leaning, explaining WHY (e.g. \"// non-negative float bits sort numerically\", \"// occulter/padding stays black\"), matching FilterMGN/decoder comment style.

## Risks
- UBO struct edit (struct Display gains `float upsilon;`) is SHARED by all six solar shaders (sphere/ortho/hpc/lati/polar/logpolar) via solarCommon.frag. The ANGLE/LWJGL driver is the final arbiter of std140 packing; must visually verify EVERY mode still renders identically with upsilon=1 (identity). The trailing-pad assumption (buffer is 24 floats, only 22 written) is verified from GLSLSolarShader.java:46 + bindDisplay, but confirm at runtime.
- Sun-center for nonzero CRVAL: the POC computed sunShiftX/Y on PRE-refactor FitsMetaData where crval was in raw arcsec. On TARGET, crval is a Vec2 already scaled by unitPerArcsec (FitsMetaData.java:289) for non-surface maps, matching Region units. The de-rotation `crota.rotateInverseVector(Vec3(-crval.x,-crval.y,0))` should compose with referenceX/Y, but the exact sign of the lly term (`r.lly + sunShiftY`, note the +) inherits the POC's pixel-Y-flip convention and MUST be validated against a real off-center image (PUNCH or shifted LASCO). If wrong, the annuli center off-Sun and the equalization smears. Highest-risk line in the PR.
- Kakadu actual-vs-requested ROI rounding when factor != 1: filterRegion is built from the REQUESTED subImage/factor while the kernel divides by the ACTUAL decoded width/height (J2KDecoder.actualWidth). Offset is <=~1px in Sun center; invisible in annulus statistics per the design notes, but it means pixX = region.width/actualWidth is very slightly off when actual != requested. Acceptable.
- Gray8 (8-bit byte) path re-quantizes RHEF's [0,1] rank output to 256 levels on the way back out of ImageFilter.filter(byte[]) -> possible banding on smooth F-corona gradients. Gray16F/FITS path is clean. JP2 AIA/LASCO are typically Gray8. Note in PR body; dither in the shader partially masks it.
- Adding roiToSunRegion to the MetaData interface forces all implementors to have it. Mitigated by implementing once on CommonMetaData (the base of Null/Basic/Fits) so the interface stays satisfied with a single concrete method. Verify no other MetaData implementor exists outside metadata/ (grep confirmed only Common/Fits/Basic/Null/XMLMetaDataContainer; the latter is a container, not a MetaData).
- Minimal-diff deviation from POC: this plan KEEPS radio buttons (POC switched to JComboBox). If Bogdan prefers the combo (he recently did bulk-metadata dropdowns), the combo is a drop-in but a larger diff. Defaulted to radio to minimize change; flagged as a reviewer preference.

## Open questions
- FitsMetaData sun-shift units: is `crval` (Vec2, set at FitsMetaData.java:289 as internalCrvalX*unitPerArcsec for non-surface maps) in the SAME linear units as referenceX/region.llx, such that `crota.rotateInverseVector(Vec3(-crval.x,-crval.y,0))` yields a correct origin shift in Region units? And is the Y sign correct given the pixel-row flip in referenceY = unitPerPixelY*(pixelH - crpix2)? (POC used `r.lly + sunShiftY`.) This is the one place the pre-refactor POC math must be re-derived on current upstream.
- Would you prefer the radial Region to be derived from the ACTUAL decoded width/height inside J2KDecoder (using actualRegion) rather than the requested subImage in J2KView.filterRegion()? The requested-vs-actual delta is <=~1px; J2KView is the cleaner place (it owns metaData[]), but you may prefer the decoder compute it from actualRegion for exactness.
- Do you want the upsilon GPU curve gated to only apply when filterType == RHEF, or always-on (it is identity at upsilon=1 regardless)? The POC applies it unconditionally via `if (display.upsilon != 1.)`, so a user could soften midtones of MGN/WOW/None too. Harmless but semantically RHEF-specific; confirm intended scope.
- UI shape: keep the existing radio-button filter selector (minimal diff, RHEF auto-appears) or switch to a JComboBox as the POC did? Your call as the sole maintainer.
- Naming: enum description "Radial histogram equalization" and toString() "RHEF" — acceptable, or do you want the fuller "Radial Histogram Equalizing Filter" / a citation in the description tooltip (Gilly & DeForest)?
- Should setUpsilon clamp at [0.05, 1] (POC) — i.e. upsilon>1 (sharpening midtones) is disallowed? The studio later extended the analogous control; confirm the [0.05,1] softening-only range is what you want in JHV.
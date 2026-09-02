# Brief: take upstream's WCS rework into HFStudio

## The task

Six upstream commits rework how FITS world-coordinate metadata is interpreted. They are the most
valuable thing upstream has done for a project whose future is FITS rather than JPEG 2000, and they
are the only upstream work since the 17 July divergence that does not apply cleanly to this tree.

Integrate them without losing the two things this fork added to the same files, and prove the result
against the astropy validator rather than against the eye.

Everything below was measured on 2 September 2026 at `dba5c70a8` on `demo-all`. Re-run anything you
change.

## The commits, in the order they were written

```
09b9ec4e7  07-27  Clear uninitialized pixels in blank FITS images     independent, do first
94c1ba63c  08-13  Support more of standard FITS angular units         supersedes ours, see below
e3dc35c1f  08-13  Fix GONG CEA validation regression                  validator only, 5 lines
ab5bdd25a  08-13  Preserve full FITS PC transforms in WCS rendering   THE BIG ONE, 17 files
256ad28ce  08-13  Support FITS WCS CD transformation matrices         builds on ab5bdd25a
51d128947  08-14  Handle CEA surface-map units consistently           finishes the CEA story
```

Apply in that order. `256ad28ce` was written after `ab5bdd25a` despite the shorter hash appearing
first in `git log --oneline` output, and taking them the other way round means resolving the same
conflict twice.

## What this fork changed in the same files, and what must survive

Only two things, both in `WcsInterpreter`. This is much smaller than the conflict count suggests.

**1. A forced-projection overload, which must survive.**

```java
static Result read(MetaDataContainer m, @Nullable WcsHeader.Projection forcedProjection)
```

It exists because `ptmc_compo` pipeline output is an indexed Carrington synoptic map whose `CTYPE`
does not say `CRLN-CAR`, so the projection has to be forced. Three checks guard it and all three
must still pass: `FitsMetaDataChpolarityCheck`, `WcsInterpreterForcedProjectionCheck`,
`ChpolarityLutRegistrationCheck`.

**2. An `isDegrees` helper, which must NOT survive.**

```java
private static boolean isDegrees(String unit)  // accepts deg / degree / degrees
```

Upstream's `94c1ba63c` replaces the same line with `arcsecPerUnit`, a switch covering `deg`,
`arcmin`, `arcsec`, `mas` and `rad`. That is strictly better and it is the same fix arrived at
independently. **Take theirs and delete ours.** Do not merge the two: keeping both leaves a helper
that is called from nowhere, and a reviewer six months from now has to work out which one is live.

One thing theirs does not cover that ours did: `degree` and `degrees` fall to `default -> 1.`,
silently scaling such an image by 1/3600. IDL-written synoptic maps do emit those. **Extend their
switch with the two extra cases** rather than restoring our helper, and send that back upstream as a
one-line PR: it is a real gap in their version and it is how we found it.

## The big one: crota becomes a matrix

`ab5bdd25a` is the commit to plan around. It replaces the rotation *quaternion* in `WcsHeader` with
a 2x2 *matrix*, because a FITS `PC` or `CD` matrix can carry skew and anisotropic scale that no
rotation can represent:

```java
-    public final Quat crota;
+    public final Mat2 imageToPlane;
+    public final Mat2 planeToImage;   // inverse, precomputed
```

It adds `math/Mat2.java`, and touches 17 files including the shader.

**The good news, and the reason this is tractable:** the shader-side change is centralised. The
quaternion is consumed in exactly three places in this tree:

```
resources/glsl/solarCommon.frag:493   wcsPlaneToTexcoord
resources/glsl/solarCommon.frag:501   wcsPlaneToWrappedXTexcoord
resources/glsl/solarLati.frag:54
```

All six shaders this fork has added since the divergence (`solarSky.frag`, `warpSurface.frag/vert`,
`warpCommon.vert`, `transition.frag/vert`) reach WCS sampling through those shared helpers. They
inherit the change for free. **Do not go shader by shader.** If you find yourself editing
`solarSky.frag` for this, stop and check why.

The Java-side ripple is one signature:

```java
GLSLSolarShader.bindWCS(Quat cameraDiff0, Region r0, Quat crota0, ...)
                                                     ^^^^ becomes Mat2
```

## Acceptance

**The numeric one, which is the whole point.** The astropy validator runs on this machine (astropy
7.2.0, sunpy 8.0.0). Baseline on the current tree, before any of this work:

```bash
F=~/JHelioviewer-SWHV/FileCache/bcc8682ca69cbff4c344ec1545fcb0488aeaa70c902fec0c6f3dc371a3b50666.fits
python3 extra/test/validate_jhv_wcs_against_astropy.py "$F" --samples 200
```

```
projection_max_error_internal = 6.51e-14
pixel_center_max_error_px     = 9.09e-13
```

After integration those numbers must be **no worse**. They are round-off, so any real regression
will be visible by many orders of magnitude, not by a digit.

**The gap to close first.** There is exactly one FITS file in the cache, and it carries no `CD`,
no `PC` and no `CROTA2` keywords, so it exercises none of what these commits change. Before
claiming anything, fetch files that do:

- a PUNCH L3 mosaic (the `punch` MCP, or `PunchClient`) for the ordinary case
- a GONG synoptic map for CEA, which `e3dc35c1f` and `51d128947` exist for
- a `ptmc_compo` output for the forced-CAR path this fork added
- anything with a genuine `CD` matrix rather than `CDELT` plus `CROTA2`, since `256ad28ce` is
  otherwise untested by construction

**The rest.** `ant jar` clean, 47/47 in `extra/test/*Check.java`, 21/21 from
`validate_glsl_syntax.py`, app launches with no new log errors. Two pre-existing log lines are not
yours: a SAMP "client not subscribed" warning, and Helioviewer's server failing to build JPX movies.

## Traps

- **`git apply --check` lies here.** It reported conflicts on two commits that `git cherry-pick`
  then merged cleanly, because cherry-pick does a three-way merge and `git apply` does not. Judge
  applicability with `cherry-pick`, and `--abort` if it goes badly.
- **Never resolve a conflict by taking a whole file from one side.** Doing exactly that while
  preparing PR #351 silently deleted an upstream `Task.submit` overload. It compiled. Audit every
  resolution with `git diff --cached <file>` and read the deletions, not just the additions.
- **Another agent may be working in this repo.** Four files were modified in the working tree
  during this session's work and were not ours. Check `git status` before starting and do not
  sweep unrelated changes into a commit.
- **`resources/glsl/*.frag` has no compiler in the build.** `validate_glsl_syntax.py` is the only
  thing that catches a shader typo, and a mismatched uniform block between Java and GLSL will not
  be caught by either. If `WcsHeader`'s std140 layout changes, count the floats by hand.

## Afterwards

Two follow-ups fall out of this, both small and both worth sending upstream while the context is
fresh:

1. The `degree`/`degrees` gap in `arcsecPerUnit`, above.
2. Whether the forced-projection overload is worth offering upstream. `ptmc_compo` is a local
   pipeline, but "CTYPE does not say what the file is" is not a local problem, and upstream already
   carries a `Fix GONG CEA validation regression` commit for the same class of thing.

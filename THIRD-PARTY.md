# Third-party components

An inventory of everything this repository ships that it did not write, with the licence each
component carries and where that licence came from.

It exists because the `LICENSE` file at the root answers a narrower question than people assume.
That file is the Mozilla Public License 2.0 and it governs JHelioviewer's own source. It says
nothing about the 64 jars and 21 native binaries under `lib/`, three of which restrict
redistribution far more tightly than the MPL does.

Two further licences ship with the application and are not visible from the repository root at all,
because they live inside a jar: `licenses/Kakadu.txt` and `licenses/EULA.txt` in
`lib/jhv/jhv-resources.jar`. Both are quoted below. The application does surface them, through the
About dialog, so this is a discoverability problem rather than a compliance one.

**Scope.** Everything below concerns *redistribution*. Running the software you built yourself, for
your own research, raises none of it.

**Standing.** This is an engineering inventory, not legal advice. Two columns are kept apart on
purpose: what the artifact itself states, and what the upstream project states. Anything in the
second column is a starting point for a question, not an answer.

Generated against `9474f0aa` (2026-09-01), nearest tag `v5.6d-coronal-research`.

## Start here: the three that gate redistribution

### 1. Kakadu is proprietary, under a non-commercial licence that cannot be transferred

| Where | What |
| --- | --- |
| `lib/formats/kdu_jni.jar` | Java bindings, `kdu_jni/*.class` |
| `lib/jhv/jhv-natives-linux.jar` | `libkdu_jni.so` |
| `lib/jhv/jhv-natives-macos.jar` | `libkdu_jni.dylib` |
| `lib/jhv/jhv-natives-macos-arm64.jar` | `libkdu_jni.dylib` |
| `lib/jhv/jhv-natives-windows.jar` | `kdu_jni.dll`, `kdu_v7AR.dll` |
| **`lib/jhv/jhv-resources.jar`** | **`licenses/Kakadu.txt`, the agreement itself** |

Kakadu is the JPEG 2000 codec, from NewSouth Innovations Ltd. It decodes every JP2, JPX and JPIP
layer, so it is not optional to the application's function.

The agreement is bundled and readable, which makes this specific rather than a matter of guessing:

```bash
unzip -p lib/jhv/jhv-resources.jar licenses/Kakadu.txt
```

It is headed **"Non-Commercial License Agreement"**. The clauses that decide what a fork may do:

- **§1.3** defines the Licensee as "the individual person who has **purchased** the Kakadu software
  and is granted the non-commercial license". That is the JHelioviewer project, through its ESA and
  NASA funding. A fork's maintainer is a Third Party under §1.4.
- **§10: "The license is not transferable to a Third Party and may not be sub-licensed to any other
  person."** This is the decisive clause. The JHelioviewer project's Kakadu licence does not reach
  anyone else by virtue of them having a copy of the repository.
- **§4** lets the Licensee distribute the libraries to a Third Party only if "the Third Party
  possesses a license to use the Kakadu software", and only with no financial return.
- **§3** permits Deployment, meaning distribution of built Applications to Third Parties, "provided
  that such Deployment does not result in any direct or indirect financial return to the Licensee
  or any other Third Party which further supplies or otherwise uses such Applications."

Two things follow, and they point in opposite directions, which is why this needs a person and not
a document. The non-commercial terms are a comfortable fit for publicly funded research software
given away at no charge. But §10 means the right to redistribute is the JHelioviewer project's to
exercise, not a fork's, and "indirect financial return" in §3 is a phrase carrying real weight for
anything attached to a funded deliverable or a paid engagement.

**§3 also requires that "all copies of Applications shall contain notification that they were
developed using the Kakadu software."** JHelioviewer complies: `AboutDialog.java:44` credits Kakadu
and links to the bundled licence. Any fork must keep that intact, which MPL §3.4 independently
requires.

The practical route for a fork that wants to distribute binaries is to ask the JHelioviewer
maintainers to publish the build, or to hold a Kakadu licence of one's own. Worth putting to NWRA
contracts before either.

### 1b. An ESA end-user agreement is also bundled

`licenses/EULA.txt` in the same jar is an ESTEC/ESA end-user agreement covering "your use of
JHelioviewer and related software components". It is a disclaimer-and-privacy instrument rather
than a grant: liability, third-party links, and a clause on collecting IP addresses and usage
telemetry for internal analysis. It does not restrict redistribution, but it is a document a fork
inherits and should either carry forward or consciously replace, particularly the privacy section,
which describes data collection a fork may not actually perform.

### 2. One bundled ffmpeg build is not redistributable at all

Verified by running the binary and by reading its embedded configuration string:

| Native jar | ffmpeg | Configuration | Consequence |
| --- | --- | --- | --- |
| `jhv-natives-macos-arm64.jar` | 7.1.1 | `--enable-gpl`, libx264, libx265 | GPLv2-or-later |
| `jhv-natives-linux.jar` | 7.x | `--enable-gpl` | GPLv2-or-later |
| `jhv-natives-windows.jar` | 7.x | `--enable-gpl` | GPLv2-or-later |
| `jhv-natives-macos.jar` (Intel) | 7.1 | `--enable-gpl --enable-version3 **--enable-nonfree**` | **Not redistributable** |

`--enable-nonfree` is FFmpeg's own marker for a build combining GPL code with components whose
licences are incompatible with it. The FFmpeg project states that the resulting binary cannot be
distributed. Only the Intel macOS build carries it; the Apple Silicon build one directory over does
not, which is what makes this look like a build-machine accident rather than a decision.

Reproduce:

```bash
T=$(mktemp -d); unzip -qo lib/jhv/jhv-natives-macos.jar -d "$T"
"$T/jhv/macos-amd64/ffmpeg" -version | head -3
```

For the other three, the GPL applies to the ffmpeg binary and not to this application: ffmpeg is
invoked as a separate process and nothing links against it, which is the ordinary aggregation case.
The obligation that does attach is the GPL's own: ship or offer ffmpeg's corresponding source,
including that configuration line.

### 3. No trademark licence

MPL 2.0 §2.3 grants no rights in any contributor's trademarks. The name "JHelioviewer" and its logo
are not licensed by `LICENSE`. A publicly distributed fork needs its own name.

## The MPL 2.0 core

`LICENSE` is stock MPL 2.0. Two details were checked rather than assumed:

- **The Exhibit B "Incompatible With Secondary Licenses" notice is not attached.** The four matches
  in `LICENSE` are the template printing its own definitions and exhibits. Consequence: §3.3 permits
  distributing the covered software under GPL, LGPL or AGPL as part of a Larger Work combined with
  those. Had the notice been attached, that would be barred.
- **Only one of 501 Java files carries the Exhibit A header** (`AboutDialog.java`). This is
  permitted: Exhibit A explicitly allows the notice to live in a `LICENSE` file where a recipient
  would look. It does mean the root `LICENSE` is doing all the work.

MPL is file-level copyleft. Modified JHelioviewer files stay MPL when distributed as source
(§3.1), including any new file that contains JHelioviewer code (§1.10(b)). New files that contain
none may carry any licence. Binary distribution requires the covered source to be available at no
more than the cost of distribution (§3.2). Notices must not be stripped (§3.4).

## Native binaries

Everything under `lib/jhv/jhv-natives-*.jar` and `lib/natives-macos/`.

| Binary | Component | Licence | Basis |
| --- | --- | --- | --- |
| `libkdu_jni.*`, `kdu_jni.dll`, `kdu_v7AR.dll` | Kakadu JPEG 2000 | Proprietary, paid | Vendor; version strings confirm Kakadu v7 |
| `ffmpeg` | FFmpeg | GPLv2+, and one build non-redistributable | Read from each binary's own configuration string |
| `libJNISpice.*`, `JNISpice.dll`, `lib/jnispice.jar` | NAIF SPICE toolkit | Permissive with attribution, per NAIF | Upstream project, not stated in the artifact |
| `libEGL.*`, `libGLESv2.*` | ANGLE | BSD-style, per the ANGLE project | Upstream project, not stated in the artifact |
| `lib/natives-macos/libjhvmetalhost.dylib` | This repository's own Metal host | MPL 2.0 with the rest | Built here from `native/macos/jhv_metal_host.m` |

`lib/jhv/jhv-resources.jar` holds no code: 27 PNGs, one GIF, the Material Design Icons webfont
(`fonts/materialdesignicons-webfont.ttf`), and the three licence texts discussed above. The icon
font is distributed by its project under SIL OFL 1.1 with Apache 2.0 code, neither of which is
asserted by anything inside this jar. The event icons carry no attribution at all and their
provenance is unrecorded.

## Java libraries

Licence taken from the jar's own `Bundle-License` header, an embedded `META-INF/LICENSE`, or its
Maven coordinates. Rows marked *(unstated)* carry no licence metadata in the artifact at all; the
licence given is the upstream project's and has not been verified from anything shipped here.

| Jar | Coordinates | Licence | Basis |
| --- | --- | --- | --- |
| `guava-33.6.0-jre` | com.google.guava | Apache 2.0 | `Bundle-License` + `META-INF/LICENSE` |
| `failureaccess-1.0.3` | com.google.guava | Apache 2.0 | `Bundle-License` + `META-INF/LICENSE` |
| `caffeine-3.2.4` | com.github.ben-manes.caffeine | Apache 2.0 | `Bundle-License` + `META-INF/LICENSE` |
| `ehcache-3.12.0` | org.ehcache.modules | Apache 2.0 (vendor IBM Corp.) | `Bundle-License: LICENSE` |
| `error_prone_annotations-2.50.0` | com.google.errorprone | Apache 2.0 | `Bundle-License` |
| **`annotations-3.0.1`** | **com.google.code.findbugs** | **LGPL** | **`Bundle-License: gnu.org/licenses/lgpl.html`** |
| `commons-io-2.22.0` | commons-io | Apache 2.0 | `META-INF/LICENSE.txt` + `NOTICE.txt` |
| `commons-compress-1.28.0` | org.apache.commons | Apache 2.0 | `META-INF/LICENSE.txt` + `NOTICE.txt` |
| `commons-validator-1.10.1` | commons-validator | Apache 2.0 | `META-INF/LICENSE.txt` + `NOTICE.txt` |
| `tika-core-3.3.1` | org.apache.tika | Apache 2.0 | `Bundle-License` + `META-INF/LICENSE` |
| `everit-json-schema-1.14.6` | com.github.erosb | Apache 2.0 | `Bundle-License` |
| `handy-uri-templates-2.1.8` | com.damnhandy | Apache 2.0 | `Bundle-License` |
| `prettytime-nlp-4.0.6.Final` | org.ocpsoft.prettytime | Apache 2.0 | `META-INF/LICENSE` + `NOTICE` |
| `fastjson2-2.0.61` | com.alibaba.fastjson2 | Apache 2.0 *(unstated)* | Maven coordinates only |
| **`json-20260522`** | **org.json** | **JSON.org licence** | **`Bundle-License` points at the project LICENSE; no licence file in the jar** |
| `nom-tam-fits-1.22.0` | gov.nasa.gsfc.heasarc | Public domain / Unlicense *(unstated)* | Maven coordinates only |
| `jsofa-20231011` | org.javastro | SOFA-derived terms *(unstated)* | Maven coordinates only |
| `jsamp-1.3.9` | uk.ac.starlink | LGPL *(unstated)* | Maven coordinates only |
| `lib/formats/stil/*.jar` (7 jars) | uk.ac.starlink | LGPL *(unstated)* | Bare manifests, no metadata at all |
| `joml-1.10.9` | JOML | MIT *(unstated)* | Manifest vendor only |
| `lwjgl-3.4.1` and 15 companions | lwjgl.org | BSD 3-clause *(unstated)* | Manifest vendor only |
| `sqlite-jdbc-3.53.0.0` (4 jars) | org.xerial | Apache 2.0 | `Bundle-License` |
| `okhttp-jvm-5.4.0`, `okio-jvm-3.17.0`, `logging-interceptor-5.4.0` | com.squareup | Apache 2.0 *(unstated)* | Vendor |
| `kotlin-stdlib-2.4.0` | JetBrains | Apache 2.0 *(unstated)* | Manifest vendor only |
| `slf4j-api-1.7.36`, `slf4j-jdk14-1.7.36` | org.slf4j | MIT *(unstated)* | Manifest vendor only |
| `flatlaf-3.7.1`, `-intellij-themes`, `-jide-oss` | FormDev Software GmbH | Apache 2.0 *(unstated)* | Manifest vendor only |
| **`jide-oss-3.7.15`** | JIDE Software | **Dual-licensed, conditions apply** *(unstated)* | No metadata in the jar |
| `kdu_jni` | Kakadu Software Pty Ltd | Proprietary | See above |

### Two rows worth a second look

**`annotations-3.0.1` declares LGPL.** FindBugs annotations are compile-time only and are normally
argued to fall outside the LGPL's reach at runtime, but it is the one copyleft jar on the list and
it declares itself as such in its own manifest. If it is only needed for `@Nullable` and friends,
`jsr305` or a compile-scope-only dependency avoids the question entirely.

**`json-20260522` is the org.json library.** Its licence has historically carried the clause "The
Software shall be used for Good, not Evil". That clause is why the Apache Software Foundation
classifies it as Category X and forbids it, and why Google bans it internally. It is not a licence
NWRA should discover in a deliverable after the fact. `everit-json-schema` depends on it, so
removing it is not a one-line change.

## Reproducing this inventory

```bash
# Licence metadata from every jar
for j in $(find lib -name "*.jar" | sort); do
  echo "### $j"
  unzip -l "$j" | grep -iE "META-INF/(LICENSE|NOTICE|COPYING)"
  unzip -p "$j" META-INF/MANIFEST.MF | tr -d '\r' | grep -iE "^(Bundle-License|Implementation-Vendor)"
done

# Every native, and the ffmpeg build configuration in each
for j in lib/jhv/jhv-natives-*.jar; do echo "--- $j"; unzip -l "$j"; done
```

## What to do with this

1. **Settle Kakadu before distributing any binary.** The bundled agreement is non-commercial and,
   by its §10, neither transferable nor sub-licensable, so the right to redistribute belongs to the
   JHelioviewer project rather than to a fork.
2. **Do not ship `jhv-natives-macos.jar` as it stands.** Replace that ffmpeg with a build that has
   no `--enable-nonfree`, matching the Apple Silicon one already in the tree.
3. **Offer ffmpeg's source** alongside the other three platform builds, or link to it.
4. **Rename any publicly distributed fork.** No trademark rights come with the MPL.
5. **Resolve the *(unstated)* rows** from upstream before this document is relied on for anything
   that matters. They are recorded here as questions, not as findings.

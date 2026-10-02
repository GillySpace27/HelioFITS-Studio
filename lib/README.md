# lib/: third-party jars and natives

Every jar here is committed to git; there is no dependency manager. What each one is, where
its licence text comes from and its checksum are recorded in `extra/licenses/licenses.json`,
which `extra/licenses/sync_licenses.py` writes from the jars themselves. The table below is
generated from that file.

## The same-commit rule

Any change under `lib/` (add, update, move or retire a jar or native) lands in the same commit as:

1. `python3 extra/licenses/sync_licenses.py`, which regenerates `extra/licenses/licenses.json`
   and `resources/licenses/ThirdParty-Notices.txt`. Afterwards
   `python3 extra/licenses/sync_licenses.py --check` must print `License files are in sync.`
2. The regenerated table in this file (command below).
3. For FFmpeg or ANGLE, the updater in `extra/ffmpeg/` or `extra/angle/`, never a hand copy.
   FFmpeg must stay the GPL build whose SHA-256 is in `extra/ffmpeg/ffmpeg.json`.

`ant check-all` runs `sync_licenses.py --check`, so a jar change without step 1 fails the gate.
A real version upgrade of any jar is its own tested change (LWJGL and ANGLE interact with the
EDR canvas). `python3 extra/licenses/freshness.py` reports newer upstream versions; it never
changes a jar.

`lib/natives-macos/libjhvmetalhost.dylib` is a build product of `ant build-metal-host`. It is
tracked today; never commit a change to it.

Kakadu is gone (ece2db710, 2026-09-16) and must not return: JPEG 2000 is decoded by OpenJPEG
(`openjp2` inside `jhv/jhv-natives-*.jar`, notice `resources/licenses/OpenJPEG.txt`).

## Flagged rows

- `annotations-3.0.1.jar` (FindBugs annotations, with JSR-305 and JCIP annotations): LGPL.
- `formats/json-20260719.jar`: its licence clause was flagged for review; the bundled
  `extra/licenses/JSON.txt` reads "Public Domain."

## Open decision

Moving `lib/` out of git into a pinned, checksummed download step would shrink clones (the
repository packs to about 2.8 GiB, `git count-objects -vH` on 2026-09-28). That is Gilly's
decision and is not made; nothing here assumes it.

## Inventory

Generated on 2026-10-02 from `extra/licenses/licenses.json` (67 artifacts).

| Path | Version | Licence evidence (from licenses.json) | SHA-256 (first 12) | Flag |
|---|---|---|---|---|
| `lib/annotations-3.0.1.jar` | 3.0.1 | GNU Lesser Public License; The Apache Software License, Version 2.0; Annotations.txt | `6b47ff0a6de0` | LGPL (FindBugs annotations) |
| `lib/caffeine-3.2.4.jar` | 3.2.4 | META-INF/LICENSE | `9d9d2cfd681f` |  |
| `lib/ehcache-3.12.0.jar` | 3.12.0 | The Apache Software License, Version 2.0; The Apache License, Version 2.0; Ehcache.txt | `b8313aeff142` |  |
| `lib/error_prone_annotations-2.50.0.jar` | 2.50.0 | Apache 2.0 | `4667724877f1` |  |
| `lib/failureaccess-1.0.3.jar` | 1.0.3 | META-INF/LICENSE | `cbfc3906b19b` |  |
| `lib/formats/commons-compress-1.28.0.jar` | 1.28.0 | META-INF/LICENSE.txt; META-INF/NOTICE.txt | `e15229452184` |  |
| `lib/formats/commons-io-2.22.0.jar` | 2.22.0 | META-INF/LICENSE.txt; META-INF/NOTICE.txt | `2b9a7b1f726f` |  |
| `lib/formats/commons-validator-1.11.0.jar` | 1.11.0 | META-INF/LICENSE.txt; META-INF/NOTICE.txt | `b7e1dcdc60d5` |  |
| `lib/formats/everit-json-schema-1.14.6.jar` | 1.14.6 | Apache License, Version 2.0 | `3ecc89542762` |  |
| `lib/formats/fastjson2-2.0.61.jar` | 2.0.61 | Apache 2 | `f3ad16c4cd25` |  |
| `lib/formats/handy-uri-templates-2.1.8.jar` | 2.1.8 | The Apache Software License, Version 2.0 | `6b83846f2ff6` |  |
| `lib/formats/json-20260719.jar` | 20260719 | Public Domain; JSON.txt | `c243f45f9590` | licence clause flagged for review; bundled JSON.txt reads "Public Domain." |
| `lib/formats/nom-tam-fits-1.22.0.jar` | 1.22.0 | Public Domain; LICENSE.txt | `d87b502a067b` |  |
| `lib/formats/prettytime-nlp-4.0.6.Final.jar` | 4.0.6.Final | MIT License; iCal4j - License; META-INF/LICENSE; META-INF/LICENSE.txt; META-INF/NOTICE; META-INF/NOTICE.txt; PrettyTime.txt | `5ad9587e3927` |  |
| `lib/formats/stil/auth.jar` | unversioned | STIL.txt | `db533ee2fdf7` |  |
| `lib/formats/stil/fits.jar` | unversioned | STIL.txt | `3ca4063ec683` |  |
| `lib/formats/stil/hapi.jar` | unversioned | STIL.txt | `70e49b2ba427` |  |
| `lib/formats/stil/jcdf.jar` | unversioned | JCDF.txt | `dbc567fbbfd8` |  |
| `lib/formats/stil/table.jar` | unversioned | STIL.txt | `7fe6131c27f5` |  |
| `lib/formats/stil/util.jar` | unversioned | STIL.txt | `e7e37e0d4707` |  |
| `lib/formats/stil/votable.jar` | unversioned | STIL.txt | `926e4b01228f` |  |
| `lib/formats/tika-core-4.0.0.jar` | 4.0.0 | META-INF/LICENSE; META-INF/NOTICE | `8f1b63d40e59` |  |
| `lib/guava-33.6.0-jre.jar` | 33.6.0-jre | META-INF/LICENSE | `dc573e1fca4f` |  |
| `lib/jhv/jhv-natives-linux.jar` | unversioned | ANGLE.txt; SPICE.txt; FFmpeg-Notices.txt; OpenJPEG.txt | `32c3a87718e2` |  |
| `lib/jhv/jhv-natives-macos-arm64.jar` | unversioned | ANGLE.txt; SPICE.txt; FFmpeg-Notices.txt; OpenJPEG.txt | `c54b1f42d444` |  |
| `lib/jhv/jhv-natives-macos.jar` | unversioned | ANGLE.txt; SPICE.txt; FFmpeg-Notices.txt; OpenJPEG.txt | `96a0c387361d` |  |
| `lib/jhv/jhv-natives-windows.jar` | unversioned | ANGLE.txt; SPICE.txt; FFmpeg-Notices.txt; OpenJPEG.txt | `d67351f5c497` |  |
| `lib/jnispice.jar` | unversioned | SPICE.txt | `06aeadaaaafd` |  |
| `lib/joml-1.10.9.jar` | 1.10.9 | JOML.txt | `feca4db85337` |  |
| `lib/jsamp-1.3.9.jar` | 1.3.9 | The Apache License, Version 2.0 | `a0bdf59e4e25` |  |
| `lib/jsofa-20231011.jar` | 20231011 | Modified SOFA Licence; JSOFA.txt | `35ced3bb3303` |  |
| `lib/log/slf4j-api-1.7.36.jar` | 1.7.36 | SLF4J.txt | `d3ef575e3e49` |  |
| `lib/log/slf4j-jdk14-1.7.36.jar` | 1.7.36 | SLF4J.txt | `5bf64690af4e` |  |
| `lib/lwjgl/lwjgl-3.4.3-natives-linux.jar` | 3.4.3 | LWJGL.txt; libffi.txt | `d719e545a6db` |  |
| `lib/lwjgl/lwjgl-3.4.3-natives-macos-arm64.jar` | 3.4.3 | LWJGL.txt; libffi.txt | `0cf1eac3c360` |  |
| `lib/lwjgl/lwjgl-3.4.3-natives-macos.jar` | 3.4.3 | LWJGL.txt; libffi.txt | `77422addd5be` |  |
| `lib/lwjgl/lwjgl-3.4.3-natives-windows.jar` | 3.4.3 | LWJGL.txt; libffi.txt | `19949bca7b78` |  |
| `lib/lwjgl/lwjgl-3.4.3.jar` | 3.4.3 | LWJGL.txt | `46eeca547183` |  |
| `lib/lwjgl/lwjgl-assimp-3.4.3-natives-linux.jar` | 3.4.3 | Assimp.txt; Draco.txt; LWJGL.txt | `ff98e95ad6b8` |  |
| `lib/lwjgl/lwjgl-assimp-3.4.3-natives-macos-arm64.jar` | 3.4.3 | Assimp.txt; Draco.txt; LWJGL.txt | `80e8c9d3ebaa` |  |
| `lib/lwjgl/lwjgl-assimp-3.4.3-natives-macos.jar` | 3.4.3 | Assimp.txt; Draco.txt; LWJGL.txt | `36dd22a9ac67` |  |
| `lib/lwjgl/lwjgl-assimp-3.4.3-natives-windows.jar` | 3.4.3 | Assimp.txt; Draco.txt; LWJGL.txt | `459c624bb694` |  |
| `lib/lwjgl/lwjgl-assimp-3.4.3.jar` | 3.4.3 | LWJGL.txt | `6e42be582e52` |  |
| `lib/lwjgl/lwjgl-egl-3.4.3.jar` | 3.4.3 | LWJGL.txt | `a5736907eaae` |  |
| `lib/lwjgl/lwjgl-jawt-3.4.3.jar` | 3.4.3 | LWJGL.txt | `b8f2d00ecb27` |  |
| `lib/lwjgl/lwjgl-opengles-3.4.3-natives-linux.jar` | 3.4.3 | LWJGL.txt | `90430b8e2253` |  |
| `lib/lwjgl/lwjgl-opengles-3.4.3-natives-macos-arm64.jar` | 3.4.3 | LWJGL.txt | `f576554e82bf` |  |
| `lib/lwjgl/lwjgl-opengles-3.4.3-natives-macos.jar` | 3.4.3 | LWJGL.txt | `dfe5b4007519` |  |
| `lib/lwjgl/lwjgl-opengles-3.4.3-natives-windows.jar` | 3.4.3 | LWJGL.txt | `84fa0ea73084` |  |
| `lib/lwjgl/lwjgl-opengles-3.4.3.jar` | 3.4.3 | LWJGL.txt | `b04d19ca16c1` |  |
| `lib/lwjgl/lwjgl-stb-3.4.3-natives-linux.jar` | 3.4.3 | LWJGL.txt; stb.txt | `5c66a2f11b18` |  |
| `lib/lwjgl/lwjgl-stb-3.4.3-natives-macos-arm64.jar` | 3.4.3 | LWJGL.txt; stb.txt | `c038b8f58621` |  |
| `lib/lwjgl/lwjgl-stb-3.4.3-natives-macos.jar` | 3.4.3 | LWJGL.txt; stb.txt | `d9df3af9177a` |  |
| `lib/lwjgl/lwjgl-stb-3.4.3-natives-windows.jar` | 3.4.3 | LWJGL.txt; stb.txt | `28dc585f78f5` |  |
| `lib/lwjgl/lwjgl-stb-3.4.3.jar` | 3.4.3 | LWJGL.txt | `544753f5e49c` |  |
| `lib/sqlite/sqlite-jdbc-3.53.0.0-natives-linux.jar` | 3.53.0.0 | The Apache Software License, Version 2.0; SQLite.txt | `f9f8ea7d00b9` |  |
| `lib/sqlite/sqlite-jdbc-3.53.0.0-natives-macos.jar` | 3.53.0.0 | The Apache Software License, Version 2.0; SQLite.txt | `739db1a8557b` |  |
| `lib/sqlite/sqlite-jdbc-3.53.0.0-natives-windows.jar` | 3.53.0.0 | The Apache Software License, Version 2.0; SQLite.txt | `e2d146773f9c` |  |
| `lib/sqlite/sqlite-jdbc-3.53.0.0-without-natives.jar` | 3.53.0.0 | The Apache Software License, Version 2.0; META-INF/maven/org.xerial/sqlite-jdbc/LICENSE; META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus | `8098b34191dd` |  |
| `lib/square/kotlin-stdlib-2.4.10.jar` | 2.4.10 | Kotlin.txt | `4ec0293bc375` |  |
| `lib/square/logging-interceptor-5.4.0.jar` | 5.4.0 | OkHttp.txt | `44abfdb49fb1` |  |
| `lib/square/okhttp-jvm-5.4.0.jar` | 5.4.0 | OkHttp.txt | `b15e605ca0a4` |  |
| `lib/square/okio-jvm-3.18.1.jar` | 3.18.1 | Okio.txt | `b97b640557a6` |  |
| `lib/ui/flatlaf-3.7.2.jar` | 3.7.2 | META-INF/LICENSE | `20033bb3038a` |  |
| `lib/ui/flatlaf-intellij-themes-3.7.2.jar` | 3.7.2 | META-INF/LICENSE; com/formdev/flatlaf/intellijthemes/themes/Carbon.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Cobalt_2.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Cyan.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/DarkFlatTheme.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/DarkPurple.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Dracula.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Gradianto.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Gray.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Hiberbee.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/HighContrast.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/LightFlatTheme.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/MaterialTheme.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Monocai.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Monokai_Pro.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Solarized.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Spacegray.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/Xcode-Dark.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/arc-themes.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/gruvbox_theme.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/material-theme-ui-lite/Material Theme UI Lite.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/nord.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/one_dark.LICENSE.txt; com/formdev/flatlaf/intellijthemes/themes/vuesion_theme.LICENSE.txt | `454169f6f330` |  |
| `lib/ui/flatlaf-jide-oss-3.7.2.jar` | 3.7.2 | META-INF/LICENSE | `76d1eb85fc2c` |  |
| `lib/ui/jide-oss-3.7.15.jar` | 3.7.15 | JIDE.txt | `df06d5de14eb` |  |

## Regenerating the table

Run from the repository root and replace the table above with the output:

```sh
python3 - <<'EOF'
import json
import re

FLAGS = {
    "lib/annotations-3.0.1.jar": "LGPL (FindBugs annotations)",
    "lib/formats/json-20260719.jar": "licence clause flagged for review; bundled JSON.txt reads \"Public Domain.\"",
}
report = json.load(open("extra/licenses/licenses.json", encoding="utf-8"))
print("| Path | Version | Licence evidence (from licenses.json) | SHA-256 (first 12) | Flag |")
print("|---|---|---|---|---|")
for a in report["artifacts"]:
    m = re.search(r"-(\d[^/]*?)(-natives-[\w-]+|-without-natives)?\.jar$", a["path"])
    version = m.group(1) if m else "unversioned"
    evidence = []
    for d in a["declarations"]:
        if d["source"].count("!") == 1 and d["name"] and d["name"] not in evidence:
            evidence.append(d["name"])
    for s in a["embedded_notices"]:
        if s.count("!") == 1:
            evidence.append(s.split("!", 1)[1])
    evidence += [p.rsplit("/", 1)[1] for p in a["supplemental_notices"] + a["external_notices"]]
    print(f"| `{a['path']}` | {version} | {'; '.join(evidence) or 'none recorded'} | `{a['sha256'][:12]}` | {FLAGS.get(a['path'], '')} |")
EOF
```

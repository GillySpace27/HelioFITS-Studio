#!/usr/bin/env python3

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
GLSL_DIR = REPO_ROOT / "resources" / "glsl"

COMMON_FRAGMENT = GLSL_DIR / "solarCommon.frag"
COMMON_SOLAR_FRAGMENTS = (
    "solarOrtho.frag",
    "solarHpc.frag",
    "solarLati.frag",
    "solarRadialWarp.frag",
    "solarRectWarp.frag",
    "solarRectWarpLati.frag",
    # The helioradial surface mesh's fragment shader. Same prepend as its siblings; it was
    # missing here, so the one shader added with the projection was the one not being checked.
    "warpSurface.frag",
)

# Vertex-stage splice, the mirror of the fragment prepend above. GLSLShader._init inserts
# warpCommon.vert AFTER the #version line (a #version directive must come first), which is why
# these cannot simply be concatenated the way the fragments are. Validating them standalone
# reports undefined warpWorld() calls, which is a fault in the harness rather than the shader.
COMMON_VERTEX = GLSL_DIR / "warpCommon.vert"
COMMON_WARP_VERTEX_SHADERS = (
    "line.vert",
    "point.vert",
    "shape.vert",
)


def shader_stage(path: Path) -> str:
    if path.suffix == ".vert":
        return "vert"
    if path.suffix == ".frag":
        return "frag"
    raise ValueError(f"Unsupported shader extension: {path}")


def validate_shader(glslang: str, path: Path) -> bool:
    try:
        label = str(path.relative_to(REPO_ROOT))
    except ValueError:
        label = path.name

    completed = subprocess.run(
        [glslang, "-S", shader_stage(path), str(path)],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if completed.returncode == 0:
        print(f"ok {label}")
        return True

    print(f"FAILED {label}")
    if completed.stdout:
        print(completed.stdout, end="" if completed.stdout.endswith("\n") else "\n")
    if completed.stderr:
        print(completed.stderr, end="" if completed.stderr.endswith("\n") else "\n")
    return False


def write_combined_solar_fragments(temp_dir: Path) -> list[Path]:
    common = COMMON_FRAGMENT.read_text()
    combined: list[Path] = []
    for fragment in COMMON_SOLAR_FRAGMENTS:
        source = GLSL_DIR / fragment
        target = temp_dir / fragment
        target.write_text(common + source.read_text())
        combined.append(target)
    return combined


def write_combined_warp_vertex_shaders(temp_dir: Path) -> list[Path]:
    """Splice warpCommon.vert in after the #version line, exactly as GLSLShader._init does."""
    common = COMMON_VERTEX.read_text()
    combined: list[Path] = []
    for shader in COMMON_WARP_VERTEX_SHADERS:
        source = (GLSL_DIR / shader).read_text()
        lines = source.split("\n")
        for index, line in enumerate(lines):
            if line.lstrip().startswith("#version"):
                spliced = "\n".join(lines[: index + 1] + [common] + lines[index + 1 :])
                break
        else:
            raise SystemExit(f"{shader}: no #version directive, so the splice point is undefined")
        target = temp_dir / shader
        target.write_text(spliced)
        combined.append(target)
    return combined


def build_shader_list(temp_dir: Path) -> list[Path]:
    common_solar = {GLSL_DIR / fragment for fragment in COMMON_SOLAR_FRAGMENTS}
    common_warp = {GLSL_DIR / shader for shader in COMMON_WARP_VERTEX_SHADERS}
    # The two common files are fragments to splice, never standalone shaders.
    ignored = common_solar | common_warp | {COMMON_FRAGMENT, COMMON_VERTEX}
    standalone = sorted(path for path in GLSL_DIR.iterdir() if path.suffix in {".frag", ".vert"} and path not in ignored)
    return standalone + write_combined_solar_fragments(temp_dir) + write_combined_warp_vertex_shaders(temp_dir)


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate JHV GLSL shader syntax with glslangValidator")
    parser.add_argument("--glslang", default="glslangValidator", help="Path to glslangValidator")
    args = parser.parse_args()

    glslang = shutil.which(args.glslang) if "/" not in args.glslang else args.glslang
    if glslang is None:
        raise SystemExit("glslangValidator not found in PATH")

    with TemporaryDirectory() as directory:
        shaders = build_shader_list(Path(directory))
        ok = True
        for shader in shaders:
            ok = validate_shader(glslang, shader) and ok

    if ok:
        print(f"All {len(shaders)} GLSL shader(s) passed.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())

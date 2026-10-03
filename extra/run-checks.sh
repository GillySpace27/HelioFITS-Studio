#!/bin/sh
# Compile and run every self-check in extra/test, and report a tally.
#
#   ant test              # the normal way in
#   extra/run-checks.sh   # the same thing, if bin/ is already built
#
# There is no test framework here and this does not add one. Each check is a plain class with a
# main() that prints "ok"/"FAIL" lines and exits nonzero if any failed; this script is only the
# thing that compiles them together and runs them one after another, which until now nobody had,
# so a check could sit broken for two commits without anyone finding out. That is exactly how
# PaletteReleaseCheck came to be asserting a contract the code had already moved off.
#
# What runs: every class whose name ends in "Check". The suffix is the whole convention, which
# also settles what does NOT run without a hand-kept skip list, because extra/test also holds
# command-line utilities (JHVMetadataDump, RhefProfile) that want arguments, and probes that need
# a native library built by hand (IOSurfacePbufferProbe). None of those end in Check.
#
# Classpath note: "resources" is on it. Several checks read settings/colors.js, luts/lut-labels.json
# or a SPICE kernel from there, and without it they fail in a way that looks like a broken check
# rather than a broken classpath.
#
# Skips are counted. A check that exits 0 but printed "SKIP" (SKIP: or SKIPPED) proved nothing, so
# it is tallied as skipped, not passed, and named on the last line. Each check's output is kept in
# extra/test-classes/logs/<Name>.log. With CHECKS_STRICT=1 (CI), a skip not listed in
# extra/test/ci-skips.txt ("ClassName reason words", one per line) fails the run.
#
#   extra/run-checks.sh --all   # ant check-all: the checks, then every other offline gate, one
#                               # "check-all: <step>: ok|FAIL|SKIPPED: <reason>" line per step
set -u

ALL=0
[ "${1:-}" = "--all" ] && ALL=1
STRICT="${CHECKS_STRICT:-0}"

cd "$(dirname "$0")/.." || exit 1

if [ ! -d bin ]; then
    echo "bin/ not built. Run 'ant compile' first, or use 'ant test'." >&2
    exit 1
fi

OUT=extra/test-classes
# Separator: a Windows JVM wants ';' between classpath entries, and Git Bash does not rewrite
# this one on the way through, so a colon-joined path arrives as a single meaningless entry and
# every application class goes missing at once. Forward slashes inside the entries are fine on
# all three platforms; only the separator differs.
SEP=':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
esac

CP="bin${SEP}${OUT}${SEP}resources${SEP}$(find lib -name '*.jar' | tr '\n' "$SEP")"

# One JVM for both halves. Ant runs on a Homebrew JDK 26 while a plain shell here finds Temurin
# 25, so compiling with whatever javac is on PATH and running with whatever java is on PATH can
# produce class files the runner cannot load: "class file version 70.0, this JRE recognizes up to
# 69.0". Take JAVA_HOME when it is set, which is how ant invokes this, and PATH otherwise.
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

rm -rf "$OUT"
mkdir -p "$OUT"

# All of them in one javac, deliberately. Four checks call a shared helper (MapMetaDataContainer)
# that is not declared as a dependency anywhere, so compiling file by file fails on those four for
# a reason that has nothing to do with the code under test.
echo "==> compiling checks"
if ! "$JAVAC" -nowarn -cp "$CP" -d "$OUT" extra/test/*.java; then
    echo "!! checks do not compile" >&2
    exit 1
fi

pass=0
fail=0
skip=0
failed=""
skipped=""
mkdir -p "$OUT/logs"

echo "==> running checks"
for class_file in $(find "$OUT" -name '*Check.class' | sort); do
    # strip the output dir and the extension, then dots for slashes: the fully qualified name.
    fqcn=$(echo "$class_file" | sed "s|^$OUT/||; s|\.class$||; s|/|.|g")
    short=${fqcn##*.}
    log="$OUT/logs/$short.log"
    ok=0
    if "$JAVA" -Djava.awt.headless=true -cp "$CP" "$fqcn" > "$log" 2>&1; then
        ok=1
    elif grep -q HeadlessException "$log"; then
        # A few checks build a real window and say so by throwing this. Rather than keep a list of
        # them (which goes stale the first time someone adds another), let the exception be the
        # declaration and give that check the display it asked for. On a machine with no display
        # it fails the same way twice, which is the honest answer there.
        if "$JAVA" -cp "$CP" "$fqcn" > "$log" 2>&1; then
            ok=1
        fi
    fi
    if [ "$ok" -eq 1 ] && grep -q SKIP "$log"; then
        skip=$((skip + 1))
        skipped="$skipped $short"
        printf '  %-38s SKIP\n' "$short"
        grep SKIP "$log" | head -2 | sed 's/^/      /'
    elif [ "$ok" -eq 1 ]; then
        pass=$((pass + 1))
        printf '  %-38s ok\n' "$short"
    else
        fail=$((fail + 1))
        failed="$failed $short"
        printf '  %-38s FAIL\n' "$short"
        sed 's/^/      /' "$log" | grep -E 'FAIL|Exception|Error' | head -5
    fi
done

# Under CHECKS_STRICT=1 a skip must be expected: listed by simple class name in ci-skips.txt.
unlisted=""
if [ "$STRICT" = 1 ]; then
    for name in $skipped; do
        if ! grep -Eq "^$name( |$)" extra/test/ci-skips.txt 2>/dev/null; then
            unlisted="$unlisted $name"
        fi
    done
fi

echo
if [ "$fail" -gt 0 ]; then
    echo "failed:$failed"
fi
if [ -n "$unlisted" ]; then
    echo "skipped without a line in extra/test/ci-skips.txt (CHECKS_STRICT=1):$unlisted"
fi
if [ "$skip" -gt 0 ]; then
    echo "checks: $pass passed, $fail failed, $skip skipped (${skipped# })"
else
    echo "checks: $pass passed, $fail failed, 0 skipped"
fi
checks_ok=1
if [ "$fail" -gt 0 ] || [ -n "$unlisted" ]; then
    checks_ok=0
fi
if [ "$ALL" -eq 0 ]; then
    [ "$checks_ok" -eq 1 ] || exit 1
    exit 0
fi

# ---- ant check-all: every other offline gate, one line per step ----------------------------
PY="${PYTHON:-python3}"
RUFF="$(command -v ruff || { [ -x "$HOME/.local/bin/ruff" ] && echo "$HOME/.local/bin/ruff"; } || true)"
ok_steps=0
bad_steps=0
skip_steps=0

step() { # step <name> <command> [args...]: run it, keep its output, print one line
    name=$1
    shift
    if "$@" > "$OUT/logs/step-$name.log" 2>&1; then
        ok_steps=$((ok_steps + 1))
        echo "check-all: $name: ok"
    else
        bad_steps=$((bad_steps + 1))
        echo "check-all: $name: FAIL"
        tail -15 "$OUT/logs/step-$name.log" | sed 's/^/      /'
    fi
}

skipped_step() { # skipped_step <name> <reason>: a skip is a failure under CHECKS_STRICT=1
    echo "check-all: $1: SKIPPED: $2"
    if [ "$STRICT" = 1 ]; then
        bad_steps=$((bad_steps + 1))
        echo "check-all: $1: FAIL: skipped under CHECKS_STRICT=1"
    else
        skip_steps=$((skip_steps + 1))
    fi
}

unittests() {
    "$PY" -m unittest discover -s extra/ffmpeg &&
        "$PY" -m unittest discover -s extra/angle &&
        "$PY" -m unittest discover -s extra/licenses &&
        { [ ! -f extra/test/test_release_assets.py ] || "$PY" extra/test/test_release_assets.py; }
}

guards() {
    "$PY" extra/ci/guards.py diff --base "${GUARDS_BASE:-origin/master}" &&
        "$PY" extra/ci/guards.py count
}

if [ "$checks_ok" -eq 1 ]; then
    ok_steps=$((ok_steps + 1))
    echo "check-all: checks: ok"
else
    bad_steps=$((bad_steps + 1))
    echo "check-all: checks: FAIL"
fi

# Legacy upstream harnesses (extra/test/run_tests.py). Suites listed under "## Known red" in
# extra/test/LEGACY.md are reported, not run; everything else must pass.
known_red=$(awk -F'|' '/^## Known red/ {on = 1; next} /^## / {on = 0} on && $2 ~ /^ *[a-z0-9]+ *$/ {gsub(/ /, "", $2); print $2}' extra/test/LEGACY.md 2>/dev/null)
suites=""
for suite in maintenance model j2k timelines event; do
    case " $(echo $known_red) " in
        *" $suite "*)
            skip_steps=$((skip_steps + 1))
            echo "check-all: legacy-$suite: SKIPPED: known red, see extra/test/LEGACY.md"
            ;;
        *) suites="$suites $suite" ;;
    esac
done
if [ -n "$suites" ]; then
    step legacy "$PY" extra/test/run_tests.py --no-build $suites
fi

if command -v glslangValidator > /dev/null 2>&1; then
    step shaders "$PY" extra/test/run_tests.py --no-build shaders
else
    skipped_step shaders "glslangValidator not on PATH (brew install glslang; apt-get install glslang-tools)"
fi

step unittest unittests
step ffmpeg "$PY" extra/ffmpeg/update_ffmpeg.py --check
step licences "$PY" extra/licenses/sync_licenses.py --check

if [ -n "$RUFF" ]; then
    step ruff "$RUFF" check .
else
    skipped_step ruff "ruff not on PATH or in ~/.local/bin"
fi

step guards guards

echo
echo "check-all: $ok_steps ok, $bad_steps failed, $skip_steps skipped"
[ "$bad_steps" -eq 0 ] || exit 1

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
set -u

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
failed=""

echo "==> running checks"
for class_file in $(find "$OUT" -name '*Check.class' | sort); do
    # strip the output dir and the extension, then dots for slashes: the fully qualified name.
    fqcn=$(echo "$class_file" | sed "s|^$OUT/||; s|\.class$||; s|/|.|g")
    short=${fqcn##*.}
    ok=0
    if "$JAVA" -Djava.awt.headless=true -cp "$CP" "$fqcn" > /tmp/check.$$ 2>&1; then
        ok=1
    elif grep -q HeadlessException /tmp/check.$$; then
        # A few checks build a real window and say so by throwing this. Rather than keep a list of
        # them (which goes stale the first time someone adds another), let the exception be the
        # declaration and give that check the display it asked for. On a machine with no display
        # it fails the same way twice, which is the honest answer there.
        if "$JAVA" -cp "$CP" "$fqcn" > /tmp/check.$$ 2>&1; then
            ok=1
        fi
    fi
    if [ "$ok" -eq 1 ]; then
        pass=$((pass + 1))
        printf '  %-38s ok\n' "$short"
    else
        fail=$((fail + 1))
        failed="$failed $short"
        printf '  %-38s FAIL\n' "$short"
        sed 's/^/      /' /tmp/check.$$ | grep -E 'FAIL|Exception|Error' | head -5
    fi
    rm -f /tmp/check.$$
done

echo
echo "checks: $pass passed, $fail failed"
if [ "$fail" -gt 0 ]; then
    echo "failed:$failed"
    exit 1
fi

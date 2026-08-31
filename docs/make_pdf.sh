#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "Usage: $(basename "$0") INPUT.md [INPUT.md ...]" >&2
    exit 2
fi

for input in "$@"; do
    if [[ ! -f "$input" ]]; then
        echo "Input file not found: $input" >&2
        exit 1
    fi
done

CCN4_ROOT="${CCN4_ROOT:-$HOME/jhv/ccn4.wiki}"
IMAGE_NAME="ccn4.doc"

if [[ ! -d "$CCN4_ROOT" ]]; then
    echo "CCN4 root not found: $CCN4_ROOT" >&2
    exit 1
fi

backend=local
if command -v docker >/dev/null 2>&1 && docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    backend=docker
else
    for tool in gpp pandoc xelatex; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "$tool not found in PATH and no prebuilt docker image '$IMAGE_NAME' is available" >&2
            exit 1
        fi
    done
fi

build_pdf() (
    input="$1"
    input_dir="$(cd "$(dirname "$input")" && pwd)"
    input_basename="$(basename "$input")"
    input="$input_dir/$input_basename"
    output="$input_dir/${input_basename%.md}.pdf"
    tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/jhv_pdf_XXXXXX")"
    trap 'rm -rf "$tmp_dir"' EXIT

    if [[ "$backend" == docker ]]; then
        cat >"$tmp_dir/wrapper.md" <<EOF
\include{/ccn4.wiki/templates/preamble.md}
\include{/input/$input_basename}
EOF
        docker run --rm \
            -v "$CCN4_ROOT":/ccn4.wiki \
            -v "$input_dir":/input:ro \
            -v "$tmp_dir":/work \
            "$IMAGE_NAME" \
            /bin/sh -lc '
                export PATH=/root/bin:$PATH
                cd /ccn4.wiki/RP
                gpp -x -T /work/wrapper.md | pandoc - \
                    --standalone \
                    --wrap=none \
                    --syntax-highlighting=idiomatic \
                    --number-sections \
                    --toc \
                    --top-level-division=chapter \
                    --resource-path=/input:/ccn4.wiki/RP:/ccn4.wiki \
                    -V classoption=oneside \
                    --template /ccn4.wiki/templates/eisvogel.latex \
                    --pdf-engine=xelatex \
                    -o /work/out.pdf
            '
    else
        cat >"$tmp_dir/wrapper.md" <<EOF
\include{$CCN4_ROOT/templates/preamble.md}
\include{$input}
EOF
        (
            cd "$CCN4_ROOT/RP"
            gpp -x -T "$tmp_dir/wrapper.md" | pandoc - \
                --standalone \
                --wrap=none \
                --syntax-highlighting=idiomatic \
                --number-sections \
                --toc \
                --top-level-division=chapter \
                --resource-path="$input_dir:$CCN4_ROOT/RP:$CCN4_ROOT" \
                -V classoption=oneside \
                --template "$CCN4_ROOT/templates/eisvogel.latex" \
                --pdf-engine=xelatex \
                -o "$tmp_dir/out.pdf"
        )
    fi

    mv "$tmp_dir/out.pdf" "$output"
    echo "Wrote $output"
)

for input in "$@"; do
    build_pdf "$input"
done

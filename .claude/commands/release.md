---
description: Ship a HelioFITS Studio release with release/release.sh, unattended after Gilly's one yes
argument-hint: <version, e.g. 0.9.1> [--skip-intel]
---
Release HelioFITS Studio $ARGUMENTS by following the deploy-studio skill (~/.claude/skills/deploy-studio).

In short: confirm origin/master has VERSION and a changelog heading for the version; run
`release/release.sh <version> --check` and fix or collect every `!!`; get Gilly's written yes naming the tag
("I confirm, deploy Studio v<version>") once, at the start, unless he already gave it; then run
`release/release.sh <version> --confirm v<version>` in the background and ask nothing more. After a failure,
fix the cause and rerun with `--resume`; a fix that needs a change to master stops the run (Gilly merges).
Never set an HFS_*_APPROVED variable and never touch ~/vscode/Website. Gilly can still run it himself in
Terminal.app without `--confirm` and type the tag when asked.

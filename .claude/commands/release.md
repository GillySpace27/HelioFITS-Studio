---
description: Ship a HelioFITS Studio release with release/release.sh (Gilly runs it in Terminal)
argument-hint: <version, e.g. 0.9.1> [--auto-smoke] [--skip-intel]
---
Release HelioFITS Studio $ARGUMENTS.

The privileged steps (Apple upload, tag push, release, site merge) are Gilly's yes and run in his own
Terminal.app via `release/release.sh`, never through this session. Do not run it and do not set any
HFS_*_APPROVED variable.

1. Confirm origin/master has VERSION and a changelog heading for the version (the bump PR is merged).
2. Run `release/release.sh <version> --check` here; it is read-only and prints what would stop a release
   (proxy, notary profile, Intel JDK, parity Python, Friday rule, running Studio). Report each `!!` line.
3. Tell Gilly to open Terminal.app and run exactly:
   `cd <any checkout> && release/release.sh <version> [--auto-smoke]`
   and to type the tag once when asked. After a failure: the same command plus `--resume`.
4. While it runs, do not touch ~/vscode/Website. When it prints `RELEASE v<version> DONE`, update the
   team memory with the outcome.

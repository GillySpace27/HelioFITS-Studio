# release/

One file, and it is a development convenience, not a release pipeline:

- `heliofits-studio-launcher.sh` is the source of the script inside
  `/Applications/HelioFITS Studio.app/Contents/MacOS/heliofits-studio`, a Dock
  tile that rebuilds the working tree and runs it. It hardcodes one source path
  and one Homebrew JDK, so it works on one machine on purpose.

**The actual release pipeline is not in this repository.** It is
`../preview-deploy/deploy_release.sh`, with `package`, `guide`, `publish` and
`notarize` modes; see `../preview-deploy/RELEASING.md`.

`build.xml` and `install4j/` used to live here. They were upstream
JHelioviewer's packaging, they built a `JHelioviewer.jar` from a
`org.helioviewer.jhv.JHelioviewer` class that has not existed since the fork,
and nothing invoked them. Removed 2026-09-12; `git log` has them if a future
Windows or Linux installer wants the install4j media definitions back as a
starting point.

# End-to-end protocol

Reproduces the memory and latency measurements in `docs/performance-plan.md`. It starts the worktree's
`HFStudio.jar` on a throwaway home (never the installed app), loads `req.json` over SAMP, zooms,
records 60 s idle and 60 s scrub JFRs, and saves footprint, heap, histogram, threads and vmmap.

```sh
ant jar
E2E_OUT=/tmp/hfs-e2e-out python3 extra/tools/e2e/protocol.py new "$PWD"
HFS_JVM_EXTRA=-Djhv.binBudgetMB=150 E2E_OUT=/tmp/hfs-e2e-out python3 extra/tools/e2e/protocol.py newcap "$PWD"
```

Notes from the first runs (2026-10-25): the request file needs the key `org.helioviewer.jhv.request.image`;
the default server IAS may time out, so the home sets GSFC; `HFSTUDIO_PREFERRED_WIDTH` and `_HEIGHT`
give a large window; `ant jar` rebuilds the tracked Metal dylib, so do not `git checkout` it before
running the jar. Do not run it while another HFStudio is using the SAMP ports: a second instance
loses its hub and the runs die silently.

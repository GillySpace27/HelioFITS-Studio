# Add a New PUNCH Layer source

## Motivation
NASA's PUNCH mission (launched 2025) images the young solar wind and CMEs across a wide
heliocentric field. Its public data is served from the SDAC archive at
`umbra.nascom.nasa.gov/punch` as native FITS, but there is currently no way to load it in
JHelioviewer. This adds a dedicated source so PUNCH frames can be viewed and composited
alongside the existing AIA/LASCO/etc. layers.

## What this adds
- A **File ▸ New PUNCH Layer…** action opening a dialog (shaped after the existing remote
  sources) that lists the archive's products/coverage and loads selected FITS frames via the
  standard image-loading path.
- `io/PunchClient.java` — queries the PUNCH archive and resolves frame URLs.

## Implementation note
Unlike SOAR (which exposes a TAP/ADQL service), the PUNCH archive publishes plain Apache
directory listings, so `PunchClient` parses those HTML indices to enumerate products and
frames. The dialog pre-fills with the current movie time range. This PR is **data-access
only**: frames load through the normal FITS/WCS path; correct on-sphere placement of the
large-FOV (~45°) L2/L3 mosaics is not separately addressed here.

## Files
- `io/PunchClient.java` (new), `io/PunchStamp.java` (new), `gui/dialog/PunchDialog.java` (new)
- `gui/Actions.java`, `gui/component/MenuBar.java` — wire the menu action
- `layers/selector/ImageLayerOptions.java` — PUNCH-only refresh button (gated)

## Deferred (follow-up)
The POC's running-difference tie-in for PUNCH is intentionally left out to keep this PR to
the data source itself.

## Testing
Builds clean (`ant compile`). **Reviewer note:** load-path verified structurally; an in-app
load of a live PUNCH frame is the remaining smoke test.

## Consistent brightness across frames (anti-strobe)
Each PUNCH frame is its own `URIView`/`FITSImage`, and the raw archive FITS carry no
`HV_DMIN/HV_DMAX`, so every frame self-normalizes to its own sampled percentile range — played
as a movie it strobes. `PunchStamp` (new, PUNCH-only) derives ONE display range the way JHV's
own multi-frame FITS already work: download the set via the existing `NetFileCache`, compute a
robust range from a bounded sample (≤30 frames, 0.5/99.5 percentiles over pooled coarse pixels),
and stamp that `HV_DMIN/HV_DMAX` into a local copy of every frame so the independent decoders
agree. No changes to `FITSImage`/`FITSViewState` or any shared/display code, and no global state.

Trade-off, made explicit in the UI: the stamp must fetch every frame before the layer can
appear (JHV would download them anyway, just lazily), so the load is eager. To avoid looking
hung, the dialog stays open with a `CircularProgressUI` spinner and a "Preparing N/M…" counter
during the fetch, then loads the flicker-free layer. A `>200`-frame selection still prompts for
confirmation, now noting the one-time prepare.

Considered and not taken here: putting the spinner on a layer row instead of the dialog, which
would require creating the layer from the remote URIs first and stamping inside the shared
`ImageLayerLoader` — and the genuinely clean version of that (per-layer FITS normalization in
the decode path: first frame sets the range, all frames reuse it; responsive, helps every FITS
movie) belongs in the shared pipeline as its own change rather than in this data-access PR.

## Per-layer refresh
A small refresh button in the layer options (hidden unless the layer is a PUNCH layer, via
`PunchClient.hasRememberedQuery`) re-queries the archive for new frames in the layer's time
range and loads any as a new layer. This is the one place the PR touches a shared panel
(`ImageLayerOptions`), gated so non-PUNCH layers are unaffected — happy to drop it or move it
if you'd prefer the PR not reach into that panel.

## Open question for the maintainer (please advise before detailed review)
Do you want JHelioviewer to host a **PUNCH-specific** source (this PR's shape), or would you
prefer a **generic remote-archive / directory-listing source** that PUNCH is one configured
instance of? This is a structural decision I'd rather settle with you than present as a
fait accompli — happy to refactor either way.

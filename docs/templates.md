# Templates

A scene template is a starting point: a saved scene (layers, view, filters, playback) with a name,
a description, a thumbnail and a rule for which dates it opens on. Opening one gives an Untitled
session, so the template itself is never written over; Save asks for a new name. (HS-25)

## File format

A template is an ordinary `.jhv` session file. Inside the state object (`org.helioviewer.jhv.state`,
beside `layers`, `imageLayers`, `masterStartTime` and the rest) it carries one more object:

```json
"template": {
  "name": "Sun pizza",
  "description": "Four slices of the corona",
  "time": {"mode": "latest", "span": "PT2H", "lag": "PT0S"},
  "thumbnail": "Sun pizza.png",
  "version": 1
}
```

- `mode` is `latest`, `relative` or `fixed`:
  - `fixed`: the dates the scene was saved with, exactly as a session opens.
  - `latest`: the range ends at the newest data for the scene's layers and starts `span` earlier.
  - `relative`: the range ends `lag` before now and starts `span` earlier.
- `span` and `lag` are ISO 8601 durations (`java.time.Duration.parse`): `PT2H` is two hours,
  `P1D` one day, `PT30M` thirty minutes. Neither may be negative; `latest` and `relative` need a
  span longer than zero. A missing `time` object reads as `fixed`.
- `thumbnail` is the file name of a PNG beside the `.jhv`, 512 pixels on its long side.
- `version` is 1. Readers accept any version; the format only ever gains keys.
- A shared copy (Share Template) also carries `thumbnail_data`: the PNG as base64, so one file
  holds everything.

The object is append-only: a Studio that predates templates ignores the unknown key and opens a
template as a plain session on its saved dates.

## What opening a template does

Before any layer is built (`State.load`, through `TemplateState.prepare`), a `latest` or
`relative` template has every image layer's `APIRequest` and `fitsRequest` start and end, the
master range and the playhead moved to the resolved range. A FITS layer's cached file list is
dropped from the copy in memory, so it re-runs its query over the new range. A layer with only a
file list (a local file, or a Heliad dataset opened from the library) has no query and keeps its
files. Then the window is pointed at its own Untitled session file. The range is kept from the
startup default the way a session's is (`MoviePanel.keepSessionRange`).

The newest data for `latest`: the Helioviewer catalog lists each source's newest frame
(`DataSources.newest`, read from the same listing that fills the New Image Layer tree). The range
ends at the oldest of those newest times across the scene's Helioviewer layers, so a scene that
mixes a prompt archive with a slow one opens where both have frames, and never later than now.
When no layer is in the catalog (FITS archives, or before the listings have loaded) the range ends
now and the archives return what they hold up to now. There is no separate newest-time lookup
per FITS archive: PUNCH has one (`PunchLatest`), but it is a network round trip, not a cheap
lookup, and is not used here.

A template whose time rule does not parse opens on its saved dates, with a warning saying what was
wrong.

## Where templates live

- Built-ins: `resources/templates/` in the application, listed by `resources/templates/templates.txt`.
  They are read-only and listed first.
- Yours: `~/HFStudio/Templates/<name>.jhv` with `<name>.png` beside it. The name is the
  template's name with anything other than letters, digits, spaces, dots, dashes and underscores
  replaced by `_`. A template of yours with a built-in's name is listed in the built-in's place.
- Retired: `~/HFStudio/Templates/attic/`. Nothing is deleted.
- A built-in set as startup is copied to `~/HFStudio/Templates/builtin/`, since startup opens a file.

A session save never writes into `~/HFStudio/Templates/` unless what it writes is a template, so
an autosave cannot turn a template back into a plain session.

## Menu items

| Item | Action id | What it does |
|---|---|---|
| File > New from Template... | `newFromTemplate` | Lists the templates with thumbnail, description, datasets and the range each would open on now. Open loads it here, or in a new window when this session has unsaved changes. |
| File > Save as Template... | `saveAsTemplate` | Asks for a name, a description, a time rule (default Latest data), a span (default the current range's length) and a lag, then writes the template and a thumbnail of the canvas. The thumbnail is taken as Edit > Copy Frame takes its picture, so a screenshot also lands in Exports. |
| Window > Templates... (macOS), File > Templates... (elsewhere) | `templates` | The template manager: one table (thumbnail, name, description, time, datasets, last modified) with Open, Duplicate, Rename, Edit, Set as Startup, Reveal, Share Template, Retire and Refresh Data. |

Set as Startup stores the template's path in the pinned default session setting
(`startup.loadState`, the one File > Set Current Session as Default uses), so each launch opens
the template and works its times out fresh; File > Clear Default Session undoes it.

Refresh Data asks Heliad (`template.refresh`, docs/api.md in the heliad repository) to bring the
library up to the newest frames of every Heliad dataset the template shows, and says what it
queued; open the template again once the downloads finish. Layers from a Helioviewer server are
not Heliad's: the template's time rule already asks them for the newest data when it opens.
`io/TemplateRefresh` installs the hook at launch when a Heliad library is found; without one
the button stays disabled with the tooltip "Needs Heliad".

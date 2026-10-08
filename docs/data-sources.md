# Compatible data sources

Everything HelioFITS Studio can load, and where each one is in the application, so that you can
check whether your data is covered before you install anything.

This list was compiled from the source code on 2026-10-08. Each row names the file that defines
the source. No service was contacted while writing it, so a row says what the application asks
for, not whether the service is up today or what it currently holds. Rows marked **unconfirmed**
could not be settled from the code alone.

To add your own Helioviewer image server or HAPI timeline server, see [sources.md](sources.md).

## Images: Helioviewer JPEG 2000 (New Layer > JPEG 2000)

Helioviewer's browse product: streamed, fast, and 8-bit (scaled at ingest by the server). The
dataset tree is fetched from each server when the application starts, so the exact list of
instruments and wavelengths is whatever the servers publish that day; it is not stored in this
repository.

| Server | Provider | API base | Defined in |
|---|---|---|---|
| ROB | Royal Observatory of Belgium | `https://api.swhv.oma.be/hv_docpage/v2/` | `src/org/helioviewer/jhv/io/DataSources.java` |
| IAS | Institut d'Astrophysique Spatiale | `https://helioviewer-api.ias.u-psud.fr/v2/` | same |
| GSFC | Goddard Space Flight Center | `https://api.helioviewer.org/v2/` | same |

Besides each server's default datasets, the application asks every server to also enable these
observatory groups (`enabledDatasetsV2` in `DataSources.java`): MLSO, TRACE, Hinode, Yohkoh,
STEREO_A, STEREO_B, PROBA2, SOLO, GOES-R, IRIS, GONG, ROB, Kanzelhoehe, RHESSI, GOES and PUNCH.
Which instruments each group contains, and which server carries it, is **unconfirmed** here: it
comes from the live catalog.

How to load: New Layer, choose **JPEG 2000**, then **Choose datasets…** (or File > New JP2 Image
Layer). Several datasets can be added at once.

## Images: native FITS through the VSO (New Layer > FITS (VSO))

Calibrated FITS at full bit depth through the Virtual Solar Observatory's federated broker
(`https://vso.nascom.nasa.gov/cgi-bin/sunpy_vsoi`, `src/org/helioviewer/jhv/io/VsoClient.java`).
Larger files and slower to load than the JPEG 2000 of the same frame. The layer keeps the query,
so it follows the master time range and cadence. The leaves offered are fixed in
`src/org/helioviewer/jhv/gui/component/VsoSelectorPanel.java`:

| Observatory | Instrument / channel | Notes from the code |
|---|---|---|
| SOHO | LASCO C2, LASCO C3 | The VSO's LASCO catalog stops in early 2025 (measured 2026-08-31, per the class comment). For recent LASCO use the NRL route below. |
| SOHO | EIT | |
| SDO | AIA 94, 131, 171, 193, 211, 304, 335, 1600 | One leaf per channel to keep queries small. |
| SDO | HMI | |
| STEREO | SECCHI COR1, COR2, EUVI, HI1, HI2 | |
| Hinode | XRT, EIS | |
| GOES | SUVI 94, 131, 171, 195, 284, 304 | Native L1b FITS served by NOAA; frames with no image in them are dropped automatically. |

The class comment records that each instrument name was checked against the live service when
the leaf was added. MDI, TRACE and SXT are deliberately not listed.

## Images: native FITS from a mission's own archive (New Layer > FITS (native))

For archives the VSO serves badly or not at all. PUNCH, SOAR and ASPIICS are also in the Layers
menu. Buttons are defined in `src/org/helioviewer/jhv/layers/selector/LayersSectionPanel.java`.

| Mission / instrument | Provider and endpoint | What you can choose | Defined in |
|---|---|---|---|
| PUNCH | NASA SDAC, `https://umbra.nascom.nasa.gov/punch` (public, no login) | Level 3, Q, 2, 1 or 0; product code (PTM, CTM, PAM, CAM, PNN, CNN, PFM, CFM, PIM, CIM, PSM, CSM, CQM and any other the archive lists); pipeline version; cadence | `io/PunchClient.java`, `gui/dialog/PunchDialog.java` |
| Solar Orbiter: EUI (FSI 174, FSI 304, HRI 174, HRI Lyman-alpha), PHI (FDT, HRT), Metis, SoloHI | ESA SOAR, `https://soar.esac.esa.int/soar-sl-tap/` | Level L1, L2 or L3; search by time range or by SOOP; cadence and exclusion filters | `io/SoarClient.java`, `gui/dialog/SoarDialog.java` |
| PROBA-3 ASPIICS | P3SC at ROB, `https://p3sc.oma.be/api/` | Level 3 by orbit, as FITS or JPEG 2000; cadence | `gui/dialog/AspiicsDialog.java` |
| SOHO LASCO C2, C3 (level 0.5) | NRL LZ archive, `https://lasco-www.nrl.navy.mil/lz/level_05` | No dialog: uses the master time range and cadence. Monthly background images from NRL are subtracted unless the `display.lascoBackground` setting is `false`. | `io/LascoClient.java`, `io/LascoBackground.java` |
| MLSO KCor and UCoMP (button "MLSO KCor and UCoMP (HAO)"; **only once PR #34 merges**) | HAO MLSO API v1, `http://api.mlso.ucar.edu/v1`. Searching is open; downloading needs an email registered at `https://registration.hao.ucar.edu`. **Not yet tested against the live API.** | Instrument and product as the API lists them; time-range search; cadence; UCoMP wave region (637, 706, 789, 1074 or 1079 nm) | `io/MlsoClient.java`, `gui/dialog/MlsoDialog.java` (in PR #34) |

The SOAR dialog also lists MAG RTN and SWA PAS descriptors; those are CDF files and load as
timelines rather than images.

## Images: synoptic maps (Layers > New Synoptic Layer)

| Product | Provider and endpoint | Choices | Defined in |
|---|---|---|---|
| HMI synoptic maps (magnetogram, continuum) | IAS IDOC, `https://idoc-ssa-prod.ias.u-psud.fr` | forecast, nowcast, temporary or archived | `gui/dialog/SynopticDialog.java` |
| AIA synoptic maps (94, 131, 171, 193, 211, 304, 335, 1600, 1700) | same | same | same |

## Your own files (File > Open Image Layer, or drag onto the window)

The format is decided from the file's content, not only its name (`src/org/helioviewer/jhv/io/DataUri.java`).
The open dialog offers these extensions (`src/org/helioviewer/jhv/io/ExtensionFileFilter.java`):

| Kind | Extensions | Notes |
|---|---|---|
| FITS | `.fits`, `.fts`, `.fit`, `.fz`, and gzipped `.fits.gz`, `.fts.gz`, `.fit.gz` | Several files selected together become one movie layer. |
| JPEG 2000 | `.jp2`, `.jpx` | |
| Plain images | `.png`, `.jpg`, `.jpeg` | A PNG exported by HelioFITS Studio carries its scene; dropping it offers to reopen that scene. |
| Zip of images | `.zip` | Unpacked and loaded as one layer. |
| A folder | (drag and drop) | Loads the image files directly inside it as one layer. |

Other local inputs:

| Kind | Extensions | How to load | Defined in |
|---|---|---|---|
| Session | `.jhv` | File > Open Session, double-click in Finder, or drag onto the window | `ExtensionFileFilter.java` |
| Timeline | `.json`, `.cdf` | Timelines > Open Timeline | `src/org/helioviewer/jhv/timelines/gui/TimelineActions.java` |
| 3-D model | `.gltf`, `.glb` (also gzipped) | File > Open Model Layer | `ExtensionFileFilter.java` |
| Point cloud | `.json`, `.json.gz` (several files become a time series) | Layers > New Point Cloud Layer, or drag onto the window | `gui/Actions.java`, `plugins/pointcloud/` |

The point cloud JSON layout is **unconfirmed** in this list; read `plugins/pointcloud/PointCloudLoader.java`
before preparing files for it.

## Timelines (Timelines > New Timeline)

| Source | Provider and endpoint | Defined in |
|---|---|---|
| HAPI time series | ROB, `https://hapi.swhv.oma.be/SWHV_Timelines/hapi/` (more HAPI servers can be added, see [sources.md](sources.md)) | `io/DataSources.java`, `timelines/band/BandReaderHapi.java` |
| CALLISTO radio spectrogram | ROB Helioviewer server, source id 5000 | `io/APIRequest.java`, `timelines/radio/RadioData.java` |
| Solar Orbiter MAG RTN, SWA PAS | ESA SOAR (CDF), through the SOAR dialog | `gui/dialog/SoarDialog.java` |

Which datasets the ROB HAPI server offers is fetched live and is **unconfirmed** here.

## Events and catalogs (Events panel)

Event types and suppliers come from `resources/settings/SWEK.json`; the request code is in
`src/org/helioviewer/jhv/plugins/swek/sources/`.

| Catalog | Endpoint | Event types |
|---|---|---|
| HEK | `https://www.lmsal.com/hek/her` | Flare (NOAA SWPC, Flare Detective), CME (CACTus), Active Region (NOAA SWPC, SPoCA), Coronal Hole (SPoCA), Sunspot (EGSO SFC), Coronal Dimming, Coronal Wave, Filament (AAFDCC), Filament Eruption, Emerging Flux, Eruption (Eruption Patrol) |
| COMESEP | `http://swhv.oma.be/comesep/comeseprequestapi/getComesep.php` | CACTus, Flaremail, Solar Demon, Drag Based Model, cgft, SEP Forecast, GLE Alert, Geomag24 |
| FHNW | `https://tap.cs.technik.fhnw.ch/__system__/tap/run/tap/sync` | RHESSI Flare List |

## Models and positions

| Data | Provider and endpoint | Defined in |
|---|---|---|
| PFSS coronal field lines | ROB, `https://swhv.oma.be/pfss/` | `plugins/pfss/PfssSettings.java` |
| Spacecraft and planet positions | ROB, `https://swhv.oma.be/position` | `astronomy/PositionLoad.java` |
| Comet catalog and ephemerides | JPL SBDB (`https://ssd-api.jpl.nasa.gov/sbdb_query.api`) and Horizons (`https://ssd.jpl.nasa.gov/api/horizons.api`) | `astronomy/Comets.java` |
| CME cone fits | NASA CCMC DONKI, `https://kauai.ccmc.gsfc.nasa.gov/DONKI/WS/get/CMEAnalysis` | `plugins/pointcloud/DonkiCone.java` |

## Opening a session on another computer

A `.jhv` session records where each layer's data came from, not the data itself
(`src/org/helioviewer/jhv/app/state/SessionOffline.java`). Layers from the remote sources above
are fetched again on the other machine. A layer made from local files records `file:` paths, so
(inferred from that, not tested) it opens elsewhere only if the same files sit at the same paths;
there is no export of local data alongside a session yet.

Paths in the tables above that start with `io/`, `gui/`, `plugins/`, `astronomy/`, `layers/` or
`timelines/` are under `src/org/helioviewer/jhv/`.

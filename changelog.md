
# Revision history

## HFStudio 0.8.2 (pre-release, 2026-09-19)

A new name and icon, and a download for every platform that carries its own Java.

### Name and icon
- Rename the application to HFStudio. HelioFITS Studio was too easily confused with the HelioFITS preview plugin. The repository is now `GillySpace27/HFStudio`, and GitHub forwards the old addresses
- Register with SAMP as `HFStudio`, so a script that finds the application by its old name needs the new one
- A new icon that fills the macOS squircle and reads HFS. macOS 26 had been shrinking the old one onto a grey plate on every fresh install

### Downloads
- A signed and notarized dmg for Intel Macs beside the Apple Silicon one, built from an Intel JDK and tested under Rosetta, not yet on an Intel Mac
- Windows and Linux packages that carry their own Java, attached to the release by CI only once each has started, drawn an image and decoded a JPEG 2000 file with its bundled OpenJPEG. Nobody has used them on real hardware yet, and the Windows build is not code-signed, so SmartScreen warns on first run
- Every package carries a trimmed Java runtime and only its own platform's native libraries, roughly halving it: the Mac app bundle goes from 319 MB to 153 MB

### Fixes
- Changing the filter with two or more image layers selected no longer throws
- When a computer's graphics cannot start (Metal, Direct3D 11 or OpenGL), HFStudio says so in words and says where the log is, instead of showing a stack trace
- The log records which OpenJPEG was loaded, and from where

## HFStudio 0.8.1 (pre-release, 2026-09-18)

Fixes found in use of 0.8.0, mostly on LASCO and PUNCH data, and a clearer layer options panel.

### Coronagraph data
- LASCO frames whose header lost its pointing keep the borrowed roll when a session is restored, not only when the data is first queried; a movie across the 2025-08 C2 gap no longer flips 178° partway through. The borrowed pointing is saved with the session, so a restore needs no extra header reads
- The mask reaches the whole field of wide-field data: masks were capped at 32 R☉, so on a PUNCH mosaic the outer handle blanked the image one step from the top and the inner handle stopped halfway
- The mask slider rescales once a layer's first frame arrives, rather than keeping a 0 to 1 R☉ scale for the session
- Sector direction and opening apply to every selected layer, like the other rows, each keeping its own other half

### Display
- Grid: a dashed solar limb circle that faces the camera from any angle, to show where the Sun is and how big, and a Main grid switch that hides the grid itself (or the helioradial rings and spokes) while leaving the other overlays up
- A layer's options are grouped into sections whose headers say what each one holds and what is changed
- Switching theme no longer crashes from the macOS menu bar, and the playhead strip, the cache dialog's status line and the CACTus Track button follow the new theme without a restart

## HFStudio 0.8.0 (pre-release, 2026-09-16)

The first pre-release under its own name (then HelioFITS Studio), for testing ahead of 1.0. It joins the PUNCH and coronagraph work of the preview builds with upstream JHelioviewer's development through 14 September 2026 in a single line. Every entry from this heading down to the JHelioviewer 5.5.0 heading belongs to this release.

### Consolidation
- Merge upstream JHelioviewer's timeline overhaul (HAPI catalogs, stacked and predefined plots, warning levels), its export refactor with failure reporting, the LWJGL and ANGLE updates, toolbar visibility and timeline maximize controls, and its grid allocation work
- Native LASCO FITS from NRL take their CROTA, so frames recorded while SOHO is rolled are no longer upside down; Helioviewer's pre-rotated LASCO JP2s are left as they are
- LASCO frames whose header lost its pointing borrow it from the nearest frame of either telescope (C2 CROTA is C3's plus 0.732°), and every borrow is logged
- Give PUNCH mosaics an inner occulter radius so their no-data center no longer paints an opaque disk over layers beneath (by @GillySpace27)
- Native PUNCH mosaics, which name PUNCH only in OBSRVTRY, get that inner occulter too
- Every product code in the PUNCH dialog explains itself on hover
- Grid labels and viewpoint markers keep their share of the frame in exported movies and images
- The update check reads the released VERSION file; it used to build a malformed address
- Decode JPEG 2000 with OpenJPEG instead of Kakadu, whose non-commercial licence does not reach a fork: the JPIP cache, the codestream rebuild, the box reader, the image source and the decoder are all new, and a fully delivered frame decodes pixel for pixel identically to the same frame's file
- Frame a newly opened dataset the way Reset View does, with View > Reset View for New Layers to turn it off
- Rename the application's threads, default export name, icon and launch scripts to HelioFITS Studio, and move files that belonged to upstream JHelioviewer or to the preview builds into `archive/`

## JHelioviewer 5.11.0 (pending)

### Display and rendering
- Add glTF/GLB model layers with surfaces, lines, points, textures, transparency, and lighting
- Improve rendering quality, performance, and memory handling
- Use consistent percentile clipping across FITS sequences to reduce brightness flicker, and remove ZScale
- Fix MGN numerical artifacts and refresh queued and difference images when changing filters

### Interaction and UI
- Improve mouse interaction and allow holding X, Y, or Z to override the default rotation axis (normally Y) in Rotate Axis mode
- Allow multiple dataset selections in the New Image Layer and New Timeline Layer dialogs
- Add general and timeline interaction guides to the Help menu
- Move FITS clipping and scaling controls into image-layer options, with SAMP settings supplied through layer `imageParams` instead of the global FITS command

### Timeline and events
- Allow additional HAPI servers to be configured by user in `sources.json`, alongside image API servers
- Allow HAPI timelines to be loaded at full time resolution and significantly improve timeline loading and drawing performance
- Improve SWEK event loading, filtering, display, and related-event handling, with more reliable updates

### Technical
- Document the heliocentric 3D data interface and add a COCONUT conversion example
- Improve JPIP movie download throughput, cache memory handling, cancellation, and JPEG 2000 resource cleanup
- Accelerate Rice-compressed 16-bit FITS decoding
- Expand rendering, WCS, JPIP retrieval and cache restoration, and Callisto decoding regression coverage
- Update bundled libraries
- Various bug fixes, cleanups, and internal refactoring

## JHelioviewer 5.10.0 (pending)

### Display and rendering
- Add annotation color and line-thickness controls, and draw the active annotation thicker instead of forcing it to red (fixes #156)
- Improve thick-line joins, rectangle corners, annotation loops, and FOV outline rendering
- Improve flat-grid stability and grid label formatting
- Adjust trajectory colors for white canvas (fixes #260)
- Add a `New PUNCH Layer` source that loads FITS frames from the PUNCH archive at `umbra.nascom.nasa.gov/punch` (by @GillySpace27)
- Add `RHEF` radial histogram equalizing filter with an Upsilon midtone control (by @GillySpace27)
- Add `RadialWarp` (circular) and `RectWarp` (angle versus solar distance)
  wide-field views with λ-controlled outer-corona compression while keeping
  the solar disk linear (λ = 1 linear, 0 logarithmic, −1 strongest), replacing
  the redundant Polar and LogPolar projections (by @GillySpace27)
- Add grid line color, opacity and width controls to the grid layer (by @GillySpace27)
- Show the application's own icon in the macOS Dock and the Windows taskbar instead of the generic Java icon
- Add `Track CME`: animate the warp lambda so a CACTus CME front stays at a fixed screen radius while the corona rubber-bands around it; engage from a CACTus event dialog or the Track picker, disengage by moving the lambda slider or leaving the warp projections
- Add a SWEK option to extend CACTus CME wedges past the LASCO catalog edge, out to the loaded field of view
- Add a cadence control and a large-download confirmation to the ASPIICS layer dialog
- Add a double-ended radial mask (inner disk and outer corona) to the layer options
- Add a per-layer refresh button to check the PUNCH archive for new frames
- Pin a multi-frame FITS layer to one shared display range so a PUNCH movie does not strobe as each frame auto-normalizes
- Improve FITS WCS interpretation of angular units, including surface maps,
  and full `PCi_j` and `CDi_j` linear transformations
- Add image-layer controls for outer-radius masking and for sector direction and opening
- Improve image canvas positioning and resizing on macOS

### Timeline, events, and UI
- Add a stacked timeline mode with independent vertical scales, scrolling when
  the plots no longer fit vertically, and a control to maximize and restore the
  timeline panel
- Expand HAPI timelines with datasets from multiple catalogs, predefined plots
  from HAPI metadata, and catalog/group browsing in the New Timeline Layer dialog
- Support HAPI-defined bar plots, value-level colors, warning thresholds, and
  switching between multicolor and single-color rendering
- Add frame-count sampling and use the selected sampling setting when adding,
  replacing, and synchronizing image layers
- Add a Movie menu command and `Cmd-R` shortcut to start or stop recording
- Add controls to collapse the sidebar and hide the status bar, the toolbar, or the toolbar text
- Show the original image URI in the Image Information dialog
- Improve HEK event handling by mapping Flare Trigger events to Flare events
  (fixes #105) and fixing CACTus event loading (fixes #190)
- Improve SWEK supplier filtering and configuration, loading, paging, storage,
  cancellation, indexing, and highlight dispatch
- Disable COMESEP event source
- Improve layer-table update/repaint behavior when layer names or metadata change

### Performance and data
- Offload and cache viewpoint orbit-trail preparation, and offload PFSS
  coordinate preparation, from the rendering thread
- Reduce allocations in status panels, grid labels, FITS scaling, time maps, and interpolation paths
- Update bundled libraries, FFmpeg (9.0.1), SPICE kernels, and supporting build tools

### Technical
- Reorganize core packages and simplify application, GUI, event, image, movie, thread, and metadata internals
- Clean up viewpoint position loading, map-scale ownership, FOV shape emission, and SWEK APIs
- Various bug fixes, cleanups, and internal refactoring

# Earlier releases, as JHelioviewer

## JHelioviewer 5.5.0 (2026-06-01)

### Display and rendering
- Add `ARC` WCS projection support
- Add option to zoom multiview panels separately
- Improve multiview layouts, flat-grid density, and grid label sizing
- Improve map projection, annotation, SWEK, and connection rendering performance

### Image loading and data
- Load user color lookup tables from `$HOME/JHelioviewer-SWHV/Settings/user-luts.txt`
- Harden FITS metadata handling, WCS parsing, LUT restoration, BLANK/BZERO/BSCALE handling, and clipping range behavior
- Speed up FITS loading with faster Rice decompression and normalized pixel-conversion lookup tables
- Improve JPIP/J2K robustness, including socket buffering, premature EOF handling, dropped decode callbacks, and resource cleanup
- Improve image-layer loading ownership and asynchronous decode handling

### Application control and integration
- Set IAS as default image data server, remove the automatic setting, and restore the preference in Settings
- Allow server-like headless operation, including full software rendering (see `extra/samp`)
- Expand SAMP support for image load completion and FITS view settings
- Improve startup, crash reporting, uncaught exception routing, and ANGLE diagnostics

### Timeline, events, and UI
- Improve timeline rendering performance with off-EDT polyline building, cached SWEK events, and reduced repaint churn
- Improve SWEK event insertion and association, related-event lookup, timeline cache, and popup ownership
- Reduce Swing coupling in layer options and layer selector updates

### Technical
- Expand GLSL/WCS validation coverage, including SwiftShader and Electron-backed shader checks
- Rework and tighten display/viewpoint/camera internals
- Various profile-driven optimizations, bug fixes, cleanups, and major internal refactoring

## JHelioviewer 5.0 (2026-05-08)

### Display and rendering
- Render through ANGLE using the native backend renderer of each platform (DirectX11, Metal, OpenGL)
- Replace the JOGL-based rendering path with LWJGL/OpenGL ES infrastructure
- Improve line rendering with shader antialiasing during live display (see `docs/line-rendering-sketch.png`)
- Improve text rendering with a fixed SDF glyph atlas
- Improve grid, FOV, SWEK, PFSS, point, and overlay rendering details
- Use MSAA specifically for frame/movie export quality
- Preserve layers across GL/ANGLE context recreation

### Image loading and playback
- Support arbitrary detector-frame masks, including the bundled EUI occulting mask
- Centralize caching and lifetime management of image-buffers. Move decoded image storage to direct/native buffers for GL upload
  and free them promptly on cache eviction (see `docs/image-buffer-cache.md`)
- Avoid extra heap copies for the common unfiltered image decode paths
- Replace serialized JPIP stream caching with compact segment log files
- Disable JPIP disk caching for the rest of the run after persistent-cache write/commit failures

### Application control and integration
- Restructure viewer, playback, recording, and state changes around explicit application commands
- Expose playback, recording, load-state, view, seek, and camera commands through SAMP (see `docs/samp-commands.md`)
- Add completion notifications for command-driven state loads and recordings

### Interaction and UI
- Decouple AWT input events from core application input handling
- Make transient cache paths ASCII-safe on Windows

### Technical
- Update bundled libraries, native rendering libraries, and supporting tools
- Various bug fixes, cleanups, and major internal refactoring

## JHelioviewer 4.8.1 (2026-04-19)

- Make possible to restrict playback range: drag with Option/Alt in frame slider to modify range, drag with Command/Ctrl to move range
- Various bug fixes, cleanups, and significant internal refactoring

## JHelioviewer 4.8.0 (2026-04-01)

### Display and interaction
- Add helioprojective Cartesian (`HPC`) projection mode
- Add "Observer at 1au" viewpoint option
- Add "New Synoptic Layer..." dialog for loading HMI and AIA synchronous synoptic FITS maps from <https://idoc-ssa-prod.ias.u-psud.fr/synopticmaps/s050a/portal/>
- Improve flat-grid rendering, labels, and position readout in non-orthographic modes
- Improve zoom, mouse interaction, and several toolbar/macOS interaction details, including `1:1` sizing in flat modes

### Image and datasets
- Add `AZP` and `ZPN` WCS projections support
- Add `CRL*-CAR` and `CRL*-CEA` WCS surface-map support
- Add more bundled colormaps

### Technical
- Add Astropy-based WCS validation tooling, test data, and documentation (see `docs/wcs-validation`)
- Various bug fixes, cleanups, and internal refactoring

### Caveats
- Display of heliospheric imaging data in `Orthographic` mode may not be ideal
- `HPC` mode is fundamentally linked to the observer viewpoint, the general Viewpoint functionality of JHV is not supported
- `CAR` and `CEA` datasets are supported only for `Latitudinal` and `Orthographic` modes

## JHelioviewer 4.7.7 (2026-03-03)

- Various bug fixes and performance improvements

## JHelioviewer 4.7.6 (2026-02-20)

- Improve display of events
- Improve startup time
- Various bug fixes and stability improvements

## JHelioviewer 4.7.5 (2026-02-10)

- Various bug fixes and stability improvements

## JHelioviewer 4.7.4 (2026-02-05)

- Make strength of radial enhancement configurable
- Add ability to manually set the FITS data clipping range and to adjust the ZScale contrast
- Load user SPICE kernels from `$HOME/JHelioviewer-SWHV/kernels`
- Load user server settings from `$HOME/JHelioviewer-SWHV/Settings/sources.json`
- Draw light timeline panel for light UI theme

## JHelioviewer 4.7.3 (2025-07-01)

- Improve performance on ARM Macs
- Add a light color UI theme
- Allow adjustment of image pointing
- Apply PV2_1 distortion for AZP and ZPN projections
- CCOR-1 dataset available from GSFC server

## JHelioviewer 4.7.2 (2025-06-11)

- Add option of ZScale clipping algorithm for FITS data
- Add logarithmic scaling for FITS data
- Add a subtle dither to reduce color banding of smooth gradient image data

## JHelioviewer 4.7.1 (2025-03-12)

- Add wavelet-optimized whitening (WOW, <https://doi.org/10.1051/0004-6361/202245345>) image enhancement
- Adjust the parameters of MGN image enhancement to bring it closer to WOW results

## JHelioviewer 4.7 (2024-07-17)

- Load timelines from new HAPI ROB server
- Support RHESSI datasets
- Add menu item to reload the list of datasets
- Allow to copy timestamp from image layer
- Switch to Java 21

## JHelioviewer 4.6.4 (2024-05-06)

- Attempt to detect image file formats and reduce dependency on file names extensions
- Allow loading of images by pasting files or their locations
- Use the possible colormap information from PNG files
- Switch to JetBrains Runtime; correct pixel factor for Linux HiDPI

## JHelioviewer 4.6.3 (2024-04-22)

- Make possible to adjust the FITS pixel conversion gamma parameter. Offer an alternative conversion controlled by beta. Use menu View -> FITS Settings
- Improve the metadata of the exported movies

## JHelioviewer 4.6.2 (2024-03-21)

- Fix Windows image canvas size bug
- Load zip files of images
- No need to restart after changing global image display settings

## JHelioviewer 4.6.1 (2024-02-08)

- Fix colormap bug

## JHelioviewer 4.6 (2024-02-07)

- New ROB server

## JHelioviewer 4.5.5 (2023-11-20)

- Avert a potential crash related to external screens

## JHelioviewer 4.5.4 (2023-11-20)

- Load PHI, Metis and SoloHI datasets from SOAR

## JHelioviewer 4.5.3 (2023-11-14)

- Add image inner masking
- Allow loading GONG images from all servers
- Set default image server by computer IP location (IAS for Europe, GSFC for rest of the world)

## JHelioviewer 4.5.2 (2023-08-14)

- Add line annotation
- Make Connection layer visible in released versions

## JHelioviewer 4.5.1 (2023-06-19)

- Toolbar button for automatic image layers refresh to current data every 15 minutes
- Allow loading IRIS SJI images from servers
- Ability to query SOAR by SOOP

## JHelioviewer 4.5 (2023-05-10)

- Capability for SSL connection to JPIP movie streaming server, required for GSFC server
- Up to six layers in multiview

## JHelioviewer 4.4.2 (2023-04-13)

- Allow loading of SOLO/EUI and GOES-R/SUVI images from all servers

## JHelioviewer 4.4.1 (2023-03-10)

- Updates and bugfixes

## JHelioviewer 4.4 (2022-12-20)

- Switch to Java 19
- Add macOS ARM64 (Apple Silicon) as supported computer architecture

## JHelioviewer 4.3.3 (2022-10-19)

- Improve metadata display to include comments and history
- Add toolbar menu to rotate view 90˚ around the axes
- Delay timelines in terms of time and not propagation speed

## JHelioviewer 4.3.2 (2022-05-11)

- Linux: switch to OpenJRE, GNOME HiDPI users will need to pass the pixel factor as argument at program start, e.g., `jhelioviewer -J-Dsun.java2d.uiScale=2.0`
- Add more export resolutions
- Load VOTable from SOAR
- Add menu option to show the current log
- Always playback at high resolution

## JHelioviewer 4.3.1 (2022-01-18)

- Windows: split program directory:
    - Cache and Downloads to `C:\Users\$USER\AppData\Local\Temp\JHelioviewer-SWHV\`
    - Exports and rest to `C:\Users\$USER\JHelioviewer-SWHV\`

## JHelioviewer 4.3 (2022-01-14)

- Overhaul logging system and remove log4j
- Switch to a dark theme
- Windows: move program directory to `C:\Users\$USER\AppData\Local\Temp\JHelioviewer-SWHV\` for better compatibility with non-ASCII user names

## JHelioviewer 4.2 (2021-12-10)

- Load EUI, MAG and SWA datasets from SOAR
- Add CDF file format support
- Allow drag'n'drop of directories
- Add preference option to adjust the image timestamp to be:
    - observed time minus light time from Sun center
    - observed time minus light time from Sun center plus light time to Earth

## JHelioviewer 4.1.1 (2021-10-30)

- Use SOLO heliospheric reference frames

## JHelioviewer 4.1 (2021-10-29)

- Indicate diameter of circle annotation
- Indicate height above sphere of loop annotation top
- Indicate pixel coordinates of the decoded image under the mouse pointer
- Add ESAC as source server
- Add preference option to playback at high resolution
- Double-click to reset sliders to default
- Add toolbar button to reset camera axis
- Allow playback without image layers loaded (within selected time interval)
- Switch to Java 17

## JHelioviewer 4.0.2 (2021-06-11)

- Add multi-scale Gaussian normalization (MGN, <https://arxiv.org/abs/1403.6613>) image enhancement

## JHelioviewer 4.0.1 (2021-05-31)

- Load SOLO/EUI, GOES-R/SUVI images from ROB server
- Allow to sync the current image layer time interval to the other layers
- Allow drag'n'drop of image files
- Use FlatLaf look'n'feel
- Bug fixes

## JHelioviewer 4.0 (2021-03-29)

### Technical
- Switch from NewtCanvasAWT to GLCanvas (full screen is lost)
- Switch to OpenGL 3.3
- Switch to Java 11
- Rework handling of threads throughout the program
- Use install4j for packaging installation
- Separate native libraries bundling per operating system
- Change video export to use FFmpeg and disk buffering
- Support pixel scale (HiDPI) in Windows 10 ([#75](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/75>),[#76](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/76>))
- Support pixel scale (HiDPI) in Linux
- Support fractional pixel scale

### User interface
- Move plugins options to preferences
- Non-modal annotations (use shift+click), add loop annotation
- Simplify datetime selection and use NLP for input time parsing
- Support setting playback speed in time period per second
- Allow customization of grid type in latitudinal projection ([#99](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/99>))
- Add preference setting for several video export qualities of H.264 and H.265, as well as series of PNGs ([#26](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/26>),[#44](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/44>),[#45](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/45>))
- Add white background view option
- Allow setting a state file to load at start-up

### Datasets
- Play sequence of files as movie ([#119](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/119>))
- Incorporate SPICE and use it for input [time parsing](<https://naif.jpl.nasa.gov/pub/naif/toolkit_docs/C/cspice/str2et_c.html>) and position calculations for planets
- Use SPICE for the calculations of internal reference frames
- Draw spiral in heliosphere through the trajectory of the highlighted object
- Draw field-of-views of some SOLO, STEREO-A, SDO, and PROBA-2 remote sensing instruments with ability to "off-point"; draw borders of visible hemisphere, center meridian, and the great circle perpendicular on the central meridian
- Optionally distort displayed images according to solar differential rotation (Snodgrass, magnetic features)
- Read Helioviewer metadata from JPG and PNG files
- Load sequences of files as movie from the "jhv.load.image" SAMP message, example at <https://github.com/Helioviewer-Project/samp4jhv/blob/master/examples/python/samp_multi.py>
- Update AIA degradation correction
- Support IRIS SJI
- Support KCor dataset ([#114](<https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues/114>))
- Support Solar Orbiter remote sensing datasets (EUI, PHI, Metis, SoloHI)
- Support Hi-C 1 and 2.1 datasets
- Support GOES-R/SUVI datasets

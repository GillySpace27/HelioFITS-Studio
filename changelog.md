
# Revision history

## HelioFITS Studio 0.8.7 (unreleased)

### New Layer
- New Layer is a button again, not a dropdown: it opens one window with a tab for each source. HV (JPEG 2000) streams Helioviewer's browse images, VSO (FITS) queries the Virtual Solar Observatory, Direct (FITS) opens a mission's own archive (PUNCH, SOAR, ASPIICS, MLSO) or loads LASCO from NRL, and Heliad (Local FITS Cache) lists what the Heliad app has already downloaded. The window has its own time range and time step (or frame count) at the top, which every tab uses. They start as the master ones beside New Layer each time it opens, and changing them affects only the layer being added, never the master range; PUNCH, SOAR and MLSO open on that range, PUNCH on that time step too. It opens on the tab used last. Layers > New Image Layer... (Cmd-N) opens the same window
- Before any FITS download (VSO, PUNCH, SOAR, ASPIICS, MLSO or LASCO), Studio looks in the Heliad library for the same dataset over the same range. If it is there, it offers Heliad's copy, with Download Anyway and Cancel beside it; with no Heliad library, or nothing matching, the download goes ahead as before. Helioviewer's JPEG 2000 is not checked, since Heliad keeps FITS
- Open Heliad, at the bottom of the window when the Heliad app is installed, starts it to manage the library and download into it
- The Heliad tab lists Spacecraft, Instrument, Detector, Wavelength, Product, Level and Cadence as columns of their own, each sortable. Cadence is the median gap between files, so gaps in the data do not inflate it (a polarization sequence reads as its spacing: COR1 shows about 20 s). Columns..., or a right-click on the column titles, shows and hides columns, remembered between runs; the combined Dataset name starts hidden

### Display Settings
- View > Display Settings (beside Theme) makes the app easier to read, two ways. Text size is a slider that resizes the text as it moves, with the layouts following; small labels, readouts and section headers follow it too. Under it, Headings, Body and Detail set one level at a time against it. All four run from 50% to 300% in 5% steps on one shared scale, printed once under the last, so section titles can be made large while the small labels and readouts stay small. Every slider applies as it moves. The window stays open beside the app, so sections can be unfolded and the app used while sizes change; Revert puts the sizes back as they were when it opened, and Done or closing keeps them. Interface scale (50% to 200%) changes everything together, text, spacing and icons, so nothing changes proportion, and applies when the app next starts; Restart Now does that at once, saving the session and reopening it. Neither scales the image itself

### Multiview
- Each view zooms separately by default. View > Separate Multiview Zoom is on for new installs and is now remembered between runs
- Zoom to Fit in Multiview fits each view to its own layer, and Reset View does the same when the views zoom separately. With Separate Multiview Zoom off it works as before. In Helioradial each view zooms until its layer's field reaches the edge, through the warp as drawn, so a SUVI view magnifies onto the disk while a PUNCH view stays wide. Orthographic without a crop fits each layer's frame; the other projections frame the same way whatever the layer
- Turning Multiview on fits every view, so each one starts filled
- View > Zoom to Fit New Layers in Multiview (on by default): a layer added while in Multiview is fitted to its own view, and the other views keep their zoom. It needs Separate Multiview Zoom

### Compare
- A Compare palette on the toolbar (Tools lists it too): pick layers A and B, then Blink between them at 0.5 to 8 Hz, or Swipe a vertical divider across every viewport with A on one side and B on the other. Neither layer is switched off or changed, and Off stops the blinking. The choices are saved with the session, never the blink phase
- Compare's Difference shows A minus B in the files' own units, each layer's stretch undone first, in place of A with B hidden; grey is no difference, and the note under the buttons gives the value white and black stand for. It refuses, and says why, when A and B cannot mean the same thing: no physical units, RHEF or another filter on, a running or base difference of their own, different instruments or units (BUNIT), or an asinh or log stretch (choose Gamma). A different size, product or time is said, not refused. The layer readout badges it [Compare: A minus B], a view, not a calibrated product. Neither layer changes, and Off restores both
- Layers > Duplicate Layer (also in the palette) adds a copy of the selected image layer, so RHEF on against the same data raw is one click

### Welcome
- The Welcome window has an Open Demo card, also Help > Open Demo Scene: a new session with the bundled SDO/AIA 171 sample image and RHEF on. Nothing downloads, so it opens offline. Start New Session's question comes first when there is work to lose
- The Welcome window shows a tip under the start cards, a different one each time it opens
- The Welcome window has Settings... and Display Settings... buttons beside Open Change Log. Display Settings opened from there moves the Welcome window aside while it is open, so the change can be seen on the app

### Help
- Help > Tours lists every guided tour. A second tour, RHEF and filters, shows the Filters palette, the Filter row in a layer's Intensity options, how to read an RHEF image (a visualization, not a calibrated radiance) and how to find a filter by name. On an empty canvas it loads the bundled AIA 171 sample first, as Getting started does

### Layers
- Each layer's readout opens with what it shows, such as "EUV 171 Å (Fe IX, log T 5.8)" for AIA, "white-light total brightness (includes the F corona)" for LASCO C2 and C3, or the PUNCH product ("total brightness, average mosaic, Level 3 (background-subtracted)"). Every label comes from a sourced instrument note; anything without one, such as SUVI, EUI, WISPR, ASPIICS and CCOR, reads "unknown". The Image Information window carries the same line in its title
- Badges after it flag what changes how to read the picture: RHEF: visualization, running or base difference, provisional LASCO (the frame's monthly background could not be fetched) and quickPUNCH. Hover the readout for what each one means

### Playback
- The Clock row has an Achieved box, off by default. Ticked, it shows the frame rate playback actually reaches over the last two seconds, beside the Play rate it asks for, so a RHEF movie can be timed against the same movie without it. The FPS at the bottom left counts canvas draws, which is not the same thing

### RHEF controls
- The Υ button beside the Filter list is greyed, not hidden, when the filter is not RHEF, and its tooltip says to choose RHEF to use it
- Its sliders are named Shadows (Υ low) and Highlights (Υ high), and a screen reader announces them by those names
- A small curve beside the sliders draws the shape they give RHEF output, the same two-sided curve the renderer applies
- A Look menu sets both sliders at once: the defaults, or Neutral (1.00 / 1.00, a straight line)
- Hold to compare shows the layer without RHEF while it is held, for that layer only; nothing is saved, and a session saved meanwhile still records RHEF

### Animation
- An animated control's lane in the Timeline panel has a band of its own above the plot, so it no longer draws over the HEK events or the layer coverage rows. The panel grows by the band when a control is animated
- Selecting an animated control's row lists its keys with their time (UTC), value and easing, each editable by typing; a typed time is taken exactly, not snapped to a frame. Add key puts one at the playhead and Delete key removes the selected one

### Heliad
- When Heliad (the separate app that downloads solar data and keeps it in one library) has a library on this computer, the New Image Layer dialog gets a second tab, Heliad library. It lists Heliad's datasets with how much of the master time range each one reaches, marked as in Load from Cache, and how many frames fall inside the range. Add loads each selected dataset as a layer from its frames in the range; a dataset with nothing in the range loads whole, asking first above 1,000 frames. Open in Heliad, shown when the Heliad app is installed, hands the range to it to search for more. With no Heliad library the dialog is unchanged, and Studio's own downloads and streaming are untouched
- When Heliad is on this computer, every FITS file Studio downloads is also added to the Heliad library. Studio caches and loads the file exactly as before, and without Heliad nothing changes

### Sharing
- File > Quick Share records the scene once as a looping movie with the Share anywhere preset and shows the file in Finder (the file manager elsewhere). It lands in Exports under the usual time-stamped name; your own recording mode, size, format and preset are left as they were
- Edit > Copy Frame puts the current view on the clipboard as an image
- File > Share Scene... saves the session as a .jhv for someone else, with your home folder written as ~. Local files outside the cache are listed first so you can decide whether to go ahead, and the open session keeps its own name
- Load from Cache has Quick Look (macOS), which previews the selected files, and Dataset Folders, which builds `~/HFStudio/Datasets`: one folder per dataset with a link to each cached frame, for browsing in Finder or a shell. Only links are written; the cache is never touched. File > Show Dataset Folders opens it directly; on Windows, where links need Developer Mode, each folder gets a files.txt listing the cached files instead
- On a Mac, Finder lists HelioFITS Studio as the app for .jhv sessions, and as a second choice for FITS files

### Send to Python
- Layers > Send to Python, also the Python button on the layer panel, writes the active layer's FITS files to `Exports/python-<time>` as science FITS: physical values in double precision with only BZERO and BSCALE applied (BLANK becomes NaN), the original header kept and the session added as HISTORY, none of the display steps. A manifest.json and a sunpy snippet come with them, and the snippet goes on the clipboard
- With a SAMP hub running, each file is also sent as image.load.fits, so astropy or another SAMP tool can pick it up; `extra/samp/receive_fits.py` is a ready receiver. The message at the end says how many SAMP clients received the files, or that none was listening
- A LASCO frame has its bias (the OFFSET header keyword) subtracted, as the viewer does, and the manifest says so; it also warns that the exposure division and the monthly background are not applied. A LASCO frame with no OFFSET card is left alone, and the manifest says that too
- One export runs at a time. While it works, the layer's Python button counts the files ("Exporting 2/5..."), the menu item and the other layers' Python buttons are greyed out, and the activity light in the footer names the export, so a second click no longer writes a second folder and snippet

### PUNCH
- The status bar shows the time of the active PUNCH layer's newest frame and its age, in amber once it is more than a week old
- Layers > PUNCH Latest loads the newest day of the averaged clear mosaic (CAM, Level 3) in the Helioradial projection with RHEF on
- PUNCH Live refresh, in Settings and off by default, adds a Live box to PUNCH layers. Ticked, it checks the archive every 15 minutes and loads new frames as a new layer, never removing old ones; it only checks while the archive's newest day is less than a week old

### Command line
- `-export png -out <file>` writes the current view as one PNG with the session stamped in, then exits, with no reopened session, no autosave and no dialogs
- The help lists every option, double-dash spellings work too, and an unknown option, a missing value or a bad `-export` is reported. The exit code says what happened: 0 done, 1 runtime error, 2 usage error, 3 input not found, 4 export failed

### Export
- A fresh install records with the Share anywhere preset (H.264, 8-bit 4:2:0, keyframes every half second), the one that plays in every player, browser and slide deck. A setting already chosen is kept
- Every MP4 now carries its colour description in the container too, where QuickTime, Keynote and PowerPoint on a Mac read it first. It said "unspecified" before, so an HDR video was not recognised as HLG there and an ordinary one was left to a guessed gamma
- The Presentation preset is now H.264 at a high quality setting, 8-bit 4:2:0, so a movie made for a talk plays from PowerPoint, Keynote and Google Slides on whatever laptop is at the lectern, and in QuickTime, Windows and every browser. It was 10-bit H.265, which a Windows laptop or PowerPoint usually has no decoder for. A preset you saved under that name is yours and is left alone
- H.264 movies larger than 4K (3840x2160, or 3072x3072 square) are scaled down to fit, keeping their shape: a 4096x4096 H.264 file is Level 6.0, which opens in VLC and shows nothing in QuickTime or PowerPoint. H.265 and FFV1 keep the full size
- H.264 and H.265 movies are written in limited (16-235) video range, the convention every player assumes, instead of full range, which some players apply inconsistently. FFV1, PNG and EXR keep every level
- Quick Share records at up to 1920 pixels on the long side, the size every phone, chat preview and slide deck is happy with; your own recording size is left as it is
- The Archive, Dome projection and HDR video presets say in their description which players open them (VLC or ffmpeg for the first two; on Windows, HEVC Video Extensions for the third)
- A movie or PNG made with RHEF on records each RHEF setting it used (an `rhef_stamp` line with the convention, the deviations and the Υ pair) in its provenance, beside the existing caveat

### LASCO
- LASCO frames shown with the monthly background removed no longer keep the camera's bias level (the OFFSET header keyword). Every such frame was too bright by OFFSET / EXPTIME in every pixel. Pictures with RHEF on look the same; linear, gamma and difference views change

### Data sources
- The ASPIICS Kind menu no longer lists every file as a kind of its own. The per-file sequence number in ASPIICS file names (such as 163040060001 or 1630400A0001) was taken as part of the kind, so a 122-frame search offered one "(1 frames)" entry per file. Kinds now differ only by the suffix that sets them apart, such as `v03`

### Projection
- Sliders in the Projection palette that do nothing in the current projection (Warp, Disk, Crop, and Field in the Observer sky box) are hidden instead of greyed out, so they no longer look broken. They come back as soon as you pick a projection that uses them
- The Surface button steps through plane of sky, celestial sphere, then Thomson sphere, so the celestial sphere comes first. Plane of sky is still the default, and saved sessions open unchanged
- When the Viewpoint layer follows a location, the Surface button skips the Thomson sphere (with the same warning) instead of getting stuck, so it always cycles back to plane of sky
- In the observer's sky over Helioradial, choosing the celestial or Thomson sphere now moves the imagery as well as the grid and limb. Before, the picture stayed on the plane of sky while the overlays moved, so the two drifted apart away from the Sun
- A new "Surface from" button, under Surface, picks which observer the sphere is measured from in the observer's sky over Helioradial. Viewpoint, the default, puts every layer on one surface that matches the grid and limb. Image observer puts each layer on the surface of the observer who took it, and the grid and limb follow the active layer. Saved sessions keep the choice; older sessions open with Viewpoint. Before this, the imagery used the image's observer and the overlays used the viewpoint, so the two sat slightly apart whenever those distances differed

### Timelines
- The Timelines toolbar button, and Animate, show the timeline plot when the panel was folded at launch. Before, the panel opened blank, and timeline layers added afterwards were listed but never drawn
- The HEK events row (listed as SWEK Events), and the coverage and radio rows, have an X while shown. It hides the row, as unticking it does; they cannot be deleted, since nothing could add them back
- The events row keeps drawing newly downloaded events after loading one session and then another. Before, it could stop updating until the timeline was panned or zoomed

### Tracking and playback
- The Track CME panel and the comet panel (Tools > Find Comets to Track...) have a Cancel button beside their status line. It stops tracking and puts the warp and crop back as they were before tracking started, even after switching between CMEs, comets or modes. The jump of the playhead and the switch to Helioradial stay
- In the Playback tab, the Preset row and the Format disclosure no longer draw on top of each other

### Smaller fixes
- The Timeline Layers section no longer shows as empty stripes. When the sidebar gave it a pixel less height than it asked for, its New Layer row collapsed to nothing and its list was drawn above its own top, so only the row shading showed (seen in the Sarah Gibson session as timeline layers rendering nothing)
- In Playback and Recording, Format, Pixels and the two checkboxes under them sit in the section as rows of their own, instead of behind a fold-down headed with a summary such as "FFV1, RGB, 16-bit"
- A movie export says how far it has got, in the footer beside the FPS: the frame being recorded (of how many, for one loop), then the frames encoded, then that the file is being written, and nothing once it is done. The activity light keeps spinning until the file is closed; before, it stopped when capture ended, and a long export then showed no sign of life while it encoded
- The layer readout no longer sits in a tall empty box. It keeps its tallest height so it does not jump while frames stream in, but one squeezed layout could pin that at a word per line; the height now starts over when the panel's width changes
- A session reopens on its own master time range. When Helioviewer's list of datasets arrived after the session had loaded, which at startup it often did, the range was reset to the last two days of data
- The packaged Mac app always loads its own Metal library. Started from a folder holding an older copy, it could load that one and fail to open; starting from Finder was not affected

## HelioFITS Studio 0.8.6 (pre-release, 2026-10-11)

### Feedback
- Send Feedback and the Report this... button on error dialogs now reach Gilly. Until reports can be sent directly, the button reads Send by Email...: it opens an email to gilly@nwra.com in your mail program, with your note, the version, the system and the top of the error filled in, and shows the full saved report (and screenshot, if you took one) in Finder or your file manager so you can attach it. Before, a report only waited in `~/HFStudio/Outbox`
- When direct sending goes live, this version picks it up by itself, with no update: Send then delivers the report, and Email Instead... stays as the second choice

### Sessions
- File > Export Session with Data saves the session plus a `.data.zip` beside it holding every local file its image layers read, so a session built on your own data opens on another computer. Archive layers are not packed; they download again there, as before. The zip carries a manifest with a SHA-256 for each file, and both files are written to a temporary name and renamed into place, so an interrupted export never leaves a half-written file under the real name
- Opening a session whose local files are not on this computer takes them from the `.data.zip` beside it, checks every file against its checksum, and unpacks them once under `~/HFStudio/SessionData`. Files still on disk are read where they are. Dropping the `.data.zip` itself on the window opens the session inside it
- A session whose local files are missing, with no archive to supply them, now says how many will not load instead of dropping those layers silently
- A session saved on another computer looks for its local files beside the .jhv and in its .data.zip before giving up, loads the frames it finds, and reports the rest once per layer (how many, one example path, and to export with data) instead of one dialog per file or "Empty list of views"

### Data sources
- The VSO tree offers STEREO SECCHI EUVI as four channels, 171, 195, 284 and 304, instead of one entry that returned a single wavelength. A file name whose time stamp happened to contain a channel number no longer narrows a query to a few frames
- The SOAR dialog has a Variant menu: EUI FSI 174 and 304 name their standard image, short-exposure and disc-occulted products in plain words with a tooltip from the EUI Data Manual, and open on the standard image; "all variants" shows every one
- The ASPIICS dialog explains bt, fe, he, pb and pa in tooltips, and when one product comes back as more than one kind of file it labels each frame with its kind and adds a Kind menu that opens on the kind with the most frames; "all kinds" shows every one

### Overlays and loading
- A fresh install opens with every section of both sidebars collapsed; each section then opens the way it was last left
- The Timestamp overlay can show the layer name after the time (Show layer name, on for new sessions), not only in multiview
- The timestamp moves clear of the miniview when both sit in the top-left corner
- A layer that is downloading shows the megabytes received as well as the frame count, so a slow archive no longer looks frozen, and frames that failed are counted as failed rather than as cached

### Updates
- HelioFITS Studio now says when a newer version has been released. A few seconds after it opens, at most once a day, it asks GitHub's list of releases (a release that is published and has a download for this computer, never one that is only planned), and shows the new version's notes with Download and Install, Later and Skip This Version. Help > Check for Updates asks at any time, including about a skipped version. Settings, "Check for updates at startup", turns the automatic check off; with no network it stays silent
- Download and Install first saves the session and copies the sessions, autosaves, recovery copies and settings to `~/HFStudio/Backups/before-<version>-<time>`. If the save cannot be confirmed, or any copy fails, the update stops and nothing is downloaded. Nothing is moved or deleted
- The new version downloads to the Downloads folder with a progress bar and Cancel, is checked against the size and SHA-256 the release lists, and is then opened: on a Mac the disk image's window, to drag HelioFITS Studio onto Applications; elsewhere the file in the file manager. An Intel Mac gets the Intel disk image, or the cross-platform zip (which needs Java 25) when the release has no Intel image. Quit Now quits the usual way, which saves the session, and the new version reopens it

## HelioFITS Studio 0.8.5 (pre-release, 2026-10-07)

### Starting
- A Welcome window opens on launch with the version, What's New for this version (read from the app itself, no network needed), and four ways to start: Continue, Start Blank, Open Session and Take the Tour. Help > Welcome to HelioFITS Studio reopens it, and a checkbox stops it opening at startup
- Startup can start blank instead of reopening the last session (in the Welcome window, or Settings under the startup state). A default session set from the File menu still opens first. A blank start keeps the last untitled session as "Untitled before blank start <date>" at the top of Open Recent
- Start New Session puts back the scene a fresh install opens with: the projection, the camera mode and the grid, viewpoint and timestamp settings reset too, not only the layer list
- An empty canvas says what to do: drop a FITS or JPEG 2000 file on it, or choose New Layer. The hint never appears in a recording or an export

### Finding things
- Help > Search Controls (Cmd-K, Ctrl-K elsewhere) finds any menu command or named control by its name or words from its tooltip. Enter runs the command, or opens the control's section or palette and points at it with the tour's spotlight
- Dropping a folder on the window opens the image files directly inside it as one layer, as choosing them all in File > Open does (it asks first above 200 files)
- A dropped file that cannot be opened is named in a message that says what can be dropped; before, nothing visible happened
- File > Open and dropping accept the same files, now including .fit, .fz and gzipped .fts and .fit. A drop now also takes .zip, as File > Open already did
- File > Open Exports Folder, and a Show in Finder (Show in Folder elsewhere) button on the movie-ready and file-ready dialogs

### Presentation mode
- Presentation mode draws at the Recording pixel size and scales it to the projector, so the picture on the dome matches a recording
- With a second display, the main window stays on your screen exactly as it is, every control working, and the projector shows a full-screen mirror of its picture. The mirror is read back every frame the projector can paint, at the projector's resolution at most. This replaces the separate presenter window and its small preview
- With a projector that does not report HDR, the picture is drawn at standard brightness on both screens while the mirror is up, since that copy cannot carry HDR; before, everything above white clipped on the projector. Esc brings HDR back on your screen
- A projector or monitor that reports HDR (with High Dynamic Range switched on for it in System Settings > Displays) gets the HDR picture instead: a second Metal layer drawn straight from the canvas, with no 8-bit copy. Both screens then share the smaller of their two headrooms, so neither clips. Any other display keeps the standard-brightness mirror
- Hold the Present button for the output aspect and long side, the size the room sees

### Smaller fixes
- Left and Right step one frame back and forward when the picture has focus, as they already did on the time slider
- Hold Undo or Redo for the whole list of steps, newest first, and jump back or forward several at once
- Playback and Recording > Output has a Canvas box: the live canvas is drawn at the Recording size and scaled to the window, as presentation does, so a small size such as 512 gives a decimated, low-latency canvas
- Double-clicking a layer slider (Opacity, Blend, Sharpen, Enhance, Upsilon, the alignment offsets) or the Projection palette's Warp, Disk and Field returns it to its default, the same value the section's revert uses, not to whatever it was when the panel was built
- The last five error and warning dialogs that lacked it (preset name, movie and layer date ranges, Levels in data units) now offer Report this...
- LASCO sessions record whether reading the headers for pointing finished, so a finished check is not repeated on every reopen and a failed one is tried again. Sessions saved before this with an empty pointing table check once

## HelioFITS Studio 0.8.4 (unreleased)

### Playback
- A movie plays without the mouse over the picture. One layer slower to decode than a movie frame used to hold the whole picture still until something else asked for a redraw, which in practice meant moving the mouse
- Every layer shows every frame. The movie waits on each frame until all layers have drawn it, so a slow layer such as a PUNCH mosaic with RHEF is no longer skipped; a layer that has not answered in two seconds is left behind for that frame so it cannot stop the movie
- Upcoming frames are decoded in parallel ahead of the playhead, so PUNCH with RHEF plays at the movie's own rate once the first frames are in. Decoded frames stay in memory, so a movie replays without decoding again
- Decoded frames may now use up to 40% of the computer's memory (at least 4 GB) instead of a fixed 8 GB. A 4096 x 4096 PUNCH movie of about a thousand frames needs about 31 GB; with 8 GB the cache filled partway through, then threw away frames decoded ahead of the playhead before they were shown, and playback fell to about 4 frames a second
- Frames are decoded ahead in whichever direction the movie is going, so a movie playing backwards or swinging, or a scrub to the left, gets the same lookahead as forward playback
- LASCO monthly backgrounds stay loaded (up to 64 of them) and load independently of one another. Every LASCO frame used to wait behind any background being fetched, and a two-month C2 and C3 session loaded its backgrounds 2,423 times
- PUNCH frames decode several times faster, with every value unchanged: RHEF works directly on the half-float image and sorts and ranks each ring with less work, and PUNCH's compressed tiles are unpacked by the app's own fast decoder. A first pass through a 4096 x 4096 PUNCH movie with RHEF and two LASCO layers now keeps up with 30 frames a second
- A movie plays at the rate set under Play. The clock used to schedule each frame from when the previous one actually fired, so every late frame delayed the rest, and 34 frames a second played at about 30. A Clock switch under Play keeps the old clock (Swing timer) for comparison with the new one (Fixed rate)

### Layers
- A Helioviewer layer longer than 1000 frames loads whole. Helioviewer builds at most 1000 frames per movie and thinned the cadence past that; such a request is now asked for in pieces of at most 900 frames that join into one layer. "Get all" still leaves the cadence to the server. Frame count now goes to 20000
- Levels shows the data values drawn as black and white wherever the layer has them (FITS layers without RHEF or a difference), and clicking them lets you type both. Other layers keep the percentages
- Reset All on an image layer's Layer Options header puts Display, Intensity and Geometry back to their defaults in one click, for every selected layer. The FITS clip and scale stay, as with Intensity's own revert
- The open hand shows only over image-layer rows; overlay and camera rows keep the arrow. Every row still drags to reorder

### Undo and redo
- Edit > Undo and Edit > Redo (Cmd-Z and Shift-Cmd-Z), and Undo and Redo buttons at the left of the toolbar. The menu names the step, such as "Undo Levels change" or "Undo Colormap change"
- A layer's colormap, Levels and other display settings, its filter, showing or hiding it, adding or removing a layer, the grid, the camera's settings, the projection, annotations, and the playback and recording settings can be undone. A drag is one step, taken when the mouse is released, and edits made in quick succession are one step. Up to 50 steps are kept
- Undoing a display change keeps the loaded movie; nothing is downloaded or read again. Undoing the removal of a layer reads its files back from the cache
- Not undone: changing what a layer loads (its time range, cadence or dataset), the master time range, timelines and automation tracks, PFSS, event, point-cloud and model layers, and panning or zooming the view
- Opening a session, Start New Session and Revert to Saved start a new history. While you are typing in a text field, Cmd-Z belongs to the field

### Sidebars
- Dragging either sidebar's edge resizes it. The width changed underneath but the layout did not follow, except when something unrelated happened to refresh it

### Downloaded data
- Files in the download cache (`~/HFStudio/FileCache`) are named after the file they came from, with a short hash before the extension, such as `PUNCH_L3_CAM_20260326062334_v0l_1a2b3c4d5e6f.fits`, so other programs can find and open them. Files cached by earlier versions are renamed the first time they are used again. An earlier version of HelioFITS Studio does not know the new names and downloads such a file again

### Export
- Every export says what made it: the build (version, revision and commit), when, from which data, and the whole scene. Movies carry it in their comment field, PNG frames in a text chunk named hfstudio, EXR frames in an hfstudio attribute beside jhv. Your home folder is written as ~
- Drop an exported PNG back on the window to reopen the scene it was made from. The scene is saved as a new session file beside your others and opened from there; nothing is overwritten
- File > Copy Provenance puts a block on the clipboard naming the build, the data and what to cite
- The timestamp layer's Annotations section has a Credit line, off by default, for talks and shows. With RHEF on it says the picture is not a calibrated radiance
- The About window shows the build as version, revision and commit, and says when it was built from uncommitted changes

### Help
- Help > Take the Tour points out the main controls one at a time: the picture, adding a layer, the layer list and its options, playback, the Timelines pane, Projection, recording, and where to find help. The rest of the window is dimmed while each control is shown. Esc leaves the tour; the arrow keys or Enter step through it
- The first launch offers the tour once, in a small card in the corner of the picture. Not now leaves it in the Help menu

### Feedback
- Help > Send Feedback... sends a problem, a request or a question to the developer from inside the app, with no account and no website. Tick boxes add system info and the last 300 lines of this run's log (on), the current session and a screenshot of the main window (off); Preview shows everything before it is sent, and your home folder is written as ~
- Error, warning and crash dialogs have a Report this... button that opens the same window with the message and, when there is one, the stack trace filled in
- This build has nowhere to send reports yet: they are saved in `~/HFStudio/Outbox`, with Copy to Clipboard and Email Instead offered, and a later version sends them when it starts. A sent report moves to `Outbox/sent`; none is deleted

## HelioFITS Studio 0.8.3 (pre-release, 2026-09-23)

### Multilayer FITS, including PUNCH
- FITS files that hold more than one image now load. PUNCH's polarized mosaics (PAM, PTM) carry two or three images in one file, and earlier builds refused them. Each image is named from the file's own `OBSLAYR` keywords, such as Polar_B and Polar_pB
- The first time such a file is loaded, HelioFITS Studio asks which image to show. Afterwards, the **Image** row in the layer's Display section, just below Color, switches between them. A saved session remembers the choice and does not ask again
- PUNCH files that are already downloaded load the same way, from the cache or from disk
- Known issue: while a layer is still downloading, the dropdowns in its options are hard to open and can close by themselves. Choose the image before starting a long download, or let the download finish first

### Loading
- Multi-frame FITS layers (PUNCH, LASCO, VSO) grow as they download. Each frame joins the movie and the timeline as it arrives, rather than everything appearing at the end, and the playhead stays where it is
- The layer's status says what is happening: Connecting, Restoring from cache, Downloading (frames downloaded and frames read from the cache counted separately, with the transfer rate), and Waiting on host when the archive has sent nothing for five seconds
- At most four frames download at once, so they finish one after another instead of all crawling in together
- Frames that fail to arrive are listed on the layer, and its Refresh button fetches them again
- The frame count sets how many LASCO and SUVI frames are requested from the VSO. It had been stuck near 97

### Display defaults
- FITS clipping defaults to the 0.5 % percentile instead of 0.001 %. The narrower range keeps PUNCH's polarized planes from rendering near white, at the cost of saturating slightly more of a bright coronagraph frame
- The HDR canvas is off until it is turned on, so a picture looks the same on a projector or in a screen share as on the Mac's own display
- Exports default to a square (1:1) frame
- The automatic crop frames the edge of the widest field rather than its corner, so a PUNCH mosaic fills the view (about 227 R☉ rather than 474)

### Projections
- The Crop is a circular cut at every warp setting. It used to renormalize the warp, which made it behave like a second zoom whenever the warp was not linear; now the warp stays fixed by the loaded data and the Crop only decides where the picture stops. A flat Helioradial figure made with both a crop and a non-linear warp will look different from before; with the warp linear nothing changes
- The radial grid lines up with the image under a warp in flat Helioradial, where it had been warped twice

### Comet tracking
- A Track Comet panel beside Track CME lists the comets that could be in the field during the movie, and marks where the chosen one is in each frame. The list follows the movie's time range

### Interface
- The layer options, Display and Intensity sections stay open
- Scrolling a sidebar scrolls it, instead of being caught by whichever slider or dropdown passes under the pointer
- A layer alone in the scene drives the movie, so it plays without first being selected
- The time-sync button sits with the layer's other buttons, beside Refresh
- When nothing loaded is in view, the timeline trims itself to the loaded data
- The movie no longer waits for the mouse when one layer is slow to deliver (the wait is capped at 120 ms), and a new layer's first picture appears as soon as it arrives
- Window > Log shows the log as it is being written. Load from Cache has moved to the Layers menu

### Name
- The application is called HelioFITS Studio again, and installs as `HelioFITS Studio.app`. HFStudio stays its technical name, as in 0.8.0 to 0.8.2: the downloads are still `HFStudio-<version>`, the settings folder is still `~/HFStudio` and SAMP still sees `HFStudio`, so nothing a 0.8.2 user has needs to move
- The repository is `GillySpace27/HelioFITS-Studio`, its original name, and the download page is `gilly.space/heliofits-studio`. Every earlier address forwards: the repository's names since September, and `/hfs`, `/hfstudio`, `/punchstudio`, `/jhv` and their capitalized forms on the site
- Between 2026-09-21 and 2026-09-23 the development builds were called PUNCHStudio. Nothing was released under that name; settings those builds wrote to `~/PUNCHStudio` are carried into `~/HFStudio` if that folder does not exist yet

### Icon
- The icon is the sun-pie iris again, lettered HFS: six wedges of the Sun at six wavelengths around a dark hexagon, an aperture made of solar images. It went to the HelioFITS plugin for a day and came back; HelioFITS keeps its AIA 171 Sun. A ladder of instrument rings was drawn for this release and dropped, because below 64 pixels it read as a grey target
- HFS is set at 128 pixels and above, the application's technical name as its downloads and settings folder carry it. At 64 and below the iris is plain, since letters there only smudge and the six wedges still read as a rosette. `release/iris_plain_1024.png` is the unlettered source art
- The Mac bundle now carries a compiled asset catalog as well as the .icns, because macOS 26 draws a .icns-only application on a grey plate at 16 and 32 pixels whatever its artwork does. The catalog is given the art unmasked, since macOS applies its own shape and insets anything that arrives already shaped
- Windows gets a .ico with the same per-size artwork, so its small icons are not shrunken copies of the large one either

### Fixes
- Restoring a session with LASCO layers no longer re-reads every header. 0.8.1 saved what the pointing probe concluded but only believed it back if it had found something to lend, and a movie clear of the 2025-08 gap has nothing to lend, so the usual case probed again on every restore
- `-state` opens a session and the window now belongs to it, so autosave and quit write back to that file. It used to load the named session but keep saving to whichever session the window remembered from the previous launch, so opening A and quitting wrote A's scene over B

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

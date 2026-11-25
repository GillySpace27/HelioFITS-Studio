# Storage folders

Two folders can be large: the exports (`~/HFStudio/Exports`, movies, screenshots, Send to Python
folders and their `.hfrec` records) and the persistent download cache (`~/HFStudio/FileCache`).
Settings, sessions and the log always stay under `~/HFStudio`.

Settings > Storage chooses another place for either. The choice is kept in
`~/HFStudio/storage.properties` (keys `exports` and `filecache`), a file of its own because the
folders are created before Settings is read. It takes effect at once; `Directories.EXPORTS` and
`Directories.FILECACHE` ask `StorageFolders` on every call.

- **Missing drive.** A chosen folder that is missing or not writable is not fatal: the default folder
  is used for that session, Settings > Storage and the log say so, and the choice is kept for the next
  launch. A link at the default place whose target is missing (the old workaround) also no longer
  stops startup.
- **Move existing files** (`StorageMove`) moves every top-level entry as a unit, so an export stays beside
  its `.hfrec` / `.hfrec.json` and a `python-*` folder keeps its `hfrecord.json`. Cache files keep their
  hashed names. A file is deleted from the source only after the copy has the same length, nothing at
  the destination is overwritten, download temp files (`*.tmp`) stay, and overlapping folders are refused.
- With a FITS library, FITS downloads move into the library (one store, below), so the download
  cache holds only downloads in flight, files that are not FITS, and `library-moves.tsv`, which moves
  with the folder like any other file.
- Check: `extra/test/StorageFoldersCheck.java`.

## One store for FITS downloads

With a FITS library (HelioFITS Core), the library is the one place a downloaded FITS file is kept
(Gilly, 2026-11-07). `LibraryStore` and `FitsCoreIngest.consume`:

- A finished download is handed to the core with `ingest.path` and `consume: true` (pyHelioFITS
  schema 6): the core moves it into its store, or deletes it when the store already has the same
  bytes, and records the URL in `file_sources`. `NetFileCache` then hands out the library's path.
- A FITS file still in `~/HFStudio/FileCache` moves the first time it is asked for, and at launch the
  whole cache moves once per library (`fitscore.cacheMoved` in Settings). Each move is written to
  `FileCache/library-moves.tsv` (cache name, library path, library root), so files whose URL Studio no
  longer knows are found again by their cache name, which is a function of the URL. A line about
  another library root (one used before Choose Folder...) is not followed.
- Lookups need no running core: `library-moves.tsv` first, then `file_sources` in the index, read-only
  (`FitsLibrary.bySource`). A hit whose file is gone is a miss and the file downloads again.
- No library, the library's drive not connected, or a core without `consume`: downloads stay in
  FileCache, as before 0.9.2, and an older core still gets a copy (`offer`).
- Check: `extra/test/LibraryStoreCheck.java` (with `HFCORE_CORE` set it also moves a file through a
  real core).

### Library folder and status (Settings > FITS Library)

- **Choose Folder...** runs `hfcore --library DIR --json init` (`FitsCoreIngest.useLibrary`): it opens
  the library in DIR, or makes one in an empty or new folder, and points HelioFITS Core's config at
  it. A folder holding other files is refused. Core's `location_warnings` (sync service folders,
  Documents or Desktop under iCloud, network filesystems) are shown. The library used before is not
  moved or emptied; the cache then moves into the new library once (`importCacheInBackground`).
- **Status.** The tab names one case and its fix: on; set to a folder that is not there now (connect
  the drive, or choose another folder); none yet with a core (Choose Folder...); no core (Studio
  works without one; the release packages include it: the Mac app since 0.9.1, the Windows and Linux
  packages from 0.9.2 (`release/bundle_core.py`); a build from source needs `HFCORE_CORE`).
- **Made by itself on macOS** (`LibraryStore.autoCreate`): the first FITS download makes
  `~/HelioFITSLibrary` when HelioFITS Core has no config at all, the folder is absent or empty, and a
  core is found. Once per computer (`fitslibrary.autoCreated` in Settings), with one notice.
- Not built yet: **Move library** (moving the files of a library to another place). Today that is:
  quit Studio and the Cache Manager, move the folder, then Choose Folder... at the new place.

## Other installs on this computer

Each install keeps its own home (see `Directories.HOME`), but the FITS library is shared, so only the
index of what was moved into it differs. `io/Siblings` reads, never writes, the other installs found at
`~/HFStudio*`, `~/hfs-rc/*` and the folders listed in `siblings.paths`: their `FileCache/library-moves.tsv`
(so a download another build moved is found, not fetched again, when the library root matches), their
`recentSessions` and `States/` (File > Open Recent > From Other Installs; opened with no save target).
"Import Into This Install" copies their recent sessions and cache locations here, never overwriting.
Settings > Storage turns it off (`siblings.enabled=false`). Check: `extra/test/SiblingsCheck.java`.

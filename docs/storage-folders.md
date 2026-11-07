# Storage folders

## One store for FITS downloads

With a FITS library (HelioFITS Core), the library is the one place a downloaded FITS file is kept
(Gilly, 2026-11-07). `LibraryStore` and `FitsCoreIngest.consume`:

- A finished download is handed to the core with `ingest.path` and `consume: true` (pyHelioFITS
  schema 6): the core moves it into its store, or deletes it when the store already has the same
  bytes, and records the URL in `file_sources`. `NetFileCache` then hands out the library's path.
- A FITS file still in `~/HFStudio/FileCache` moves the first time it is asked for, and at launch the
  whole cache moves once per library (`fitscore.cacheMoved` in Settings). Each move is written to
  `FileCache/library-moves.tsv` (cache name, library path), so files whose URL Studio no longer knows
  are found again by their cache name, which is a function of the URL.
- Lookups need no running core: `library-moves.tsv` first, then `file_sources` in the index, read-only
  (`FitsLibrary.bySource`). A hit whose file is gone is a miss and the file downloads again.
- No library, the library's drive not connected, or a core without `consume`: downloads stay in
  FileCache, as before 0.9.2, and an older core still gets a copy (`offer`).
- Check: `extra/test/LibraryStoreCheck.java` (with `HFCORE_CORE` set it also moves a file through a
  real core).

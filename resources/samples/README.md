# Tour sample images

Bundled with the program and loaded by the Getting Started tour when it starts on an empty canvas
(`src/org/helioviewer/jhv/gui/search/TourSamples.java`). Small, real data with real headers, in
the spirit of sunpy's sample data. Not for science. Add a row for every file, with its source and
the terms it is shared under; `TourSamplesCheck` fails a file without one.

| File | Bytes | Header: TELESCOP / INSTRUME, WAVELNTH, DATE-OBS | Made from | Terms |
|---|---|---|---|---|
| `aia_171_20120831T173411_1024.fits` | 766080 | SDO / AIA_3, 171, 2012-08-31T17:34:11.34 | `extra/test/data/sample.171.fits` (AIA level 1, 4096 x 4096), 4 x 4 mean rebin to 1024 x 1024 int16, RICE compressed, by `python3 extra/tools/make_tour_sample.py extra/test/data/sample.171.fits resources/samples/aia_171_20120831T173411_1024.fits 4` (astropy, numpy). CDELT, CRPIX, X0_MP, Y0_MP and R_SUN scaled; two HISTORY cards say so. | SDO data policy, https://sdo.gsfc.nasa.gov/data/rules.php (read 2026-10-08): SDO images are not copyrighted unless marked, and non-commercial and educational use needs no authorization. Credit: "Courtesy of NASA/SDO and the AIA, EVE, and HMI science teams." Its rule 9 asks that a value-added product be labelled as different from the PI's product (the HISTORY cards) and that the PI be told. |

Header values are read from the file (`astropy.io.fits`, HDU 1); bytes with `ls -l`.

## Demo scene (Help > Open Demo Scene)

Six frames of one moment, 2025-08-14 about 09:00 UTC, each an n x n **block median** (NaN-aware, so
no pixel is dropped and the noise falls) of the original, written by
`python3 extra/tools/make_demo_samples.py RAW_DIR OUT_DIR` (astropy, numpy). CDELT, CRPIX, the
alternate-WCS CDELT/CRPIX (PUNCH), and the pixel-valued keys R_SUN, X0_MP, Y0_MP (AIA) are set from the WCS; ASPIICS X_IO and Y_IO (occulter centre, pixel convention not checked)
are dropped; ASPIICS inf pixels become NaN (they ruin the display clip); two HISTORY cards say so. The date has no Moon in any field of view (lunar elongation
about 109 degrees from Earth at 18:00 UTC, computed with astropy; seen from L1 the Moon is always
about 15 degrees from the anti-Sun direction), every instrument has a frame within 6 minutes of
09:00 UTC (the helioviewer and punch archives, and the P3SC REST API, were queried), and the
frames precede the CACTus westward CMEs listed in HEK from 17:48 UTC. Not for science.

Originals (all fetched 2026-11-10):

| File | Bytes | Header: TELESCOP / INSTRUME, WAVELNTH, DATE-OBS | Made from | Terms |
|---|---|---|---|---|
| `aia_171_20250814T090009_512.fits` | 273600 | SDO/AIA / AIA_3, 171, 2025-08-14T09:00:09.350 | `AIA20250814_0900_0171.fits` from the JSOC synoptic archive, https://jsoc1.stanford.edu/data/aia/synoptic/2025/08/14/H0900/ (level 1.5, 1024 x 1024), 2 x 2 block median. Only the synoptic file was retrievable (JSOC export returned HTTP 500). | SDO data policy, as for the tour sample above. Credit: "Courtesy of NASA/SDO and the AIA, EVE, and HMI science teams." |
| `aia_193_20250814T090004_512.fits` | 282240 | SDO/AIA / AIA_2, 193, 2025-08-14T09:00:04.843 | `AIA20250814_0900_0193.fits` from the JSOC synoptic archive, https://jsoc1.stanford.edu/data/aia/synoptic/2025/08/14/H0900/ (level 1.5, 1024 x 1024), 2 x 2 block median. | As the 171 file. |
| `aspiics_bt_20250814T090012_512.fits` | 391680 | Proba-3 / ASPIICS, 5511, 2025-08-14T09:00:12.368 | `aspiics_bt_l3_20250814T090012_1340803F0006_v03.fits`, P3SC archive, https://p3sc.oma.be/datarepfiles/L3/v03/ (L3 total brightness, 2048 x 2048), 4 x 4 block median. | https://www.sidc.be/proba-3/terms-of-use (read 2026-11-10): "open data policy which means that you can freely use the data". Requires "The ASPIICS data are courtesy of the Proba-3/ASPIICS consortium." Redistribution is not addressed on that page. |
| `lasco_c2_20250814T090005_512.fits` | 239040 | SOHO / LASCO C2, 2025/08/14 | `22999194.fts`, https://lasco-www.nrl.navy.mil/lz/level_05/250814/c2/ (level 0.5, 1024 x 1024), 2 x 2 block median. | LASCO data policy, https://lasco-www.nrl.navy.mil/index.php?p=content/retrieve/retrieve (read 2026-11-10): "completely open and unrestricted". Asks: "The SOHO/LASCO data used here are produced by a consortium of the Naval Research Laboratory (USA), Max-Planck-Institut fuer Aeronomie (Germany), Laboratoire d'Astronomie (France), and the University of Birmingham (UK). SOHO is a project of international cooperation between ESA and NASA." |
| `lasco_c3_20250814T085406_512.fits` | 218880 | SOHO / LASCO C3, 2025/08/14 | `32829445.fts`, https://lasco-www.nrl.navy.mil/lz/level_05/250814/c3/ (level 0.5, 1024 x 1024), 2 x 2 block median. | As C2. |
| `punch_cam_20250814T084800_512.fits` | 221760 | PUNCH 1-2-3-4 / WFI+NFI Mosaic, 530, 2025-08-14T08:48:00.000 | `PUNCH_L3_CAM_20250814084800_v0m.fits`, https://umbra.nascom.nasa.gov/punch/3/CAM/2025/08/14/ (level 3 clear average mosaic, 4096 x 4096; the archive's frames are 32 minutes apart, this is the one before 09:00), 8 x 8 block median. | The file's LICENSE card: CC BY 4.0. Required acknowledgement: "PUNCH is a heliophysics mission to study the corona, solar wind, and space weather as an integrated system, and is part of NASA's Explorers program (Contract 80GSFC14C0014)". |

Header values are read from the written files (`astropy.io.fits`, HDU 1); bytes with `ls -l`.
The six demo files total 1627200 bytes.

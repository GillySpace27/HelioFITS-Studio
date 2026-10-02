# Test data

Every fixture in this folder, what its header says it is, and what reads it. Header values are
quoted as written in each file (primary or compressed-image header); they are labels, not checked
instrument facts. Sizes are bytes (`ls -l`). "Used by" is every tracked file naming the fixture
(`git grep -l <name>`). Add a row with every new fixture.

| File | Bytes | Header: TELESCOP / INSTRUME / DETECTOR, WAVELNTH | Image | Used by |
|---|---|---|---|---|
| `20241224_194245_d4c2A.fts` | 8426880 | STEREO / SECCHI / COR2 | 2048 x 2048, BITPIX 16, DATE-OBS 2024-12-24T19:42:45.009 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `20250622_000831_s4h1A.fts` | 4236480 | STEREO / SECCHI / HI1 | 1024 x 1024, BITPIX 32, DATE-OBS 2025-06-22T00:08:31.005 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `20250622_000851_s4h2A.fts` | 4248000 | STEREO / SECCHI / HI2 | 1024 x 1024, BITPIX 32, DATE-OBS 2025-06-22T00:08:51.004 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `CCOR2_1A_20260902T211514_V00_NC.fits` | 9158400 | CCOR-2 / CCOR2 / 1 | tile-compressed (GZIP_1), 2048 x 1920, ZBITPIX -32, DATE-OBS 2026-09-02T21:15:14.121 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `PUNCH_L3_CAM_20260425001600_v0k.fits` | 23768640 | PUNCH 1-2-3-4 / WFI+NFI Mosaic / (none), 530 | tile-compressed (RICE_1), 4096 x 4096, ZBITPIX -32, DATE-OBS 2026-04-25T00:16:00.000 | `extra/test/FitsPlaneCheck.java`, `extra/test/PunchNameCheck.java`, `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `coconut-corona-scene.glb.gz` | 4523887 | not FITS: a gzipped glTF scene | n/a | no tracked file names it; `extra/examples/create_coconut_scene.py` writes `coconut-corona-scene.glb` here by default |
| `mrzqs260301t2314c2308_169.fits` | 267840 | NSO-GONG / (none) / (none), 676.8 | 360 x 180, BITPIX -32, DATE-OBS 2026-03-01 | `extra/test/wcs/compare_java_metadata_to_validator.py`, `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `psp_L3_wispr_20231227T150508_V1_1211.fits` | 3954240 | (none; OBSRVTRY Parker Solar Probe) / WISPR / 1 | 960 x 1024, BITPIX -32, DATE-OBS 2023-12-27T15:05:18.281 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `psp_L3_wispr_20231227T150704_V1_2222.fits` | 3954240 | (none; OBSRVTRY Parker Solar Probe) / WISPR / 2 | 960 x 1024, BITPIX -32, DATE-OBS 2023-12-27T15:07:14.456 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `sample.171.fits` | 12484800 | SDO / AIA_3 / (none), 171 | tile-compressed (RICE_1), 4096 x 4096, ZBITPIX 16, DATE-OBS 2012-08-31T17:34:11.34 | `extra/test/wcs/compare_java_metadata_to_validator.py`, `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `solo_L2_eui-fsi174-image_20251002T150055171_V00.fits` | 4697280 | SOLO / EUI / FSI, 174 | tile-compressed (RICE_1), 3040 x 3072, ZBITPIX 16, DATE-OBS 2025-10-02T15:00:55.171 | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `sunerf_map.fits` | 2076480 | (none) / (none) / (none) | 720 x 360, BITPIX -64, DATE-OBS 2012-08-30T00:00:00.000 | `extra/test/wcs/compare_java_metadata_to_validator.py`, `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `syn_AIA_171_2026-01-12T00-00-00_f_V3.fits` | 7378560 | SDO / AIA / (none), 171 | 1920 x 960, BITPIX -32, DATE-OBS 2026-01-12T00:00:00.000Z | `.github/workflows/launch.yml`, `.github/workflows/package.yml`, `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |
| `syn_HMI_hmi.m_720s_2026-02-25T00-00-00_a_V1.fits` | 7378560 | SDO / HMI / (none), 6173 | 1920 x 960, BITPIX -32, DATE-OBS 2026-02-25T00:00:00.000Z | `extra/test/wcs/run_jhv_wcs_hpc_validation_suite.py`, `extra/test/wcs/validate_jhv_wcs_with_electron.py`, `docs/wcs-validation/jhv_wcs_hpc_validation_note.md` |

How the columns were made (2026-10-01, at 7671c40d9): bytes with `ls -l extra/test/data`; header
values by reading the first `TELESCOP`, `INSTRUME`, `DETECTOR`, `WAVELNTH`, `OBSRVTRY`, `DATE-OBS`,
`NAXIS1`, `NAXIS2`, `BITPIX`, `ZNAXIS1`, `ZNAXIS2`, `ZBITPIX` and `ZCMPTYPE` cards in each file's
headers (for a tile-compressed file the image keys are in HDU 1); "Used by" with `git grep -l -F <file name> -- . ':!extra/test/data'`.

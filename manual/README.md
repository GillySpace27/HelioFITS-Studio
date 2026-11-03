# The user manual (DOCS-2)

Sphinx with MyST Markdown. Read the Docs builds it from `.readthedocs.yaml` (HTML and PDF per
version); CI and `ant check-all` build the HTML with warnings as errors.

```sh
python3 -m pip install -r manual/requirements.txt
python3 release/build_manual_refs.py                         # regenerate manual/generated/
python3 -m sphinx -W -b html manual manual/_build/html       # HTML
python3 -m sphinx -b latex manual manual/_build/latex && make -C manual/_build/latex   # PDF (needs XeLaTeX)
```

- Chapters (`*.md` here) are written by hand. Name a menu item as
  ``{menuselection}`Layers --> New PUNCH Layer…` ``: `build_manual_refs.py --check` fails on one
  that is not in `docs/actions.md`, so a renamed menu item cannot go stale here.
- `generated/` is written by `release/build_manual_refs.py` from the files the app reads: the command
  list (`docs/actions.md`, `docs/commands.json`), the tours (`resources/data/tours/`), the tips
  (`resources/data/tips.json`) and `changelog.md`. Edit those, then regenerate; never edit
  `generated/` by hand.
- `reference/` pages include files from `docs/` and `README.md` whole, so there is one copy of each.
  Their links to repository files point at GitHub (the `RepoLinks` transform in `conf.py`).
- Figures come from `release/guide_assets/`.
- A changelog bullet's `[docs: ...]` pointer (release/RELEASING.md) names the manual page it changed,
  for example `[docs: manual/loading-data.md]`.

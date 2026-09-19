# Menu figures -- generated, not captured

Each `*.menu` file here is the source for the matching `Docs_menu_*.png` in the
parent folder. **Edit the spec and re-run the renderer; never edit the PNG.**

```bash
python3 tools/render_menu_mock.py --all          # regenerate every figure
python3 tools/render_menu_mock.py --all --check  # also verify the item labels
```

Run from the monorepo root.

## Why these are mock-ups

The QP Scope menu is nested three levels deep (`Acquisition controls`, `PPM`,
`Utilities > Image Quality | Project Tools | Microscope Configuration`).
Documenting that with real screenshots means re-capturing several *nested*
figures -- each one requiring the right cascade to be held open -- every time a
single label moves. That is the kind of chore that gets skipped, which is how
menu figures end up a year out of date.

So these are drawn from a text spec instead. The trade is explicit:

- **Accurate:** item text, ordering, grouping/separators, which items have
  submenus, and which branch is open.
- **Approximate:** fonts, spacing, shading, shadows, and the non-QPSC menus
  (QuIET, Class Distribution) shown for context.

Docs that embed them must say so. `UTILITIES.md` carries the standard note.

`--check` cross-references every label against `menu.*` in `strings.properties`
and the `new Menu("...")` / `new MenuItem("...")` literals in `SetupScope.java`.
It is advisory: labels built at runtime -- the per-modality `PPM` submenu, and
other extensions' menus -- have no literal to match and are reported as
unverified rather than wrong. Four such labels are expected
(`QuPath SCope`, `QuIET`, `Class Distribution`, `PPM`); anything beyond those
four means a spec has drifted from the code.

## Spec format

```
panel Extensions            # starts a cascade column; the title is never drawn
  > QuPath SCope [open]     # '>' has a submenu; [open] = next panel hangs here
  > QuIET                   #                      and this row is highlighted
panel QuPath SCope
  Acquisition Wizard...
  ---                       # separator
  * Bounded Acquisition     # '*' = highlighted (the hovered leaf)
```

Every panel except the last needs exactly one `[open]` item, or the renderer
has nothing to cascade from and will tell you which panel is missing it.

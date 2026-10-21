# Background Profiles

> Menu: Extensions > QP Scope > Utilities > Image Quality > Background Profiles...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

A flat-field background depends on more than the optics. The slide carrier or insert puts the slide at a different height in the light path and under different glass; slides from different scanners differ in thickness and mounting; the slide's orientation under the camera and the mounting medium change the gradient in every tile. One background per objective and camera is not enough.

A **background profile** is one complete set of backgrounds for one sample setup. Keep a profile per carrier, slide source or medium, collect backgrounds into each, and make the right one active before acquiring. Every acquisition records which profile it used.

## Where profiles live

```
<background folder>/
  profiles/
    <profile id>/
      profile.yml
      <detector>/<modality>/<magnification>/[<wb mode> | <acquisition profile>]/
```

The background folder is `background_correction.base_folder` in `imageprocessing_<scope>.yml`, one per microscope. Everything below a profile folder is the usual layout (see [Background Collection](background-collection.md)). Folders directly under the background folder, from before profiles existed, are not read; **Adopt old folders...** moves them into a profile.

`profile.yml`:

```yaml
id: carrier4_aperio
name: 4-slide carrier, Aperio slides
description: Slides scanned on the Aperio GT450, 1 mm glass, in the quad carrier.
created: 2026-10-22T09:14:00
expects:                 # optional; checked before an acquisition, warn only
  insert: quad_v         # a stage.inserts id from the microscope YAML
  slide_insertion: B     # A (label left, as scanned) or B (label right)
attributes:              # free text; recorded, never checked
  carrier: quad_v
  slide_source: Aperio GT450
  medium: Permount
```

## The active profile

The active profile is a QuPath-wide preference (`activeBackgroundProfile`), because it describes what is on the stage now, not a project. It appears as a **Background profile** combo in:

- Bounded Acquisition and Existing Image Acquisition, with the background status for the chosen hardware beside it;
- the Acquisition Wizard's hardware section;
- Background Collection, where it is the profile the new set is filed under.

All of them show and set the same value. **(none)** clears it: acquisitions then run without background correction and say so, and Background Collection refuses to start until a profile is chosen.

When you change the **Insert** in the Stage Map and exactly one profile declares that insert, a small window offers to switch to it. It is not modal and never switches on its own.

## The dialog

| Control | What it does |
|---|---|
| Background folder / Choose... | Shows the base folder; Choose... writes it to the imageprocessing YAML. |
| List | Every profile, the active one in bold. The panel below shows its id, description, expected insert and placement, attributes, creation time, how many sets it holds, and its folder. |
| New... | Id (a folder name: letters, digits, `-`, `_`), name, description, expected insert (the Stage Map's inserts, or type one), slide placement, attributes (one `key: value` per line). The first profile created becomes active. |
| Edit... | The same form, id fixed. |
| Set active | Makes the selected profile the active one. |
| Open folder | Opens the profile folder in the file browser. |
| Delete... | Removes the profile folder and every background in it, after confirmation. |
| Adopt old folders... | Shown only when folders from before profiles exist under the background folder: moves the ticked ones into a profile. |

## What is checked, what is recorded

Before an acquisition locks the stage, the usual background check (lamp level, exposure) also compares the active profile's `expects` with the current setup: the insert selected in the Stage Map and the open image's slide placement. A difference is a warning with **Proceed Anyway / Cancel**, never a block, like every other background mismatch. Nothing in `attributes` is checked.

Recorded:

- the acquisition command carries `--bg-profile <id>` beside `--bg-folder`;
- the project entry gets `background_profile`;
- the stitch record's `[acquisition]` section has `background profile:` and `background folder:` lines;
- each `background_settings.yml` has a `context` block with the profile, the insert and slide placement at collection, the stage position and `collected_at`.

## Tips

- Name profiles after what you would say at the microscope: "quad carrier, Aperio", "single slide, Permount".
- Declare the insert and placement when they matter; the warning is the only thing that stops a wrong profile from going unnoticed.
- One profile per run: a multi-slide batch uses the active profile for every slot.
- After a lamp change or a new white balance calibration, every profile is stale. Re-collect the ones you use.

## See Also

- [Background Collection](background-collection.md) - collecting a set into a profile
- [Bounded Acquisition](bounded-acquisition.md) and [Existing Image Acquisition](existing-image-acquisition.md) - where the active profile is chosen for a run
- [Stage Map](stage-map.md) - the insert the profile is checked against

# Documentation Image Annotation Reference

> **THIS IS A REFERENCE, NOT GROUND TRUTH.**
>
> This file records what the numbered labels / colored brackets on each
> documentation screenshot are *supposed* to point at, so re-annotation is
> faster next time. It is **not** authoritative: as the UI changes, sections
> get added, removed, renamed, or reordered, and both the target list below
> **and** the pixel coordinates in `tools/annotate_screenshots.py` must be
> updated to match. If this file disagrees with the current dialog, the
> current dialog wins -- fix this file as part of the same change.
>
> Last reconciled against the UI: **2026-07-22** (see per-image "Verified"
> notes; unverified rows are carried over from the script and may be stale).

## How the annotations are produced

The numbered brackets and circles are **not** part of the raw screenshot. They
are drawn on afterward by `tools/annotate_screenshots.py`, which reads raw
screenshots from `OtherDocuments/Docs_*.png` and writes annotated copies into
`qupath-extension-qpsc/documentation/images/`.

- Images with a custom `annotate_*()` function get numbered brackets/circles.
  Their pixel coordinates are **hardcoded per image size** and go stale the
  moment a screenshot is re-captured at a different resolution or layout.
- All other `Docs_*.png` are copied through unchanged.

Re-annotate after replacing any raw screenshot:

```bash
cd /home/msnelson/QPSC_Project
python3 tools/annotate_screenshots.py
```

Then **verify placement** (see below) before committing.

## How to check annotation accuracy (agent-assisted)

The coordinates cannot be trusted after a re-capture. The reliable check is
**visual**: open the annotated PNG and confirm each number sits on the section
this file says it should.

A vision-capable agent (e.g. Claude via the `Read` tool on the PNG) can do this
directly -- it reads the rendered image, enumerates the real sections top to
bottom, and reports any number whose bracket/circle does not line up with its
intended target. This already caught live drift on 2026-07-22 (see below). A
practical loop:

1. `Read` the annotated PNG.
2. List the actual sections/regions in the dialog, top to bottom.
3. For each numbered target in this file, confirm the label overlays the right
   section. Flag any that are off, and note the correct Y-band.
4. Adjust the coordinates in `tools/annotate_screenshots.py`, re-run, re-check.

This does not require live hardware -- it works entirely from the committed PNG,
so it can run in CI or a pre-push agent pass.

## Per-image annotation targets

### Docs_ExistingImage_ConsolidatedDialog.png  (`annotate_existing_image_dialog`)
Style: numbered brackets down the left margin.
**Verified 2026-07-22: the committed image is a RAW re-capture with NO brackets,
and the section list in the script is stale (it still says "Advanced Options").**
Current real sections, top to bottom:

| # | Target section | Notes |
|---|----------------|-------|
| 1 | Project & Sample | Sample Name, Project |
| 2 | Hardware Configuration | Modality, Objective, Detector, WB Mode |
| 3 | Alignment Configuration | Use existing / manual; saved transform; confidence |
| 4 | Refinement Options | Proceed without / Single-tile / Full manual |
| 5 | Modality-Specific Options | Autofocus + Modality Options (PPM Polarization Angles, Save as MDA) -- script mislabels this "Advanced Options" |
| 6 | Z-Stack Options | Collapsed section -- new since the script was written |
| 7 | Acquisition Preview | Annotations / Images / Est. time / storage |

Script currently defines only 6 sections and omits Z-Stack Options; add #6/#7
and re-measure all Y-bands against the new capture.

### Docs_BoundedAcquisition.png  (`annotate_bounded_acquisition`)
Style: numbered brackets down the left margin.
**Verified 2026-07-22: brackets are present but MISALIGNED with the current
layout** (e.g. #3 is labeled "Bounding Box Region" in the script but brackets
Hardware Configuration; the collapsible sections shifted everything below
Acquisition Region). Current real sections:

| # | Target section | Notes |
|---|----------------|-------|
| 1 | Project & Sample | Sample Name |
| 2 | Hardware Configuration | Modality, Objective, Detector, WB Mode |
| 3 | Acquisition Region | Center Point + Size / Two Corners; X/Y/W/H; bounds |
| 4 | Collapsible options | Modality-Specific Options, Z-Stack Options, Time-Lapse Options (all collapsed) |
| 5 | Acquisition Preview | Region / FOV / tile grid / images / est. time / storage |

Note: the script's list (Project, Hardware, "Bounding Box Region", "Advanced
Options", Preview) predates the Modality-Specific / Time-Lapse split. Re-measure.

### Docs_AcquisitionWizard.png  (`annotate_acquisition_wizard`)
Style: numbered brackets down the left margin.
**Verified 2026-07-22: the committed image is a RAW re-capture with NO brackets.**
The script's 6-section structure still matches the current dialog; only the
Y-bands need re-measuring against the new capture.

| # | Target section | Notes |
|---|----------------|-------|
| 1 | Hardware Configuration | Modality, Objective, Detector |
| 2 | Server Connection | Checklist row + Connect |
| 3 | White Balance | Checklist row + Calibrate... |
| 4 | Background Correction | Checklist row + Collect... |
| 5 | Microscope Alignment | Checklist row + Align... |
| 6 | Start Acquisition | Bounded Acquisition / Existing Image Acquisition buttons |

(There is also an "Autofocus ... Validate AF" row between #5 and #6; the script
does not number it. Add a target if you want it labeled.)

### Docs_LiveViewer.png  (`annotate_live_viewer`)
Style: numbered circles at region centers + colored highlight boxes.
**NOT re-verified 2026-07-22.** Flagged by the drift check (new XYZ position
overlay toggle, histogram dock toggle, and more controls). The region model is
likely still valid but the box coordinates need re-measuring, and a new toggle
may deserve its own number.

| # | Target region | Notes |
|---|----------------|-------|
| 1 | Toolbar | Live toggle, focus buttons, Display, new XYZ/Hist toggles |
| 2 | Live Image | Central camera view |
| 3 | Stage Control | Right-side panel (now the "Navigate" tab, renamed from "Position") |
| 4 | Histogram & Contrast | Bottom strip |
| 5 | Status Bar | Very bottom |

## Images with no numbered annotation (copied through unchanged)

Everything else in this folder is a plain screenshot with no overlay. If one of
those needs callouts in the future, add an `annotate_*()` function for it and a
row here. Current pass-through images include the alignment, camera control,
communication settings, stage map, white balance, propagation, autofocus,
noise, z-stack, setup wizard, and menu screenshots.

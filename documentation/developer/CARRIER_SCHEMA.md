# Stage carrier schema

This document describes the YAML schema for stage insert ("carrier") configurations
loaded by `StageInsertRegistry` from per-microscope YAML files (under
`microscope_configurations/`). The Stage Map overlay and the experimental
Multi-Slide Existing Image workflow both consume the parsed `StageInsert` model
described here.

The schema is additive over the legacy single-slide/quad-slide configuration. Files
that omit the new fields keep their previous behaviour exactly.

## Top-level structure

```yaml
stage:
  inserts:
    slide_margin_um: 2000          # legacy global; safety margin around samples
    default: quad_v
    configurations:
      <carrier_id>:
        kind: slide_holder          # NEW; one of slide_holder | dish_holder | well_plate
        name: "Display name"
        # 4-point outer-frame calibration (unchanged)
        aperture_left_x_um: ...
        aperture_right_x_um: ...
        slide_top_y_um: ...
        slide_bottom_y_um: ...
        aperture_top_y_um: ...      # optional; used in place of slide_top/bottom if present
        aperture_bottom_y_um: ...   # optional
        # Sample list (replaces num_slides / slide_spacing_mm when present)
        samples:
          - kind: slide | dish | well | well_grid
            ...
```

When `samples:` is absent, the parser uses this precedence:
1. **Per-slot calibration** (captured centers): If any `slideK_center_x_um` / `slideK_center_y_um` fields are present (K = 1, 2, 3, ...), each calibrated slot builds one rectangular SLIDE sample centered on that captured position. Partial calibration (e.g., only slides 1 and 3 captured) is valid — only the calibrated slots are included.
2. **Explicit samples list**: If `samples:` is present, it is used as-is.
3. **Legacy pitch derivation**: Otherwise, the parser falls back to `num_slides` / `slide_spacing_mm`, which produces rectangular SLIDE samples with fixed spacing and no lips.

Per-slot calibration is the highest-precedence method, designed for multi-slot holders where the exact center of each slot has been measured interactively via the Stage Map **Calibrate...** dialog.

## Fields

### `kind` (carrier)

Discriminator for the overall carrier. Defaults to `slide_holder` when omitted.

| Value          | Meaning                                        | Workflow support today      |
|----------------|------------------------------------------------|-----------------------------|
| `slide_holder` | Holds 1..N rectangular slides                  | Overlay + MS workflow       |
| `dish_holder`  | Holds a single petri dish (circular sample)    | Overlay only                |
| `well_plate`   | Holds a regular grid of circular wells         | Overlay only                |

### `samples` (list)

Each entry is one sample (or, for `well_grid`, expands into many). Common fields:

| Field                | Type     | Default       | Meaning                                                    |
|----------------------|----------|---------------|------------------------------------------------------------|
| `kind`               | string   | `slide`       | `slide` / `dish` / `well` / `well_grid`                    |
| `id`                 | string   | auto-id       | Stable id for metadata + UI references                     |
| `label`              | string   | "Sample N"    | Display label rendered on the overlay                      |
| `shape`              | string   | per-kind      | `rectangle` or `circle`; defaults to circle for dish/well  |
| `width_mm`           | number   | (carrier)     | Rectangle width along long axis                            |
| `height_mm`          | number   | (carrier)     | Rectangle height along short axis                          |
| `diameter_mm`        | number   | 0             | Circle diameter (required for circles)                     |
| `rotation_deg`       | number   | 0             | 0 = long axis along stage X; 90 = along stage Y            |
| `center_offset_x_mm` | number   | 0             | Sample center X relative to the insert center              |
| `center_offset_y_mm` | number   | 0             | Sample center Y relative to the insert center              |
| `lip_a_mm`           | number   | 0             | Lip width at end "a" of the long axis (rectangles only)    |
| `lip_b_mm`           | number   | 0             | Lip width at end "b" of the long axis (rectangles only)    |
| `lip_inset_mm`       | number   | 0             | Radial lip inset (circles only)                            |
| `label_anchor`       | string   | `center`      | `center` / `top` / `bottom` / `outside_top` / `outside_bottom` |

#### Lip convention

For rectangle samples the lip is modelled as **a pair of measurable widths along
the slide's long axis**. `lip_a_mm` is the lip nearer end "a" and `lip_b_mm`
nearer end "b". With `rotation_deg = 90` the long axis is along stage Y, so
lip A appears at the top and lip B at the bottom (in the overlay's screen
coordinates). With `rotation_deg = 0` the long axis is along stage X, so lip A
is at the left and lip B is at the right.

For circular samples, `lip_inset_mm` is a radial inset that shrinks the usable
interior radius by the same amount on all sides.

Lipped regions are rendered as a semi-transparent shade over the affected band
or annulus on the overlay, and shrink the legal-zone region used for movement
safety warnings.

### `well_grid` (sugar)

A `well_grid` entry expands into N circular WELL samples arranged in a grid.

| Field                       | Type   | Default                 | Meaning                                    |
|-----------------------------|--------|-------------------------|--------------------------------------------|
| `rows`                      | int    | 2                       | Grid rows                                  |
| `cols`                      | int    | 3                       | Grid columns                               |
| `well_diameter_mm`          | number | 34.8                    | Well diameter (mm)                         |
| `row_spacing_mm`            | number | 39.12                   | Center-to-center spacing between rows      |
| `col_spacing_mm`            | number | 39.12                   | Center-to-center spacing between columns   |
| `a1_center_offset_x_mm`     | number | -col_spacing*(cols-1)/2 | A1 well center X (relative to insert center) |
| `a1_center_offset_y_mm`     | number | -row_spacing*(rows-1)/2 | A1 well center Y                           |
| `row_labels`                | string | "AB..."                 | One character per row, e.g. "AB" or "ABCDEFGH" |
| `col_labels`                | string | "123..."                | One character per column                   |
| `lip_inset_mm`              | number | 0                       | Applied to every well in the grid          |

Defaults match the SBS/ANSI 6-well plate footprint. Override for other plate formats.

### Per-slot calibration fields

When `samples:` is absent, the parser checks for per-slot center calibration. These fields define the absolute stage position (in micrometers) of each slide's center, captured interactively via the Stage Map **Calibrate...** dialog.

| Field                      | Type   | Default | Meaning                                            |
|----------------------------|--------|---------|-----------------------------------------------------|
| `slideK_center_x_um`       | number | —       | Slide K center stage X position (K = 1, 2, 3, ...) |
| `slideK_center_y_um`       | number | —       | Slide K center stage Y position                    |

If both `slideK_center_x_um` and `slideK_center_y_um` are present for a given K, that slot builds one rectangular SLIDE sample centered on the captured position. If either coordinate is missing, the slot is skipped. This allows partial calibration (e.g., calibrating only slots 1 and 3 in a 4-slot holder).

Per-slot calibration takes precedence over the legacy `num_slides` / `slide_spacing_mm` pitch. When per-slot centers are present, the pitch is ignored entirely.

## Examples

### Single slide, horizontal (today's default carrier)

```yaml
single_h:
  kind: slide_holder
  name: "Single Slide (Horizontal)"
  aperture_left_x_um: 5000
  aperture_right_x_um: 60000
  slide_top_y_um: -2000
  slide_bottom_y_um: 23000
  samples:
    - kind: slide
      id: slide_1
      label: "Slide"
      shape: rectangle
      width_mm: 75.0
      height_mm: 25.0
      rotation_deg: 0
      center_offset_x_mm: 0
      center_offset_y_mm: 0
      lip_a_mm: 5.0          # left lip
      lip_b_mm: 5.0          # right lip
      label_anchor: bottom
```

### 4-slide vertical, label at bottom

```yaml
quad_v:
  kind: slide_holder
  name: "4-Slide Holder (Vertical, Label-Bottom)"
  aperture_left_x_um: 0
  aperture_right_x_um: 120000
  slide_top_y_um: 0
  slide_bottom_y_um: 75000
  samples:
    - kind: slide
      id: slide_1
      label: "Slide 1"
      shape: rectangle
      width_mm: 75.0          # long axis
      height_mm: 25.0         # short axis
      rotation_deg: 90        # long axis along stage Y
      center_offset_x_mm: -45  # leftmost column
      center_offset_y_mm: 0
      lip_a_mm: 5.0           # top lip
      lip_b_mm: 8.0           # bottom (label) lip
      label_anchor: bottom
    - kind: slide
      id: slide_2
      label: "Slide 2"
      shape: rectangle
      width_mm: 75.0
      height_mm: 25.0
      rotation_deg: 90
      center_offset_x_mm: -15
      center_offset_y_mm: 0
      lip_a_mm: 5.0
      lip_b_mm: 8.0
      label_anchor: bottom
    - kind: slide
      id: slide_3
      label: "Slide 3"
      shape: rectangle
      width_mm: 75.0
      height_mm: 25.0
      rotation_deg: 90
      center_offset_x_mm: 15
      center_offset_y_mm: 0
      lip_a_mm: 5.0
      lip_b_mm: 8.0
      label_anchor: bottom
    - kind: slide
      id: slide_4
      label: "Slide 4"
      shape: rectangle
      width_mm: 75.0
      height_mm: 25.0
      rotation_deg: 90
      center_offset_x_mm: 45
      center_offset_y_mm: 0
      lip_a_mm: 5.0
      lip_b_mm: 8.0
      label_anchor: bottom
```

### Petri dish (60 mm)

```yaml
petri_60mm:
  kind: dish_holder
  name: "60 mm Petri Dish"
  aperture_left_x_um: 0
  aperture_right_x_um: 70000
  slide_top_y_um: 0
  slide_bottom_y_um: 70000
  samples:
    - kind: dish
      id: dish
      label: "Dish"
      shape: circle
      diameter_mm: 60.0
      center_offset_x_mm: 0
      center_offset_y_mm: 0
      lip_inset_mm: 1.0
      label_anchor: outside_bottom
```

### 6-well plate (SBS standard)

```yaml
plate_6_well:
  kind: well_plate
  name: "6-Well Plate (SBS)"
  aperture_left_x_um: 0
  aperture_right_x_um: 127760
  slide_top_y_um: 0
  slide_bottom_y_um: 85480
  samples:
    - kind: well_grid
      rows: 2
      cols: 3
      well_diameter_mm: 34.8
      row_spacing_mm: 39.12
      col_spacing_mm: 39.12
      row_labels: "AB"
      col_labels: "123"
      lip_inset_mm: 0.5
```

### 4-slide holder with per-slot calibration

When a multi-slot holder is calibrated interactively via the Stage Map **Calibrate...** dialog, the captured center of each slot is stored as `slideK_center_*_um` fields. These override the fixed pitch:

```yaml
quad_v:
  kind: slide_holder
  name: "4-Slide Holder (Vertical, Label-Bottom)"
  aperture_left_x_um: 0
  aperture_right_x_um: 120000
  slide_top_y_um: 0
  slide_bottom_y_um: 75000
  num_slides: 4
  slide_spacing_mm: 30.0          # ignored when per-slot centers are present
  # Per-slot calibration: one pair per slot captured via the Calibrate dialog
  slide1_center_x_um: 20000
  slide1_center_y_um: 37500
  slide2_center_x_um: 50000
  slide2_center_y_um: 37500
  slide3_center_x_um: 80000
  slide3_center_y_um: 37500
  slide4_center_x_um: 100000
  slide4_center_y_um: 37500
```

When `samples:` is absent and per-slot center fields are present, the parser builds one SLIDE sample per slot, each centered on the captured position. The size of each slide (width/height/rotation) is derived from the aperture frame and `slide_width_mm`/`slide_height_mm` fields (if specified) or carrier defaults.

## Java model

| Class | Responsibility |
|---|---|
| `StageInsert` | Carrier-level fields (id, aperture, origin, kind, axis inversion, samples list) |
| `StageInsert.Kind` | Enum: SLIDE_HOLDER / DISH_HOLDER / WELL_PLATE |
| `StageInsert.SlidePosition` | One sample inside a carrier. Contains kind/shape/lip fields; legacy constructor delegates with rectangle defaults |
| `StageInsert.SlidePosition.Kind` | Enum: SLIDE / DISH / WELL |
| `StageInsert.SlidePosition.Shape` | Enum: RECTANGLE / CIRCLE |
| `StageInsertRegistry` | Loads `stage.inserts.configurations` from the active microscope YAML |

The legacy `SlidePosition` constructor is preserved so callers that build carriers
programmatically continue to compile. New code uses the extended constructor.

## What does NOT change

- The 4-point outer-frame calibration (`aperture_left_x_um` / `aperture_right_x_um`
  / `slide_top_y_um` / `slide_bottom_y_um`) is unchanged. All sample geometry
  is interpreted relative to the calibrated insert frame.
- Axis inversion handling (X/Y) is unchanged. Sample positions are stored in
  insert-relative coordinates; the existing inverted-axis logic in
  `containsStagePositionWithMargin` is preserved for rectangles and extended
  with radial hit-tests for circles.
- The legacy `num_slides` / `slide_spacing_mm` fallback is preserved when
  `samples:` is omitted.

## Forward-looking notes

- A fully unattended Multi-Slide workflow ("option 3" in the design discussion)
  is expected to seed per-slide alignment from the calibrated outer frame plus
  each slide's known sample offset. The data model already records both pieces;
  the workflow controller is the part that will need to grow.
- Dish/well-plate **workflow** integration (assignment-style dialogs that target
  individual wells) is deferred. The schema admits them; the workflow does not.

# Parfocality Calibration

> Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Calibrate Parfocality...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Capture the Z offset between acquisition profiles on the same objective so the
stage refocuses automatically when you switch modality. The classic case: on
the same detector, brightfield and LED fluorescence land on different focal
planes (filter cube engagement, lamp vs LED illumination path). Without
parfocality, switching from a focused brightfield view to fluorescence puts
you out of focus -- and a combined brightfield + fluorescence acquisition like
`BF_IF_*` produces blurred channels.

Calibrated offsets live in a sidecar file
`parfocality_<scope>.yml` next to your microscope config; the values override
any inline `parfocal_offset_um` on the main YAML profile entries.

## Prerequisites

- An active microscope connection (the dialog reads the live stage Z).
- A focusable slide on the stage (any sample with clear contrast works -- a
  fluorescent reference or thin section is ideal).
- At least one acquisition profile defined in the active microscope config.

## How parfocality is modeled

Each acquisition profile has one signed Z offset (microns) relative to a
**reference profile** chosen per objective. The reference profile has offset 0
by convention. Switching from profile A to profile B on the same objective
shifts the stage by `offset(B) - offset(A)`.

Only same-objective switches apply a delta -- changing objective is a separate
concern (and will eventually be handled by motorized turret support).

## Workflow

1. **Open the dialog** from `Extensions > QP Scope > Utilities > Microscope
   Configuration > Calibrate Parfocality...`. The dialog is non-modal, so you can interact with the
   Live Viewer while it is open.

2. **Pick objective + reference profile** at the top. The reference combo
   defaults to the first profile of that objective, or whatever you set
   previously.

3. **Capture the reference Z.**
   - In the Live Viewer Camera tab, switch to the reference profile via the
     Modality dropdown.
   - Focus the slide using the Z controls (mouse wheel, arrow buttons, or
     direct entry on the Navigate/Position tab).
   - In the calibration dialog, click **Capture Reference Z**. The reference
     profile's row in the table jumps to offset = 0.00 um.

4. **Capture each other profile's offset.**
   - In the Live Viewer Camera tab, switch to the next profile. The lamp /
     filter cube changes; the Z stage does NOT move yet (no offset is saved
     for this profile -- it is unknown).
   - Refocus the slide.
   - In the calibration dialog, click **Capture** on the row for that profile.
     The dialog reads the current Z and records `offset = currentZ -
     referenceZ`. The row shows the signed delta and is marked "Captured
     (unsaved)".
   - Repeat for every profile on this objective.

5. **Save.** Click **Save** to write the sidecar. The status column for each
   saved row flips to "Saved".

6. **Test.** Switch the Camera tab Modality dropdown between calibrated
   profiles. The stage should move by the calibrated delta each time, keeping
   the sample focused. If a switch lands out of focus, refocus, click Capture
   for that row again, and Save.

7. **Calibrate other objectives** by changing the Objective combo and
   repeating steps 3-6. The dialog merges new values with previously-saved
   offsets so you do not lose work from a different objective when saving.

## Output

The sidecar `parfocality_<scope>.yml` looks like:

```yaml
metadata:
  generated: '2026-05-23T15:42:18.123'
  version: '1.0'
  description: 'Parfocality calibration offsets (relative Z deltas)...'
reference_profile_per_objective:
  LOCI_OBJECTIVE_OLYMPUS_10X_001: Brightfield_10x
offsets:
  Brightfield_10x: 0.0
  Fluorescence_10x: -12.5
  BF_IF_10x: -6.0
```

The file lives next to your main config -- if the config is
`microscope_configurations/config_OWS3.yml`, the sidecar is
`microscope_configurations/parfocality_OWS3.yml`. Hand-editing is fine; the
sidecar is reloaded automatically on the next config reload.

## When the auto-apply is skipped

A switch through the Camera tab Modality dropdown applies the delta unless any
of the following is true (each logs at INFO):

- Either profile lacks a calibrated offset (sidecar miss).
- The two profiles belong to different objectives (cross-objective is handled
  separately).
- The computed delta is below 0.05 um (stage noise floor).
- The previous profile is unknown (the very first switch of a session
  establishes the baseline; subsequent switches apply deltas).

The auto-apply also uses the standard stage Z-limit guard
(`MicroscopeController.moveStageZ`) -- if the delta would move beyond stage
limits the move is refused and a notification appears.

## Tips

- **Calibrate at a representative slide thickness.** Parfocality is a property
  of the optics, but extreme cover-slip thickness deviations can shift the
  numbers slightly. Calibrate at the thickness you usually image.
- **One reference per objective is enough.** All other profiles on that
  objective are stored as deltas from that reference; the choice of reference
  is bookkeeping only.
- **Recalibrate after maintenance** (objective service, filter cube swap,
  lamp / LED replacement, condenser height changes).
- **Hand-editing the sidecar** is fine -- the schema is short. The main
  config's inline `parfocal_offset_um` per profile is the fallback when the
  sidecar is absent, so you can seed values manually in YAML and the dialog
  will read them on next open.

## See Also

- [Live Viewer](live-viewer.md) - has the Camera tab Modality dropdown that
  drives the test loop.
- [Camera Control](camera-control.md) - alternate place to verify per-modality
  exposure and gain after calibrating focus.
- [Autofocus Configuration Editor](autofocus-editor.md) - autofocus runs
  per-modality; parfocality reduces the search range needed when switching
  modalities mid-acquisition.

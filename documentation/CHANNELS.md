# QPSC Channels Reference

This document describes how to configure channel-based modalities -- widefield immunofluorescence (IF) and combined brightfield + IF (BF+IF) -- in the QPSC QuPath extension. It is the primary reference for the Java side of the channel pipeline: YAML schema, how the picker reaches the acquisition loop, and common failures.

For the full cross-repo design rationale (pipeline shape, vendor-agnostic primitives, the "BF is just another channel" principle, the end-to-end OWS3 example, and per-tile / per-annotation file layout), see `../../QPSC/docs/multichannel-if-overview.md` -- that document is the source of truth. This file only covers what a QPSC Java-side user or developer needs to configure and debug a channel library.

---

## 1. Overview

A **channel** in QPSC is a fully data-driven description of one acquisition step: an id, a display name, a default exposure, an ordered list of Micro-Manager ConfigGroup presets to apply, an ordered list of device property writes to apply, and an optional settle time. A **channel library** is the ordered list of channels declared under a widefield modality in the microscope YAML.

The design principle is that every multi-channel illuminator can be driven through exactly two Micro-Manager primitives -- `core.setConfig(group, preset)` and `core.setProperty(device, property, value)` -- so nothing in QPSC's Java base layer knows what a "DLED" or a "CoolLED" or a "Spectra-X" is. It just reads strings out of YAML and hands them to the server.

For the narrative, the OWS3 end-to-end example, and how the channel branch composes with stitching and the multichannel merger, read the cross-repo overview at `../../QPSC/docs/multichannel-if-overview.md`.

---

## 2. Channel Library Schema

Channels live at the modality level, under `modalities.<name>.channels`. Minimal annotated example:

```yaml
modalities:
  Fluorescence:
    type: widefield
    channels:
      - id: DAPI                          # short stable id used in filenames and CLI flags
        display_name: DAPI (385 nm)       # human-readable label shown in the UI (optional; defaults to id)
        exposure_ms: 100                  # default exposure in milliseconds
        mm_setup_presets:                 # ConfigGroup presets, applied in order via core.setConfig
          - { group: Filter Turret, preset: Single photon LED-DA FI TR Cy5-B }
        device_properties:                # direct device property writes, applied in order via core.setProperty
          - { device: DLED, property: Intensity-385nm, value: 25 }
          - { device: DLED, property: Intensity-475nm, value: 0 }
          - { device: DLED, property: Intensity-550nm, value: 0 }
          - { device: DLED, property: Intensity-621nm, value: 0 }
        settle_ms: 0                      # optional dumb-sleep after presets/properties (default 0)
```

### Field reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | yes | Short stable identifier. Used as the on-disk subdirectory name for this channel's tiles and as the token in the `--channels` CLI flag. Must be non-blank. |
| `display_name` | string | no | Label shown in the channel picker and in log messages. Defaults to `id` if omitted. |
| `exposure_ms` | number | yes | Default camera exposure in milliseconds. Must be positive and finite. |
| `mm_setup_presets` | list | no | Ordered list of `{group, preset}` entries. Applied by the server via `core.setConfig(group, preset)` at the start of this channel's step. May be empty. |
| `device_properties` | list | no | Ordered list of `{device, property, value}` entries. Applied by the server via `core.setProperty(device, property, value)` after the presets. May be empty. |
| `settle_ms` | number | no | Dumb-sleep (in milliseconds) applied after all presets/properties have settled via `waitForDevice`. Only needed when a piece of hardware reports not-busy too early (some filter wheels and reflector turrets). Defaults to 0. |
| `intensity_property` | object | no | `{device, property}` pointer into this channel's own `device_properties` list, naming the primary brightness knob. When present, the channel picker renders an Intensity spinner for this row; when absent, the row shows a grayed "-" placeholder. See [Intensity Property Pointer](#intensity-property-pointer). |

### Intensity Property Pointer

Declaring `intensity_property` tells the UI which single device property is the "brightness knob" for this channel, so the per-channel Intensity spinner in the picker knows what to adjust. The field is a reference into the channel's own `device_properties` list -- it does not add a new property, it selects one.

```yaml
modalities:
  Fluorescence:
    type: widefield
    channels:
      - id: DAPI
        display_name: DAPI (385 nm)
        exposure_ms: 100
        mm_setup_presets:
          - { group: Filter Turret, preset: Single photon LED-DA FI TR Cy5-B }
        device_properties:
          - { device: DLED, property: Intensity-385nm, value: 25 }
          - { device: DLED, property: Intensity-475nm, value: 0 }
          - { device: DLED, property: Intensity-550nm, value: 0 }
          - { device: DLED, property: Intensity-621nm, value: 0 }
        intensity_property:
          device: DLED
          property: Intensity-385nm
```

The same schema works for brightfield-style channels in a `bf_if` modality -- the BF entry just points `intensity_property` at `DiaLamp.Intensity` (the transmitted lamp) instead of a DLED wavelength:

```yaml
      - id: BF
        display_name: Brightfield
        exposure_ms: 15
        mm_setup_presets:
          - { group: Light Path, preset: Transmitted }
        device_properties:
          - { device: DiaLamp, property: Intensity, value: 70 }
        intensity_property:
          device: DiaLamp
          property: Intensity
```

Channels without an `intensity_property` declaration still work -- they just get a grayed "-" in the Intensity column and cannot be tuned from the UI. This is appropriate for channels whose brightness is fixed in hardware (laser interlocks, filter-only channels).

### Notes

- `value` in a `device_properties` entry is always stringified in the YAML loader (`PropertyWrite` stores it as a String). Any Micro-Manager property type (int, double, string, enum) round-trips correctly -- the target device is responsible for parsing.
- Empty `mm_setup_presets` and empty `device_properties` are both valid. A channel that consists purely of switching the filter turret is fine; so is a channel that consists purely of setting LED intensities.
- The channel library is loaded by `MicroscopeConfigManager.getModalityChannels(modality)` and parsed into immutable `Channel` records.

---

## 3. Profile-Level Selection and Overrides

Acquisition profiles (`acquisition_profiles.<profile>`) can optionally filter and tune the modality's channel library without redeclaring it:

```yaml
acquisition_profiles:
  Fluorescence_20x:
    modality: Fluorescence
    detector: HAMAMATSU_DCAM_01
    mm_setup_presets:
      - { group: Light Path, preset: 2-R100 (Epi Camera) }
      - { group: Epi Shutter, preset: Open }
    illumination_intensity: 1.0
    channels: [DAPI, FITC, TRITC, Cy5]     # optional subset filter; if omitted, all library channels are used in library order
    channel_overrides:
      Cy5:
        exposure_ms: 250                   # scalar exposure override for this one channel
```

The profile's own `mm_setup_presets` run **once** before the tile loop starts (one-time light path, shutter, and combiner setup). The per-channel `mm_setup_presets` inside the channel library run **inside** the loop on every tile.

### Subset filter (`channels:`)

- If present, only the listed channels are acquired, in the order listed. Unknown ids are logged as warnings and skipped.
- If absent, every channel in the modality library is acquired in library order.

### Scalar exposure override (`channel_overrides.<id>.exposure_ms`)

Replaces the library's `exposure_ms` for a single channel in a single profile. Straight scalar override.

### Extended `device_properties` override

For cases where a profile needs to tune one device property on one channel on one objective (for example, lowering the transmitted-lamp intensity for the BF step of a low-magnification BF+IF profile), `channel_overrides.<id>.device_properties` supports the extended schema:

```yaml
BF_IF_10x:
  modality: BF_IF
  channels: [BF, DAPI, FITC, TRITC, Cy5]
  channel_overrides:
    BF:
      exposure_ms: 20
      device_properties:
        # Replaces the BF channel library entry's (DiaLamp, Intensity) value in place.
        # Does NOT touch any other property on the BF channel.
        - { device: DiaLamp, property: Intensity, value: 70 }
```

**Merge rule (`MicroscopeConfigManager.mergeDevicePropertyOverrides`):**

1. For each override entry, search the channel's library `device_properties` for a matching `(device, property)` tuple.
2. If matched, replace the `value` in place, preserving list order.
3. If not matched, append the override entry to the end of the list.

This lets one profile tune one property on one channel with a single YAML line -- no duplication of the rest of the channel definition. The Python side (`_merge_device_property_overrides` in `microscope_command_server/acquisition/workflow.py`) implements the same rule so the Java-side resolution and the server-side resolution stay in lockstep.

---

## 4. The `BF_IF` Modality Type

Combined brightfield + immunofluorescence on a single-camera instrument is declared as its own modality with `type: bf_if`. The Java handler `BfIfModalityHandler` extends `WidefieldFluorescenceModalityHandler` and only overrides its display name -- there is no separate data path. The BF step is expressed as a regular channel library entry whose `mm_setup_presets` switch the light path back to the transmitted port and whose `device_properties` turn on the transmitted lamp; subsequent entries switch back to the epi path for each fluorescence wavelength.

The handler is registered under the prefix `bf_if` in `ModalityRegistry`. Any profile whose modality key starts with that prefix (e.g. `BF_IF_20x`, `BF_IF_10x`) routes through it.

The separate modality type exists so that:

- Users see "Brightfield + Immunofluorescence" in the acquisition dialog as a distinct, discoverable option.
- A scope can offer pure Brightfield, pure Fluorescence, and combined BF+IF simultaneously with different channel libraries.
- Future BF+IF-specific behavior (image type defaults, background correction strategy) has a clear home that does not touch the pure-IF handler.

See `../../QPSC/docs/multichannel-if-overview.md` for the full OWS3 `BF_IF_20x` walkthrough.

---

## 5. The Channel Picker UI

The picker is rendered by `WidefieldChannelBoundingBoxUI` and appears in the Bounded and Existing-Image dialogs whenever the resolved profile has a non-empty channel library. It has one row per channel with four interactive columns:

| Column | Control | Purpose |
|--------|---------|---------|
| (select) | Checkbox | Include this channel in the acquisition. |
| Exposure (ms) | Spinner | Override the library default exposure for this run. |
| Intensity | Spinner or "-" | Override the library default value of the channel's `intensity_property` for this run. Shows a grayed "-" placeholder when the library entry does not declare `intensity_property`. |
| Focus | Radio button | Pick which channel autofocus runs against. Mutually exclusive across rows via a shared JavaFX `ToggleGroup`. Disabled when the row is not selected. |

### Intensity Spinner (NEW -- 2026-04-13)

The Intensity column lets the user adjust the brightness of the channel from the picker without editing YAML. The spinner is seeded with the current value of the channel's `intensity_property` in the library and is bounded by the device-reported limits of that property (or a sensible default range when limits are not available).

Only values that have actually been changed from the library default are emitted; untouched rows are omitted from the CLI flag to keep the command tight. Changed values are sent as a `--channel-intensities "(FITC=30.0,TRITC=45.0)"` flag on `BGACQUIRE`, and the Python server applies them as runtime device-property overrides at the start of each tile's per-channel step -- the YAML on disk is never modified.

Channels whose library entry has no `intensity_property` declaration render a disabled "-" placeholder instead of a spinner and contribute nothing to `--channel-intensities`.

### Focus-Channel Radio (NEW -- 2026-04-13)

The Focus column is a single column of radio buttons (one per channel row) that all belong to the same `ToggleGroup`, so exactly one channel can be picked as the autofocus reference at a time. The radio button for a row is disabled until that row's select checkbox is on.

The picked focus channel has two effects on the acquisition:

1. It is **moved to position 0** in the per-tile acquisition sequence. The server collects it first on every tile, so the autofocus snap and the first real image share hardware state (filter position, LED, exposure). The remaining channels follow in their original library order.
2. It is passed to the server as `--focus-channel <id>` on `BGACQUIRE`. The autofocus subsystem uses that channel's hardware state (including any per-run Intensity/Exposure overrides from the picker) when it runs the focus gate and Z search. See [AUTOFOCUS.md](AUTOFOCUS.md) for how the focus channel interacts with modality-aware autofocus strategies.

The picked channel is persisted in `PersistentPreferences` across sessions, keyed per microscope/profile, so reopening the dialog remembers the last focus-channel choice. If no radio button is selected (either because persistence has no value yet or because the user cleared the selection), the server defaults to the first channel in library order.

---

## 6. How It Reaches the Acquisition Loop

The channel path is pure data flow from YAML to server CLI flags. Each step below lists the class/method that carries the data, so future readers can find the code quickly:

- YAML file -- `modalities.<name>.channels` and `acquisition_profiles.<profile>.channel_overrides`.
- `MicroscopeConfigManager.getModalityChannels(modalityName)` -- parses the channel library.
- `MicroscopeConfigManager.getChannelsForProfile(profileKey)` -- filters by the profile subset and applies `channel_overrides` (scalar `exposure_ms` and extended `device_properties` merge).
- `WidefieldFluorescenceModalityHandler.getChannels(modality, objective, detector)` -- returns the resolved `List<Channel>` to the workflow.
- `WidefieldChannelBoundingBoxUI` -- channel-picker panel (checkbox + exposure spinner per channel, with a master "Customize channel selection" checkbox). Shown by `WidefieldFluorescenceModalityHandler.createBoundingBoxUI()` when a library is present; otherwise the angle-based single-snap fallback is used.
- `ChannelResolutionService.resolve(modality, objective, detector, overrides)` -- combines the library with the UI's selection map into the final `List<ChannelExposure>`.
- `ChannelResolutionService.isEmptySelectionForChannelBasedModality(...)` -- guard that the workflow checks to refuse acquisitions where the user has actively deselected every channel.
- `AcquisitionCommandBuilder.channelExposures(list)` -- emits `--channels "(id1,id2,...)"` and `--channel-exposures "(exp1,exp2,...)"` on the BGACQUIRE command instead of `--angles`/`--exposures`. Also emits `--channel-intensities "(id=val,...)"` for any channels whose Intensity spinner was changed from the library default, and `--focus-channel <id>` for the picked focus channel. Channel-based and angle-based acquisitions are mutually exclusive per acquisition.
- Python server -- `acquisition/workflow.py` re-resolves the plan from YAML, moves the `--focus-channel` entry to position 0, then for each tile iterates every channel: `apply_channel_hardware_state` (including any per-run intensity overrides from `--channel-intensities`) -> set exposure -> snap -> write to `{annotation}/{channel_id}/{tile}.tif`.
- `StitchingHelper.stitchChannelDirectories` -- iterates the channel ids and calls `processAngleWithIsolation` per channel to produce one single-channel pyramid per channel.
- `ChannelMerger` / `ChannelMergeImageServer` (in `qupath-extension-tiles-to-pyramid`) -- merges the per-channel pyramids into one multichannel OME-TIFF. See [Multichannel Stitching and Merge](#7-multichannel-stitching-and-merge) below.

Both `BoundedAcquisitionWorkflow` and the `AcquisitionManager` used by the Existing Image workflow route through `ChannelResolutionService`, so any workflow that reaches an acquisition profile with a channel library takes the channel branch automatically.

### Profile-Key Lookup Fix (`b40c98e` / `0a67083`)

Channel-based modalities now correctly resolve their library through the **enhanced profile key** (e.g. `Fluorescence_10x`) rather than the base modality name (`Fluorescence`). An earlier bug silently fell back to single-angle stitching because `performRegionStitching` (used by the bounded workflow) was passing the bare modality name while `performAnnotationStitching` (used by the existing-image workflow) was already passing the full profile key. Dryruns on bounded acquisitions looked like single-angle stitches even though the acquisition had collected the full channel set. The fix wires the channel branch into `performRegionStitching` as well, so both entry points resolve channels consistently. If an older session log shows multichannel tiles on disk but a single-channel (`001.ome.tif`) stitched output, this is the bug.

---

## 7. Multichannel Stitching and Merge

After the per-channel tile directories are written, the channel branch runs a two-stage stitching pipeline:

1. **Per-channel stitching.** `StitchingHelper.stitchChannelDirectories` iterates the acquired channel ids and calls `processAngleWithIsolation` once per channel. Each call produces a single-channel OME-TIFF pyramid on disk. Because the QuPath project tree would otherwise fill up with N intermediates per annotation, the stitching params for this stage set `skipProjectImport: true` -- the per-channel pyramids land in the output directory but are not added to the project.
2. **Channel merge.** `ChannelMerger.merge()` (in `qupath-extension-tiles-to-pyramid`) then combines the N single-channel pyramids into one multichannel OME-TIFF via `ChannelMergeImageServer`. Only the merged file is imported into the QuPath project, so the user sees exactly one entry per annotation regardless of how many channels were acquired.

### Merged Filename Convention

The merged file is named using the **short modality name** from the `ModalityRegistry` prefix instead of the long display name, and it drops the `_merged` suffix now that only the merged file is visible in the project. For example, the OWS3 Fluorescence_20x profile writes:

```
PollenIF_fl_001.ome.tif
```

instead of the old form `PollenIF_Fluorescence_001.ome.tif`. For the combined brightfield + IF modality, the full registry prefix is used so the two distinct modality types are still disambiguated on disk:

```
PollenBFIF_bf_if_001.ome.tif
```

This keeps filenames short and regular across modalities while preserving enough information for downstream tooling to route by modality.

---

## 8. Troubleshooting

Channel-specific failure modes and log lines to search for. General acquisition troubleshooting lives in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

### Unknown channel id in profile subset

**Symptom:** Fewer channels than expected show up in the picker or get acquired.

**Log:** `Profile '<profile>' references unknown channel id '<id>' (not in modalities.<modality>.channels); skipping`

**Fix:** Check for typos in `acquisition_profiles.<profile>.channels`. The ids must match the library entries exactly (case-sensitive).

### `channel_overrides.<id>` for a channel not in the profile subset

**Symptom:** An override block has no visible effect.

**Cause:** `getChannelsForProfile` only applies overrides to channels that survive the subset filter. If `<id>` is missing from `acquisition_profiles.<profile>.channels`, its override is silently dropped.

**Fix:** Either add the id to the profile's `channels:` list, or remove the unused override block to avoid confusion.

### Empty user selection

**Symptom:** Acquisition refuses to start with a clear error about zero channels selected.

**Cause:** The picker's "Customize channel selection" checkbox is on, and every individual channel checkbox is off. `ChannelResolutionService.isEmptySelectionForChannelBasedModality` detects this and the workflow blocks the acquisition rather than silently falling back to the full library.

**Log:** `Channel selection is empty for modality '<modality>' -- acquisition should be refused`

**Fix:** Check at least one channel in the picker, or uncheck "Customize channel selection" to acquire every channel at its library default exposure.

### Channel library declared on an angle-based modality (ignored)

**Symptom:** A `channels:` block under a PPM / brightfield / laser-scanning modality has no effect.

**Cause:** Only widefield fluorescence and BF+IF handlers take the channel branch. `PPMModalityHandler.getChannels`, `BrightfieldModalityHandler.getChannels`, and `LaserScanningModalityHandler.getChannels` all return empty regardless of what is in YAML.

**Fix:** If you actually want channel-based acquisition, declare a new modality with `type: widefield` or `type: bf_if` and move the `channels:` block there.

### Missing or non-numeric `exposure_ms` in library entry

**Symptom:** The offending channel disappears from the picker and the library list returned by `getModalityChannels`.

**Log:** `Skipping channel '<id>' in modalities.<modality>.channels: missing or non-numeric 'exposure_ms'`

**Fix:** Every channel library entry must declare `exposure_ms` as a positive finite number. String values and zero/negative numbers are rejected at parse time.

### Channel picker is empty

**Symptom:** The Bounded / Existing-Image dialog shows a greyed-out "No fluorescence channels configured for this microscope" message instead of a channel grid.

**Cause:** `WidefieldChannelBoundingBoxUI.loadChannelLibrary()` could not find any modality with a non-empty `channels:` list in the YAML, or `MicroscopeConfigManager` failed to load the config.

**Fix:** Verify the Microscope Config File preference points at a YAML that declares at least one `modalities.<name>.channels` block, and check the log for parse warnings on startup.

### Channel transitions race the snap

**Symptom:** The first tile of a channel is dim or black; later tiles of the same channel look correct.

**Cause:** Some hardware (filter wheels, reflector turrets, certain light paths) reports `isBusy() = false` before the LED intensity or filter position has actually settled. `core.waitForDevice` cannot detect this.

**Fix:** Add `settle_ms: <N>` to the offending channel in its library entry. Start with 50-100 ms and tune down. This is a dumb sleep applied after all presets and property writes have completed; it is the last step before the exposure is set and the image is snapped.

---

## 9. See Also

- [AUTOFOCUS.md](AUTOFOCUS.md) -- how the picked focus channel feeds the modality-aware autofocus strategies.
- [WORKFLOWS.md](WORKFLOWS.md) -- user-level multi-channel acquisition walkthrough (what the dialog looks like, what lands on disk).
- [PREFERENCES.md](PREFERENCES.md) -- persistent channel picker preferences (`widefield.channel.*` dynamic keys, including the persisted focus-channel selection).
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) -- multi-channel acquisition subsection.
- `../../QPSC/docs/multichannel-if-overview.md` -- cross-repo design overview, vendor-agnostic primitives, OWS3 end-to-end example.
- `../../microscope_command_server/` -- Python server implementation of `resolve_channel_plan` and `apply_channel_hardware_state`.
- `../../qupath-extension-tiles-to-pyramid/` -- `ChannelMerger` and `ChannelMergeImageServer` for the multichannel merge step.

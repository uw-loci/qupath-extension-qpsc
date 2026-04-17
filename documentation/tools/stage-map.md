# Stage Map

> Menu: Extensions > QP Scope > Stage Map
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Visual representation of the microscope stage insert showing slide positions. Helps users understand where slides are located on the stage, see the current stage position, and optionally overlay macro images for spatial reference. Use this tool to verify slide placement and prevent moving to positions outside the stage insert.

![Stage Map window](../images/Docs_StageMap.png)

## Prerequisites

- Connected to microscope server (see [Communication Settings](server-connection.md))
- Stage insert configuration defined in the microscope configuration file

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Insert Configuration | ComboBox | From config | Select the stage insert layout matching your hardware |
| Preset | ComboBox | Last used | Select a saved scanner-to-stage transform preset. Determines how the macro overlay is positioned on the map. Filtered to show only presets matching the current microscope. Presets are created during [Microscope Alignment](microscope-alignment.md). |
| Apply Flips | CheckBox | From config | Flip the Stage Map to match the Live Viewer orientation. Use when the map appears mirrored relative to the Live Viewer. |
| Overlay Macro | CheckBox | Auto | Overlay the current macro image on the map display. Automatically enabled when a macro image and alignment transform are detected. Requires a Preset to be selected. Also updates when switching between images in the project. |

## Workflow

1. Open Stage Map from the menu
2. Select the insert configuration that matches your physical stage insert
3. Select the scanner preset that matches how the current slide was scanned (e.g., "Ocus40 to PPM")
4. View slide boundaries, accessible areas, and current stage position
5. Open a scanned slide image -- the macro overlay enables automatically if a preset is selected
6. Use the map as a reference while navigating with the [Live Viewer](live-viewer.md)

## Output

No persistent output. The Stage Map provides a real-time visual display showing:

- Slide boundaries within the stage insert
- Current stage position indicator
- Visual preview of accessible areas
- Optional macro image overlay

## Tips & Troubleshooting

- If slide positions do not match the physical layout, verify the correct insert configuration is selected
- **No presets in dropdown?** Run [Microscope Alignment](microscope-alignment.md) through the Existing Image workflow to create a scanner-to-stage transform preset
- **Macro overlay not appearing?** Ensure a preset is selected AND the current image has an embedded macro (SVS files typically do)
- The macro overlay helps confirm that your overview image is correctly positioned relative to the stage
- The preset selector remembers your last selection across sessions
- Use this tool alongside the Live Viewer to understand spatial context when navigating
- If the current position indicator seems wrong, verify stage communication via [Communication Settings](server-connection.md)

## See Also

- [Live Viewer](live-viewer.md) - Navigate the stage with real-time camera feed
- [Microscope Alignment](microscope-alignment.md) - Create coordinate transforms between image and stage
- [Communication Settings](server-connection.md) - Verify server connection if position display is incorrect
- [Bounded Acquisition](bounded-acquisition.md) - Define acquisition regions using stage coordinates

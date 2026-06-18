# Stage Map

> Menu: Extensions > QP Scope > Stage Map
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Visual representation of the microscope stage insert showing slide positions. Helps users understand where slides are located on the stage, see the current stage position, and optionally overlay macro images for spatial reference. Use this tool to verify slide placement and prevent moving to positions outside the stage insert.

![Stage Map window](../images/Docs_StageMap.png)

## Prerequisites

- Connected to microscope server (see [Communication Settings](server-connection.md))
- Stage insert configuration defined in the microscope configuration file

## Visual Elements

The Stage Map displays several visual indicators:

| Element | Color | Meaning |
|---------|-------|---------|
| Crosshair circle | Lime green | Current stage position |
| Crosshair lines | Lime green | Current stage X/Y position markers |
| Target crosshair | Yellow (thin black outline) | Position under the mouse cursor on the Stage Map -- double-click to move the stage there |
| FOV rectangle | Orange | Field of view at current position |
| Bounding-box preview | Translucent green | Region that will be acquired when using [Bounded Acquisition](bounded-acquisition.md) (shown while the acquisition dialog is open) |
| Search-range preview | Translucent cyan (dashed) | Area scanned during SIFT auto-align (shown during refinement camera step). Centered on current stage position and sized to the search region: one FOV plus search margin on each side. With coarse-to-fine search enabled this is the area the coarse pass covers. |

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Insert Configuration | ComboBox | From config | Select the stage insert layout matching your hardware |
| Preset | ComboBox | Active microscope | Select which scanner's macro to display. The dropdown remembers your last selection across sessions. When you open Stage Map, it defaults to: (1) your persistent choice from the prior session, (2) the active microscope (same-scope identity), or (3) the first available preset. When you switch between project images, the dropdown **does NOT change automatically** — to display a different macro overlay (e.g., when working with derived images), pick manually from the dropdown. This lets you stay on your preferred scanner without the dropdown jumping when you navigate to a sub-acquisition with a different ancestor. Saved scanner-to-stage transform presets are created during [Microscope Alignment](microscope-alignment.md). **Important:** This dropdown drives macro overlay display only; it does NOT relabel the entry's acquisition-origin metadata. |
| Apply Flips | CheckBox | From config | Flip the Stage Map to match the Live Viewer orientation. Default state composes the active scope's **Stage Polarity** + **Camera Orientation** (same composition the stitcher uses) XOR'd with the macro overlay's flipMacroX/Y from the selected source preset. Independent of which project image is open. Updates automatically when stage polarity or camera orientation are edited (e.g., via Calibrate Directions). Use the toggle to compare against the un-flipped view. |
| Overlay Macro | CheckBox | Auto | Overlay the current macro image on the map display. Automatically enabled when a macro image and alignment transform are detected. Requires a Preset to be selected. Also updates when switching between images in the project. |
| Show Acquisitions | CheckBox | Off | Show / hide the acquisition overlay. First check scans the project for per-slide alignments and paints a translucent thumbnail at each acquired image's stage position. Unchecking hides the overlay but retains the cached thumbnails, so re-checking is instant. Use the **Clear** button to drop the cache and force a fresh project rescan next time. |
| Images | MenuButton | All visible | Dropdown list showing each loaded acquisition image as a checkbox. Toggle individual images on/off to control which ones appear in the acquisitions overlay. Right-click the button for **Select All** / **Select None** shortcuts. Only enabled when acquisitions are loaded. |
| Clear | Button | — | Drop the cached acquisition thumbnails and Images list. Unlike unchecking **Show Acquisitions** (which just hides the overlay and keeps the cache), Clear frees memory and forces a fresh project rescan the next time you re-enable **Show Acquisitions**. Use this after acquiring new images that should appear in the overlay. |

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
- **Switching between images with different macro sources?** When you switch to a derived entry (e.g., a PPM sub-image) with a different ancestor scanner, manually pick its scanner from the Preset dropdown to view the parent's macro overlay. The dropdown no longer auto-syncs on image switches so your selection persists across navigation
- Use this tool alongside the Live Viewer to understand spatial context when navigating
- If the current position indicator seems wrong, verify stage communication via [Communication Settings](server-connection.md)
- **Show Acquisitions not displaying coverage?** Check the console log when toggling the checkbox. Acquired images may not display if alignment files are missing or if the project used annotation-based acquisition without alignment registration. The tool searches for three types of alignment records: saved JSON files, auto-registered sub-frame alignments from stitched outputs, and entry-level stage metadata. If none are found, no overlay is drawn.
- **Uncheck vs Clear:** Unchecking **Show Acquisitions** hides the overlay but keeps the cached thumbnails in memory — re-checking it again is instant. Use the **Clear** button to free memory and force a fresh project rescan (useful after acquiring new images). You do not need to clear unless you've added new acquisitions to the project.
- **Too many acquisitions on the overlay?** Use the **Images** dropdown to toggle visibility of individual acquisitions, or right-click the button for quick **Select All / Select None** to manage which images are displayed.

## See Also

- [Live Viewer](live-viewer.md) - Navigate the stage with real-time camera feed
- [Microscope Alignment](microscope-alignment.md) - Create coordinate transforms between image and stage
- [Communication Settings](server-connection.md) - Verify server connection if position display is incorrect
- [Bounded Acquisition](bounded-acquisition.md) - Define acquisition regions using stage coordinates

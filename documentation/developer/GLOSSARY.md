# Glossary

The words this codebase uses for its core concepts. Use the preferred term in new code, comments, logs and docs. When you change code that uses an older spelling, move it to the preferred term in that change.

This covers names inside the Java code and prose. It does not cover saved or shared names, listed under [Names that must not change](#names-that-must-not-change).

## Units in names

A number with a unit carries the unit as a suffix.

| Unit | Suffix | Example | Avoid |
|---|---|---|---|
| Micrometers | `Um` | `pixelSizeUm`, `fovXUm` | `Microns`, `Micrometers`, `InMicrons` |
| Milliseconds | `Ms` | `exposureMs`, `timeoutMs` | `Millis`, `Msec` (the JDK's `currentTimeMillis` stays) |
| Seconds | `Sec` | `intervalSec` | `Seconds`, `Secs` |
| Degrees | `Deg` | `rotationDeg`, `angleDeg` | `Degrees`, `Rot` |
| Pixels | `Px` | `widthPx`, `marginPx` | `Pixels` |

A pixel size or FOV without a unit in its name is a defect, because both exist in more than one frame. Say which image or which microscope too: `macroPixelSizeUm`, `fullResPixelSizeUm`.

## Imaging

| Term | Meaning | Avoid |
|---|---|---|
| modality | A key under `modalities:` in the microscope config, such as `ppm` or `Brightfield`. `ModalityRegistry` finds its handler by name prefix. | `scanType`, `modalityName`, `baseModality` |
| modality family | A modality with any magnification suffix removed (`HardwareKey.modalityFamily`). Used to compare hardware. | |
| profile | An entry under `acquisition_profiles:`, found by `resolveProfileKey`. Its key is `profileKey`. A modality can have several profiles. | using `modality` for a profile |
| modality folder | `<modality>_<magnification>`, for example `ppm_20x`. Built by `ObjectiveUtils.createEnhancedFolderName`. | `enhancedModality`, `enhancedScanType` |
| acquisition folder | A modality folder with its run index, for example `ppm_20x_1`. | `imagingModeWithIndex`, `modalityWithIndex` |
| objective | The objective ID from the config, such as `LOCI_OBJECTIVE_OLYMPUS_20X_POL_001`. | `objectiveId` is acceptable only to contrast with a display name |
| magnification | The `20x` part of an objective. A text value such as `20x`. | `mag` outside a short local scope |
| detector | The configured imaging device, by its ID. It may be a camera or a point-scanning detector. | `camera` for a config entry |
| camera | The physical area camera, used only when the code is about one: `isJAICamera`, camera orientation, camera FOV. | |
| channel | One acquired component of a channel-based modality: a fluorescence filter set or an LC-PolScope state. | |
| angle | One rotation setting of a rotation-stage modality such as PPM. | |
| exposure | Camera integration time in ms. | `integrationTime` |
| white balance | Per-channel gain or exposure to neutralize color. Its setting is `wbMode`. | |
| background | A reference image of an empty field, or the set of them. A background profile is one such set for a sample setup. | |
| flat-field | A correction derived from images, without an empty-field reference. BaSiC is one method. | using it for `background` |

## Geometry

| Term | Meaning | Avoid |
|---|---|---|
| FOV | Field of view: the area one frame covers, in um unless the name says otherwise. Use `fovXUm` and `fovYUm`. | `frameWidthMicrons`, `fovWidthUm`, `fovW` |
| frame | One camera image. `TilingRequest.frameWidth` is a trap: its unit depends on the caller (pixels for a QuPath image, um for a bounding box). | |
| tile | One planned position in a grid and the frame acquired there. | |
| region | One area to acquire, usually from an annotation. `annotationName` is the name of the QuPath annotation behind it, also stored in image metadata. | `regionName` when it means `annotationName` |
| stage coordinates | Positions in the stage frame, in um: `stageX`, `stageY`, `stageZ`. | `xStage`, `posX`, `xPos` |
| pixel size | um per pixel of a named image or objective. | `umPerPixel` |
| flipped | An optical property of a light path. Affects pixel rendering only. | using `inverted` |
| inverted | Stage wiring: which way a positive command moves. Affects command direction only. `stage.polarity` in the config holds it. | using `flipped` |
| baked parity | The flip already written into a stored image. | |

A pixel size comes from one of four places, and they are not interchangeable:

| Source | Read with | Use for |
|---|---|---|
| The open image | `getPixelCalibration()` | Anything in that image's pixels |
| The scanner config | `MacroImageUtility.getMacroPixelSize` | The macro image |
| The microscope config | `MicroscopeConfigManager.getHardwarePixelSize` | An objective and detector pair |
| Micro-Manager, live | `MicroscopeSocketClient.getMicroscopePixelSize` | Which objective is really in place |

Flipped and inverted are independent, and `COORDINATE_TRANSFORMS.md` and `ORIENTATION_STACK.md` define them fully.

## Projects and images

| Term | Meaning | Avoid |
|---|---|---|
| sample name | The name the user gives the sample. It starts acquired file names. | `displayName` when it holds the sample name, `sampleLabel` |
| image collection | The group of project images from one physical slide placement. | |
| entry | A `ProjectImageEntry` in the QuPath project. | `image` when the entry is meant |
| macro image | The low-magnification slide overview from a scanner. | `overview` in code |
| base image | The root of a chain of derived images, by name (`base_image`). It stays the same down the chain. | `baseEntry` unless it holds an entry |
| parent entry | The image one step up the chain. | `sourceEntry` for this |
| source entry | The image a workflow was started from. | |
| sub-acquisition | An image acquired from a region of another image. Prefer this over `subImage` when the acquisition is meant. | `derived` |
| Camera View entry | A display copy of an entry with a flip applied, named `(Camera View)`. Annotations live on the unflipped entry, and this copy is not authoritative for flip state. | `flipped duplicate`, `flipped sibling` |

## Names that must not change

These are read from disk, sent to other programs, or typed into configs. Keep each spelling as it is, even where it disagrees with this glossary.

- Preference keys in `PersistentPreferences` and `PPMPreferences`.
- YAML keys, including `stained_colour`, `search_range_um` and everything under `acquisition_profiles`.
- Image metadata keys in `ImageMetadataManager`, such as `xy_offset_x_microns`, `base_image` and `orientation_colour.*`.
- Socket command names, field names and status words such as `CANCELLED`. See `SOCKET_PROTOCOL.md`.
- File and folder names that existing data uses.
- Public classes and methods that `qupath-extension-ppm` imports. Check that repo before renaming one.

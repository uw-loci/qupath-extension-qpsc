# Session Scope: QPSC QuPath Extension

This session is focused on **qupath-extension-qpsc** -- the main QPSC QuPath extension (Java/Gradle).

## Scope

- Primary work directory: `qupath-extension-qpsc/`
- Language: Java 21, Gradle build system
- Key packages: `qupath.ext.qpsc.*` (controller, service, ui, utilities, modality)

## Part of the QPSC Project

This repo is one of several interconnected components. See the root `/home/msnelson/QPSC_Project/CLAUDE.md` for full project architecture.

**Direct dependencies (often need coordinated changes):**
- `microscope_command_server/` - Python socket server that executes commands sent by this extension
- `microscope_configurations/` - YAML config files read by MicroscopeConfigManager

**Indirect dependencies (called by microscope_command_server):**
- `microscope_control/` - Hardware abstraction (Pycromanager)
- `microscope_imageprocessing/` - General image processing (background correction, OME-TIFF writer, Z-stack projections)
- `ppm_library/` - PPM-specific processing (WB coefficients, PPM modality parsing)

**Sibling Java extensions:**
- `qupath-extension-tiles-to-pyramid/` - Stitching (called via StitchingHelper)

## Cross-Repo Work Guidelines

- When fixing acquisition issues, changes often span this repo AND microscope_command_server (and sometimes ppm_library)
- Always check for uncommitted changes across related repos before testing
- Confirm with the user before modifying other repos, but expect cross-repo work to be common

## Guardrails

- Git operations apply to THIS repository only -- verify with `pwd` and `git status` before committing
- Confirm with the user before touching: DL classifier, microscope_control, or unrelated extensions

## UI Conventions

### Alerts opened from always-on-top windows

When a parent dialog or stage sets `setAlwaysOnTop(true)` (Refine Alignment,
StageMap, Acquisition Wizard, autofocus benchmark progress, Single-Point
Acquisition controller), an `Alert.showAndWait()` opened from inside it
defaults to owner = main QuPath window and modality = APPLICATION_MODAL.
The alert sinks behind the always-on-top parent while still holding modal
focus, so the parent appears frozen and the user cannot dismiss the alert.

**Use `UIFunctions.showAlertOverParent(Alert, Window)`** instead of
`alert.showAndWait()` whenever the call site is inside an always-on-top
parent. The helper:

- parents the alert via `initOwner(parent)`,
- raises the alert's stage to alwaysOnTop on `setOnShown` so it co-floats
  with the parent rather than sinking,
- still uses `showAndWait` so existing blocking call sites migrate with a
  one-line diff.

Existing migrated sites: `SingleTileRefinement` SIFT settings,
`AutofocusBenchmarkWorkflow`, `StageMapWindow` (4 alerts), `AcquisitionWizardDialog`.

## Build & Test

Java 21+, Gradle. Linux/WSL can run `compileJava`/`build` for compilation checks; full GUI runtime testing requires Windows.

```bash
./gradlew build           # extension JAR
./gradlew shadowJar       # all-deps JAR
./gradlew test            # all tests
./gradlew test --tests "qupath.ext.qpsc.ConfigurationTestRunner"          # one class
./gradlew test --tests "qupath.ext.qpsc.QPProjectFunctionsTest.testCreateProject"  # one method
./gradlew clean
./gradlew compileJava     # check deprecation/unchecked warnings
```

Tests need JavaFX modules — the `test` task is configured with `--add-modules javafx.base,javafx.graphics,javafx.controls` and `--add-opens javafx.graphics/javafx.stage=ALL-UNNAMED`.

## Architecture

QPSC bridges QuPath digital pathology with microscope control via Pycro-Manager. Users define regions in QuPath, the extension transforms coordinates, drives acquisition, and re-imports the stitched result.

**Workflow pattern:** `QuPath annotation -> coordinate transform -> microscope control -> acquisition -> stitching -> import`.

**Package layout (`qupath.ext.qpsc.*`):**

- **`controller/`** — workflow orchestration. `QPScopeController` (menu router), `BoundingBoxWorkflow`, `ExistingImageWorkflow`, `MicroscopeAlignmentWorkflow`, `TestWorkflow`.
- **`modality/`** — pluggable imaging modes. `ModalityHandler` interface, `ModalityRegistry` (ConcurrentHashMap), `ppm/PPMModalityHandler`. New modalities register with a name prefix (e.g. `ppm` matches `ppm_20x`).
- **`service/`** — external integration. `AcquisitionCommandBuilder`, `microscope/MicroscopeSocketClient`.
- **`ui/`** — dialogs per workflow step, `UIFunctions` (shared), modality-specific UI like `ppm/ui/PPMBoundingBoxUI`. UI updates always go through `Platform.runLater()`.
- **`utilities/`** — `MicroscopeConfigManager` (singleton, hierarchical YAML + LOCI resource lookup), `QPProjectFunctions`, `TilingUtilities`, `ImageMetadataManager`, coordinate helpers.

**Configuration system:** microscope-specific YAML + shared LOCI resource tables + QuPath preferences. `MicroscopeConfigManager` provides type-safe access with automatic LOCI resolution. `QPScopeChecks.validateMicroscopeConfig()` runs at startup; workflows are disabled if the config is invalid.

**Multi-sample projects** track image collections, XY offsets, flip status, and macro->sub-acquisition parent relationships.

**Dependencies:** `qupath-extension-tiles-to-pyramid:0.1.0` (stitching), SnakeYAML, JavaFX, Mockito (tests). Background acquisition uses daemon thread pools.

### Microscope communication — dual-socket architecture

`MicroscopeSocketClient` keeps two independent TCP connections to the Python server:

| Socket | Lock | Purpose | Auto-connects |
|---|---|---|---|
| Primary | `socketLock` | Acquisition, CONFIG, long-running commands | At startup (auto-connect pref); lazily via `ensureConnected()` on any `executeCommand()` |
| Auxiliary | `auxSocketLock` | Live Viewer frames, stage XY/Z/R, position polling | Lazily via `ensureAuxConnected()` on first use |

- `MicroscopeController.isConnected()` is true if **either** socket is connected. `isPrimaryConnected()` / `isAuxConnected()` are diagnostic-only.
- `disconnectPrimary()` leaves auxiliary intact (Connection-dialog reconnect path).
- Heartbeat monitoring keeps long-running acquisitions alive.

**When adding new socket commands:** route to the primary (`executeCommand`) for long-running ops, the auxiliary (`executeCommandOnAux`) for anything that must not block Live Viewer. See `claude-reports/2026-04-22_dual-socket-connection-state-fix.md`.

**Read timeouts (2026-04-26):** primary default `readTimeout` is 5s, but `getAcquisitionStatus` overrides per-call to 30s and the auxiliary connects with 30s. Both bumps avoid `SocketTimeoutException -> handleIOException -> reconnect storm` when aux/STAT calls share the server hardware lock with a 10-15s 3-angle JAI tile capture. See `claude-reports/2026-04-26_connection-stability-and-pre-acq-af-edge-retry.md`.

**Server-side same-IP takeover guard:** when a CONFIG arrives from an IP that already has a connection, the server checks every connection from that IP for a RUNNING/CANCELLING workflow; if any is active, the new CONFIG is rejected with `CFG_BLCK: BLOCKED: Active acquisition on (...)`. Protects in-flight acquisitions from aux-socket reconnects under load.

## Coordinate system terminology

**Flipped vs inverted are different concepts — do not conflate them.**

**Flipped** = optical property of the microscope **light path** for a given (source-scanner, target-microscope) pair. The Ocus40 macro pixel frame and the OWS3 / PPM camera pixel frame may disagree by a horizontal or vertical mirror. Captured per-pair as `flipMacroX/Y` on the saved alignment preset (and on the per-slide alignment JSON), **not** as separate flipped image entries.

**Inverted** = stage wiring/configuration. Whether positive X/Y stage commands move right/up or left/down. Varies per system; affects stage-command direction, **not** pixel rendering.

These are **independent** transformations. The macro overlay flip path uses only the optical flip; the stage-command path uses only the wiring polarity. Conflating them via XOR (the pre-2026-05 stagemap implementation) is a class of bug — see commit `d436da8`.

**Source of truth for flip (Step B refactor, 2026-05-04):**
- Flip belongs to the `(source_scanner, target_microscope)` preset pair, captured as `TransformPreset.flipMacroX/Y`. Persisted in `saved_transforms.json`.
- Per-slide alignment JSON (`alignmentFiles/<sample>_<scope>_alignment.json`) also records `flipMacroX/Y` -- the alignment-time pixel frame. `AlignmentHelper.checkForSlideAlignment` bakes this into `state.transform` so all downstream callers consume **unflipped-base pixel coords**.
- **One project entry per slide.** No more `(flipped X|Y|XY)` duplicates. Existing projects can be migrated via `Extensions > QP Scope > Utilities > Migrate Flipped Duplicates...` (`FlippedDuplicateMigrator`).
- Per-entry `FLIP_X` / `FLIP_Y` metadata is no longer load-bearing for macro rendering or acquisition. It survives on legacy entries but is treated as advisory at best.
- Macro overlay flip resolution (`StageMapWindow.resolveCurrentFlipAxes`):
  1. Preset for `(entry.SOURCE_MICROSCOPE, activeMicroscope)` if it has `hasFlipState()`.
  2. Currently-selected dropdown preset.
  3. Default `(false, false)`.

**Naming convention:**

| Concept | Java vars | Source of truth | Metadata key |
|---|---|---|---|
| Optical flip (macro image) | `flipMacroX`, `flipMacroY` | `TransformPreset` (per `(source, target)` pair) | (no per-entry key) |
| Stage wiring polarity | `stageInvertedX`, `stageInvertedY` | `getStageInvertedXProperty()` | (auto-detected) |
| Camera orientation | (enum) | `getCameraOrientationProperty()` | (none) |
| Source scanner of an entry | `SOURCE_MICROSCOPE` | per-entry metadata (set at import) | `source_microscope` |

### StageImageTransform — single source of truth (e3cb133, 2026-04-09)

**All live-view stage <-> display sign math goes through `qupath.ext.qpsc.utilities.StageImageTransform`.** It composes `StagePolarity` (hardware wiring) and `CameraOrientation` (8-element dihedral group: how the camera maps the sample frame to the displayed image) and exposes:

- `transform.screenPanDeltaToMmDelta(dx, dy)` — arrows, joystick, click offset
- `transform.clickOffsetToMmTarget(...)` — click-to-center
- `transform.stitcherFlipFlags()` — stitcher wiring

**Never read `stageInvertedX/Y` booleans directly for coordinate math in new subsystems.** Access via `StageImageTransform.current()` or `QPPreferenceDialog.getStagePolarityProperty()`. This prevents the "three subsystems, three sign conventions" drift bug class (see OWS3 incident 2026-04-09 where arrows, clicks, and stitcher had drifted apart).

**Exception:** `TilingUtilities.processTileGridRequest` and callers read the booleans directly to determine serpentine scan order. Correctness-neutral (only iteration order changes); acceptable to leave unmigrated.

If you need to verify a specific sign behaviour on a specific scope, write a unit test against `StageImageTransform` rather than hand-rolling math at the call site.

Design doc: `claude-reports/2026-04-09_stage-image-transform-refactor.md`.

### Object propagation (ForwardPropagationWorkflow)

- Must work **offline** (no microscope connection). Never require a live connection for coordinate math.
- Half-FOV offset correction is critical: `correctedOffset = xy_offset - halfFOV`. Without it, annotations land shifted by half a field of view.
- FOV resolution via `resolveFovForEntry()`: (1) per-image `fov_x_um`/`fov_y_um` metadata, (2) config file via modality/objective/detector from entry metadata, (3) live microscope fallback.
- FOV depends on the **objective at acquisition time**, not the current objective — source (2) uses the stored `objective` metadata key.
- **Pixel frames (Step B):** annotations live on the **unflipped base** entry. `propagateBack` and `propagateForward` apply the alignment-time flip (`flipMacroX/Y` from the per-slide JSON or the active preset) as a transform step inside the math, so annotation coords are always interpreted in the unflipped-base frame. `propagateBackFanOut` is now a thin wrapper that writes only to the unflipped base — there are no flipped siblings to fan to.
- Full transform chain in `claude-reports/2026-03-10_coordinate-system-terminology-standardization.md` ("Object Propagation and the FOV Offset").

## QuPath GeneralTools policy

**Always check `qupath.lib.common.GeneralTools` before implementing utilities.** ([JavaDoc](https://qupath.github.io/javadoc/docs/qupath/lib/common/GeneralTools.html))

Common methods worth knowing:

- **Files/paths:** `stripExtension(String)` (handles `.ome.tif`/`.ome.zarr`), `getExtension`, `isMultipartExtension`, `stripInvalidFilenameChars`, `isValidFilename`.
- **Platform:** `isWindows()` / `isMac()` / `isLinux()` / `isAppleSilicon()`.
- **Strings/numbers:** `formatNumber(double, maxDecimalPlaces)` (locale-aware), `clipValue`, `almostTheSame(a,b,tol)`, `splitLines`, `generateDistinctName(name, existing)`.
- **Memory:** `estimateAvailableMemory()`, `estimateUsedMemory()`.

Use GeneralTools for: extension handling, platform detection, string/number formatting, value clamping. Implement custom only when domain logic is required (microscopy tiling, YAML config), GeneralTools is insufficient (Windows reserved-name checks), or the behaviour differs (replace vs remove invalid chars). Document the divergence in a comment when overlapping.

Existing migrations: `SampleNameValidator.extractBaseName()` -> `GeneralTools.stripExtension()`; `MinorFunctions.isWindows()` -> `GeneralTools.isWindows()`. Custom but informed by GeneralTools: `SampleNameValidator.sanitize()` (adds Windows reserved-name check), `ImageNameGenerator.sanitizeForFilename()` (replaces instead of removes).

## Documentation maintenance

Review docs **monthly**, on every version bump, and after major features.

| File | Path |
|---|---|
| Main README | `qupath-extension-qpsc/README.md` |
| UTILITIES.md | `qupath-extension-qpsc/documentation/UTILITIES.md` |
| WORKFLOWS.md | `qupath-extension-qpsc/documentation/WORKFLOWS.md` |
| PREFERENCES.md | `qupath-extension-qpsc/documentation/PREFERENCES.md` |
| TROUBLESHOOTING.md | `qupath-extension-qpsc/documentation/TROUBLESHOOTING.md` |
| Python READMEs | `microscope_command_server/README.md`, etc. |

Checklist: menu items match UI, preference names/defaults accurate, code examples compile, screenshots current, links not broken, version numbers current, new features documented, removed features cleaned up. Record reviews in `claude-reports/TODO_LIST.md` under "Documentation Review Log" with date, files, changes, next review date.

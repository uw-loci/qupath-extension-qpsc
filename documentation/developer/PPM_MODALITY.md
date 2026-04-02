# PPM Modality Implementation

Developer reference for the Polarized light microscopy (PPM) implementation in QPSC. PPM captures tissue at multiple polarizer rotation angles to reveal collagen fiber orientation via birefringence.

## Overview

PPM acquires images at 2-4 rotation angles per tile position. Each angle has an independent exposure time because transmitted light intensity varies significantly with polarizer orientation (crossed polarizers transmit very little light; uncrossed transmit maximum).

The standard PPM angle set is:

| Name | Ticks | Degrees | Typical Exposure | Purpose |
|------|-------|---------|-----------------|---------|
| negative | -7.0 | -14 deg | 500 ms | Birefringence signal (negative offset) |
| crossed | 0.0 | 0 deg | 800 ms | Crossed polarizers (minimum transmission) |
| positive | 7.0 | +14 deg | 500 ms | Birefringence signal (positive offset) |
| uncrossed | 90.0 | 180 deg | 10 ms | Uncrossed polarizers (maximum transmission) |

The birefringence image is computed from the positive and negative angles. The crossed image shows extinction. The uncrossed image provides a brightfield-like reference.

## Class Diagram

```mermaid
classDiagram
    class ModalityHandler {
        <<interface>>
        +getRotationAngles(modality, objective, detector) CompletableFuture~List~AngleExposure~~
        +createBoundingBoxUI() Optional~BoundingBoxUI~
        +applyAngleOverrides(angles, overrides) List~AngleExposure~
        +getImageType() Optional~ImageType~
        +validateBackgroundSettings(...) BackgroundValidationResult
        +getModalityMenuContributions() List~ModalityMenuItem~
        +prepareForAcquisition(objective, detector)
        +getPostProcessingDirectorySuffixes() List~String~
    }

    class PPMModalityHandler {
        +getRotationAngles() creates RotationManager
        +createBoundingBoxUI() returns PPMBoundingBoxUI
        +applyAngleOverrides() adjusts +/- angles
        +getImageType() returns BRIGHTFIELD_H_E
        +prepareForAcquisition() loads exposure profile
        +validateBackgroundSettings() checks angle/exposure match
        +getPostProcessingDirectorySuffixes() [.biref, .sum]
        +getDefaultAngleCount() returns 4
        +getDefaultExposureForAngle(ticks) maps to preference slot
    }

    class RotationManager {
        -strategy: RotationStrategy
        +initializeStrategies(modality, objective, detector)
        +getRotationTicksWithExposure() List~AngleExposure~
        +getConfiguredAngles() List~AngleExposure~
    }

    class RotationStrategy {
        <<interface>>
        +appliesTo(modalityName) boolean
        +getRotationTicksWithExposure() List~AngleExposure~
        +getConfiguredAngles() List~AngleExposure~
    }

    class PPMRotationStrategy {
        -plus: AngleExposure
        -minus: AngleExposure
        -zero: AngleExposure
        -uncrossed: AngleExposure
        +getRotationTicksWithExposure() shows dialog
        +getConfiguredAngles() returns all 4
    }

    class AngleExposure {
        <<record>>
        +ticks: double
        +exposureMs: double
    }

    class PPMPreferences {
        +minusExposureMs: double
        +zeroExposureMs: double
        +plusExposureMs: double
        +uncrossedExposureMs: double
        +overridePlusAngle: double
        +overrideMinusAngle: double
        +overrideEnabled: boolean
        +loadExposuresForProfile(objective, detector)
    }

    class PPMBoundingBoxUI {
        -overrideCheckBox: CheckBox
        -plusSpinner: Spinner
        -minusSpinner: Spinner
        +getAngleOverrides() Map~String,Double~
    }

    class PPMAngleSelectionController {
        +showDialog(angles) List~AngleExposure~
        -minusCheck, zeroCheck, plusCheck, uncrossedCheck
        -minusExposure, zeroExposure, plusExposure, uncrossedExposure
    }

    ModalityHandler <|.. PPMModalityHandler
    PPMModalityHandler --> RotationManager
    PPMModalityHandler --> PPMBoundingBoxUI
    PPMModalityHandler --> PPMPreferences
    RotationManager --> RotationStrategy
    RotationStrategy <|.. PPMRotationStrategy
    PPMRotationStrategy --> PPMAngleSelectionController
    PPMRotationStrategy --> AngleExposure
    PPMAngleSelectionController --> AngleExposure
    PPMPreferences --> PPMAngleSelectionController
```

## Angle Resolution Flow

```mermaid
sequenceDiagram
    participant AM as AcquisitionManager
    participant ARS as AngleResolutionService
    participant PPM as PPMModalityHandler
    participant PP as PPMPreferences
    participant RM as RotationManager
    participant RS as PPMRotationStrategy
    participant DLG as PPMAngleSelectionController
    participant User

    AM->>ARS: resolve(modality, objective, detector)
    ARS->>PPM: prepareForAcquisition(objective, detector)
    PPM->>PP: loadExposuresForProfile(objective, detector)
    PP-->>PPM: exposure defaults loaded

    alt Has angle overrides from BoundingBoxUI
        ARS->>PPM: getRotationAnglesWithOverrides(overrides)
        PPM->>RM: getConfiguredAngles()
        RM->>RS: getConfiguredAngles()
        RS-->>PPM: all 4 angles (with defaults)
        PPM->>PPM: applyAngleOverrides(angles, overrides)
        PPM->>DLG: showDialog(overridden angles)
    else No overrides
        ARS->>PPM: getRotationAngles()
        PPM->>RM: create(modality, objective, detector)
        RM->>RS: new PPMRotationStrategy(config angles, exposures)
        PPM->>RS: getRotationTicksWithExposure()
        RS->>DLG: showDialog(configured angles)
    end

    DLG->>User: Show angle selection dialog
    User-->>DLG: Select angles, adjust exposures, click OK
    DLG->>PP: save selections to preferences
    DLG-->>ARS: List of AngleExposure (selected only)
    ARS-->>AM: final angle list
```

## Configuration

### YAML Structure (config_PPM.yml)

```yaml
modalities:
  ppm:
    type: 'polarized'
    rotation_stage:
      device: 'LOCI_STAGE_PI_001'      # Hardware ID -> resources_LOCI.yml
      type: 'polarizer'
    rotation_angles:
      - name: 'negative'
        tick: -7                         # Hardware units (degrees for PI stage)
      - name: 'crossed'
        tick: 0
      - name: 'positive'
        tick: 7
      - name: 'uncrossed'
        tick: 90
```

### Exposure Profile (imageprocessing_PPM.yml)

```yaml
imaging_profiles:
  ppm:
    LOCI_OBJECTIVE_OLYMPUS_20X_POL_001:
      LOCI_DETECTOR_JAI_001:
        exposures_ms:
          # Per-channel for JAI 3-CCD (R/G/B independent)
          minus: { r: 480.0, g: 520.0, b: 550.0 }
          zero:  { r: 750.0, g: 800.0, b: 850.0 }
          plus:  { r: 480.0, g: 520.0, b: 550.0 }
          uncrossed: { r: 8.0, g: 10.0, b: 12.0 }
```

The `PPMPreferences.loadExposuresForProfile()` method reads this structure and extracts the green channel value (median channel, matches background collection) as the per-angle exposure default.

## Exposure Priority Chain

When resolving exposure for a PPM angle, the system checks in order:

```mermaid
graph TB
    BG["1. Background Settings<br/>(exposure used when BG was collected)"]
    CFG["2. imageprocessing_PPM.yml<br/>(per-objective, per-detector profile)"]
    PREF["3. PPMPreferences<br/>(persistent user defaults)"]
    HARD["4. Hardcoded Defaults<br/>(500, 800, 500, 10 ms)"]

    BG -->|"not found"| CFG
    CFG -->|"not found"| PREF
    PREF -->|"never set"| HARD
```

This ensures consistency: if background images were collected at specific exposures, acquisitions default to the same values.

## Angle Override System

Users can override the default +/- angles for a single acquisition without changing the global config. This is useful when the birefringence signal is weak and a wider angular spread is needed.

```mermaid
graph LR
    subgraph "Acquisition Dialog"
        CB["Override checkbox"] --> SP["Plus/Minus spinners<br/>(-180 to 180, step 0.5)"]
    end

    subgraph "PPMBoundingBoxUI"
        SP --> GO["getAngleOverrides()<br/>{'plus': 10.0, 'minus': -10.0}"]
    end

    subgraph "PPMModalityHandler"
        GO --> AAO["applyAngleOverrides()<br/>Replace +/- ticks,<br/>keep zero/uncrossed"]
    end

    AAO --> DLG["AngleSelectionDialog<br/>(pre-filled with overrides)"]
```

The override only affects the positive and negative angles. Crossed (0) and uncrossed (90) are always preserved because they are physically meaningful reference positions.

## Background Validation

Before acquisition, `PPMModalityHandler.validateBackgroundSettings()` checks that background images are compatible:

```mermaid
graph TB
    VA["validateBackgroundSettings()"]
    VA --> C1{"Background exists<br/>for each selected angle?"}
    C1 -->|"No"| W1["Warning: angles without background"]
    C1 -->|"Yes"| C2{"Exposure within<br/>0.1ms tolerance?"}
    C2 -->|"No"| W2["Warning: exposure mismatch"]
    C2 -->|"Yes"| C3{"WB mode matches?"}
    C3 -->|"No"| W3["Warning: WB mode mismatch"]
    C3 -->|"Yes"| OK["Validation passed"]

    W1 --> D["Disable BG correction<br/>for affected angles<br/>(--bg-disabled-angles)"]
```

## Post-Processing Outputs

The Python acquisition server creates additional outputs for PPM:

```
tiles/
  ppm_20x_1/
    Region_1/
      tile_000_ang-7.0.tif    # Raw tile at -7 degrees
      tile_000_ang0.0.tif     # Raw tile at 0 degrees
      tile_000_ang7.0.tif     # Raw tile at +7 degrees
      tile_000_ang90.0.tif    # Raw tile at 90 degrees
    Region_1.biref/           # Computed birefringence
      tile_000.tif
    Region_1.sum/             # Sum of angle images
      tile_000.tif
```

The `.biref` and `.sum` directories are discovered by `StitchingHelper` via `PPMModalityHandler.getPostProcessingDirectorySuffixes()` and stitched as additional output images.

## PPM Menu Contributions

`PPMModalityHandler.getModalityMenuContributions()` adds four items to the PPM menu in QuPath:

| Menu Item | Workflow | Purpose |
|-----------|----------|---------|
| Polarizer Calibration | `PolarizerCalibrationWorkflow` | Calibrate rotation stage tick values |
| Rotation Sensitivity Test | `PPMSensitivityTestWorkflow` | Analyze impact of angular deviations |
| Birefringence Optimization | `BirefringenceOptimizationWorkflow` | Find optimal +/- angles for max signal |
| Reference Slide (Sunburst) | `SunburstCalibrationWorkflow` | Create hue-to-angle mapping from reference |

## Key Files

| File | Purpose |
|------|---------|
| `modality/ModalityHandler.java` | Plugin interface |
| `modality/ModalityRegistry.java` | Prefix-based handler lookup |
| `modality/AngleExposure.java` | Immutable (ticks, exposureMs) record |
| `modality/ppm/PPMModalityHandler.java` | PPM implementation (~450 lines) |
| `modality/ppm/RotationManager.java` | Config loading + strategy creation |
| `modality/ppm/RotationStrategy.java` | PPMRotationStrategy + NoRotationStrategy |
| `modality/ppm/PPMPreferences.java` | Persistent exposure/angle defaults |
| `modality/ppm/ui/PPMBoundingBoxUI.java` | Angle override spinners |
| `modality/ppm/ui/PPMAngleSelectionController.java` | Angle/exposure dialog |
| `service/AngleResolutionService.java` | Orchestrates the resolution pipeline |
| `service/AcquisitionCommandBuilder.java` | Formats `--angles` and `--exposures` |

# QPSC Extension Architecture

Developer reference for the `qupath-extension-qpsc` Java codebase. This document covers the package structure, key abstractions, and how the major subsystems connect.

## Package Overview

```mermaid
graph TB
    subgraph "Entry Point"
        SS[SetupScope<br/>QuPathExtension]
        QC[QPScopeController<br/>Singleton, routes menu actions]
    end

    subgraph "controller/"
        BAW[BoundedAcquisitionWorkflow]
        EIW[ExistingImageWorkflowV2]
        MAW[MicroscopeAlignmentWorkflow]
        BCW[BackgroundCollectionWorkflow]
        WBW[WhiteBalanceWorkflow]
        STW[StackTimeLapseWorkflow]
    end

    subgraph "controller/workflow/"
        AM[AcquisitionManager]
        TH[TileHelper]
        SH[StitchingHelper]
        STR[SingleTileRefinement]
    end

    subgraph "service/"
        ACB[AcquisitionCommandBuilder]
        ARS[AngleResolutionService]
        MSC[MicroscopeSocketClient]
    end

    subgraph "modality/"
        MR[ModalityRegistry]
        MH[ModalityHandler<br/>interface]
        PPM[PPMModalityHandler]
        MPH[MultiphotonModalityHandler]
    end

    subgraph "utilities/"
        MCM[MicroscopeConfigManager]
        TF[TransformationFunctions]
        TU[TilingUtilities]
        IMM[ImageMetadataManager]
        IFH[ImageFlipHelper]
        ATM[AffineTransformManager]
    end

    subgraph "ui/"
        UAC[UnifiedAcquisitionController]
        LVW[LiveViewerWindow]
        DPD[DualProgressDialog]
        SMW[StageMapWindow]
    end

    SS --> QC
    QC --> BAW & EIW & MAW & BCW & WBW & STW
    BAW --> AM
    EIW --> AM
    AM --> ARS --> MR --> MH
    MH -.-> PPM & MPH
    AM --> ACB --> MSC
    AM --> TH --> TU
    AM --> SH
    AM --> STR --> MSC
    AM --> DPD
    BAW --> UAC
    EIW --> UAC
    MCM --> ACB
    TF --> AM
    IMM --> AM & SH
```

## Package Details

### controller/ -- Workflow Orchestration

Each workflow is a self-contained class that orchestrates a complete user operation from dialog to completion. They follow a common pattern:

1. Show a dialog to collect parameters
2. Validate hardware state and configuration
3. Execute the operation (often async via `CompletableFuture`)
4. Report results and clean up

| Workflow | Purpose |
|----------|---------|
| `BoundedAcquisitionWorkflow` | Draw bounding box -> create project -> tile -> acquire -> stitch |
| `ExistingImageWorkflowV2` | Select annotations on existing image -> transform coords -> acquire -> stitch |
| `MicroscopeAlignmentWorkflow` | Calibrate QuPath-to-stage coordinate mapping |
| `BackgroundCollectionWorkflow` | Acquire flat-field correction images |
| `WhiteBalanceWorkflow` | Acquire white balance calibration coefficients |
| `StackTimeLapseWorkflow` | Z-stack or time-lapse at current position |

### controller/workflow/ -- Acquisition Pipeline Helpers

These are called by the workflow classes above during the acquisition phase:

```mermaid
sequenceDiagram
    participant W as Workflow
    participant AM as AcquisitionManager
    participant ARS as AngleResolutionService
    participant TH as TileHelper
    participant ACB as CommandBuilder
    participant MSC as SocketClient
    participant SH as StitchingHelper
    participant DPD as DualProgressDialog

    W->>AM: acquireAnnotations(annotations)
    loop For each annotation
        AM->>ARS: resolve(modality, objective, detector)
        ARS-->>AM: List of AngleExposure
        AM->>TH: createTiles(annotation, fov, overlap)
        TH-->>AM: TileConfiguration
        AM->>ACB: build socket command
        ACB-->>AM: command string
        AM->>MSC: startAcquisition(command)
        AM->>DPD: update progress
        MSC-->>AM: acquisition complete
        AM->>SH: stitch(tiles, metadata)
        SH-->>AM: stitched image entry
    end
```

**AcquisitionManager** is the central orchestrator. For each annotation it:
1. Resolves rotation angles via `AngleResolutionService`
2. Creates tile grid via `TileHelper` -> `TilingUtilities`
3. Builds the acquisition command via `AcquisitionCommandBuilder`
4. Sends to Python server via `MicroscopeSocketClient`
5. Monitors progress and updates `DualProgressDialog`
6. Triggers stitching via `StitchingHelper`
7. Writes metadata via `ImageMetadataManager`

### service/ -- External System Integration

```mermaid
graph LR
    subgraph "Java (QuPath)"
        ACB[AcquisitionCommandBuilder<br/>Fluent builder]
        MSC[MicroscopeSocketClient<br/>Binary TCP protocol]
        ARS[AngleResolutionService]
    end

    subgraph "Python (Microscope Server)"
        QPS[qp_server.py<br/>Command dispatch]
        WF[workflow.py<br/>Tile acquisition]
        HC[Hardware Control<br/>Camera + Stage + Rotation]
    end

    ACB -->|"--yaml --sample<br/>--angles --exposures<br/>--detector ..."| MSC
    MSC -->|"8-byte command +<br/>ENDOFSTR message"| QPS
    QPS --> WF --> HC
    MSC <-->|"STARTED / PROGRESS /<br/>SUCCESS / FAILED"| QPS
```

**MicroscopeSocketClient** uses a dual-socket architecture:
- **Primary socket**: Long-running operations (acquisitions, calibrations)
- **Auxiliary socket**: Concurrent live viewer + stage control during acquisitions

The protocol is binary: 8-byte padded ASCII commands (e.g., `acquire_`, `getxy___`) with big-endian length-prefixed payloads.

**AcquisitionCommandBuilder** constructs flag-based string messages:
```
--yaml /path/config.yml --projects /data --sample S1 --scan-type ppm_20x_1
--region R1 --angles "(-7.0,0.0,7.0,90.0)" --exposures "(500,800,500,10)"
--objective LOCI_OBJECTIVE_20X --detector LOCI_DETECTOR_JAI_001
--pixel-size 0.1725 --wb-mode per_angle --af-tiles 9 ENDOFSTR
```

### modality/ -- Pluggable Imaging Mode System

```mermaid
graph TB
    subgraph "Registration (startup)"
        SS[SetupScope] -->|"registerHandler('ppm', ...)"| MR[ModalityRegistry]
        SS -->|"registerHandler('shg', ...)"| MR
    end

    subgraph "Runtime Lookup"
        MR -->|"prefix match<br/>'ppm_20x' -> 'ppm'"| MH[ModalityHandler]
        MH -.->|"PPM"| PPM[PPMModalityHandler]
        MH -.->|"SHG"| MPH[MultiphotonModalityHandler]
        MH -.->|"fallback"| NOP[NoOpModalityHandler]
    end

    subgraph "PPMModalityHandler"
        PPM --> RM[RotationManager]
        RM --> RS[PPMRotationStrategy]
        RS --> PP[PPMPreferences<br/>exposure defaults]
        PPM --> BUI[PPMBoundingBoxUI<br/>angle override spinners]
        PPM --> PASC[PPMAngleSelectionController<br/>angle/exposure dialog]
    end
```

**ModalityHandler** interface defines the plugin contract:

```java
public interface ModalityHandler {
    CompletableFuture<List<AngleExposure>> getRotationAngles(modality, objective, detector);
    Optional<BoundingBoxUI> createBoundingBoxUI();
    List<AngleExposure> applyAngleOverrides(angles, overrides);
    Optional<ImageType> getImageType();
    BackgroundValidationResult validateBackgroundSettings(...);
    List<ModalityMenuItem> getModalityMenuContributions();
}
```

New modalities are added by:
1. Implementing `ModalityHandler`
2. Registering with a prefix in `ModalityRegistry`
3. Defining `rotation_angles` in the YAML config

See [PPM_MODALITY.md](PPM_MODALITY.md) for the full PPM implementation.

### utilities/ -- Core Utilities

#### Coordinate Transformation Pipeline

```mermaid
graph LR
    QP["QuPath Full-Res<br/>(pixels, origin top-left)"]
    MF["Macro Flipped<br/>(if flip_x/flip_y)"]
    MO["Macro Original<br/>(unflipped pixels)"]
    ST["Stage Position<br/>(micrometers)"]

    QP <-->|"downsample<br/>+ flip transform"| MF
    MF <-->|"flip transform<br/>(scale -1, translate)"| MO
    MO <-->|"affine transform<br/>(from alignment)"| ST
```

**TransformationFunctions** provides the complete chain:
- `transformQuPathFullResToStage(coords, transform)` -- annotation to stage
- `transformStageToQuPathFullRes(coords, transform)` -- stage back to annotation
- `createFlipTransform(flipX, flipY, width, height)` -- optical flip matrix

**AffineTransformManager** persists transforms as JSON with metadata (microscope name, mounting method, Z scale/offset).

#### Configuration System

```mermaid
graph TB
    YAML["config_PPM.yml<br/>(microscope-specific)"] --> MCM[MicroscopeConfigManager<br/>Singleton]
    RES["resources_LOCI.yml<br/>(shared hardware catalog)"] --> MCM
    AF["autofocus_PPM.yml<br/>(AF parameters)"] --> MCM
    IP["imageprocessing_PPM.yml<br/>(exposure/WB profiles)"] --> MCM

    MCM -->|"getString, getDouble,<br/>getSection, getList"| Callers["Workflows, Handlers,<br/>CommandBuilder, UI"]
    MCM -->|"getDetectorFlipX/Y<br/>getDetectorDimensions<br/>getModalityPixelSize"| Callers
```

`MicroscopeConfigManager` merges the microscope config with the shared resources file. Hardware IDs (e.g., `LOCI_DETECTOR_JAI_001`) are resolved against `resources_LOCI.yml` for dimensions, flip state, debayering requirements, etc.

#### Tiling

`TilingUtilities` computes tile grids from annotations or bounding boxes:
- Calculates grid dimensions from camera FOV and configured overlap
- Applies serpentine (snake) traversal pattern for efficient stage movement
- Handles axis inversion for stage coordinate mapping
- Writes `TileConfiguration.txt` for the stitching pipeline

#### Image Metadata

`ImageMetadataManager` stores per-image metadata in QuPath project entries:

| Key | Example | Purpose |
|-----|---------|---------|
| `flip_x` / `flip_y` | `"1"` / `"0"` | Optical flip state (per-detector) |
| `detector_id` | `"LOCI_DETECTOR_JAI_001"` | Which detector captured this image |
| `image_collection` | `"3"` | Groups related images from same slide |
| `xy_offset_x/y_microns` | `"12500.0"` | Physical position for coordinate transforms |
| `modality` | `"ppm"` | Imaging modality |
| `objective` | `"LOCI_OBJECTIVE_20X"` | Objective used |
| `base_image` | `"slide_macro"` | Root image in the parent chain |

### ui/ -- User Interface

The UI layer uses JavaFX exclusively. Key components:

| Component | Purpose |
|-----------|---------|
| `UnifiedAcquisitionController` | Single consolidated dialog for all acquisition parameters |
| `AcquisitionWizardDialog` | Multi-step guided acquisition setup |
| `LiveViewerWindow` | Floating live camera feed with focus controls |
| `DualProgressDialog` | Split progress bars for acquisition + stitching |
| `StageMapWindow` | Top-down stage position visualization |
| `StageControlPanel` | XYZ joystick/button controls |
| `CameraControlController` | Exposure/gain settings |

**LiveViewerWindow** runs concurrent with acquisitions using the auxiliary socket:

```mermaid
graph TB
    subgraph "LiveViewerWindow Threads"
        FP["Frame Poller<br/>ScheduledExecutor ~10 FPS"]
        HC["Histogram Computer<br/>ExecutorService ~5 Hz"]
        FX["FX Application Thread<br/>Rendering"]
    end

    subgraph "Socket Layer"
        AUX["Auxiliary Socket<br/>(independent from primary)"]
    end

    FP -->|"GETFRAME"| AUX
    FP -->|"Platform.runLater"| FX
    HC -->|"compute histogram"| FX
```

## Design Patterns

| Pattern | Usage | Benefit |
|---------|-------|---------|
| **Strategy + Registry** | `ModalityHandler` + `ModalityRegistry` | New modalities without core changes |
| **Fluent Builder** | `AcquisitionCommandBuilder` | Readable command construction |
| **Singleton** | Controller, ConfigManager, LiveViewer | Shared state with controlled init |
| **Async/Future** | `CompletableFuture` throughout | Non-blocking UI |
| **Observer** | `NotificationService` | Decoupled event handling |
| **Command** | Socket protocol | Decouples QuPath from server implementation |

## Thread Safety

- `ModalityRegistry` uses `ConcurrentHashMap`
- All UI updates use `Platform.runLater()`
- `MicroscopeSocketClient` uses `synchronized` locks per socket
- Acquisition runs on `ForkJoinPool.commonPool` threads
- Stitching uses a single-threaded executor to prevent resource exhaustion

# Hardware Abstraction Layer

Developer reference for the Python-side hardware abstraction in `microscope_control`. This layer sits between the QPSC command server (which receives socket commands from QuPath) and Micro-Manager (which controls the physical hardware).

## Component Architecture

A microscope is composed of five swappable components:

```mermaid
graph TB
    subgraph "MicroscopeHardware (ABC)"
        CAM["camera: Camera<br/>(required)"]
        STG["stage: Stage<br/>(required)"]
        ROT["rotation_stage: RotationStage<br/>(optional)"]
        ILL["illumination: Illumination<br/>(optional)"]
        DET["detector: Detector<br/>(optional)"]
    end

    subgraph "Camera Implementations"
        PMC["PycromanagerCamera<br/>Generic MM camera<br/>Bayer debayer, software WB"]
        JAI["JAICamera<br/>3-CCD prism<br/>per-channel exp/gain"]
        LSC["LaserScanningCamera<br/>OSc-LSM<br/>configurable resolution"]
    end

    subgraph "Stage"
        PMS["PycromanagerStage<br/>Any MM stage<br/>+ optional condenser<br/>+ optional turret"]
    end

    subgraph "Rotation"
        PIZ["PIZRotationStage"]
        THR["ThorRotationStage"]
        DUM["DummyRotationStage"]
    end

    subgraph "Illumination"
        LED["LEDIllumination<br/>Analog voltage 0-5V"]
        POC["PockelsCell<br/>Laser power 0-1V"]
    end

    subgraph "Detector"
        PMT["PMTDetector<br/>DCC-100 gain + overload"]
    end

    CAM --> PMC & JAI & LSC
    STG --> PMS
    ROT --> PIZ & THR & DUM
    ILL --> LED & POC
    DET --> PMT
```

## How Components Are Created

`PycromanagerHardware.__init__()` auto-detects hardware from the YAML config and Micro-Manager state:

```mermaid
sequenceDiagram
    participant Init as __init__()
    participant Core as MM Core
    participant YAML as Config YAML
    participant Reg as Camera Registry

    Init->>Core: get_property("Core", "Camera")
    Core-->>Init: "JAICamera"

    Init->>YAML: read id_detector section
    YAML-->>Init: {LOCI_DETECTOR_JAI_001: {device: JAICamera, flip_x: false, ...}}

    loop For each detector in config
        Init->>Reg: create Camera for device type
        Note over Reg: JAICamera -> JAICamera class<br/>OSc-LSM -> LaserScanningCamera<br/>other -> PycromanagerCamera
    end

    Init->>YAML: read stage config
    Init->>Init: create PycromanagerStage(core, settings)

    Init->>YAML: read modalities for rotation_stage
    alt Rotation stage configured
        Init->>Init: create PIZ/Thor/DummyRotationStage
    end
```

## Camera Hierarchy

```mermaid
classDiagram
    class Camera {
        <<ABC>>
        +snap_image(**kwargs)*
        +get_name()*
        +set_exposure(ms)*
        +get_exposure()*
        +get_fov_pixels()*
        +get_pixel_size_um()*
        +extract_green_channel(img)*
        +flip_x: bool
        +flip_y: bool
        +get_fov_um()
        +supports_per_channel_exposure()
        +supports_hardware_white_balance()
        +white_balance(img, ...)
        +start_continuous_acquisition()
        +stop_continuous_acquisition()
        +get_live_frame()
    }

    class PycromanagerCamera {
        -_core: Core
        -_studio: Studio
        -_detector_config: dict
        -_name: str
        -_flip_x: bool
        -_flip_y: bool
        +snap_image() stop streaming, snap, reshape, debayer, reorder
        +extract_green_channel() Bayer green pixel extraction
        #_should_debayer() True for MicroPublisher6
        #_pre_snap_setup() hook for subclasses
        #_debayer_image() GRBG pattern -> RGB
        #_reorder_channels() BGRA -> RGB
    }

    class JAICamera {
        -_properties: JAICameraProperties
        +snap_image() WB Off, snap, BGRA->RGB, no debayer
        +extract_green_channel() np.mean(img, axis=2)
        +set_exposure() frame rate + exposure
        +supports_per_channel_exposure() True
        +set_channel_exposures(r, g, b)
        +set_unified_gain(gain)
        +set_rb_analog_gains(r, b)
        +clear_awb_corrections()
    }

    class LaserScanningCamera {
        -_resolution: int
        -_base_pixel_size_um: float
        +snap_image() scan, reshape to square mono
        +set_resolution(256-2048)
        +set_pixel_rate_hz(50k-1.25M)
        +get_dwell_time_us()
        +set_exposure(ms) converts to pixel rate
        +get_pixel_size_um() scales with resolution
        +extract_green_channel() return as-is (mono)
    }

    Camera <|-- PycromanagerCamera
    PycromanagerCamera <|-- JAICamera
    PycromanagerCamera <|-- LaserScanningCamera
```

### Per-Detector Optical Flip

Each camera carries `flip_x` and `flip_y` properties read from the detector's YAML config. This replaces the old global flip preference.

```yaml
# resources_LOCI.yml
id_detector:
  LOCI_DETECTOR_QCAM_001:
    device: 'QCamera'
    flip_x: true       # Brightfield camera IS flipped
    flip_y: true
  LOCI_PMT_001:
    device: 'OSc-LSM'
    flip_x: false      # Laser scanner is NOT flipped
    flip_y: false
```

On the Java side, `MicroscopeConfigManager.getDetectorFlipX/Y(detectorId)` reads these values. The flip fallback chain is:

```
Image metadata (most specific)
  -> Detector config from YAML
    -> Global preference (legacy fallback)
```

For SIFT alignment, flip is XOR'd: `sift_flip = macro_flip XOR detector_flip` (if both flipped, they cancel out).

### Multi-Camera Registry

`PycromanagerHardware` maintains a registry of all configured cameras:

```python
hardware.camera_registry
# {'LOCI_DETECTOR_JAI_001': JAICamera(...),
#  'LOCI_DETECTOR_TELEDYNE_001': PycromanagerCamera(...)}

hardware.camera                        # Returns active camera
hardware.set_active_camera('LOCI_PMT_001')  # Switch active
hardware.get_camera_for_detector('LOCI_DETECTOR_JAI_001')  # Direct access
```

## Stage

```mermaid
classDiagram
    class Stage {
        <<ABC>>
        +move_xy(x, y)*
        +move_xy_no_wait(x, y)*
        +get_xy()*
        +wait_xy()*
        +move_z(z)*
        +move_z_no_wait(z)*
        +get_z()*
        +wait_z()*
        +get_xyz()
        +wait_all()
        +has_secondary_z()
        +move_secondary_z(z)
        +has_turret()
        +set_turret_position(label)
        +get_turret_position()
    }

    class PycromanagerStage {
        -_core: Core
        -_settings: dict
        +move_xy() core.set_xy_position + wait
        +move_z() ensure_z_device + set_position + wait
        +has_secondary_z() checks config f_stage
        +move_secondary_z() switch focus device, move, switch back
        +has_turret() checks config obj_slider
        +set_turret_position() core.set_property(turret, label)
    }

    Stage <|-- PycromanagerStage
```

The CAMM microscope uses:
- `ZStage:Z:32` -- primary Z focus
- `ZStage:F:32` -- secondary Z condenser (different focal plane per modality)
- `Turret:O:35` -- objective turret (4x / 20x switching)

Micro-Manager only has one active "focus device" at a time. `PycromanagerStage.move_secondary_z()` temporarily switches the focus device to the F-stage, moves it, then switches back to the primary Z.

## Rotation Stage

```mermaid
graph LR
    subgraph "RotationStage ABC"
        SA["set_angle(theta)"]
        GA["get_angle()"]
        HM["home()"]
        WT["wait()"]
    end

    subgraph "PIZRotationStage"
        P1["device_pos = theta * 1000 + offset"]
        P2["theta = (device_pos - offset) / 1000"]
    end

    subgraph "ThorRotationStage"
        T1["device_pos = -2 * theta + 276"]
        T2["theta = (276 - device_pos) / 2"]
    end

    subgraph "DummyRotationStage"
        D1["self._angle = theta<br/>(in-memory, no hardware)"]
    end
```

Each rotation stage has its own angle-to-device-position conversion formula. The caller always works in degrees (birefringence angle space); the implementation handles the hardware-specific conversion.

## Illumination & Detector

These are optional components for systems with separate light sources and detectors (e.g., CAMM with LED + laser + PMT).

```mermaid
classDiagram
    class Illumination {
        <<ABC>>
        +on()*
        +off()*
        +set_power(power)*
        +get_power()*
        +get_power_range()*
    }

    class LEDIllumination {
        +set_power(voltage) 0-5V
        device: LED-Dev1ao0
    }

    class PockelsCell {
        +set_power(voltage) 0-1V
        +set_transmission(fraction)
        device: PockelsCell-Dev1ao1
    }

    class Detector {
        <<ABC>>
        +enable()*
        +disable()*
        +set_gain(gain)*
        +get_gain()*
        +clear_overload()
    }

    class PMTDetector {
        +enable() On + ClearOverload + confirm On
        +disable() Off (SAFETY: before bright light!)
        +set_gain(fraction) 0-1 mapped to 0-100%
        +reset() Off -> On -> ClearOverload
        device: DCC100
    }

    Illumination <|-- LEDIllumination
    Illumination <|-- PockelsCell
    Detector <|-- PMTDetector
```

## Data Flow: QuPath to Hardware

```mermaid
sequenceDiagram
    participant QP as QuPath<br/>(Java)
    participant SC as SocketClient<br/>(Java)
    participant SV as qp_server.py<br/>(Python)
    participant WF as workflow.py<br/>(Python)
    participant HW as PycromanagerHardware
    participant CAM as Camera
    participant STG as Stage
    participant ROT as RotationStage

    QP->>SC: startAcquisition(commandBuilder)
    SC->>SV: ACQUIRE + "--yaml ... --angles ... ENDOFSTR"
    SV->>WF: run_acquisition(hardware, params)

    loop For each tile position
        WF->>STG: move_xy(x, y)
        loop For each angle
            WF->>ROT: set_angle(theta)
            WF->>CAM: set_exposure(ms)
            WF->>CAM: snap_image()
            CAM-->>WF: (image, tags)
            WF->>WF: save tile to disk
        end
    end

    WF-->>SV: SUCCESS + JSON results
    SV-->>SC: SUCCESS
    SC-->>QP: acquisition complete
```

## File Listing

| File | Lines | Purpose |
|------|-------|---------|
| `hardware/base.py` | 326 | MicroscopeHardware ABC + Position + delegations |
| `hardware/camera/base.py` | 191 | Camera ABC |
| `hardware/camera/pycromanager_camera.py` | 416 | Generic MM camera |
| `hardware/camera/jai_camera.py` | 211 | JAI 3-CCD prism camera |
| `hardware/camera/laser_scanning_camera.py` | 217 | OSc-LSM laser scanner |
| `hardware/stage.py` | 300 | Stage ABC + PycromanagerStage |
| `hardware/rotation.py` | 193 | RotationStage ABC + PIZ/Thor/Dummy |
| `hardware/illumination.py` | 163 | Illumination ABC + LED/PockelsCell |
| `hardware/detector.py` | 175 | Detector ABC + PMTDetector |
| `hardware/pycromanager.py` | 1058 | PycromanagerHardware (composes all above) |

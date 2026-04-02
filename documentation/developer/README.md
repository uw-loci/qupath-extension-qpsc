# Developer Documentation

Technical reference for QPSC extension developers. These documents describe the internal architecture, not user-facing workflows.

## Documents

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Package structure, class roles, design patterns, and how the major subsystems connect |
| [PPM_MODALITY.md](PPM_MODALITY.md) | How Polarized light microscopy is implemented: modality handler, angle resolution, exposure management, calibration workflows |
| [HARDWARE_ABSTRACTION.md](HARDWARE_ABSTRACTION.md) | Python-side hardware component model: Camera, Stage, RotationStage, Illumination, Detector hierarchies and how they map to Micro-Manager |
| [SOCKET_PROTOCOL.md](SOCKET_PROTOCOL.md) | Binary TCP protocol between QuPath and the Python server: command format, message types, acquisition lifecycle |
| [COORDINATE_TRANSFORMS.md](COORDINATE_TRANSFORMS.md) | How pixel coordinates in QuPath map to physical stage positions: flip, inversion, affine transforms, SIFT alignment, tilt correction |

## Quick Orientation

The system has three main layers:

```
QuPath Extension (Java)          -- UI, workflows, coordinate transforms
    |
    | TCP socket protocol (8-byte commands + payloads)
    v
Microscope Command Server (Python) -- command dispatch, acquisition workflow
    |
    | Hardware abstraction API
    v
Microscope Control (Python)      -- Camera, Stage, Rotation via Micro-Manager
```

Start with [ARCHITECTURE.md](ARCHITECTURE.md) for the Java package structure, then [HARDWARE_ABSTRACTION.md](HARDWARE_ABSTRACTION.md) for the Python hardware layer, and [SOCKET_PROTOCOL.md](SOCKET_PROTOCOL.md) for how they communicate.

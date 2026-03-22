# QPSC Utilities Reference

This document provides an overview of all utilities available in the QPSC extension. Click any tool name for full documentation including all options, workflow details, and troubleshooting.

---

## Quick Reference

| Utility | Purpose | Menu Location |
|---------|---------|---------------|
| [Setup Wizard](tools/setup-wizard.md) | Create microscope config files (first-time setup) | Extensions > QP Scope > Setup Wizard |
| [Acquisition Wizard](tools/acquisition-wizard.md) | Guided setup wizard for new users | Extensions > QP Scope > Acquisition Wizard |
| [Live Camera Viewer](tools/live-viewer.md) | Real-time camera feed with integrated stage control | Extensions > QP Scope > Live Viewer |
| [Camera Control](tools/camera-control.md) | View/test camera exposure and gain settings | Extensions > QP Scope > Camera Control... |
| [Stage Map](tools/stage-map.md) | Visual map showing stage insert with slide positions | Extensions > QP Scope > Stage Map |
| [Communication Settings](tools/server-connection.md) | Configure server connection and notification alerts | Extensions > QP Scope > Communication Settings... |
| [Background Collection](tools/background-collection.md) | Capture flat-field correction images | Extensions > QP Scope > Collect Background Images |
| [White Balance Calibration](tools/white-balance-calibration.md) | Calibrate JAI 3-CCD camera white balance | Extensions > QP Scope > White Balance Calibration... |
| [WB Comparison Test](tools/wb-comparison-test.md) | Compare white balance modes side-by-side | Extensions > QP Scope > WB Comparison Test... |
| [JAI Noise Characterization](tools/noise-characterization.md) | Measure camera noise statistics | Extensions > QP Scope > JAI Camera > Noise Characterization... |
| [Polarizer Calibration](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/polarizer-calibration.md) | Calibrate polarizer rotation stage for PPM | Extensions > QP Scope > Polarizer Calibration (PPM)... |
| [PPM Reference Slide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md) | Hue-to-angle calibration from sunburst slide | Extensions > QP Scope > Utilities > PPM Reference Slide... |
| [Autofocus Editor](tools/autofocus-editor.md) | Configure per-objective autofocus parameters | Extensions > QP Scope > Autofocus Configuration Editor... |
| [Autofocus Benchmark](tools/autofocus-benchmark.md) | Find optimal autofocus settings systematically | Extensions > QP Scope > Autofocus Parameter Benchmark... |
| [PPM Sensitivity Test](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-sensitivity-test.md) | Test rotation stage precision | Extensions > QP Scope > PPM Rotation Sensitivity Test... |
| [PPM Birefringence Optimization](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-birefringence-optimization.md) | Find optimal polarizer angle for maximum contrast | Extensions > QP Scope > PPM Birefringence Optimization... |
| [PPM Hue Range Filter](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-hue-range-filter.md) | Interactive HSV filtering for PPM images | Extensions > QP Scope > PPM Hue Range Filter |
| [PPM Polarity Plot](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-polarity-plot.md) | Polar histogram visualization of fiber orientations | Extensions > QP Scope > PPM Polarity Plot |
| [Surface Perpendicularity](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/surface-perpendicularity.md) | Verify surface is perpendicular to optical axis | Extensions > QP Scope > Surface Perpendicularity |
| [Batch PPM Analysis](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/batch-ppm-analysis.md) | Batch processing of PPM image sets | Extensions > QP Scope > Batch PPM Analysis |
| [Back-Propagate Annotations](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/back-propagate-annotations.md) | Copy annotations from child images back to parent | Extensions > QP Scope > Back-Propagate Annotations |

---

## Imaging & Stage Control

### [Live Camera Viewer](tools/live-viewer.md)

Real-time camera feed with integrated stage control, histogram, and noise statistics. The primary tool for verifying microscope communication, positioning the stage, and checking camera settings before acquisition. Includes a virtual joystick, saved stage positions, and per-channel saturation monitoring.

### [Camera Control](tools/camera-control.md)

View and test camera exposure and gain settings loaded from calibration profiles. Particularly useful for JAI 3-CCD camera white balance troubleshooting. Displays per-angle cards with exposure, gain, and an Apply button that sets camera settings and rotates the polarizer.

### [Stage Map](tools/stage-map.md)

Visual representation of the microscope stage insert showing slide positions and current stage location. Supports configurable macro image overlay for navigation context.

---

## Connection & Configuration

### [Setup Wizard](tools/setup-wizard.md)

Step-by-step wizard that creates all required microscope YAML configuration files. Appears automatically as the first menu item when no valid configuration is found. Guides users through hardware selection (objectives, detectors, stage), pixel size calibration, modality setup, and server connection. Uses a bundled catalog of known LOCI hardware for quick selection.

### [Communication Settings](tools/server-connection.md)

Configure and test the connection between QuPath and the microscope control server, and manage notification alerts. Includes connection, advanced timeout, status, and alerts tabs with push notification support via ntfy.sh.

### [Acquisition Wizard](tools/acquisition-wizard.md)

Guided setup wizard that walks new users through the complete first-time configuration process. Checks prerequisites (server connection, configuration files, calibrations) and launches the appropriate setup tools.

---

## Calibration Tools

### [White Balance Calibration](tools/white-balance-calibration.md)

Calibrate per-channel (R, G, B) exposure times for JAI 3-CCD prism cameras. Supports Simple (single angle) and PPM (4 angles) modes. Results are saved to YAML and used automatically by background collection and acquisition.

### [WB Comparison Test](tools/wb-comparison-test.md)

Acquire and display side-by-side images using different white balance modes. Helps users visually compare Off, Camera AWB, Simple, and Per-angle WB modes to select the best option for their sample.

### [Background Collection](tools/background-collection.md)

Acquire flat-field correction images with adaptive exposure control. Automatically adjusts exposure to reach target intensity at each angle. Supports per-channel white balance for JAI cameras.

### [Polarizer Calibration](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/polarizer-calibration.md)

Two-stage calibration (coarse sweep + fine sweep) to find the exact hardware offset for the PPM rotation stage. Determines the encoder position corresponding to crossed polarizers. Only needed after hardware installation or repositioning.

### [PPM Reference Slide (Sunburst Calibration)](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md)

Create a hue-to-angle calibration from a reference slide with a sunburst pattern. Samples hue values along radial spokes and creates a linear regression for PPM image processing. Includes manual center selection and debug mask visualization.

### [Autofocus Configuration Editor](tools/autofocus-editor.md)

Configure per-objective autofocus parameters (n_steps, search_range_um, n_tiles) in an intuitive GUI. Supports editing multiple objectives before saving to YAML.

### [Autofocus Parameter Benchmark](tools/autofocus-benchmark.md)

Systematically find optimal autofocus settings by testing multiple parameter combinations at various defocus distances. Generates CSV results and performance recommendations.

### [JAI Noise Characterization](tools/noise-characterization.md)

Measure camera noise statistics (mean, standard deviation, SNR) with Quick, Full, or Custom presets. Useful for characterizing camera performance and detecting hardware issues.

---

## PPM Analysis Tools

> **PPM calibration and analysis workflows are provided by the [qupath-extension-ppm](https://github.com/uw-loci/qupath-extension-ppm) modality plugin.** See the [PPM README](https://github.com/uw-loci/qupath-extension-ppm#readme) for a complete overview and recommended workflow order. PPM computations use the [ppm_library](https://github.com/uw-loci/ppm_library) Python package.

**All analysis tools below require a completed [Sunburst Calibration](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md)** (.npz file) and operate on PPM birefringence or sum images.

### Calibration & Hardware Validation

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| PPM Sensitivity Test | Test rotation stage precision and repeatability | Connected microscope | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-sensitivity-test.md) |
| PPM Birefringence Optimization | Find optimal polarizer angle for maximum contrast | Connected microscope, sample slide | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-birefringence-optimization.md) |

### Interactive Analysis

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| PPM Hue Range Filter | Real-time overlay highlighting pixels whose fiber angle falls within a user-specified range | Sunburst calibration, open PPM image | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-hue-range-filter.md) |
| PPM Polarity Plot | Rose diagram of fiber orientation distribution with circular statistics (mean, std, resultant length) | Sunburst calibration, open PPM image, selected annotation | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-polarity-plot.md) |

### Boundary & Perpendicularity Analysis

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| Surface Perpendicularity (PS-TACS) | Score fiber orientation relative to annotation boundaries using the PS-TACS method | Sunburst calibration, open PPM birefringence image, boundary annotation (tissue interface) + region annotation (analysis zone), pixel classifier or thresholder for foreground segmentation | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/surface-perpendicularity.md) |

### Batch & Project-Wide

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| Batch PPM Analysis | Run polarity and/or perpendicularity analysis across all annotations in a project; exports CSV | Sunburst calibration, project with annotated PPM images | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/batch-ppm-analysis.md) |
| Back-Propagate Annotations | Transfer annotations from sub-images back to parent/base images using alignment transforms | Multi-sample project with sub-images from Existing Image workflow, classified annotations | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/back-propagate-annotations.md) |

---

## See Also

- [Workflows Guide](WORKFLOWS.md) - Step-by-step workflow documentation
- [Preferences Reference](PREFERENCES.md) - All settings explained
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions

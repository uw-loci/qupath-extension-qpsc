# Setup Wizard

**Menu:** Extensions > QP Scope > Setup Wizard (Start Here)...
*(Moves to Extensions > QP Scope > Utilities > Setup Wizard... once configured)*

**Purpose:** Create all required microscope YAML configuration files through a guided step-by-step process. Designed for first-time setup when no valid microscope configuration exists.

![Setup Wizard welcome step](../images/Docs_SetupWizard_Welcome.png)

---

## Overview

The Setup Wizard walks you through creating three YAML configuration files:

| File | Contents |
|------|----------|
| `config_<name>.yml` | Hardware definitions: objectives, detectors, stage, modalities |
| `autofocus_<name>.yml` | Per-objective autofocus parameters (n_steps, search_range, n_tiles) |
| `imageprocessing_<name>.yml` | Imaging profiles with placeholder exposures per modality/objective/detector |

These files are required before any acquisition workflow can run.

---

## When to Use

- **First-time QPSC setup** -- the wizard appears automatically as the first menu item
- **Setting up a new microscope** -- creates config files for a different microscope system
- **Starting from scratch** -- when existing config files are missing or corrupted

---

## Prerequisites

- QuPath with QPSC extension installed
- Knowledge of your microscope hardware (objectives, detectors, stage limits)
- Optional: microscope server running (for connection test in Step 6)

---

## Wizard Steps

### Step 1: Welcome & Basics

Configure the output location and microscope identity:

- **Configuration Directory**: Where to save the generated YAML files (uses a directory chooser)
- **Microscope Name**: Alphanumeric identifier used in filenames (e.g., `PPM`, `BF_Scope1`)
- **Microscope Type**: Select from supported types (standard, inverted, stereo, confocal)

### Step 2: Hardware (Objectives & Detectors)

Define the objectives and detectors available on your microscope:

- **Objectives table**: ID, Name, Magnification, Numerical Aperture
- **Detectors table**: ID, Name, Sensor Width (px), Sensor Height (px)
- **Add from Catalog**: Select from known LOCI hardware entries (bundled `resources_LOCI.yml`)
- **Add Custom**: Manually enter hardware specifications
- Duplicate IDs are automatically detected and prevented

### Step 3: Pixel Size Calibration

Enter the physical pixel size (micrometers/pixel) for each objective-detector combination:

- Dynamic grid showing all objective x detector combinations
- Tooltips show auto-calculated values from sensor pixel size and magnification (when available from catalog)
- All combinations must have a positive pixel size before proceeding

### Step 4: Stage Configuration

Define stage limits and select the stage hardware:

- **Stage ID**: Select from catalog or enter custom ID
- **Stage Limits**: Min/Max for X, Y, Z axes (micrometers)
- Spinners with configurable ranges

### Step 5: Modalities

Configure imaging modalities available on this microscope:

- **Modality table**: Name, Type, and type-specific configuration
- Supported types: Polarized (PPM), Brightfield, Fluorescence, Multiphoton (SHG)
- Each type has a dedicated configuration panel with type-specific fields

#### Modality Type Reference

| Type | Wizard Fields | Use Case |
|------|--------------|----------|
| **Polarized** | Rotation stage device, rotation angles (name + tick value table). Pre-filled with standard PPM defaults (crossed, uncrossed, positive, negative). | Polarized light microscopy (PPM) |
| **Brightfield** | Lamp device name | Standard transmitted light imaging |
| **Fluorescence** | Filter wheel device name | Epifluorescence imaging |
| **Multiphoton** | Laser (device, wavelength, range), Pockels cell (device, max voltage), PMT (device, connector, max gain), zoom (device, range, default), shutter (device). See below. | SHG, two-photon, multiphoton imaging |

#### Multiphoton Configuration Details

The multiphoton panel collects the essential hardware settings for laser scanning microscopy. Fields are organized into sections:

| Section | Field | Required? | Description |
|---------|-------|-----------|-------------|
| **Laser** | Device | Yes | Micro-Manager device name (e.g., Chameleon) |
| | Wavelength (nm) | Yes | Default operating wavelength |
| | Wavelength range | Yes | Tunable min-max range in nm |
| **Pockels Cell** | Device | Yes | Analog output for laser power control |
| | Max voltage | Yes | Safety limit (typically 1.0V) |
| **PMT** | Device | Yes | PMT controller (e.g., DCC100) |
| | Connector | Yes | Connector number on multi-channel controller |
| | Max gain (%) | Yes | Safety limit for high-voltage percentage |
| **Zoom** | Device | No | Scan magnifier (leave blank if none) |
| | Range | No | Min-max zoom factors |
| | Default | No | Default zoom factor |
| **Shutter** | Device | No | Laser safety shutter (leave blank if none) |

> **Note:** Some advanced multiphoton settings (digital shutter IO states, NI DAQ property names, mode positions, XY offsets between modalities) are not collected by the wizard. These are configured by editing the YAML file directly. See [Laser Scanning Quick Start](../QUICKSTART-LSM.md) for the complete YAML reference.

### Step 6: Server Connection (Optional)

Configure the microscope control server:

- **Host**: IP address or hostname (default: 127.0.0.1)
- **Port**: Server port number (default: 5000)
- **Test Connection**: Background TCP connection test with pass/fail feedback
- This step is optional -- you can skip it and configure server settings later in Preferences

### Step 7: Review & Save

Review all configured values before saving:

- Read-only summary of all settings from previous steps
- List of files that will be created
- Warnings for any incomplete or potentially problematic settings
- Click **"Save & Finish"** to generate all files

---

## What Happens on Save

1. **Three YAML files** are created in the chosen directory
2. **Resources file** (`resources_LOCI.yml`) is copied to the config directory if not already present
3. **QuPath preferences** are updated automatically:
   - Microscope Config File path
   - Server Host and Port
4. **MicroscopeConfigManager** reloads with the new configuration
5. A completion notification lists recommended next steps

---

## After the Wizard

Once configuration files are created, follow these steps:

1. **Restart QuPath** to fully load the new configuration
2. **Connect to the microscope server** (Extensions > QP Scope > Communication Settings)
3. **Run Background Collection** for flat-field correction images
4. **Run White Balance Calibration** (for JAI/prism cameras)
5. **Run Autofocus Benchmark** to tune focus parameters for your objectives

---

## Schema Sync Mechanism

The wizard uses `ConfigSchema.java` as a single source of truth for all required YAML keys and structure. This ensures:

- Generated configs always match the expected layout
- If the config format changes, the wizard and validation code stay in sync
- `SYNC` comments in `MicroscopeConfigManager.validateConfiguration()` reference `ConfigSchema`

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Wizard doesn't appear in menu | Check that QPSC extension is loaded (Extensions menu) |
| "Save Failed" error | Verify write permissions to the chosen directory |
| Config created but QPSC says invalid | Restart QuPath to reload configuration |
| Hardware not in catalog | Use "Add Custom" to enter specifications manually |
| Server test fails | Verify the server is running and host/port are correct |

---

## See Also

- [Acquisition Wizard](acquisition-wizard.md) - Pre-acquisition prerequisite checker
- [Communication Settings](server-connection.md) - Server connection and notification alerts
- [Autofocus Editor](autofocus-editor.md) - Fine-tune autofocus parameters after setup
- [Preferences Reference](../PREFERENCES.md) - All QPSC preferences explained

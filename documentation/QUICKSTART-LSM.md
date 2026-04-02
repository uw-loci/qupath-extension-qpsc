# Quick Start: Laser Scanning / SHG / Multiphoton Acquisition

> Get from "everything is installed" to "I have my first SHG scan."

> [Back to README](../README.md) | [Workflows Guide](WORKFLOWS.md) | [Brightfield Quick Start](QUICKSTART-BF.md)

---

## Overview

This guide covers setting up QPSC for **laser scanning microscopy** -- Second Harmonic Generation (SHG), two-photon fluorescence, or other galvo-scanned modalities. If your system uses a point-scanning detector (PMT) rather than an area camera, this is the right guide.

**Key differences from brightfield:**
- The "camera" is a galvo scan engine (e.g., OpenScan OSc-LSM) that builds images pixel-by-pixel
- Images are **grayscale 16-bit** from the PMT -- no color channels, no white balance
- Exposure is controlled by **pixel dwell time** (scan speed), not shutter time
- Resolution is configurable (256, 512, 1024, 2048 pixels per line, always square)
- Additional hardware: Pockels cell (laser power), PMT (detector gain), optional zoom magnifier

---

## Step 1: Start the Software (2 minutes)

Start the three programs **in order**, with additional verification for laser scanning hardware:

| Order | Program | What to Do |
|-------|---------|------------|
| 1st | **Micro-Manager** | Launch and load your config. Verify the scan engine, PMT controller, and Pockels cell are recognized. |
| 2nd | **Python Server** | Run `start_server.bat`. Wait for "Server ready." |
| 3rd | **QuPath** | Open QuPath. The "QP Scope" menu should appear. |

**Micro-Manager hardware check:**
- The scan engine should appear as a camera device (e.g., `OSc-LSM`)
- PMT controller should be listed (e.g., `DCC100`)
- Pockels cell analog output should be accessible (e.g., via NI DAQ device)
- If using a zoom magnifier (e.g., `OSc-Magnifier`), verify it responds

---

## Step 2: Configure the SHG Modality

You can configure your microscope either through the **Setup Wizard** or by editing YAML files directly. Both approaches produce the same configuration files.

### Option A: Setup Wizard (Recommended for First Time)

**Menu: Extensions -> QP Scope -> Setup Wizard**

Follow the wizard through Steps 1-4 (basics, hardware, pixel sizes, stage) as you would for any microscope. At **Step 5 (Modalities)**:

1. Click **Add Modality**
2. Set **Name** to `shg` (this becomes the YAML key and scan type prefix)
3. Set **Type** to `multiphoton`
4. Fill in the multiphoton configuration panel:

| Section | Field | Description | Example |
|---------|-------|-------------|---------|
| **Laser** | Device | Micro-Manager device name for your laser | `Chameleon` |
| | Wavelength (nm) | Default operating wavelength | `800` |
| | Wavelength range | Tunable range of your laser | `690` - `1040` |
| **Pockels Cell** | Device | Analog output device for laser power | `PockelsCell-Dev1ao1` |
| | Max voltage | Maximum safe voltage for the Pockels cell | `1.0` |
| **PMT** | Device | PMT controller device name | `DCC100` |
| | Connector | Which connector on a multi-channel controller | `1` |
| | Max gain (%) | Maximum high-voltage percentage (safety limit) | `100.0` |
| **Zoom** | Device | Scan magnifier device (leave blank if none) | `OSc-Magnifier` |
| | Range | Min and max zoom factors | `0.5` - `10.0` |
| | Default | Zoom factor for standard operation | `1.0` |
| **Shutter** | Device | Laser safety shutter (leave blank if none) | `UniblitzShutter` |

5. If your system also has brightfield, click **Add Modality** again and add a `brightfield` entry with the lamp device.

### Option B: Manual YAML Configuration

Create `config_<YourScope>.yml` in your configuration directory. Use this template as a starting point:

```yaml
microscope:
  name: 'MyScope'
  type: 'Multimodal'
  detector_in_use: null
  objective_in_use: null
  modality: null

modalities:
  shg:
    type: 'multiphoton'
    laser:
      device: 'Chameleon'
      wavelength_nm: 800
      wavelength_range_nm: [690, 1040]
    pockels_cell:
      device: 'PockelsCell-Dev1ao1'
      max_voltage: 1.0
      # type: 'analog_voltage'    # Manual YAML only -- not set by wizard
      # property: 'Voltage'       # Manual YAML only -- NI DAQ property name
    pmt:
      device: 'DCC100'
      connector: 1
      max_gain_percent: 100.0
    zoom:
      device: 'OSc-Magnifier'
      range: [0.5, 10.0]
      default: 1.0
    shutter:
      device: 'UniblitzShutter'
    # Digital IO shutter (optional -- for NI DAQ digital shutter control)
    # digital_shutter:
    #   device: 'Shutters-DigitalIODev1'
    #   open_state: 3
    #   closed_state: 0
    background_correction:
      enabled: false

  # Add brightfield if your system has a second camera path
  # brightfield:
  #   type: 'brightfield'
  #   illumination:
  #     device: 'LED-Dev1ao0'
  #     type: 'analog_voltage'
  #     max_voltage: 5.0
  #   background_correction:
  #     enabled: true
  #     method: 'divide'

hardware:
  objectives:
    - id: 'YOUR_OBJECTIVE_ID'
      pixel_size_xy_um:
        YOUR_PMT_DETECTOR_ID: 0.509   # At 256 resolution; scales with resolution
        # YOUR_BF_CAMERA_ID: 0.222    # If dual-modality

  detectors:
    - 'YOUR_PMT_DETECTOR_ID'
    # - 'YOUR_BF_CAMERA_ID'

stage:
  stage_id: 'YOUR_STAGE_ID'
  limits:
    x_um: {low: -5000, high: 40000}
    y_um: {low: -5000, high: 25000}
    z_um: {low: -8500, high: 17000}

slide_size_um:
  x: 40000
  y: 20000
```

### YAML Fields Reference

**Wizard-configured fields** (collected automatically by the Setup Wizard):

| YAML Path | Description |
|-----------|-------------|
| `laser.device` | Micro-Manager device name for the laser |
| `laser.wavelength_nm` | Default operating wavelength |
| `laser.wavelength_range_nm` | [min, max] tunable wavelength range |
| `pockels_cell.device` | Device controlling laser transmission |
| `pockels_cell.max_voltage` | Safety limit for Pockels cell drive voltage |
| `pmt.device` | PMT controller device name |
| `pmt.connector` | Controller connector number (for multi-channel) |
| `pmt.max_gain_percent` | Safety limit for PMT high-voltage |
| `zoom.device` | Scan magnifier device (optional) |
| `zoom.range` | [min, max] zoom factors (optional) |
| `zoom.default` | Default zoom factor (optional) |
| `shutter.device` | Safety shutter device (optional) |

**Manual-only fields** (not in wizard -- edit YAML directly):

| YAML Path | Description |
|-----------|-------------|
| `pockels_cell.type` | Control type (`analog_voltage`) |
| `pockels_cell.property` | Micro-Manager property name (`Voltage`) |
| `digital_shutter.device` | NI DAQ digital IO device for shutter |
| `digital_shutter.open_state` | Digital state that opens the shutter |
| `digital_shutter.closed_state` | Digital state that closes the shutter |

---

## Step 3: Verify with the Live Viewer (2 minutes)

**Menu: Extensions -> QP Scope -> Acquisition Wizard...**

The Live Viewer opens with the scan engine as the active camera.

**What you should see:** A grayscale image updating as the galvo scans. The image will be square (e.g., 512x512).

**Quick checks:**
- Image is not pure black (PMT is on, Pockels cell is transmitting)
- Image is not saturated/clipped (PMT gain is not too high)
- Arrow buttons move the stage
- Z scroll changes focus

| Problem | Fix |
|---------|-----|
| Pure black image | Check PMT is enabled, Pockels cell voltage > 0, laser shutter is open |
| Very noisy / hot pixels | Reduce PMT gain, increase averaging |
| Image is saturated (white) | Reduce PMT gain or Pockels cell voltage |
| Wrong resolution | Set LSM-Resolution property in Micro-Manager |

---

## Step 4: Autofocus Configuration (Optional but Recommended)

SHG images typically have different contrast characteristics than brightfield. The autofocus system handles this automatically (tissue detection adapts for SHG signal characteristics), but you should verify the parameters work for your sample type.

**Menu: Extensions -> QP Scope -> Autofocus Editor**

Select your objective and adjust parameters. For SHG, the key differences are:
- `texture_threshold` may need to be lower (SHG signal is often sparser)
- `tissue_area_threshold` may need to be lower (not all tissue generates SHG)
- The focus metric (`normalized_variance` or `laplacian_variance`) works well for SHG

See [Autofocus System](AUTOFOCUS.md) for detailed guidance.

---

## Step 5: Run a Test Acquisition (5 minutes)

**Menu: Extensions -> QP Scope -> Bounded Acquisition**

1. **Sample Name:** Enter a name (e.g., "SHG_Test01")
2. **Hardware:** Select your SHG modality, objective, and PMT detector
3. **Center position:** Click **"Use Current Position as Center"**
4. **Region size:** Start small: **Width: 500, Height: 500** micrometers
5. **Click Start Acquisition**

The system acquires tiles, stitches them, and adds the result to your QuPath project. SHG images will appear as grayscale.

---

## Step 6: Safety Considerations

### PMT Protection

The PMT is sensitive to excessive light. **Always disable the PMT before switching to brightfield or turning on room lights.** Overloading the PMT can permanently damage it.

QPSC's modality switching sequence should handle this automatically (PMT is disabled before brightfield illumination is activated), but verify this is configured correctly for your hardware.

### Laser Safety

- Follow your institution's laser safety protocols
- The Pockels cell controls laser power reaching the sample -- verify its range is correct
- The safety shutter (if configured) provides an additional layer of protection
- Never look into the beam path, even with the shutter "closed"

---

## Step 7: Multi-Modality Setup (BF + SHG)

If your system has both a brightfield camera and a laser scanning detector (like the CAMM configuration), additional setup is required:

### Per-Detector Flip States

The brightfield camera and PMT/scan engine may have **different optical orientations**. Each detector in `resources_LOCI.yml` (or `id_detector` in your config) should have its own `flip_x` and `flip_y` settings:

```yaml
id_detector:
  MY_BF_CAMERA:
    flip_x: true     # Brightfield camera orientation
    flip_y: true
    compatible_modalities: ['brightfield']

  MY_PMT:
    flip_x: false    # LSM orientation (may differ!)
    flip_y: false
    compatible_modalities: ['shg']
```

### Mode-Specific Stage Positions

Different modalities may have different focal planes. Configure `mode_positions` in your YAML:

```yaml
mode_positions:
  bf_20x:
    z: -6980
    f: -15800          # Condenser/collector position
    led_voltage: 5.0   # LED on for brightfield
  shg_20x:
    z: -6640           # Different focus for SHG
    f: -18500
    led_voltage: 0.0   # LED off for laser scanning
```

### XY Offsets Between Modalities

If the brightfield and SHG optical paths are physically offset, configure `modality_xy_offsets`:

```yaml
modality_xy_offsets:
  bf_20x: [-600, 10]
  shg_20x: [-580, -280]
```

### Acquisition Profiles

Define per-modality acquisition profiles that combine modality + objective + detector + LSM-specific settings:

```yaml
acquisition_profiles:
  shg_20x:
    modality: 'shg'
    objective: 'MY_OBJECTIVE_20X'
    detector: 'MY_PMT'
    lsm_resolution: 512
    lsm_pixel_rate_hz: 250000.0   # Pixel dwell time = 1/rate
    pockels_power: 0.4            # Fraction of max voltage
    pmt_gain: 0.40                # Fraction of max HV
    averaging: 2                  # Frames averaged per tile

  shg_20x_highres:
    modality: 'shg'
    objective: 'MY_OBJECTIVE_20X'
    detector: 'MY_PMT'
    lsm_resolution: 1024
    lsm_pixel_rate_hz: 125000.0   # Slower scan for better SNR
    pockels_power: 0.4
    pmt_gain: 0.40
    averaging: 4
```

> **Note:** Mode positions, XY offsets, and acquisition profiles are advanced multi-modality settings. They are configured in the YAML file directly, not through the Setup Wizard.

---

## Troubleshooting

| Problem | Quick Fix |
|---------|-----------|
| Black screen in Live Viewer | PMT off, Pockels at 0, or laser shutter closed |
| Very dim image | Increase PMT gain (carefully) or Pockels cell voltage |
| Noisy / grainy image | Increase averaging, reduce scan speed (lower pixel rate) |
| Image resolution looks wrong | Check LSM-Resolution property in Micro-Manager |
| Pixel size incorrect | Pixel size scales with resolution: `base_size * 256 / resolution` |
| Autofocus fails on SHG | Lower `texture_threshold` and `tissue_area_threshold` -- SHG signal is often sparser than brightfield |
| PMT overload warning | Reduce gain immediately; check for stray light sources |
| Stage does not move | Verify hardware in Micro-Manager |

For more help, see the full [Troubleshooting Guide](TROUBLESHOOTING.md).

---

## What's Next?

- **[Workflows Guide](WORKFLOWS.md)** -- Bounded, Existing Image, and Alignment workflows
- **[Autofocus System](AUTOFOCUS.md)** -- How focus tracking works during acquisition
- **[Brightfield Quick Start](QUICKSTART-BF.md)** -- If your system also has brightfield
- **[Setup Wizard](tools/setup-wizard.md)** -- Full wizard documentation with all modality options

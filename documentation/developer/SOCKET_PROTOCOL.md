# Socket Communication Protocol

Developer reference for the binary TCP protocol between the QuPath Java extension (`MicroscopeSocketClient`) and the Python microscope server (`qp_server.py`).

## Connection Architecture

```mermaid
graph LR
    subgraph "QuPath (Java)"
        MSC["MicroscopeSocketClient"]
        PS["Primary Socket"]
        AS["Auxiliary Socket"]
    end

    subgraph "Python Server"
        QPS["qp_server.py<br/>Thread per client"]
    end

    MSC --> PS -->|"Acquisitions, tests,<br/>calibrations"| QPS
    MSC --> AS -->|"Live viewer, stage control<br/>(concurrent with primary)"| QPS
```

The dual-socket design allows the live viewer and stage controls to operate during long-running acquisitions. Each socket gets its own handler thread on the server.

## Protocol Format

### Command Structure

All commands are **8-byte ASCII strings**, padded with underscores:

```
| 8 bytes: command |
| e.g.: "acquire_" |
```

### Message Types

**Simple command (no payload):**
```
Client: [8-byte command]
Server: [8-byte response]
```

**Command with string payload:**
```
Client: [8-byte command]
Client: [UTF-8 string + "ENDOFSTR"]
Server: [variable response, read until timeout]
```

**Command with binary payload:**
```
Client: [8-byte command]
Client: [4-byte big-endian length] [payload bytes]
Server: [4-byte big-endian length] [response bytes]
```

### CONFIG Handshake

The first command after connection must be CONFIG:

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: "config__" (8 bytes)
    C->>S: path length (4 bytes, big-endian)
    C->>S: config file path (UTF-8)

    alt Success
        S->>C: "CFG___OK" (8 bytes)
        S->>C: version length (4 bytes)
        S->>C: version JSON (UTF-8)
    else Failure
        S->>C: "CFG_FAIL" (8 bytes)
        S->>C: error length (4 bytes)
        S->>C: error message (UTF-8)
    else Blocked
        S->>C: "CFG_BLCK" (8 bytes)
        Note over S: Another client already connected
    end
```

## Command Reference

### Stage Control

| Command | Wire Format | Payload | Response |
|---------|------------|---------|----------|
| GETXY | `getxy___` | none | 16 bytes: X,Y as big-endian doubles |
| GETZ | `getz____` | none | 8 bytes: Z as big-endian double |
| GETXYZ | `getxyz__` | none | 24 bytes: X,Y,Z as big-endian doubles |
| MOVE | `move____` | 16 bytes: X,Y doubles | 8-byte ack |
| MOVEZ | `move_z__` | 8 bytes: Z double | 8-byte ack |
| MOVEXYZ | `movexyz_` | 24 bytes: X,Y,Z doubles | 8-byte ack |
| MOVER | `move_r__` | 8 bytes: angle double | 8-byte ack |
| GETR | `getr____` | none | 8 bytes: angle double |

### Acquisition

| Command | Wire Format | Payload | Response |
|---------|------------|---------|----------|
| ACQUIRE | `acquire_` | flag-based string + ENDOFSTR | STARTED -> SUCCESS/FAILED |
| BGACQUIRE | `bgacquir` | flag-based string + ENDOFSTR | STARTED -> SUCCESS/FAILED |
| STATUS | `status__` | none | status string |
| PROGRESS | `progress` | none | progress string |
| CANCEL | `cancel__` | none | 8-byte ack |

### Acquisition Message Format

The ACQUIRE payload is a flag-based string:

```
--yaml /path/config.yml
--projects /path/projects
--sample SampleName
--scan-type ppm_20x_1
--region AnnotationName
--objective LOCI_OBJECTIVE_OLYMPUS_20X_POL_001
--detector LOCI_DETECTOR_JAI_001
--pixel-size 0.1725
--angles "(-7.0,0.0,7.0,90.0)"
--exposures "(500.0,800.0,500.0,10.0)"
--bg-correction true
--bg-method divide
--bg-folder /path/to/backgrounds
--wb-mode per_angle
--processing "(debayer,background_correction,white_balance)"
--af-tiles 9
--af-steps 20
--af-range 10.0
--hint-z -3245.5
--z-stack
--z-start -5.0
--z-end 5.0
--z-step 2.0
--z-projection max
--save-raw true
ENDOFSTR
```

### Camera Control

| Command | Wire Format | Payload | Response |
|---------|------------|---------|----------|
| GETEXP | `getexp__` | none | exposure values |
| SETEXP | `setexp__` | exposure string | ack |
| GETGAIN | `getgain_` | none | gain values |
| SETGAIN | `setgain_` | gain string | ack |
| GETMODE | `getmode_` | none | mode flags |
| SETMODE | `setmode_` | mode string | ack |
| SNAP | `snap____` | exposure bytes | image data |
| GETCAM | `getcam__` | none | camera name string |

### Live Mode

| Command | Wire Format | Payload | Response |
|---------|------------|---------|----------|
| GETLIVE | `getlive_` | none | 1 byte: 0/1 |
| SETLIVE | `setlive_` | 1 byte: 0/1 | 8-byte ack |
| GETFRAME | `getframe` | none | image metadata + pixel data |
| STRTSEQ | `strtseq_` | none | 8-byte ack |
| STOPSEQ | `stopseq_` | none | 8-byte ack |

### Calibration & Testing

| Command | Wire Format | Payload | Response |
|---------|------------|---------|----------|
| TESTAF | `testaf__` | params + ENDOFSTR | AF result |
| TESTADAF | `testadaf` | params + ENDOFSTR | adaptive AF result |
| AFBENCH | `afbench_` | params + ENDOFSTR | benchmark results |
| SIFTAL | `siftal__` | `--wsi-region ... --flip-x ENDOFSTR` | `SUCCESS:x,y\|inliers:N` |
| PPMBIREF | `ppmbiref` | params + ENDOFSTR | optimization result |
| SBCALIB | `sbcalib_` | params + ENDOFSTR | calibration result |
| WBSIMPLE | `wbsimple` | params + ENDOFSTR | WB result |
| WBPPM | `wbppm___` | params + ENDOFSTR | WB result |
| PROBEZ | `probez__` | none | `PROBEZOK` or `PROBEZFL` (logs to server_session) |
| SMOOTHZ | `smoothz_` | `--yaml <path> [--objective <id>] [--range <um>] ENDOFSTR` | `SUCCESS:<i>:<f>:<shift>:<n>:<span>` / `UNAVAILABLE:<reason>` / `FAILED:<reason>` |

#### PROBEZ

One-shot Z-stage diagnostic probe. No payload. Runs a snapshot of
the focus device's property table, move-timing tests, a MaxSpeed
sensitivity sweep, a streaming-during-motion test, and a
per-exposure metric-stability sweep. Every log line is tagged
`PROBEZ [step-N]:` for easy filtering. Two CSVs per run are
written to the same `logs/` directory as the session log:
`probez_metric_range6_*.csv` and `probez_metric_range12_*.csv`.

State restoration: all writable properties on the focus device
are snapshotted at entry and restored in a `finally` block,
including Z position and camera exposure.

Intended as diagnostic tooling for new-rig onboarding and for
debugging `SMOOTHZ` UNAVAILABLE responses. See
[developer/PROBEZ.md](PROBEZ.md) for the detailed guide and
`handlers/probez.py` for the implementation.

Response: `PROBEZOK` on normal completion (~30-60 seconds),
`PROBEZFL` if a safety check failed (sequence already running,
server not configured, etc.).

#### SMOOTHZ

Streaming-based continuous-Z autofocus scan. Used by the Live
Viewer's **Smooth Focus** button as a drop-in replacement for
stepped Sweep Drift Check on calibrated hardware.

Payload flags (text, terminated by `ENDOFSTR`):

| Flag | Required | Description |
|---|---|---|
| `--yaml <path>` | yes | Path to the active `config_<scope>.yml` |
| `--objective <id>` | no | Caller's preferred objective (e.g. `LOCI_OBJECTIVE_OLYMPUS_20X_POL_001`). If missing, the server auto-resolves via pixel-size match against `config.hardware.objectives`. |
| `--range <um>` | no | Override of `sweep_range_um` from the yaml. |

Server-side sequence:

1. Resolve objective (client-provided > pixel-size match > yaml first entry).
2. Load `sweep_range_um` for the objective.
3. Pre-flight: motion blur budget and saturation checks; fail with `UNAVAILABLE` if either refuses.
4. Seed-move to `z_start` at full speed; drop speed property to slow for the scan motion; start continuous sequence acquisition; fire non-blocking move to `z_end`; pop every frame with `(t_ms, z_at_pop, metric)`; parabolic-fit peak; commit final Z.
5. Always restore speed property in `finally`.

Response formats:

- `SUCCESS:<initial_z>:<final_z>:<shift>:<n_samples>:<z_span>`
  -- scan completed and committed a new focus
- `UNAVAILABLE:<reason>` -- a pre-flight check refused to run;
  caller should fall back to stepped Sweep Focus (this is
  informational, not an error)
- `FAILED:<reason>` -- mid-scan error; stage state has been
  restored but no new focus was committed

See `handlers/smooth.py` and `claude-reports/2026-04-14_smooth-focus-design-and-probez-tooling.md` for the design rationale and PPM characterization results.

### System

| Command | Wire Format | Payload | Response |
|---------|------------|---------|----------|
| CONFIG | `config__` | path length + path | CFG___OK/CFG_FAIL/CFG_BLCK |
| SHUTDOWN | `shutdown` | none | none (server exits) |
| DISCONNECT | `quitclnt` | none | none (close connection) |
| GETPXSZ | `getpxsz_` | none | 8 bytes: pixel size double |
| GETFOV | `getfov__` | none | 16 bytes: FOV X,Y doubles |

## Acquisition Lifecycle

```mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> STARTED: ACQUIRE command received
    STARTED --> RUNNING: Server begins tile loop
    RUNNING --> RUNNING: PROGRESS queries (tile N/M)
    RUNNING --> SUCCESS: All tiles complete
    RUNNING --> FAILED: Error during acquisition
    RUNNING --> CANCELLED: CANCEL command received
    SUCCESS --> IDLE
    FAILED --> IDLE
    CANCELLED --> IDLE
```

The client polls STATUS and PROGRESS on a background thread while the primary socket blocks on the ACQUIRE response.

## Timeouts

| Operation | Default Timeout |
|-----------|----------------|
| Socket connection | 3000 ms |
| Default read | 5000 ms |
| Acquisition acknowledgment | 30 s |
| Autofocus test | 120 s |
| Background acquisition | 180 s |
| Z-stack / time-lapse | 600 s |

# PROBEZ: Z-Stage Diagnostic Probe

PROBEZ is a one-shot diagnostic command that characterizes a
microscope's focus device (Z stage + camera pipeline) end-to-end.
It was built to answer the question "can we run Streaming Autofocus on
this rig, and with what parameters?" but the same data is useful
whenever you need to understand the timing, property table, or
sample density envelope of a new or broken setup.

## What PROBEZ does

Seven steps, all logged with the `PROBEZ [step-N]:` tag so the
whole transcript is greppable:

| Step | What it measures |
|---|---|
| 0 | Focus device snapshot: adapter name, full property table, current Z |
| 1 | Blocking round-trip timing at default speed for moves of 1, 5, 10, 20, 50 um |
| 2 | Non-blocking issue latency + real-time position readback during motion |
| 3 | MaxSpeed (or equivalent) sensitivity sweep -- 20 um move times at each of [100, 50, 25, 10, 5, 2, 1] |
| 4 | Streaming during motion at several ranges -- does the camera actually deliver frames while the stage moves? |
| 5a | Static metric stability: 20 snaps at a constant Z, verify the focus metric doesn't drift |
| 5b | Streaming metric stability across exposures [0.7, 5, 20, 50, 100] ms |
| 5 per-range | Snap-during-motion + stepped ground-truth comparison at 6 and 12 um, CSV dumped |

All state (Z position, speed property, exposure) is snapshotted
at the start and restored in a `finally` block. Safe to re-run.

## When to use it

- **New rig onboarding**. Before enabling Streaming Autofocus (or
  diagnosing why a stepped sweep is slow on an unfamiliar
  microscope), run PROBEZ to establish baselines.
- **Streaming Autofocus returning UNAVAILABLE on tissue**. PROBEZ shows
  exactly which pre-flight check would fail and why: blur
  budget, metric stability, saturation, or speed-property
  detection.
- **Comparing stepped vs streaming on a specific camera**. Step 5
  runs a direct 3-way comparison and reports a VERDICT per range.
- **Regression testing for smooth-scan code changes**. PROBEZ's
  known-good output on PPM is a reference baseline.

## When NOT to use it

- **During an acquisition.** It moves the stage, starts the
  camera streaming, and writes camera properties. The handler
  refuses to run if `is_sequence_running()` is true, but you
  should not invoke it during a shared-resource operation anyway.
- **Trusting exact numbers for non-Prior stages.** The
  `MAXSPEED_VALUES` list assumes Prior ProScan's 1-100 percent
  scale. Other stages will show different curves and may need
  their own test values.

## How to run

Prerequisites: server is running, no acquisition is in progress,
the stage is over tissue with decent contrast (bare glass gives
you a flat focus curve and doesn't tell you anything useful).

From a shell on the microscope machine:

```
D:\python_microscope_server\venv_qpsc\Scripts\python.exe ^
  -m microscope_command_server.client.probez_client ^
  --config D:\python_microscope_server\microscope_configurations\config_PPM.yml
```

The client connects, sends `CONFIG` (which starts a fresh session
log under `<config_dir>/logs/`), then `PROBEZ__`, and waits up to
120 seconds for `PROBEZOK` / `PROBEZFL`.

The whole run takes 30-60 seconds on PPM. Two CSV files are
written to the same `logs/` directory:

- `probez_metric_range6_<timestamp>.csv`
- `probez_metric_range12_<timestamp>.csv`

CSV columns: `scan_type, t_ms, z_um, metric, z_span_um, exposure_ms`.

## Reading the output

Filter by tag for a clean transcript:

```
parse_server_log.py <server_session_*.log> --grep "PROBEZ" --short-time --no-level
```

Key lines to look at:

- **`PROBEZ [step-0]: Focus device ... = '<name>'`** -- which
  device is the active focus device? If this is wrong (e.g.,
  reports `PIZStage` when you expected a Prior Z), your MM
  startup config has the wrong `Core,Focus` role.
- **`PROBEZ [step-0]:   <name> = '<value>' [RW/RO] ...`** -- the
  writable property list. Streaming Autofocus needs one of
  `MaxSpeed`, `Velocity`, `Speed`, or `MaxVelocity`.
- **`PROBEZ [step-1]: dz=+X.X um  out=YYYms`** -- default-speed
  blocking round-trip times. Compare to step-2's non-blocking
  times: the difference is the `wait_for_device` polling
  overhead that Win 1 eliminates.
- **`PROBEZ [step-2]: dz=... trace: (Tms,z,B/I)`** -- the
  mid-motion position trace. If the first-sample z is already
  near the target, the stage is too fast to resolve at the
  current poll rate, and streaming AF needs the stage slowed further.
- **`PROBEZ [step-3]: MaxSpeed=N  out=YYYms (~Z um/s)`** --
  velocity at each speed. Pick the slowest speed where forward
  velocity is > 5 um/s.
- **`PROBEZ [step-4 range=X]: z_reported_span=Y.Y um`** -- how
  much z the streaming samples actually cover. Should be close
  to the requested range.
- **`PROBEZ [step-5 static]: CV=X.XX% ... PASSED`** -- snap
  metric stability at z0. If this FAILS, the camera pipeline
  has adaptive gain/WB and no motion-based comparison is valid.
- **`PROBEZ [step-5 stream-stable exp=X]: verdict=STABLE|DRIFT|NOISY`**
  -- streaming stability per exposure. Finds the exposure
  ceiling above which streaming-mode mean-metric drifts.
- **`PROBEZ [step-5 range=X]: VERDICT: GOOD|BORDERLINE|BAD`** --
  the feasibility gate. GOOD means snap-motion and stepped
  agree within 0.5 um. BAD means motion blur or timing is
  corrupting the metric and the scan configuration needs work.

## Extending PROBEZ

The handler lives in `microscope_command_server/server/handlers/probez.py`.

Adding a new diagnostic step:

```python
def _step6_my_new_test(core, focus_device, z0, speed_prop):
    _log("step-6", "My new test starting")
    # ... do stuff, log with _log / _warn / _err ...

# in handle_probez, after _step5_metric_validation(...):
_step6_my_new_test(core, focus_device, z0, speed_prop)
```

Guidelines:

- **Log every observation** with `_log("step-N", msg)` so the
  transcript is greppable.
- **Snapshot any writable property** you modify by adding it to
  the `restore` dict in `_snapshot_focus_device`. The finally
  block will put it back.
- **Never leave the stage at a non-default Z.** The top-level
  restore returns to `z_original`, but intermediate moves
  inside your step should return to a known position before
  the next step runs.
- **Scale any hardcoded numbers** (move sizes, sweep ranges,
  speed values) in the module-level constants at the top of
  `probez.py`, not inline. That keeps the file editable for
  per-rig tuning.
- **Refuse to run** if you detect a condition that would make
  the test pointless (e.g., sequence already running, no tissue
  visible). Call `_err("step-N", reason)` and return early.

Adding new pixel/metric helpers: `_pop_image_as_numpy` and
`_focus_metric` are intentionally duplicated between `probez.py`
and `handlers/streaming_focus.py` for module isolation. If you need a
third consumer, lift them into a shared module.

## Client script

`microscope_command_server/client/probez_client.py` is the
standalone entry point. It's 140 lines of socket plumbing and
doesn't do anything fancy -- its only job is to send CONFIG +
PROBEZ and wait for the response. You can invoke PROBEZ from
any Python socket client the same way; the client script is
just a convenience wrapper.

## Related

- `handlers/streaming_focus.py` uses the same pixel and metric helpers,
  and its pre-flight checks mirror the feasibility gates that
  PROBEZ step 5 validates.
- `claude-reports/2026-04-14_smooth-focus-design-and-probez-tooling.md`
  has the full design story and results from the PPM runs
  that shaped both PROBEZ and Streaming Autofocus.
- `documentation/AUTOFOCUS.md` user-facing doc for the Live
  Viewer Streaming Autofocus button.

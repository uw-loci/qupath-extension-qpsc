# Multi-Slide Acquisition

> Menu: Extensions > QP Scope > Multi-Slide Acquisition...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md) | [All Workflows](../WORKFLOWS.md)

## Purpose

Acquire several slides in one carrier (holder) in a single, mostly-unattended
batch. Multi-slide acquisition is a shepherding layer over the regular
[Existing Image Acquisition](existing-image-acquisition.md) workflow: it aligns
and acquires each slot in a slide holder using the same per-slide logic, but
splits the work into a **setup pass** (interactive alignment on every slot) and
an **acquire pass** (walk-away, no dialogs). Use it when you have a multi-slide
holder (currently the 4-slide vertical holder, `quad_v`) and want to set up all
slides once and then let the run proceed on its own.

![Multi-slide carrier assignment dialog](../images/Docs_MultiSlide_Assignment.png)

![Multi-slide batch progress panel](../images/Docs_MultiSlide_BatchPanel.png)

> **Dialogs you will see.** Two are specific to this workflow -- the **carrier /
> slot assignment** dialog and the **batch progress panel** (above). The rest
> are the *same* dialogs as the single-slide
> [Existing Image Acquisition](existing-image-acquisition.md): the acquisition
> config dialog, the green-box tissue preview, tile selection, and the
> [refinement / Auto-Align (SIFT) dialogs](microscope-alignment.md#step-4-refinement-manual-or-automatic).
> This page does not re-screenshot those shared dialogs; see their own pages.

## When the menu appears

The **Multi-Slide Acquisition...** menu item appears automatically when the
active microscope config defines a multi-slide holder (carrier) -- e.g. the
4-slide vertical holder, `quad_v`. If your config has no multi-slide holder, the
entry is hidden. (There is no preference to toggle; it follows the config. See
[Stage carrier schema](../developer/CARRIER_SCHEMA.md) for defining a holder.)

## Prerequisites

- A QuPath project containing one macro/overview entry per slide you want to
  acquire (the same kind of entry the single-slide Existing Image workflow uses).
- A configured multi-slide stage insert (e.g. `quad_v`) in the microscope
  config. See [Stage carrier schema](../developer/CARRIER_SCHEMA.md).
- A microscope connection for acquisition (the assignment and alignment steps
  are interactive; the acquire pass runs against the saved per-slide alignments).

## Before you start

When you launch **Multi-Slide Acquisition...**, the **Live Viewer** and **Stage Map** are automatically opened (if not already visible). These show the camera feed and stage context while you assign slides to carrier slots. If either window is already open, it is brought to focus instead of opening a duplicate.

## Step 1 -- Assign slides to slots

In the assignment dialog:

1. **Pick the carrier** for the holder you have mounted.
2. **Set rotations.** Use **Rotate all slides** at the top to set every slot at
   once when slides are mounted the same way. The default follows the insert
   type: horizontal inserts (e.g. `single_h`) default to 0 degrees; vertical
   inserts (e.g. `quad_v`, `single_v`) use your last-saved quarter-turn choice.
   Per-slot pickers below override this for exceptions.
3. **Assign each slot** to one project macro entry and set its **Rotation** to
   match how the slide physically sits in the holder:
   - **0** -- normal orientation
   - **90** -- rotated 90 degrees clockwise
   - **180** -- upside down (label at the opposite end)
   - **270** -- rotated 270 degrees clockwise (90 counter-clockwise)

   Each non-zero rotation creates or reuses a rotated duplicate entry so
   acquisition aligns to the mounted orientation. Check **Skip** to leave a slot
   empty.
4. **Preview placement.** The [Stage Map](stage-map.md) is displayed alongside this
   dialog and shows all assigned slides rendered at their chosen rotations over the
   holder's slots, updating live as you change rotations. The preview stays
   visible after the dialog closes, as a layout reference through the alignments
   that follow.

   ![Stage Map preview of a 4-slide vertical holder with tissue bounding boxes](../images/Docs_MultiSlide_StageMap.png)

> **Slide orientation note.** Each slot's true orientation comes from *its own
> alignment during setup*, not from the holder calibration (which only knows the
> slot center). Placing a slide label-end-reversed is a pure 180-degree rotation
> about the slot center, and a manual landmark alignment measures that rotation
> directly, so a reversed slide is still handled correctly.

### Hardware for the batch

The assignment dialog also sets the **Modality**, **Objective** and **Detector** for the
whole run, and reports underneath the status of **backgrounds**, **white balance**, and
**focus approach** calibration for that combination.

This lives here rather than in each slide's acquisition dialog for two reasons.
Calibration is keyed on (modality, objective, detector), so until those are fixed there is
nothing to check against -- previously the first point at which they were known was
slide 1's acquisition dialog, after you had already committed to a carrier and its
assignments. And every slide in the batch uses the same combination, so picking it once
removes a repeated choice from every per-slide dialog.

The calibration line is **advisory, not a gate** -- you may be deliberately acquiring
without background correction. It exists so that "no backgrounds for this objective" is
something you learn before the run rather than several slides into it.

**Focus approach status** reports whether autofocus has been characterised
for this modality/objective combination. Because multi-slide acquisition runs unattended,
an uncharacterised approach cannot recover from a bad landing on the first slide. The
status appears as a separate line when there is a problem:

- **Not characterised:** Run **Utilities > Focus Approach Validation** to measure the
  approach for this modality/objective. The validation measures how autofocus behaves
  on this microscope and whether surfaces (coverslips) sit before the tissue plane,
  ensuring the approach will work on tissue rather than landing on glass.
- **Stale characterisation:** The stored characterisation no longer applies because
  safe-Z or imaging conditions (exposure, illumination) have changed since measurement.
  Re-run the validation tool to update it.
- **Failed characterisation:** The validation run completed but found a problem (e.g.
  autofocus keying on glass rather than tissue, or a coverslip sitting before focus).
  The reported reason says which; fix that before relying on unattended focus.

Your choice of modality/objective is published to the shared state, so the acquisition dialog
for each slide opens already set to it.

## Step 2 -- Run the batch

The batch progress panel is organized into collapsible sections: **Slots**
(per-slide table), **Alignment**, **Refinement**, and **Advanced / SIFT
settings**. Controls are color-coded by mode -- the **Slots** table is framed in
**green** ("one slide at a time", the per-row manual controls), and the
automated drivers are grouped in a **blue "AUTOMATED RUN"** frame at the bottom.
An **attention pulse** glows the recommended next step so you always know what to
click.

There are two ways to run, and you can mix them:

### Two-pass (walk-away) run

1. **Step 1: Set Up All Remaining** -- runs the interactive align + refine +
   tissue pass on every slot **without acquiring**. Each slot advances to
   **Set up (ready to acquire)** and remembers its acquisition settings.
2. **Step 2: Acquire All Set-Up** -- acquires every set-up slot **unattended,
   with no dialogs**, replaying each slot's settings against the alignment saved
   during setup. The acquire pass **pipelines over stitching**: a slot advances
   to the next as soon as its stage work completes, while stitching and import
   happen in the background, so later slots acquire while earlier ones stitch.

Front-load your decisions in the setup pass, then leave it running.

> **Tip:** choose **single-tile refinement** during each slot's setup -- the
> focus Z it establishes seeds that slot's first-tile autofocus in the acquire
> pass, so acquisition starts near focus instead of hunting.

### Automating the setup pass (experimental)

The setup pass is the part that still needs an operator. The preference
**Setup-pass automation** (Edit > Preferences > *QuPath SCope Multi-Slide*) can
confirm each setup dialog for you:

| Mode | Behavior |
|------|----------|
| `MANUAL` (default) | Every setup dialog waits for you. |
| `FULLY_AUTOMATIC` | Each dialog confirms its primary action after 1 second; interacting with a dialog does **not** pause it. |
| `AUTOMATIC_WITH_OVERRIDE` | Each dialog counts down -- the primary button shows the seconds remaining, e.g. **Continue (auto 7s)** -- then confirms. Clicking or typing in the dialog cancels the countdown and gives the **rest of that slide** back to you; the next slide resumes automatically. |

Only the confirm control is ever pressed ("Collect Regions", "Start Acquisition",
"Current Position is Accurate", "Continue") -- never Cancel, Back, or "Save MDA...".
If that control is disabled, the countdown stops rather than firing and the slide
is handed back to you.

The countdown length lives in the microscope YAML so it can differ per scope
(default 10 seconds when the key is absent):

```yaml
acquisition:
  multislide:
    auto_advance_seconds: 10
```

The mode is read when a driver starts, so changing it mid-batch takes effect at
the next **Set Up All Remaining** / **Acquire All Set-Up**. **Run Single** and
the other per-row buttons always stay manual.

**The live view is set up for you.** Both refinement dialogs (single- and multi-tile)
now prepare the microscope when they open: they start the Live Viewer if it is not
already streaming, and put the modality into its alignment reference state -- for PPM
that is the **uncrossed** angle with its calibrated exposure and gain, because the
near-extinction angles are dark by design and SIFT finds nothing in them; for
channel-based modalities it is the channel autofocus prefers. A status line reports
what was applied, and both dialogs now carry the focus/exposure reminder that the
other SIFT dialogs already had.

If that state cannot be reached -- not connected, live view will not start, or the
uncrossed exposure is not calibrated for this objective -- the dialog says so in red.
In **manual** mode you can still proceed (you may know something the check does not).
In an **automatic** mode the run stops instead: nobody is watching, and four silently
mis-aligned slides is a worse outcome than a batch that refuses to start.

**Autofocus skips are now visible.** Autofocus before an alignment step used to be
skipped silently in several cases -- not connected, no objective resolvable, or the
Live Viewer not streaming. That last one is the important one: without a live stream,
streaming autofocus downgrades to a narrow drift check that cannot recover focus on a
fresh slide, and can report success without having found it. All of these now warn.

**Every tile is picked for you.** In an automatic mode no "Select Tile" dialog appears at
all -- not for the first landmark of a slide, not for single-tile refinement, and not for
each multi-tile reference point. Tiles are chosen the way an operator would: only tiles
whose full ring of 8 neighbours is present and whose centre is inside the annotation are
eligible (so the camera does not land half on background), those are ranked by how much
texture they contain (SIFT needs structure), and well-separated ones are taken so the fit
is well conditioned.

This mattered more than it sounds. That selection dialog's Confirm button is created
*disabled* and only enables once it sees a tile selected in the QuPath viewer -- so no
countdown could ever have driven it. Picking the tile is what removes the stop, not
counting down on it. The multi-tile refinement panel now also drives its own **Select
tile** and **Solve & Save**, so the whole panel runs without presses.

**Where an automatic batch still stops -- and how you find out.** Two situations hand a
slide back on purpose, because proceeding would mis-align it:

| Situation | What happens |
|---|---|
| No tile qualifies (tissue thinner than three tiles across) | You are asked to pick by hand, rather than a poor tile being chosen silently |
| SIFT comes back below the confidence threshold | The refinement panel waits for you to nudge and capture; the position is never accepted unverified |

Both stop automation **for that slide only** -- the next slide resumes automatically --
and both send a push notification, as does a setup slot that makes no progress for 20
minutes. Configure one under *Notifications* in preferences, or an unattended run will
wait silently. Before **Set Up All Remaining** starts, an automatic batch also checks
every pending slot for annotations and lists any that have none, since each of those
would otherwise stop the pass waiting for you to draw a region.

> **Still not validated for real work.** An auto-confirmed alignment accepts whatever
> position the base transform predicted, with nobody comparing it to the live view. The
> server-side "find tissue, then focus" jog that would recover from a bad landing is not
> built; the first landmark of a slide has been measured landing roughly 600 um out. Leave
> `MANUAL` for real acquisition, and run a batch in `AUTOMATIC_WITH_OVERRIDE` -- where any
> click hands the slide back -- before trusting `FULLY_AUTOMATIC`.

### Manual, one slot at a time

Use the **Status** dropdown to mark a slot **Done** (acquired outside the batch)
or **Skipped**. **Open** switches to that slot's macro entry. Right-click a
slot's entry name for **Run Single**, which launches the regular workflow on
that slot.

### Control buttons

- **Stop after current slide** -- halts the driver cleanly once the running
  slide finishes (an in-flight acquisition is never interrupted).
- **Abort All** (red, footer) -- stops the driver immediately and prevents
  further slides from starting. With no acquisition in flight it closes the
  panel; during an acquisition it confirms, then cancels the running acquisition
  (tiles already captured are kept, current tile discarded). The refinement
  dialog carries an equivalent **Abort Batch** button so it stays reachable when
  the refinement window covers the panel.
- **Collapse / Expand** -- shrink the panel to a thin bar during alignment so it
  does not cover the Stage Map or refinement views. The panel also auto-collapses
  when you click another window during setup, and stays expanded during the
  unattended acquire pass so progress stays visible.

### Finishing

A slot advances to **Done** on a successful acquisition, or stays **In
Progress** / **Set up** if a run is cancelled at a gate or hits a handled error
(so you can retry or mark it Skipped). Once every slot is **Done** or
**Skipped**, click **Finish**. A combined saturation summary is shown if any
tiles had concerning saturation during the run.

After the run finishes, if the microscope configuration declares a safe Z for the
insert/modality combination used in this run, a **Safe-Z Clearance** notification
may appear. This is a maintenance signal about the stage insert, not a data
problem. It reports when:
- **Focus positions on both sides of the safe Z** — the retraction point sits
  inside the range of sample planes (serious case). The sample plane may have
  drifted past the retraction point, so the objective risks collision on future
  runs.
- **Closest focus within 50 µm of the safe Z** — clearance is shrinking. The
  sample plane is drifting toward the retraction point.

Both conditions recommend re-measuring the safe Z for this insert before running
unattended focus on subsequent batches. See [PREFERENCES > Safe Z](../PREFERENCES.md#safe-z-retraction-point)
for configuration details.

## Alignment during multi-slide runs

By default, **every slide is aligned fresh** on each batch pass -- a saved
per-slide alignment is never reused, because a slide's position in a holder
depends on its current mount and a prior single-slide alignment is meaningless
for the holder. Fresh alignment works one of two ways:

- **Primary path:** re-detect tissue with the green-box + scanner-preset path.
  The scanner preset supplies optical orientation/scale (calibrated at setup),
  and the holder's per-slot center calibration provides a rough auto-move near
  the first tile.
- **Fallback path:** if no usable scanner preset exists, fall back to the full
  3-point manual landmark alignment (which establishes position AND rotation).

A **refinement** step then corrects the per-slot position and captures focus Z
for unattended acquisition:

- **Single-tile** corrects the offset only (fast; cannot fix a slide sitting
  rotated in its slot).
- **Multi-tile** captures 2+ spread reference tiles and solves a rotation +
  scale correction, keeping alignment accurate across the whole slide. For a
  vertical holder (e.g. `quad_v`) slides sit loosely and can rotate, so the
  dialog **defaults to Multi-tile** for these holders; horizontal inserts and
  single vertical slides default to single-tile. If acquisition is accurate near
  one spot but drifts with distance after a single-tile refine, switch that slot
  to multi-tile.

  ![Multi-tile refinement panel with numbered SIFT steps](../images/Docs_MultiTileRefinement.png)

- **Autofocus on slot jump** (Advanced / SIFT settings, default on) autofocuses
  after the stage jumps to each refinement tile, before the capture pane
  appears. Keep a Live Viewer stream open during multi-slide alignment so the
  streaming full-search AF is used (slot jumps can land far from the previous
  slide's focus). While slot-jump autofocus is running, the Live Viewer's
  stage-movement controls (arrows, joystick, go-to-centroid) are locked to
  prevent accidental stage bumps mid-scan; the Autofocus button becomes a
  "Cancel Autofocus" toggle (red background) so you can abort the scan if needed
  (Z is restored to the pre-scan position on cancel).

The setup pass writes a fresh per-slide alignment JSON specific to that slide's
position in the current holder, and records which stage insert it was made on.
If you switch stage inserts, an old alignment from a different insert is detected
and treated as invalid, and the dialog recommends fresh refinement.

> **TEST-ONLY alignment reuse.** The preference **Reuse saved alignment (TESTING
> ONLY)** makes the batch reuse each slot's saved alignment instead of
> re-deriving it. This is **UNSAFE for real acquisition** -- it assumes every
> slide is still mounted exactly as when its alignment was captured. Slots with
> no valid saved alignment fall back to fresh alignment. See
> [Preferences](../PREFERENCES.md) for when to enable it.

## Estimated run time

The panel shows a live whole-batch time estimate. Before setup it reads "set up
slides to see an estimate"; after **Step 1** it shows total slides and tiles with
a "(measured timing)" or "(rough -- no measured timing yet)" qualifier; during
**Step 2** it updates live with a "Remaining ~Xs of ~Ys" line. The estimate is
**annotation-aware** -- each region incurs a startup overhead (~6 s for a region
move + focus pass), so many small regions cost more than one large region at
equal tile count. It uses the learned per-file wall-clock cost when available
(self-calibrating after every run) and a conservative fallback otherwise.

## Alerts

Per-slide completion alerts (system beep + toast) are sent as each slide
finishes, and a batch-complete push notification is sent once all stitching is
done. Push notifications require ntfy.sh configured in
[Communication Settings](server-connection.md); see
[Alerts](../PREFERENCES.md#alerts-qupath-scope-alerts).

## Provenance and recovery

Each assigned entry gets `slide_position`, `slide_carrier`, and `ms_run_id`
metadata so a run is auditable and partial runs are recoverable after a crash.

## See Also

- [Existing Image Acquisition](existing-image-acquisition.md) -- the per-slide
  workflow this batches over
- [Stage Map](stage-map.md) -- live placement preview and slot calibration
- [Stage carrier schema](../developer/CARRIER_SCHEMA.md) -- defining a
  multi-slide holder
- [Workflows Guide](../WORKFLOWS.md) -- the full narrative version of this
  workflow

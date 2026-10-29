# Changelog

All notable changes to the QPSC QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **See every autofocus while it runs.** The initial focus, a focus survey and each tile's search used to show nothing until they finished; on a slow focus stage that was minutes with no sign of progress, and the Live Viewer only showed autofocus it had started itself. The acquisition progress windows (Acquire from Existing Image, Bounded Acquisition) now show an **Autofocus** section once the first search starts: what it is for ("Initial focus", "Focus survey point 2 of 5", "Tile 12 of 80"), the step and Z, a plot of the focus score against Z with the best score marked, and the result. The Live Viewer's Autofocus button reads **Autofocusing...** during any autofocus, its status line follows the steps, and the Z bar's focus trace shows the search's own scores. Needs microscope server and microscope_control from 2026-10-28 (new `AFTRACE` command); an older server shows nothing new. Each acquisition also writes every search to `af_trace.ndjson` beside its tiles.

- **LC-PolScope: Measure LC voltage curves.** A diagnostic step in LC-PolScope Setup. On a clear field the server sweeps each crystal's drive voltage and measures the retardance it really has from the fringes (new LCSWEEP command, microscope server 1.5.x and `polscope_library` with `SWEEP_GEOMETRY`). The result plots the measured curve beside the Meadowlark adapter's waves-to-volts table with the palette's states marked, and lists each swing state's offset from extinction commanded, measured and in volts. Changes nothing; writes `lc_sweep_<stamp>.json` and a measured-curve CSV that is not installed. For the lopsided LC-B palette, which the adapter's table is suspected of causing. See [UTILITIES.md](documentation/UTILITIES.md#measure-lc-voltage-curves).
- **LC calibration result: waves or volts.** With a calibration from microscope server 1.5.x, which records the drive volts, the Search curves tab offers **Commanded waves | Drive volts**, and the Palette lines show each state's volts offset.

- **LC-PolScope: a per-slide reminder before every acquisition.** Bounded Acquisition, Acquire from Existing Image and a single multi-slide slot stop just before the stage locks to say that the LC calibration and the background must be redone on this slide (its glass, coverslip and mountant add retardance of their own; OpenPolScope's manual advises recalibrating whenever the slide changes). It lists the calibration and backgrounds in use, and offers **Acquire**, **Open LC-PolScope Setup** (cancels and opens it) or **Cancel**; Enter cancels. A multi-slide **Acquire All** asks once, before the batch, that it will use one calibration and one background for every LC-PolScope slide, and stays quiet during the unattended run. An interim step until the acquisition can calibrate and collect a background per slide itself. See [UTILITIES.md](documentation/UTILITIES.md#lc-polscope-setup).

- **Per-insert Z limit.** `stage.inserts.configurations.<insert>.z_limit_um` is the closest the stage may come to the sample with that insert mounted; the stage's own `limits.z_um` is only the hardware's travel. Captured from **Stage Map > Calibrate...** ("Z limit (closest to the sample) for THIS insert", offered for every insert like safe Z). While the insert is selected, QPSC refuses any Z move past it and sends it with every streaming autofocus request as `--z-limit`, where the server narrows the stage limits for that scan: standard walk, approach from safe Z and profiling traverse alike (microscope server 1.5.1 or later; older servers ignore the flag). No fallback and no default. See [PREFERENCES.md](documentation/PREFERENCES.md#per-insert-z-limit-z_limit_um).

- **LC calibration result: Palette and Search curves tabs.** Beside the text summary, the result dialog plots where each swing state landed relative to the ideal palette, and the intensity the search measured along each crystal through extinction with the swing-state target. Shows at a glance a crystal that reaches the target short of the swing on one side, which the extinction ratio cannot. The **Apply a saved calibration...** confirmation has the same tabs, so an old calibration file can be inspected. See [UTILITIES.md](documentation/UTILITIES.md#lc-polscope-calibration).
- **LC-PolScope Setup: Apply a saved calibration...** applies an `lc_calibration_*.json` from an earlier run to the config QPSC is set to, through the normal Apply step (palette, date, then the exposure and black-level offers). For a calibration that landed in the other LC-PolScope config file; collect the background again afterwards.
- **Ready to acquire? checks the server's config file.** A FAIL line when the microscope server is still running a different config file from the one QPSC is set to, which is how a calibration, the backgrounds and the acquisition ended up reading three different files on 2026-10-26.
- **Collect background now... after every calibration that invalidates a background set.** The JAI white balance results (Simple and PPM), the LC palette Apply summary and the Live Viewer Camera tab's three **Save to Profile** buttons (brightfield lamp and exposure, LC-PolScope exposure, fluorescence channels) all offer the same button, which opens Background Collection on the modality just calibrated (and, for white balance, the objective, detector and WB mode) in the active background profile. In the Camera tab it appears beside Save after a save that changed something and stays until used. The white balance results now say that sets collected before the calibration are stale in every profile.

- **LC-PolScope calibration can measure on the centre of the field.** The Calibrate Liquid Crystals dialog has **Measure on: N % of the field, centred**. Below 100 the camera's own ROI is set for the run (faster frames, which the iterative search feels) and cleared afterwards; a camera that cannot take one has every frame cropped in software, and the result says so. Keeps dust and other junk at the sensor's edges out of the intensity means. Sent as `--roi-fraction` (microscope server 1.5.1 or later). Remembered across sessions.
- **Retardance display ceiling in the LC-PolScope panel.** **Full brightness at ... nm retardance** sits under the Orientation colour image dropdown in Bounded Acquisition and Existing Image. It is the retardance shown at full brightness in the orientation colour image and the top of the display range a stitched retardance image opens with; some mounting media and thicker sections need more than the config's 20 nm. Remembered across sessions; **Config (N nm)** returns to `reconstruction.display_retardance_ceiling_nm`. A value other than the config's is sent as `--display-ceiling-nm` (microscope server 1.5.1 or later; older servers ignore it for the colour tiles but QPSC still applies it on import). The Orientation Colour View starts from the same value.
- **Orientation Colour View can export.** **Export colour image...** writes the coloured image, with the current map, ceiling and colour-only setting, as a pyramidal RGB OME-TIFF beside the stitched images (`<name>_orientation_colour_<map>_<ceiling>nm.ome.tif`) and adds it to the project with the settings as `orientation_colour.*` metadata. Each pyramid level is coloured from the orientation image's own level, so no angles are interpolated. Written tile by tile with a progress window; a whole slide takes minutes. See [UTILITIES.md](documentation/UTILITIES.md#orientation-colour-view).

### Changed

- **Single-tile refinement no longer refocuses after SIFT.** The autofocus that runs when the stage jumps to the refinement tile has already focused there, and SIFT moves the stage only a field or two, so the sweep that followed every SIFT match was a second focus at nearly the same place. Multi-tile refinement still refocuses after each SIFT move, because its later points are reached without an autofocus of their own.
- **The demo microscope's slide looks like tissue.** It was pink with evenly scattered purple specks, so every field looked the same and a move in the wrong direction could not be seen. The virtual slide now has fibrous stroma with wavy collagen bundles and spindle nuclei along them, glands with a lumen and a ring of nuclei, fat lobules, lymphoid follicles and winding blood vessels full of red cells, and LC-PolScope's slow axis follows each collagen fibre. Frames also render about twice as fast. Needs microscope_command_server from 2026-10-29.
- **LC calibration and voltage sweep wait 30 s for the server's acknowledgement**, then 600 s per progress read as before, so a server that does not know the command fails quickly instead of hanging for ten minutes.
- **Stitching runs in the background and never touches the viewer.** A stitched image is added to the project and the project browser refreshes, but the image is not opened, the viewer's image data is not saved on its behalf and the project is not re-set. The completion toast says how many images were added and names the first few; open them from the project browser. On 2026-10-27 (LC-PolScope) a stitch from the previous slide finished while the next slide's refinement dialog was open and switched the viewer to the stitched image under it.
- **Closing the refinement dialog cancels the workflow.** Every outcome other than an accepted refinement used to proceed to acquisition, so the window's X, a cancelled tile pick and multi-tile's Cancel all started a run. Now only **Skip refinement** (single-tile's **Skip point**, multi-tile's new **Skip refinement** button) acquires with the prior alignment; X, **Cancel workflow** and a cancelled tile pick stop the workflow with no acquisition, and **Create New Alignment** runs the full manual alignment and then acquires. The log records `Refinement outcome <OUTCOME> -> <DECISION>`.
- **Project entries are reserved when a region is handed to stitching.** Each stitch job adds its expected entries to the project up front, one per output (each PPM angle plus biref and sum, each LC-PolScope state merge plus retardance, orientation and orientation colour, each split IF channel plus the merge, the single brightfield file), with their full QPSC metadata and a four-piece puzzle picture as the image, described "Stitching in progress". When the stitched OME-TIFF lands the entry is pointed at it in place, so the project browser never reorders and the entry you can see during the stitch is the one you get. An output that never arrives (a failed stitch keeps its entry with the reason in the description; a wrongly predicted name is dropped and the real file added the ordinary way) costs nothing. The picture lives at `<project>/qpsc/stitching-pending.tif`. OME-TIFF only; zarr output is added when it lands, as before.
- **A stitching queue window instead of a blocking dialog.** The window-modal, always-on-top "Stitching in Progress" dialog with its "Dismiss (At Your Own Risk)" button is gone. **Extensions > QP Scope > Acquisition controls > Stitching queue** (it also opens on its own when a job is enqueued) lists every stitch job of the session as queued, stitching, done or failed, with its latest status, elapsed time and an **Open** button on finished rows. It is non-modal, never forces itself to the front, and hides itself a while after the queue drains unless a job failed. The acquisition progress dialog closes when the acquisition ends instead of waiting for stitching.
- **A new run cannot start while an acquisition holds the stage**, and a run whose acquisition is over but whose stitching is not hands the shared transform and acquisition flag to the next run instead of clearing them under it (the registry behind this is `WorkflowRegistry`). Starting a run during an acquisition offers to stop it.
- **Stop acquisition from anywhere.** **Extensions > QP Scope > Acquisition controls > Stop acquisition...** and a red **Stop acquisition** button in the Live Viewer (shown only while an acquisition holds the stage) stop the running acquisition after the current tile. Before this the progress dialog's Cancel was the only control, and that dialog could be gone.
- **The progress dialog waits for the microscope to stop.** After Cancel it stays open saying so until the run confirms the stage is released, then closes; after 60 s without confirmation it closes and points at **Stop acquisition**. It used to close itself two seconds after Cancel whatever the microscope was doing. Closing the dialog with the X during a run now asks "Cancel the acquisition?" instead of being ignored.

- **Stage polarity lives in the microscope config, not in Preferences.** The **Inverted X stage** and **Inverted Y stage** preferences are gone; each microscope's config now says `stage.polarity: NORMAL | INVERT_X | INVERT_Y | INVERT_XY` (PPM `NORMAL`, OWS3 `INVERT_XY`, both LC-PolScope configs `INVERT_Y`; pull `microscope_configurations`). The preferences were one pair per QuPath install, so a QuPath that switched microscopes carried one stage's polarity to the next. Calibrate Directions' **Manual override** writes `stage.polarity` to the active config; the calibration itself still writes only the camera orientation. Connecting to the demo no longer swaps polarity (its config says `NORMAL`); it still holds your camera orientation while the demo is active. A config without the key reads as `NORMAL`, with a warning in the log. See [PREFERENCES.md](documentation/PREFERENCES.md#stage-polarity-in-the-microscope-config-not-a-preference).

### Fixed

- **Stage Map: the macro is drawn where this slide is, not where the scanner preset assumes.** The overlay was placed by the scanner-to-microscope preset, moved only by the last refinement's change, so a slide aligned by hand, from another microscope's alignment, or refined in an earlier session was drawn where the preset's own slide had lain. Every per-slide alignment save (green-box path, manual, Microscope Alignment, refinement) now records where the alignment puts the slide's image centre, and the Stage Map moves the macro so the slide's green box sits there, redrawing as soon as an alignment is saved. Checked on a real Ocus40 macro and preset (MetroHealth_211, stage axes inverted): the drawn green-box centre landed on the recorded centre to 0.0 um, and moved with it after a simulated refinement.
- **The autofocus after a stage jump can always be cancelled, and shows that it is running.** Single-tile refinement and the multi-slide slot jump focus before their dialogs open, and the only Cancel was the Live Viewer's Autofocus button. That button was reset by the live stream stopping and restarting around the search, so on 2026-10-28 a refinement that jumped onto bare glass showed neither a Cancel nor any sign it was focusing. The button now keeps **Cancel Autofocus** (and the status line its autofocus text) through the stream restart, and a small **Autofocus** window opens for every such search, Live Viewer or not, with the live curve and **Cancel autofocus** (its X does the same). Cancelling returns Z to where the search started and the workflow carries on as after a failed focus. On the microscope server, Cancel now also stops the streaming search's Brent fallback (the wide stop-and-snap search it falls back to when it finds nothing, as on glass), which ignored it and committed a focus (microscope server 0614827).
- **Background profiles named the wrong label side.** A new profile's suggested name and description said "label left" for placement A and "label right" for B on every microscope. On the LC-PolScope, A puts the label on the right, as the Stage Map shows. They now use the Stage Map's own caption ("Label on your right (A)"), which the Stage Map remembers per microscope and insert once it has drawn a slide, and say "placement A" without a side until then. The profile form's Slide in holder list and the placement warnings use the same captions.

- **The Live Viewer image no longer wobbles in Fit mode.** Once the bottom-docked histogram shared the image's column (0.12.0), the image was sized from its scroll pane while the scroll pane's preferred size came from the image, and at 150% display scaling the two settled one physical pixel apart several times a second; the 3 px Live border also kept a scroll bar on in Fit mode. Fit now sizes the image from the viewport in whole pixels with room for the border, the scroll pane's preferred size no longer follows the image, and Fit mode has no scroll bars (25/50/100% still scroll).
- **The first Live click shows the Camera tab's settings.** Opening the Live Viewer filled the Modality and Profile dropdowns without applying them, so the first stream showed the camera and lamp as Micro-Manager loaded them (on PPM and OWS3, not a usable image) until you picked another profile or modality and came back. The first Live click after the window opens now applies the tab's profile and re-sends its saved exposure and lamp intensity; on PPM, which has no acquisition profile, it re-sends the saved values only. Skipped while an acquisition is running.
- **A modality switch applies the profile for the mounted objective.** The switch sent the first profile declared for the new modality, so on OWS3 with the 20x mounted, choosing Brightfield applied `Brightfield_10x` while the Camera tab showed `Brightfield_20x`. It now identifies the objective from Micro-Manager's pixel size and applies that objective's profile, falling back to the first only when none fits (logged as a warning). A profile applied from the Camera tab that does not fit the reported objective or camera turns the tab's status line orange with the reason.
- **A cancel pressed before the acquisition command is sent is honoured.** The acquisition loop only read the progress dialog's cancelled flag while monitoring a running region, so a Cancel between the dialog opening and the ACQUIRE send (which can wait tens of seconds for the server to go idle) was followed by the send, and the dialog had already closed itself: on 2026-10-27 an acquisition ran with no cancel control anywhere until the server was killed. The loop now checks every cancel signal (dialog, Stop acquisition, batch abort) before each region, before the idle wait and again before the send, and logs `Cancelled (<source>) ... ACQUIRE not sent`.
- **An acquisition reads the image it was started on, never whatever the viewer shows.** The workflow now pins its source entry once routing has settled on the final (flipped, rotated or Camera View) entry, re-opens it before refinement and before acquisition if the viewer has moved on, and takes its annotations, tile hierarchy and project-entry saves from that entry. A refinement dialog whose image is switched away while it is open cancels instead of refining against the wrong picture. Before this every stage re-read the viewer, and a stitch import or a click in the project browser could redirect the run.
- **Calibrate Directions warns before it changes the camera orientation.** Truthful answers give the same orientation every run, so the result page now says when Apply would change it, and why that reverses every direction. A result turned 180 degrees from the current one is named for what it usually is: answers describing how the view moved instead of the tissue. On OWS3 on 2026-10-27 two runs two minutes apart applied ROT_180 and then NORMAL.
- **The autofocus validation test no longer times out on a slow focus stage.** The client gave the test a fixed 3 minutes; on OWS3 its two stop-and-snap scans took 183 s and the dialog reported "Read timed out" just before the server's answer. The limit is now 15 minutes; Cancel still stops it at once.
- **Stage View shows the slide as it sits on an inverted-axis stage.** The Stage Map draws stage coordinates through the stage polarity (**Inverted X stage** / **Inverted Y stage**), and Stage View did not undo it, so an inverted axis came out mirrored relative to the stage seen from the front. OWS3 (both axes inverted) drew the Ocus40 slide turned 180 with its label on the left; the LC-PolScope (Y inverted) had been given `stage_view_flip: y` by hand to compensate. Stage View now includes the polarity, the LC-PolScope configs are back to `stage_view_flip: none`, and the setting remains only for a residual mirror. Display only: the alignment, acquisition and Camera View are unchanged.
- **Existing Image uses the scanner preset you selected before borrowing another microscope's alignment.** With no per-slide alignment for the active microscope, the workflow composed one from another microscope's per-slide file first (and switched refinement off for it), so a slide aligned once on the PPM was acquired on the LC-PolScope through the PPM file even with `Ocus40_to_LCPolScope_Hamamatsu` selected. A selected preset for the active microscope now wins: green box on the macro, then the refinement you chose. The cross-scope composition is the fallback when no such preset is selected.
- **The alignment's Image Orientation step can flip a Camera View that acquisitions were made from.** A "(Camera View)" companion that any acquisition recorded as its parent was kept as built, so it could never change orientation: in a project shared by the PPM (companion built flipped X and Y) and the LC-PolScope (needs Y only), ticking Y reloaded the same image and ticking X did nothing, and alignment ran on a view that did not match the camera. The companion is now rebuilt with the flip asked for and those acquisitions are repointed to it (`original_image_id`, the only reference to it by ID).
- **Existing Image: the refinement you clicked is the refinement that runs.** The recommendation under the refinement radios re-applied its default whenever the saved-transform combo changed, and the default for an image with a slide-specific alignment is "Proceed without refinement", so a Single-tile choice could be undone before Start. Once a radio has been clicked the recommendation only updates its text; the log now records `Refinement default set to`, `Refinement chosen by the operator` and `Refinement at Start`.
- **"Slide in holder" captions count the preset's macro flip.** The Stage Map draws the macro overlay from the active preset's `flipMacroX/Y`, but the captions were named from the scanner's label side without it, so on a scope whose preset mirrors X (both OWS3 presets) "Label on your right (A)" drew the label on the left. The captions now name the side the overlay is actually drawn on.
- **"Slide in holder" names the ends on a vertical holder.** On the four-slide carrier (and `single_v`) a slide stands along Y, so its label is at the far or the near end and the two placements are upside down to each other; the Stage Map still offered "Label left" / "Label right". The captions now follow the open entry's companion rotation: **Label away from you (A)** / **Label toward you (B)** for a vertical holder, left/right for a horizontal one, and the generic captions before the macro is placed follow the selected insert. Warnings and the Background Profiles form say **Placement A (as scanned)** / **Placement B (rotated 180)**, which is true on every holder.
- **Autofocus never runs on a tile the overview image shows as glass.** Existing-image acquisitions now send every tissue-bearing tile (tissue fraction at or above `wsi_tissue_threshold`) as the autofocus allow-list, `--af-tissue-tiles`; the server restricts every focus search to it (microscope server 1.5.1 or later; an older server ignores the flag). When scoring ran and no tile qualified the server is told `none` and searches only the preferred tile. One macro-image scan now serves the preferred tile, the allow-list and the focus survey. On 2026-10-27 a sparse LC-PolScope region drew 28 focus searches, most on bare glass the camera-side texture gate could not tell from tissue; each found a noise peak tens of micrometres off and the seeding spread those Zs across the mosaic.
- **A second reference point on the same slide no longer retracts and traverses the whole approach bound.** Only the slot-jump scan's own success marked a slide's focus as known, so a slide whose first scan failed and was then focused by hand (Live Viewer autofocus, the post-SIFT refocus, a sweep) still ran the full approach at its next point. On 2026-10-26 that drove from safe Z 0 toward -693 through a focus plane at -109 and 450 um past it before the operator cancelled. Every successful focus on the slide now counts.
- **The approach licence checks its distance against the focus already measured on this carrier.** The validation record is keyed by scope, modality and objective, not by insert or Z frame, so its measured safe-Z-to-focus distance can be hundreds of micrometres wrong for the carrier that is mounted (443 um on record, 121 um in fact, on 2026-10-26). When a focus measured this run disagrees with the record by more than the 250 um headroom, the approach is refused with the reason and the standard scan runs from the focus just measured.
- **A refinement point's focus height is recorded when the point is captured, not when its scan returns.** A scan that found nothing left +169.6 in one slide's focus surface; the point was captured ten minutes later at -121.

## [0.13.0] - 2026-10-25

Pairs with microscope_command_server 1.5.0 (background profiles, the orientation color map choice and the cancelled-approach stage stop need it), microscope_control 1.2.0, microscope_imageprocessing 0.4.0 and polscope_library 0.1.0. Stitching needs tiles-to-pyramid 0.7.8. The installer now also installs polscope_library, which LC-PolScope needs.

### Added

- **RGB Histogram in the Live Viewer.** An **RGB Histogram** checkbox beside **Always Auto-Scale** appears while the camera delivers RGB frames. Ticked, the histogram shows the red, green and blue distributions as three overlaid curves instead of one brightness histogram. Auto-scale and the Min/Max range still work on brightness.
- **Background profiles: one set of flat-field backgrounds per sample setup.** A background depends on the slide carrier (height in the light path, glass), the slide source, the slide's orientation under the camera and the mounting medium, not only on the optics. Backgrounds are now filed by a named profile above the detector/modality/objective layout (`<base>/profiles/<id>/...`), with a `profile.yml` that names the setup, can declare the stage insert and slide placement it was collected with, and carries free-form attributes. **Utilities > Image Quality > Background Profiles...** creates, edits, activates and deletes them, and moves background folders from before profiles into one. A **Background profile** combo in Bounded Acquisition, Existing Image Acquisition, the Acquisition Wizard and Background Collection shows and sets the active profile (QuPath-wide, like the Stage Map insert). Background Collection files its set in the selected profile and refuses to start without one; folders in the old layout are not read, so until a profile is chosen acquisitions run without background correction and say so. Before an acquisition locks the stage, the existing background check also compares the profile's declared insert and slide placement with the Stage Map insert and the open image (warn only). When the Stage Map's insert changes and exactly one profile declares it, a small always-on-top window offers to switch. The profile is recorded on every acquisition: `--bg-profile` on the command, `background_profile` on the project entry, `background profile:` and `background folder:` in the stitch record, and a `context` block (profile, insert, placement, stage position, `collected_at`) in each `background_settings.yml`. See [documentation/tools/background-profiles.md](documentation/tools/background-profiles.md).
- **Orientation Colour View (Utilities > Project Tools).** Colours a stitched LC-PolScope orientation image live, with its retardance image as brightness. Colour map (uniform / PPM / HSV), brightness ceiling and a colour-only mode change the view at once; nothing is re-stitched or written. It reads only the tiles on screen at the current zoom level, and follows the viewer to any LC-PolScope retardance or orientation image. The colour maps are a Java port of the microscope server's, checked against its reference values. See [UTILITIES.md](documentation/UTILITIES.md#orientation-colour-view).
- **Orientation colour image dropdown (LC-PolScope).** The LC-PolScope panel of Bounded Acquisition and Existing Image has an **Orientation colour image** choice: as in the config, Uniform lightness, PPM colours (when the config has a PPM calibration) or HSV rainbow. It is remembered across sessions and sent as `--display-colormap` (microscope server 1.5.0 or later). Display only; tiles already written keep their colours.
- **LC-PolScope autofocus can run at its own exposure.** The **Focus on a dedicated channel** option is now available in the LC-PolScope state picker (Bounded Acquisition and Existing Image), which hides the Customize override it used to wait for. Focus frames use the focus exposure set there, which can be far shorter than the one exposure used for all five states (300 ms on the Hamamatsu); every state is still snapped at that shared exposure. The Intensity field is hidden, since the LC states have no intensity control. See [CHANNELS.md](documentation/CHANNELS.md#dedicated-focus-channel-optional).

### Fixed

- **The stage stayed locked during a manual-focus pause, and the joystick threw.** While the server waits for the operator to focus by hand, Z and XY are now both unlocked (the operator may have to move to tissue to see focus; the server returns the stage to the tile afterwards). The lock's status label was also set from the joystick thread, which threw "Not on FX application thread" and swallowed the move.
- **Stage View showed a face-down slide face up on the LC-PolScope.** The Stage View applied only the slide rotation; whether the map's baseline already carries the inverted scope's mirror depends on the stage polarity, and on this rig it does not. A per-microscope `light_path.stage_view_flip` (`none | x | y | xy`, in Utilities > Microscope Configuration > Light Path Orientation as **Stage View mirror**) adds the missing mirror; both LC-PolScope configs set `y`. Other rigs keep `none`, the previous behaviour.
- **"Slide in holder" captions named the wrong side.** A was always "Label left (as scanned)", but on the LC-PolScope A draws the label on the right. The Stage Map now names the two options after where the label is as you face the microscope, once the macro is placed: "Label on your right (A)" / "Label on your left (B)" on that rig. The scanner's own label side comes from `macro.label_side` in its config (right on the Ocus40); "as scanned" is gone, since it says nothing about the stage.
- **Changing the microscope config file did nothing while connected.** The switch offer compared the new file with the config manager's path, and the manager had already reloaded the new file on its own (the Stage Map's position poller reads the preference within a second), so they matched and no switch, reconnect or server CONFIG happened. The server stayed on the old file while QPSC read the new one. The offer now compares with the file the extension was last built against; declining puts the manager back too.

### Changed

- **The P98 - P2 focus trace is visible.** In the Z Focus bars the second curve was a 1 px line at 40% opacity over a 10% fill and could not be seen on the dark panel. It is now a solid dashed blue line, 1.6 px, over a light fill; the dash keeps it apart from the solid orange Brenner curve.
- **Background Profiles: New... starts from the setup on the stage and checks the id as you type.** The form used to open empty and refused a bad id only after it closed, losing what was typed. It now suggests the insert, slide in holder, name, id, description and slide source from the Stage Map and the open image, says where each came from, disables **OK** with the reason while the id is unusable or taken, and warns about an unknown insert or malformed attribute lines. With no background folder set, New... asks for one.
- **"Slide in holder" everywhere.** The per-slide placement is called **Slide in holder**, with **Label left (as scanned)** and **Label right (rotated 180)**, in the Stage Map, Background Profiles, the pre-acquisition warning and the change notification. It was "Slide", "slide placement" and "label-right (180)" in different places.
- **Saved transforms show their age instead of a confidence score.** The alignment dialogs used to show "Saved transform confidence: MEDIUM (70%)" for a transform saved seconds ago: the score started every saved preset at 65% (70% with green-box parameters), lost 0.3% per day and never reached HIGH, and nothing about the alignment's quality entered it. The line now reads "Saved transform: saved <date> (today / N days ago)", green under 14 days, amber under 90, red after, matching the Acquisition Wizard's alignment row. The Existing Image refinement recommendation stays Single-tile refinement for a saved preset and adds the age and the Full manual suggestion from 90 days.
- **One background folder per microscope.** `background_correction.base_folder` in `imageprocessing_<scope>.yml` is now scope-wide; `enabled` and `method` stay per modality. Background Collection writes the base folder and enables the modality there instead of rewriting a per-modality folder. The setup wizard seeds the new shape. **Edit each rig's imageprocessing YAML once** (the matching `microscope_configurations` commit does it for the tracked configs; OWS3's file lives on the rig).
- **Brightfield exposure comes from the profile's background set.** The Bounded dialog and the brightfield handler read the exposure from the active profile's `background_settings.yml` for the detector, magnification and white balance mode, the way PPM always has, instead of the single `calibration_targets.background_exposures` row that every collection overwrote. With background correction off, the last used exposure applies as before; with it on and no set in the active profile, acquisition stops with a message naming the profile.
- **Images imported through the multichannel merge carry the same record stamps as single images.** `white_balance` (added in 0.12.0) and the new `background_profile` are now set on fluorescence and LC-PolScope entries too; 0.12.0 stamped only the single-image import path.
- **The dedicated focus channel is stored per modality.** Its enabled state, channel, exposure and intensity are saved under each modality's own key, so a setting made for fluorescence is not the starting value for LC-PolScope or the reverse. A setting saved by an earlier version is not read; tick the option again once per modality.
- **Stale means older than the calibration by its own clock.** A background set's `collected_at` is compared with the white balance calibration time; the file date is used only for sets from before it was recorded.

### Fixed

- **A new alignment no longer replaces another microscope's.** The Save Transform name was prefilled with the last name typed, so an LC-PolScope Hamamatsu alignment was saved under the Oryx alignment's name and replaced it: the Stage Map lost Ocus40 as a source and showed no macro overlay. The name is now always suggested fresh as `<source scanner>_to_<config>_<date>` (for example `Ocus40_to_LCPolScope_Hamamatsu_20261024`), and a name already used by another scanner/microscope pair gets a `_2` suffix. The Acquisition Wizard's alignment age now counts only the active microscope's transforms.
- **A slide set to label right no longer reads as label left in the background profile check.** The check used the open image's placement, which reads "label left" when the image records none; stitched sub-images never record one. It now uses the slide's recorded setting, from the open image or else its base image, and compares nothing when none is recorded.
- **Approach-from-safe-Z autofocus is refused when the stage is on the far side of safe Z.** Single-tile refinement on PPM ran the approach licensed by an old Focus Approach Validation (safe Z 0, sample toward -693) while the slide was in focus at +258: it drove away from focus and kept going until cancelled. The licence now also requires the current stage Z to lie on the validated approach side of safe Z, else the standard scan runs and the log names the problem and asks for a new Focus Approach Validation.
- **The LC-PolScope readiness check no longer blames specimen for an uncalibrated background.** With no QPSC LC calibration applied, the swing states are not balanced for the light path, so their background means differ on a clear field (28% on the Hamamatsu, 10-21). The check now reports that as expected (the background correction removes it) and asks for a new background after Calibrate + Apply. It still warns about specimen when a calibration is applied, or when the server measured the states' balance changing across the field (`swing_field_variation` above 0.25, recorded by the server from this version).
- **LC-PolScope background collection no longer reports "intensity 0" for every state.** The per-channel summary and `background_settings.yml` printed 0 for a channel with no intensity control, which read as five channels switched off. Such a channel now shows only its exposure and records no `intensity`. Nothing used the 0: a channel without an intensity control was always treated as in use.
- **The acquisition time estimate no longer swings by hours.** It averaged the time between progress polls as if each poll were one image, but one poll often covers several images (five LC-PolScope states at one position), and it kept only the last few dozen polls, so autofocus tiles moving in and out of that window made it jump. On a 10-22 LC-PolScope run it read anywhere from 2.5 to 10 hours with about 2.7 hours left. The estimate is now the mean time per image written over the whole run so far, excluding the first update, and the time saved to preferences for the next run's starting estimate is per image too.
- **Live Viewer contrast holds during LC-PolScope acquisitions.** With Show Tiles on, the viewer previewed the newest file in the tile folder, which was usually the 8-bit orientation-colour output. Its 0-255 range clamped the 16-bit Min/Max to 254 and 255, so the following camera frames rendered all white or all black, and turning Always Auto-Scale off left 254/255. The tile preview now skips every modality's computed outputs, and each bit depth keeps its own Min/Max. Programmatic slider updates also no longer round the range to the slider's 256 steps or uncheck Always Auto-Scale.
- **The Switch Microscope offer opens after you change the config-file preference.** The offer ran inside an animation pulse, where its confirmation dialog throws, so no offer was ever shown and the preference was left naming a file QPSC had not loaded. A switch that does not happen (declined, refused during an acquisition, or failed validation) now puts the preference back to the config still in use.
- **A config the server refuses is reported once, with the reason.** A "Microscope config refused" notification names the file and the server's message, and points at the mistake when the preference names a companion file (`autofocus_*` or `imageprocessing_*`). The auxiliary connection (Live Viewer and stage control) no longer retries a refused config every second: the backoff resets only after the server accepts the config.
- **Existing Image acquisition sends the dedicated focus channel.** The **Focus on a dedicated channel** setting was shown in the dialog but dropped before the command reached the server, so autofocus ran at the focus channel's acquisition settings. Ticked settings now reach the server for every modality.

## [0.12.0] - 2026-10-21

Pairs with microscope_command_server 1.4.0 and microscope_control 1.2.0 (which bring microscope_imageprocessing 0.4.0 and polscope_library 0.1.0): every item below that says it needs the matching server needs that release. Stitching still needs tiles-to-pyramid 0.7.1 or later; 0.7.8 adds the black background that LC-PolScope orientation images declare.

### Added

- **Stitched images record their white balance mode.** The stitch record (`<image>.stitch-info.txt`) has a `white balance:` line, and the project entry carries `white_balance` (`off`, `camera_awb`, `simple` or `per_angle`), read from the acquisition command. An acquisition with white balance off records `off`; one whose command had no mode records nothing on the entry and "not recorded" in the stitch record, without an error.
- **Choosing an objective can select it in Micro-Manager.** Micro-Manager picks its pixel size from the pixel-size preset matching the current hardware state, which includes the objective. On the LC-PolScope, whose nosepiece is turned by hand, choosing 20x in QPSC left Micro-Manager's objective label elsewhere, so the pixel size read 0 and SIFT refused to run. An objective may now declare `mm_preset: {group, preset, motorized}` in the microscope YAML (Micro-Manager group and preset names vary between labs, so nothing happens without it). A labelling device (`motorized: false`) is selected silently, also whenever QPSC connects. A motorized turret is moved only after a confirmation dialog whose default answer is "Don't move", and never on connect. Never during an acquisition. A group or preset that does not exist gives a warning listing those that do. The LC-PolScope configs declare it for 4x, 10x and 20x. Needs the matching microscope_command_server (MMPRESET).
- **Bounded Acquisition shows the tile count beside the region size.** Next to **Height (um)** in Center Point + Size mode and under the Corner 2 inputs in Two Corners mode, e.g. `20 tiles (10 x 2)`, updated as you type. It is the same count as **Tile Grid** in the Acquisition Preview, which is further down the dialog.
- **The demo microscope offers Fluorescence and LC-PolScope.** Besides brightfield, Connect to a Demo Microscope now has a 12-bit monochrome camera with two modalities on it: Fluorescence (DAPI, FITC, TRITC and Cy5 channels from a four-line LED) and LC-PolScope (five states through a simulated Meadowlark compensator over a birefringent specimen, reconstructed by the real code). Switching modality in the Camera tab switches camera. The simulated compensator has a calibrated palette for Calibrate Liquid Crystals to find again, and a field-fixed residual retardance that a collected background removes. An existing demo folder is replaced automatically (the old files move to a `previous-<date>` folder) and reloaded. Needs the matching microscope_command_server.
- **LC-PolScope Camera tab.** The Live Viewer's Camera tab has its own LC-PolScope layout: one exposure for all five states, a selector to view any state live, **Measure all states** (each state at that exposure against the camera's range, with a suggested exposure for a target brightness), **Use suggested**, and **Save to Profile**, which writes the one exposure to all five states. Needs microscope_command_server 35bb265 (LCSIGNAL).
- **LC-PolScope backgrounds are always used when collected.** An LC-PolScope acquisition sends its background whenever a background folder is configured, independent of the "Use background correction" checkbox (flat-field only; now hidden for LC-PolScope) and the YAML `enabled` flag. The server still refuses one from another calibration. Background Collection shows the five states at their one exposure and hides Target Intensity for this modality, since the background must be taken at the acquisition exposure.
- **LC-PolScope: optional per-tile estimated background.** For a background that differs from tile to tile: `reconstruction.estimated_background.enabled: true` in the microscope YAML makes the server fit and remove a smooth residual from each tile after the measured background. A tile that is mostly specimen is left uncorrected, because the fit cannot tell smooth specimen retardance from residual. Off by default. Needs the matching microscope_command_server and polscope_library. See [CHANNELS.md](documentation/CHANNELS.md).

### Changed

- **Live Viewer: the Z position field and Move Z moved into the Z Focus section** of the Navigate tab, under Mark Z, Max Z Focus and the step size. Move to Position now holds X/Y (and rotation, where fitted).

- **The Live Viewer's bottom-docked histogram stops at the tabs.** It used to span the full window width, which cut the Navigate / Saved Points / Camera tabs short. It now sits under the image only, and the tabs run down to the status bar, which gives the movement controls and Z bars the extra height.
- **Connect to a Demo Microscope moved to Utilities > Microscope Configuration**, beside Switch Microscope. It was a top-level item. It is still always enabled, including with no configuration.

### Fixed

- **Dismiss on "Microscope Server Unresponsive" now sticks.** Any background poll brought the dialog back 30 seconds after Dismiss, so after the microscope was shut down for the night it could not be got rid of. It now stays away until you reconnect. The dialog also checks whether anything is still listening: when the server is gone, it says the server has stopped instead of claiming it accepts connections.
- **Microscope Alignment checks for the Data Bounds Classifier before it starts.** The alignment cannot be saved without it, but it was read only at the save, so a missing file (for example `WhiteBackground.json` not copied to a new computer) threw away all the tile work with "Error during Save Error". The workflow now stops before its first dialog and says which file is missing and where to set it. If the save still fails, the message says the alignment was not saved and why.
- **Data bounds detection removes everything it draws.** Only the "Other" regions and the Bounds annotation were removed, so any other class the classifier produced stayed on the image after alignment.
- **Error dialogs no longer read "Error during ... Error".** The heading is the dialog's own label ("Stitching Error", "No Image") instead of being prefixed with "Error during".
- **The Image orientation dialog says when it is switching images.** Ticking a box loads a different version of the scan, which can take several seconds with no other sign of activity. The dialog now shows "Switching to the flipped image, please wait" with a spinner, and locks the boxes and OK until the new image is showing.
- **OK in the Image orientation dialog no longer flips the image back and forth.** It reloaded the original scan and then flipped it again even when the image already matched the boxes.
- **Changing the flip in the Image orientation dialog changes the image.** The Camera View copy kept the pixels of the first flip it was built with. Ticking Y and then X as well showed a Y-only image labelled X+Y. The copy is now rebuilt when the flip changes, unless acquisitions were made from it. Its Camera View tag is also saved right away, so a QuPath crash no longer leaves an unrecognized copy that the next run duplicates.
- **Alignment moves to the second and third tiles in the right direction after Calibrate Directions.** The first guess at where each later tile sits came from the stage polarity setting alone. It ignored the camera orientation that Calibrate Directions records, so on the LC-PolScope, whose calibration put a Y flip in the camera orientation, the stage went the wrong way in Y. The fine-tuning after each tile corrects only the position, so the saved alignment would have stayed mirrored in Y. Alignment now uses the same direction mapping as double-click-to-center in the Live Viewer. Scopes with a NORMAL camera orientation (PPM, OWS3) behave exactly as before.
- **Microscope Alignment stays in Camera View after the orientation dialog.** With a flip checked and Camera View already open, OK switched the viewer back to the original scan for "Alignment - Select tile". Switching images reported success before QuPath had finished changing the open image, so the next step saw the old image and did nothing.
- **The Image orientation dialog shows the image its boxes describe.** The boxes are flips relative to the original scan, but the dialog only switched images when a box changed, and could not switch at all once a Camera View entry was open. So with Camera View open and the boxes unticked, the view could match the Live View while OK recorded "no flip". The dialog now switches to the matching image as soon as it opens and on every change, going back to the original scan first when needed.
- **The Position Confirmation dialog shows all its text and gives the steps in order.** It had a fixed size, so the focus warning, the instructions and any SIFT error were each cut to one line. It now sizes to its content and grows to show a SIFT error. Without a stage estimate, the instruction now says to move roughly onto the tile (within the SIFT search margin), then use Auto-Align (SIFT), then confirm. Previously it said to drive the stage until the view matched, which leaves SIFT nothing to do.
- **The SIFT "pixel size 0" error names both causes.** It said Micro-Manager had no pixel-size calibration, even when one was defined. Micro-Manager also returns 0 when a preset exists but none matches the current hardware state (for example, a different active camera), and the server returns 0 to a connection that is not yet configured. The message now says both, and which server log line tells them apart.
- **Stage direction calibration solved truthful answers 180 degrees off.** The step questions ask which way the image content moved, but the answer was compared with the way the view pans, the opposite direction. So the solved camera orientation was rotated 180 degrees, and double-click-to-center then moved the wrong way on both axes (LC-PolScope). The answer is now compared with the tissue's own motion, and the question reads "which way did the tissue in the image move across the screen?". **After updating, re-run Calibrate Directions on any microscope calibrated with this dialog.**
- **The Alignment - Select tile dialog no longer strands single words on a line.** Its text was wrapped at a fixed width that was slightly too narrow for the 16 px font, so a line such as "...the estimated position for this tile." ended with a word or two alone on the next line. The dialog now sizes its text to the longest line (up to 820 px).
- **Microscope Alignment no longer requires particular annotation classes.** It stopped with "Alignment: no annotations" unless an annotation was classed Tissue, Scanned Area, Bounding Box or one of your collection classes. Those are still preferred when present, but any area annotation, classified or not, now works.
- **The Stage Map says when a slide has no alignment.** With a whole-slide image open that has a macro image, but no alignment between the selected **Source** and this microscope, the map used to be simply empty. It now shows "No alignment found for selected source microscope or scanner" in large bold text, with a pointer to **Utilities > Microscope Alignment**. A slide without a macro image shows nothing, as before.
- **The Stage direction calibration dialog is usable again.** It opened at a fixed size computed before its content was added, so the instructions were cut to one line and the Begin / Next / Apply buttons were below the window with no way to reach them; every later step had the same problem. The dialog now sizes to each step's content. It stays non-resizable so the Dialog Manager remembers only its position: a remembered size would clip the next step, since every step shares one title.
- **Double-clicking the Live Viewer image no longer moves the stage while Live is off.** Every other movement control is disabled when Live View is off or a calibration has locked the viewer, but double-click-to-center sat on the image and checked neither. It now refuses in both cases; with Live off the status line says "Movement disabled - Live View is Off".
- **The Live Viewer histogram shows a 12-bit camera's real curve.** Frames from a 12-bit camera (the LC-PolScope Oryx) arrive in 16-bit buffers, and the histogram, sliders, Full Range and Auto Scale all spanned 0-65535, so the whole image sat in the first sixteenth of the axis. The counts were also drawn on a log scale that flattened every peak. The range now comes from the camera's bit depth (the server reports it; needs the matching microscope_command_server), the counts are drawn linearly as in Micro-Manager, "Sat" counts pixels at the camera's own maximum (it could never flag a clipped 12-bit frame), and the status bar says "12-bit in 16".
- **The LC-PolScope calibration result is no longer lost.** QPSC read the server's reply one network read at a time, as if each read were one message. When the last progress message and the start of the result arrived together, the result was discarded and the progress dialog waited until its 10-minute timeout. Found on the demo microscope, where it happened every time.
- **The LC-PolScope calibration pauses the Live Viewer.** The viewer's "no frames" watchdog restarted streaming in the middle of the calibration's exposures.
- **Apply to acquisition keeps a state's other settings.** Applying an LC calibration replaced each state's whole `device_properties` list with the two crystal settings. Entries for other devices (a lamp the state turns on, for example) are now kept after them.
- **Fluorescence, BF_IF and LC-PolScope acquisitions show their own channels.** The channel picker in the acquisition dialogs showed the first channel list in the microscope YAML, whatever modality was selected. On a config that lists Fluorescence first, the LC-PolScope dialog offered DAPI to Cy5 as its "states", and BF_IF offered the fluorescence channels without BF.
- **Background Collection selects the profile's detector.** It kept the last-used detector, which on a scope with a camera per modality filed the background under the wrong camera.
- **Bounded Acquisition uses the projects folder shown in its dialog, and requires one.** It used the Projects Folder preference and ignored the field. With the preference empty, the project was created relative to QuPath's working directory and the run stopped after creating it, with nothing on screen.
- **The demo microscope no longer runs backwards on an install calibrated for an inverted stage.** Stage direction and camera orientation are QuPath-wide preferences set by Calibrate Directions on the real microscope. The demo's stage is not inverted, so its arrows ran the wrong way and its stitched mosaics were mirrored top to bottom, with a step at every seam. Connecting to the demo now sets them to the demo's and switching to another microscope restores yours.
- **Switch Microscope to the active config re-reads it.** Choosing the config that is already loaded, after it changed on disk, kept the copy in memory.
- **The LC-PolScope Camera tab no longer shows another modality's lamp.** It showed whatever light source the server had active, for example the fluorescence LED; it now shows a lamp only when the LC-PolScope modality declares one.
- **A SIFT miss during refinement offers a wider search.** When SIFT found no match, the pane said "nudge manually", and choosing Skip continued with the existing alignment, even when that alignment was the problem. On 2026-10-19 a slide's alignment was 2.5 mm out, and five 1 mm searches failed before one found it. The pane now says how far it searched and offers **Search wider (3 mm)**, a one-off retry that leaves the Search margin preference alone.
- **Cancelling a region no longer deletes regions that already finished.** The cancel and error path force-deleted the whole run folder, including the tiles of regions acquired earlier in the run and still stitching. Cancelling the second region of a run deleted the first region's tiles while it was being stitched, and three of its four images were never written. Finished regions are now kept; only regions that did not complete are cleaned up.
- **The Stage Map's macro overlay follows a refinement.** It was always placed from the scanner preset, so a refinement that moved the slide's alignment by 2.5 mm left the overlay where it was. The overlay now moves by as much as each refinement moves the slide's alignment, and keeps that for the slide. The shift is stored in the slide's alignment file, and a new alignment resets it.
- **Moving the stage during manual focus no longer stops the run as a stage frame fault.** The manual focus dialog lets you move XY to find tissue, but the stage frame checks stayed on. Moving the stage by hand (the joystick, or Micro-Manager's own controls) then made the server stop the run with "Stage coordinate frame is no longer trustworthy ... with nothing commanded in between". Both the server's check and QPSC's watchdog now pause while a dialog waits on you. The server also re-measures from where the stage stands when a run starts, so a manual move between runs no longer trips it either. A controller restart is still caught. Needs microscope_command_server 87a1d97 and microscope_control 0d0ed10.

## [0.11.0] - 2026-10-18

### Added

**Stitch record beside every stitched image**
- Each stitched image now has a `<image name>.stitch-info.txt` in `SlideImages/` describing how it was produced. tiles-to-pyramid writes the stitching part: source tiles, stitch settings, what tile registration did, and software versions. QPSC adds an `[acquisition]` section: sample, modality, objective, detector, microscope, region, parent image and offset, stage bounds, field of view, stage/camera transform, the acquisition folder and its record files, and the full acquisition command. The record follows the image through QPSC's renames. A merged multichannel image's record includes each channel's record. Stitching Recovery and the MicroManager folder stitch write one too. Requires tiles-to-pyramid 0.7.1.

**Choose which channel(s) line the tiles up when stitching fluorescence**
- New **Align** column in the channel picker. It sets what content-based tile registration measures the tile overlaps on. Tick one channel to align on it alone, or several to align on a normalized merge of them: each channel is scaled once for the whole dataset, so a dim channel counts as much as a bright one. With nothing ticked, QPSC aligns on the channel autofocus ran on. If there is no usable focus channel, it uses a merge of every acquired channel. Previously fluorescence always aligned on the first channel in the list. All channels are still placed with the same result, so they keep overlaying each other.
- For channel acquisitions the alignment is now solved once before any channel is stitched, and then all channels stitch in parallel. Previously the first channel was stitched on its own first.
- **Stitching Recovery** re-stitches channel folders with the same alignment as the acquisition. It first uses whatever the previous `TileRegistration.txt` aligned on, then the recorded focus channel, then a merge of all channels. Previously it treated channel folders like angles and aligned on the first one.
- Requires tiles-to-pyramid 0.7.0, now the version QPSC builds against. With an older tiles-to-pyramid installed, QPSC aligns on the first ticked channel (or the focus channel) and logs a warning instead of merging.

**Acquisition failure push notifications**
- A failed region now sends an urgent push notification as well as opening the modal error dialog. The dialog waits at the workstation, so an unattended run that died hours ago was only discovered at the next login -- the same channel that reports "slide acquired" said nothing about "slide failed". Requires ntfy.sh to be configured in Communication Settings; see [Alerts](documentation/PREFERENCES.md#alerts-qupath-scope-alerts).

**Apply an LC-PolScope calibration to acquisition**
- The LC-PolScope calibration result dialog has an **Apply to acquisition** button. It writes the calibrated palette into the microscope YAML, so acquisition uses that calibration rather than whatever calibrated the Micro-Manager Channel presets. Each state's LC retardances are written in waves, its stale voltage preset is dropped, `reconstruction.state_order` is reset to the palette's order, and the calibration file, time and extinction ratio are recorded under `reconstruction`. A calibration below an extinction ratio of 80 asks for confirmation first. The server reads the crystals back on the first tile, and derived tiles record `polscope.lc_readback_agrees`. See [UTILITIES.md](documentation/UTILITIES.md#lc-polscope-calibration).

**Try QPSC on a demo microscope**
- New top-level item **Extensions > QP Scope > Connect to a Demo Microscope...**, always enabled. It starts the microscope server in demo mode on this computer (`microscope-command-server --demo`, found through the *Server launcher* preference or the PATH; a server already listening is used instead), extracts a bundled demo configuration, switches to it, connects, and opens the Live Viewer. The demo server is the production server with its Micro-Manager core replaced by an in-memory microscope: a virtual H&E slide on an XYZ stage with a focal surface, so the Live Viewer, Stage Map, autofocus, acquisition and stitching all run the real code path. Needs QuPath, the two JARs and the Python packages only; no Micro-Manager. When the server cannot be started, a dialog shows the command to run and offers to locate the program. The *Server Launcher* preference is also on the preferences pane (Edit > Preferences > QuPath SCope). See [INSTALLATION.md](documentation/INSTALLATION.md#try-it-without-a-microscope-optional).
- The demo configuration lives in `<QuPath user directory>/qpsc-demo-microscope/` beside the Offline / Analysis placeholder; both are installed by the extension and written only when missing.

**Switch microscope without restarting QuPath**
- Changing the Microscope Config File preference now offers a **Switch Microscope** action instead of requiring a restart. The switch disconnects from the command server, reloads the config, rebuilds the QP Scope menu, and reopens the Live Viewer and Stage Map if they were open. Other QP Scope windows are closed, since they describe the previous microscope. It is also available any time from Extensions > QP Scope > Utilities > Microscope Configuration > Switch Microscope... It is refused during an acquisition, and a file that fails validation is rolled back to the previous configuration. See [PREFERENCES.md](documentation/PREFERENCES.md#microscope-config-file).

### Fixed

**Autofocus recovers from a starting Z far from focus**
- A wrong starting Z (slide 4 of the 2026-10-04 batch: -285.8 um, tissue at -330 to -394 um) no longer holds a whole slide out of focus. Every standard autofocus may now widen its search, a flat primary metric follows the p98_p2 trend toward focus, and a skipped autofocus falls back to the last accepted focus instead of the starting Z without being recorded as a measurement. Needs microscope_command_server and microscope_control from 2026-10-17. See [AUTOFOCUS.md](documentation/AUTOFOCUS.md).
- QPSC's runaway guard on each annotation's final Z adopts a value once two annotations in a row disagree with the last good Z on the same side and agree with each other, instead of holding the stale value for the rest of the run.
- Tile measurements record a skipped autofocus as `af_type` 3 (captured at a fallback Z), where it used to read 0 (no autofocus).

**Autofocus gets its own section; Bounded Acquisition follows the open project; pixel-size drift is reported**
- In both acquisition dialogs the autofocus controls moved out of MODALITY-SPECIFIC OPTIONS into their own **AUTOFOCUS** section, collapsed by default. Every modality autofocuses, so under "modality-specific" they read as a PPM concern on a fluorescence run.
- The Bounded Acquisition dialog's PROJECT & SAMPLE rows now follow QuPath's open project while the dialog is up, and Start Acquisition uses the project open at that moment. Previously opening or closing a project after the dialog appeared could send the acquisition to a folder the dialog never showed.
- When the config and MicroManager pixel sizes differ by 0.5% to 5%, the acquisition goes ahead with a WARNING in the log and a once-per-session notification showing both values. Previously anything under 5% passed silently, and tile registration absorbed the drift. More than 5% still cancels, because that means the wrong objective.

**Calibration warnings name only what the acquisition uses**
- The Acquisition Wizard no longer warns about white balance or backgrounds when an acquisition starts. That check ran before the white balance mode was chosen and rolled up every mode, so an operator acquiring with per-angle white balance was warned that simple white balance was not calibrated. Once the acquisition dialog fixes the mode, both Bounded and Existing Image acquisitions now check that mode alone, its white balance calibration and its backgrounds, and ask before acquiring if it is not ready. In an armed automatic batch the problem is logged and the run continues.
- The launch warning about a missing microscope alignment now appears only for Existing Image Acquisition; Bounded Acquisition does not use an alignment.

**Cancel autofocus validation in the Acquisition Wizard; larger text in the tile dialog**
- **Validate AF** in the Acquisition Wizard shows a **Cancel** button while it runs. Cancel stops the test between Z steps, in either phase, and Z returns to where the test started. Needs microscope_command_server and microscope_control from the same date. The autofocus status now sits on its own line, so it is no longer cut off while the test runs.
- The **Alignment - Select tile** dialog uses larger text: 16 px, with the double-click instruction at 17 px bold.

**Acquisition wizard icons, tile dialog wording, and a second focus curve on Navigate**
- The Acquisition Wizard's two large buttons show what they acquire: a tiled rectangle for **Bounded Acquisition** and annotated tissue for **Existing Image Acquisition**. Their longer descriptions moved to tooltips.
- The tile-selection dialog is titled **Alignment - Select tile** and says to double-click a tile in the image, since a single click selects the annotation around it.
- The Z focus trace on the Live Viewer's Navigate tab adds a faded P98 - P2 curve behind the Brenner curve, each on its own scale. P98 - P2 still finds focus on bright, low-texture fields where Brenner reads flat.

**Every error dialog is written to the QuPath log**
- 86 of QPSC's 175 error dialogs left no trace in the log, so a bug report could not show what the operator had seen. Every error dialog and error notification now writes its title and text to the log at ERROR first, on a line starting `Error dialog`.

**Demo microscope: a batch-launched server stops with QuPath, and a real server is never adopted**
- A demo server started through `start_server.bat` kept running after QuPath quit, holding port 5000. QPSC stopped only `cmd.exe`, not the Python server it had started. QPSC now stops the launcher and everything it started, at exit and when a start times out. Verified on Windows 11 with the real batch file.
- A failed `start_server.bat` preflight check no longer waits silently at `pause` until the 60 s timeout. QPSC closes the launcher's input, so the batch file exits at once and its message appears in the dialog. A start that does time out now also shows the program's last output.
- **Connect to a Demo Microscope** used to adopt any server already listening on the port and send it the demo configuration. It now asks the server for its camera first and refuses one that is not the demo's `DCam`, naming the camera. See [TROUBLESHOOTING.md](documentation/TROUBLESHOOTING.md#q-connect-to-a-demo-microscope-says-a-microscope-server-is-already-running-with-a-real-camera).

**The alignment check before an unattended acquire now sets up the camera first**
- Before its snaps, the check now starts the Live Viewer and applies the modality's alignment state, the same one the refinement dialogs use. On PPM that is the uncrossed angle with its white-balanced exposures and gains. Previously it matched against whatever angle and exposure the last step had left, and a slide could fail all three points with "SIFT matching failed - insufficient features or matches". If the camera cannot be set up, `halt` stops the batch with the reason and `warn` acquires unchecked. The result line in the log names the camera state used. See [PREFERENCES.md](documentation/PREFERENCES.md#check-alignment-against-the-sample-before-acquiring).
- The check also no longer runs on QuPath's interface thread, which had frozen QuPath for about 30 seconds per slide.

**LC-PolScope Setup window**
- New **Extensions > QP Scope > LC-PolScope > LC-PolScope Setup...**: the camera, the calibration and the background as three numbered steps, under the live "Ready to acquire?" status, so the order is the layout. Not modal, so you can fix a camera property in Micro-Manager and come back; the status rechecks when the window regains focus.

**LC-PolScope calibration measures the black level**
- The scope cannot block its own lamp, so the black level had to be typed in, and the stored 103 turned out to belong to the Hamamatsu, not the Oryx. After the search the calibration now holds the crystals at extinction and snaps at four exposures. Light scales with exposure and the camera offset does not, so the intercept of counts against exposure is the black level. The result shows it, and Apply offers to write it to `reconstruction.black_level` when it differs from the value in use. Requires the matching microscope_command_server.
- The same fit checks that the camera is linear: if counts are not proportional to exposure (a gamma curve, or gain changing between frames), the result is marked unreliable and nothing is offered.
- Apply's settings step is now one dialog with a tick box for the exposure and one for the measured black level, each shown only when the calibration measured something for it.

**LC-PolScope "Ready to acquire?" summary**
- The top of the LC-PolScope acquisition panel lists, before the stage moves: whether a QPSC calibration is applied, how old it is (a warning past 14 days) and its extinction ratio; whether a black level is set; for each LC-PolScope profile, whether a background exists and was collected under the current calibration (a background from another calibration shows as **Fix**, because the acquisition refuses it); and, from the live microscope, the camera settings and any problem with them (auto gain or exposure **Fix**, gamma or 8-bit **Check**), and whether the LC adapter's wavelength matches the reconstruction's. **Recheck** runs it again. Each of these used to appear only as a log line at the first tile. Requires the matching microscope_command_server (new `LCREADY` command); with an older server the camera line says it could not check.

**LC-PolScope Apply sets the exposure and leads into background collection**
- Apply to acquisition asks for the exposure all five states will use, prefilled from the usable range the calibration measured (the current exposure when it is inside the range, otherwise the nearer end pulled 10 percent inside), and writes it to every state with the palette. Keeping the current exposure is one click.
- The Apply summary ends with **Collect background now...**: a prompt to move to a specimen-free area and defocus 10-20 um, then Background Collection. A new palette makes the old background invalid, and the acquisition refuses it.

**LC-PolScope results open on a fixed scale, with a cyclic orientation colour image**
- Stitching an LC-PolScope run now also produces an orientation colour image: hue is the slow-axis orientation, one colour cycle over 0 to 180 deg so 0 and 180 deg match, and brightness is the retardance up to `reconstruction.display_retardance_ceiling_nm` (default 20 nm). It is display only; measure on the retardance and orientation images. QuPath cannot apply a cyclic colour map to an image channel, so the microscope server renders it as RGB. Requires the matching microscope_command_server.
- The retardance image opens at 0 to that ceiling and the orientation image at 0 to 180 deg, instead of each being stretched to its own percentiles, which made a blank field and a strongly birefringent sample look alike. The range is stored with the project entry and can still be changed.

**LC-PolScope acquisition panel: one exposure, all states**
- The LC-PolScope acquisition panel is titled "LC-PolScope states" and offers a single exposure for all five states, starting from the microscope YAML. Every state is always acquired. The per-state Use, Exposure, Intensity and Split columns and the preset bar are gone, because each was a way to produce a run the reconstruction cannot use: a missing state, or unequal exposures that bias retardance and orientation with nothing to see. Focus and Align stay. The microscope server now also refuses such a run before anything moves.

**Channel backgrounds are found by the acquisition; LC-PolScope background correction per tile**
- Channel acquisitions now send the per-profile background folder that channel background collection writes to. They sent the folder above it, so collected channel backgrounds were never found and every channel run, fluorescence included, ran uncorrected. When the profile folder does not exist the log says so.
- LC-PolScope background correction works the way OpenPolScope's does: a specimen-free, defocused five-state set, applied per tile inside the reconstruction rather than by dividing raw states. Collection averages 10 frames per state, refuses a camera on auto gain, and records the LC calibration it was taken under. The acquisition refuses a background from a different calibration, reconstructs uncorrected, and logs why. Requires the matching microscope_command_server. See [UTILITIES.md](documentation/UTILITIES.md#lc-polscope-calibration).

**LC-PolScope stitching produces retardance and orientation; merged channels leave no duplicates**
- Stitching an LC-PolScope acquisition now also stitches the `retardance/` and `orientation/` folders the microscope server writes during acquisition, and imports each as its own project entry (channels named "Retardance (nm x100)" and "Orientation (deg x100)"). These are the results of the method; before, the channel stitch never looked for them and only the five raw states were stitched. Orientation is axial, and tiles-to-pyramid builds its pyramid levels accordingly.
- After a successful channel merge, the per-channel pyramids that went into it are deleted, with their stitch records. This applies to **every** channel acquisition, fluorescence included: the merged file holds each channel, so the per-channel files were duplicates that looked like separate images in `SlideImages`. Only the exact files the merge consumed are removed, and only once the merged file exists. Channels you chose to write as separate files are kept, and a failed merge keeps everything.

**Acquisition no longer crashes on an empty background_correction value**
- A `background_correction` entry with `base_folder: null` (or `method: null`, or an empty string) in the image-processing YAML stopped every acquisition for that modality with a NullPointerException before anything reached the microscope. The LC-PolScope configuration declares exactly that. Empty values now mean "not configured", the same as an absent key.

**LC-PolScope calibration records the camera and refuses a dark field**
- The calibration parameters dialog has an **Exposure (ms)** field, prefilled with the acquisition's exposure for the LC-PolScope states. The server sets it for the calibration and restores the previous exposure afterwards; blank keeps whatever Micro-Manager is set to. The result dialog, the QuPath log and the calibration file now carry the camera as the frames were taken (device, exposure and its source, bit depth, pixel format, frame size, binning, gain) and the frame counts the extinction ratio was computed from (extinction, brightest state, brightest frame of the search, fraction of the camera's range). The first two runs on the LC-PolScope were analysed blind: frame means of 1 to 17 counts, with no record of exposure or pixel format.
- A field whose brightest frame averages under a tenth of the camera's range is refused as too dark after about 25 exposures, with the counts and camera state in the error. Previously the search ran to completion on noise and offered the resulting palette for Apply. A black level above the darkest state measured (the YAML's 103 against 9-count frames) is called out as being in another pixel format's units. Requires microscope_command_server with the matching LCCALIB changes. See [UTILITIES.md](documentation/UTILITIES.md#lc-polscope-calibration).
- The LC adapter's Wavelength is now part of the calibration. The server sets it to the reconstruction wavelength before measuring and fails if it does not take. Apply writes the wavelength the palette was measured at ahead of each state's retardances, so every acquisition replays the palette at the same waves-to-volts conversion. The acquisition logs the adapter wavelength at the first state, warns when it differs from the reconstruction wavelength, and records it on every derived tile. On the LC-PolScope it was 546 against a 549/15 filter, a 0.55 percent retardance scale error.
- The default output folder is `calibrations/lcpolscope` inside the open project, falling back to the projects folder preference when no project is open; it was the projects folder, one level above the project.
- The headline is **unreliable**, whatever the extinction ratio, when the server could not trust its frames: a clipped state, a swing contrast under 1 percent of range, a black level above the darkest state, a camera gamma curve, 8-bit frames. The dialog names the issues, the ratio's own band is still shown, and Apply asks for confirmation. The server refuses to calibrate at all through a camera on auto gain or auto exposure. On the LC-PolScope the first accepted run read "Good" at an extinction ratio of 133.8 with one state 99.9 percent saturated and `Gain Auto: Continuous`; every state had settled at the camera's own brightness target.
- The result reports the swing contrast (what the palette was resolved from) and the extinction leakage above the black level, warns when the contrast is under 1 percent of the camera's range or when a state clipped, and scales the counts to the acquisition exposure from the YAML, naming the exposure to set when the acquisition would be dark or would clip, together with the usable range at this illumination. A PNG of per-state pixel histograms is written beside the calibration file and linked from the dialog.

**LC-PolScope calibration result is readable, copyable and logged**
- The **LC Calibration Complete** dialog, and the **LC palette applied** dialog after it, now use a resizable, wrapping, selectable text body. A long warning line used to stretch the dialog to the width of the screen, nothing wrapped, and the palette could not be copied. Everything the dialog shows is also written to the QuPath log, with each warning at WARN; previously the log held only the command that was sent.
- The black level field is prefilled from `reconstruction.black_level` in the microscope YAML. Left blank, the server measures a dark frame, which needs lamp control; on a scope without it the calibration assumed 0 and overstated the extinction ratio, even with 103 recorded in the file. The server now also falls back to the YAML value itself.

**LC-PolScope calibration dialog shows the LC control mode**
- The pre-calibration summary now lists the LC control mode from the microscope YAML. A config that sets `MM-Voltage` is marked NOT SUPPORTED, because the microscope server refuses that mode until a retardance/voltage curve is wired up. Calibration failures and dialog errors are also written to the QuPath log, so they reach a bug report and not only the dialog.

**Setup Wizard shows detectors for configs that list them in the shared resources**
- Some configs (the LC-PolScope config is one) keep detector definitions in the shared resources file and list only their IDs under `hardware.detectors`. The wizard's Detectors table was empty for these configs. It now shows each listed detector, with its name, camera type, device and sensor size taken from the bundled detector catalog. An ID missing from the catalog still appears in the table, with only its ID filled in, and is logged as a warning.

**Modality submenus no longer repeat when a modality has several names**
- A modality registered under more than one name (LC-PolScope is registered as both `lcpolscope` and `lcps`) got one QP Scope submenu per name when the config was invalid. Each modality now gets one submenu.

**XYZ and fast Z reads no longer report a hardware error as a position**
- When the microscope server could not read the stage, `GETXYZ` replied three zeros and `GETZF` one zero, which QPSC took for the stage sitting at the origin. The server now sends `HW_ERROR____` (12 bytes) and `HWERR` (5 bytes) in their place, and QPSC raises a hardware error for both, as it already did for `GETXY` and `GETZ`. Requires the matching microscope server update; against an older server nothing changes, since it never sent the markers.

**Z and rotation reads no longer misread a server hardware error**
- When the microscope server could not read the Z or rotation stage, it replied `HWERR` (5 bytes) where a 4-byte value was expected. QPSC decoded the first four bytes as a position and left the fifth on the socket, which shifted every later reply on the auxiliary connection by one byte. QPSC now raises a hardware error and the connection stays in step. No server update is needed for this fix.

**"QPSC - restart recommended" now appears when you update, not after you restart**
- QuPath installs an updated jar in the running session but keeps running the classes it already loaded, and only runs the new version's start-up code at the next launch, so the start-up version check could only ever fire on the launch after the restart it asked for. QPSC now watches for its own jar being replaced while QuPath runs and advises the restart then, once. Nothing shows at start-up, including after a fresh install. The unused `lastLoadedQpscVersion` preference is removed.


## [0.10.0] - 2026-09-16

### Added

**LC-PolScope liquid-crystal calibration workflow**
- New menu item "Calibrate Liquid Crystals..." appears under the QP Scope menu when using an LC-PolScope microscope. Finds the extinction point (where the crystals transmit the least light) and the swing states either side, then writes the palette the acquisition uses. Runs on a clear, specimen-free field; automatically uses the microscope configuration's swing, scheme, and wavelength settings so the calibration cannot disagree with the acquisition. Reports an extinction ratio as a quality figure (100 or above is good); supports single-pass or iterative search strategies.

**Focus Approach Validation: live vertical focus-metric plot during the traverse**
- The focus-approach validation window now displays a vertical plot of the focus metric while scanning from the safe Z toward the sample. The plot is oriented with the traverse start at the top (retracted) and the far end at the bottom (toward sample), matching the stage's direction of motion. During the scan, the Live Viewer's per-frame focus samples accumulate as a live trace (yellow); when the scan returns, the server's actual measurements replace the live approximation with the server's profile (cyan), named for the metric it used (e.g., `brenner_gradient`, `p98_p2`). Reference lines show your hand-focused Z (green dashed) and the current stage Z (gray). The validation window stays open after completion with a "Continue" button so you can review the curve before proceeding to the second (bare-slide) scan.

**Stage Map: slide placement updates as alignments are collected**
- During multi-slide batch acquisition, slides now appear on the Stage Map at their measured actual position once their alignment is saved, rather than staying at their nominal slot centre. A slide's true position in the holder can differ from its nominal slot by a median ~600 µm, so the map's accuracy improves throughout a batch run as alignments accumulate. Before any alignment is saved, slides show at their nominal positions (the existing behavior); after alignment, each slide moves to reflect reality. Only each slide's position is corrected, not its rotation or size, so the map remains an approximate spatial reference rather than an exact one.

**Bug reporter: optional contact fields, and an honest note about anonymity**
- Reports are filed by a shared reporting service, not from your GitHub account, so GitHub has
  nobody to notify -- you would never see a reply unless you went back and commented yourself.
  The dialog now says this at the top, before you write anything.
- Two optional contact fields let you stay in the loop: a **GitHub username** (mentioned on the
  issue, which does notify you) and an **image.sc forum username** (linked to your profile).
  "alice", "@alice" and a pasted profile URL are all accepted; leave both blank to stay anonymous.
  Both appear publicly in the issue.
- New **"Open the issue in my browser after submitting"** checkbox, on by default.

**MDA export: custom tile field of view**
- Clicking "Save MDA..." (or the per-modality "Save as MicroManager MDA..." button) now opens a small prompt for the tiling field of view, pre-filled with the camera FoV for the selected objective/detector. Accepting the default reproduces the acquisition's own tiling; entering a different width/height tiles the annotations for another device's field -- the motivating use case is handing the position list to the MicroManager laser scanner, whose scan field differs from the camera. An "apply tile overlap" checkbox reuses the current overlap preference or sets 0%. This affects the exported `.pos` positions only; the real camera acquisition is unchanged. Cancelling the prompt aborts the export silently.

### Fixed

**Multi-Slide: stage controls now lock during slot-jump autofocus, with a Cancel affordance**
- When autofocus-on-slot-jump ran during a multi-slide alignment, the Live Viewer's stage-movement controls stayed enabled, so the operator could bump the stage mid-scan, and there was no way to cancel the scan. The slot-jump autofocus now locks the Live Viewer's stage-movement controls (arrows, joystick, go-to-centroid, live toggle) while it runs, and turns the Live Viewer Autofocus button into a "Cancel Autofocus" toggle -- the same affordance as a single-slide scan. Clicking it sends `ABORTAF` (aborts both the streaming and sweep paths); the controls unlock automatically when the scan settles (success, failure, or cancel). The same lock covers the multi-tile refinement's per-point autofocus, which shares the slot-jump path.

### Changed

**Multi-Tile refinement: "Select tile" becomes "Select a new tile" after the first, and re-picking a used tile is rejected**
- In the multi-tile alignment refinement, the reference-tile button is relabeled to "Select a new tile" once the first point is captured, and selecting a tile that was already captured for an earlier point is now rejected (a "Tile already used" notification; the operator re-picks a different tile). Tiles are matched by their stable `TileNumber` measurement, falling back to object identity.

## [0.9.3] - 2026-08-11

### Fixed

**"Save as MicroManager MDA..." button: exported .pos now carries real stage coordinates**
- The pre-acquisition MDA export button (on the Existing Image Acquisition modality panel) synthesized tile centroids in the open image's full-resolution pixel space, then applied either a saved **macro -> stage** preset (which maps ~81 um/px macro pixels, not ~0.25 um/px full-res pixels -- off by ~300x) or, when no preset was selected, raw `pixel * pixel-size` with no stage offset. The resulting `.pos` coordinates matched no real stage position. The button now uses the same **full-resolution -> stage** transform the acquisition itself uses (`MicroscopeController.getCurrentTransform()`), so the exported positions match what the acquisition process writes. If no such transform is available yet (alignment not run), and no already-full-res-scale preset is selected, the export now reports "run alignment first" instead of writing miscalibrated coordinates.

## [0.9.2] - 2026-08-11

### Fixed

**Multi-Slide: a slide assigned to a new slot no longer reverts to its old position**
- In the Multi-Slide assignment dialog, the same project image could be selected in two slots at once -- e.g. it was pre-filled at slot 5 (from a prior run's `slide_position`) and then re-picked at slot 4 without the leftover slot-5 selection clearing. On start, the run stamped that base entry once per slot, and because the slots stamp in order the **later slot won**, silently reverting the assignment (the "I set it to slot 4 but it keeps defaulting to 5" report). The dialog now enforces one slide per slot: selecting an image in a slot clears it from any other slot, and on start a base entry is stamped with exactly one `slide_position` (first slot wins if a duplicate ever slips through). Existing projects self-heal on the next correct run.

## [0.9.1] - 2026-08-11

### Fixed

**Multi-Slide (PPM): base annotations now copied onto the rotated "(Camera View)" companion**
- After the 0.9.0 orientation refactor, a multi-slide slot's rotated/flipped working entry ("X (rotated 270) (Camera View)") was built directly and opened without passing through the single-slide base->companion annotation mirror, so it started empty -- the operator's macro annotations never reached the acquisition entry, and the workflow took the "no annotations" branch. The root cause was that the multi-slide entry-creation path (`createRotatedFlippedDuplicate`) never stamped the new light-path metadata (the `camera_view` flag and baked parity), because `applyImageMetadata` no longer records flip. The fix: (1) the multi-slide path now stamps the full light-path snapshot on the companion at creation (matching the single-slide path); (2) a pre-dialog step brings the source macro's annotations onto an empty companion -- transformed through the same rotate+flip that produced its pixels, then persisted -- **before** the acquisition dialogs read its annotation classes.

### Changed

**Rotation is now recorded in metadata, not read from the entry name**
- The baked quarter-rotation of a companion is stored in `lp_rotation_deg` metadata and is the single source of truth the code reads. The "(rotated N)" name token is retained for user reference only; no code path parses the name for rotation (or for camera-view classification) any more -- rotation comes from `getRotationDegrees(entry)` and companion classification from `isCameraView(entry)`. Rotated-companion lookup (single- and multi-slide) now matches on `base_image` + baked rotation + the `camera_view` flag rather than on the composed name. The `(rotated N)(flipped ...)` name-based transform math is consolidated into one shared implementation (`AnnotationHelper`) used by both the existing-alignment gate and the new multi-slide populate.

## [0.9.0] - 2026-08-11

### Changed

**Light path & orientation: consolidated model, per-slide placement, "(Camera View)" companion**
- The scattered flip/orientation logic is consolidated into a single cohesive, forkable `LightPath` class (new `utilities/lightpath` package: `LightPath`, `Parity`, `LightPathSnapshot`), which composes the orientation stack per detector and is the one place a differently-configured scope plugs in its geometry.
- **Per-slide placement.** A slide can now be placed either way in the holder (label-left "as scanned" or label-right, a 180). The Stage Map "Slide:" control now stores this **per slide** and bakes it into the corrected whole-slide image so that image matches the live camera for the placement actually used. Changing placement marks the corrected image and any saved alignment stale (re-run alignment).
- **The corrected companion is renamed to a single "(Camera View)" entry** (was "(flipped X|Y|XY)"). The flip axes are recorded in metadata rather than the name; check the entry's light-path metadata for specifics.
- **`flip_x`/`flip_y` metadata removed.** Per-entry orientation was a lossy single-bit proxy; it is replaced by the full light-path metadata set plus a derived net "baked parity". This is an internal change with no effect on existing as-scanned slides (locked by regression tests), but it is **not backwards compatible**: projects with old "(flipped X)" entries will have a fresh "(Camera View)" companion created on next alignment.

### Fixed

**Multi-Slide & Existing Image Acquisition: removed hard requirement for macro pixel size preference**
- The Existing Image Acquisition workflow (including Multi-Slide) previously required the "Macro image pixel size in microns" preference to be set, and crashed with "Macro image pixel size is not set" if it was empty. That preference was dead code: once a fullRes->stage alignment transform exists (which is always present in these paths), tiling needs only the camera FOV and the open image's own pixel calibration. The workflow now automatically resolves pixel size from the open image's calibration instead, so users no longer need to set the preference (though leaving it set causes no harm). This fixes the multi-slide batch acquisition blocker that was preventing workflow progress until the preference was manually configured.

**SIFT auto-align: incorrect stage move on flipped entries**
- Auto-Align (SIFT) was moving the stage to the mirror (off by ~1.5 tiles) on flipped entries such as PPM flipped siblings. The offset was being applied raw, which only works for axis-aligned positive-scale transforms. The fix composes the SIFT offset through the same alignment transform used to predict the tile position, so the stage move is correct on any rig by construction -- no hard-coded signs. The raw offset is still shown in the UI for debugging; the stage move uses the composed delta.

### Changed

**Stage Map: per-microscope light path configuration**
- The Stage Map's orientation is now derived from per-microscope YAML configuration (`light_path` block). Three separate dropdowns govern the display: **Scope type** (Upright | Inverted, factor 1a: how the slide physically sits), **Slide insertion** (A | B, factor 1b: which side the label is on), and **Optics** (none / flip X / flip Y / 180 (XY), factor 2: objective + tube-lens parity). **Scope + Slide** determine the Stage View (how the slide sits on the bench); **Optics** feeds the Camera View (so the map matches the Live Viewer). These are display only; acquisition already gets the orientation right via the empirical alignment transform. Changes write directly to the microscope config (`light_path.scope_type`, `slide_insertion`, `optical_flip`), so the Stage Map and Setup Wizard share one source of truth. Defaults (Upright + A + Optics: none) reduce both views to identity — the historical inert behaviour, no regression until the rig is described.

**Live Viewer Camera tab: Profile default now matches current objective**
- The Profile dropdown in the Camera tab now defaults to the profile matching the currently-active objective (e.g., showing `Brightfield_20x` when the objective is 20x), using the same resolution rule as acquisition workflows. Previously the dropdown defaulted to the first profile in the list regardless of objective, which could show `Brightfield_10x` on a 20x objective and require a manual selection change. A user's manual selection is still retained across rebuilds (e.g., when switching modalities).

### Added

**Background image missing: non-blocking warning notification**
- When acquisition begins and one or more angles lack a matching background image, a warning toast now notifies the operator which angles will not have background subtraction applied. The acquisition proceeds automatically (the toast is advisory, not a prompt); the operator can let it run or cancel and re-collect backgrounds. Previously the disabling was silent.

**MicroManager Folder Stitch: axis inversion controls**
- New checkboxes **"Invert X axis"** and **"Invert Y axis"** in the Stitch MicroManager Folder dialog allow correcting for stage-axis inversion that occurs when a microscope's MicroManager configuration inverts an axis. When inverted, MicroManager records stage positions that run opposite to pixel space, causing tiles to be laid out mirrored; negating that coordinate before stitching corrects the mirroring. Checkboxes default to the current microscope's stage polarity (for the common case of stitching data just acquired on that same scope) and can be overridden to match different hardware configurations when stitching data from a different microscope. User choices are remembered across sessions.

## [0.8.0] - 2026-07-22

### Changed

**QuPath 0.7.0 is now required**
- The extension declares and builds against QuPath 0.7.0 (was 0.6.0). Install QuPath 0.7.0 or later. Docs updated to match.

**Multi-Slide Acquisition promoted out of experimental**
- The former "MS-Existing Image (experimental)" workflow is now **"Multi-Slide Acquisition..."**, shown directly beneath "Acquisition Wizard..." at the top of the QP Scope menu. The opt-in "Enable Multi-Slide Workflow (experimental)" preference has been removed: the menu entry appears automatically whenever the active microscope config defines a multi-slide holder (a `slide_holder` insert with more than one slot) and is hidden otherwise. Launching it now auto-opens the Live Viewer and Stage Map (like the Acquisition Wizard) so the stage/insert context is visible while assigning slides to slots.

### Added

**"Restart QuPath" advisory after an install or update**
- When the extension jar is installed or updated in a running QuPath session, the old classes stay loaded until a restart -- which can look like the update did not take effect. On the first launch after the packaged version changes, a one-time modal now advises restarting QuPath. It does not appear on normal relaunches, and unpackaged IDE/dev builds are skipped. (Added to QP-CAT and the DL Pixel Classifier as well.)

### Fixed

**Stage Map: multi-slide macro previews lingered after switching inserts**
- After a multi-slide run, the rotated per-slot macro previews (e.g. a vertical `quad_v` layout) stayed on the Stage Map when you manually switched the insert dropdown back to a single-slide holder, so the macro appeared "still vertical" over the wrong insert. The previews are now cleared when the Stage Map is switched to a different carrier (re-selecting the same carrier keeps them as a layout reference). No flip/rotation math changed.

**Multi-Slide assignment: diagnostic logging for eligible-macro selection**
- The assignment dialog now logs each project entry it considers -- name, `base_image`, source scanner, and the include/exclude reason -- plus a final eligible-of-total tally, so a "no eligible macro entries" result is diagnosable from the log instead of silent.

## [0.7.2] - 2026-07-22

_Bundles all work merged to `main` since 0.7.1 (the items below), plus the stitching fix._

### Fixed

**Stitching: NPE crashed the stitch after a completed acquisition (null transform)**
- Stitching runs asynchronously after an acquisition finishes. `AcquisitionManager.launchStitching` was passing the *live* microscope transform (`MicroscopeController.getCurrentTransform()`) into the async stitch, but the workflow's cleanup nulls that transform (`setCurrentTransform(null)`) once the run winds down. On a long acquisition the cleanup won the race, so the stitch-metadata step dereferenced a null transform and threw a `NullPointerException` -- failing the stitch *after* the tiles had been fully acquired. The fix passes the immutable transform captured in the workflow state (the same value every other site in `AcquisitionManager` uses), and adds a defensive null guard so a missing transform degrades to a zero metadata offset instead of crashing an already-completed acquisition's stitch. The acquired tiles on disk are unaffected; only the automatic stitch+import was lost, and can be re-run on the tile folder without re-acquiring.

### Added

**Multi-Slide Existing Image: auto-collapse progress panel on focus loss**
- The progress panel in the Multi-Slide Existing Image workflow now automatically collapses when focus shifts to another window (e.g., an alignment dialog or the Stage Map) and automatically expands when the panel regains focus. This mirrors the Acquisition Wizard's behavior, letting the panel tuck itself out of the way during alignment steps without a manual click. The manual **Collapse** button still works as before.

### Fixed

**Bug reporter: issue titles came from the first line of the description**
- The "Report a bug" dialog now has a required one-line **Summary** field, which becomes the GitHub issue title. Previously the shared Cloudflare Worker derived the title from `description.split("\n")[0]`, so a report that opened with a greeting filed an issue titled `[bug] Hi,` -- and nothing in the dialog hinted that the first line mattered. Surfaced by `uw-loci/qupath-extension-cell-analysis-tools#8`. The Worker (`cloudflare-worker-bug-reporter/src/index.js`) now prefers a `summary` field and falls back to the old first-line behaviour, so jars that predate this change keep working. The same field was added to the QP-CAT and DL Pixel Classifier reporters. **The Worker must be redeployed for the new field to take effect.**

### Added

**High-bit-depth PPM capture (opt-in)**
- New PPM preference **"High Bit Depth PPM Capture"** (default OFF). When enabled, the PPM angle frames are acquired at the camera's higher-bit PixelFormat so the birefringence image is computed from genuinely high-precision inputs instead of 8-bit. The `.biref` file has always been 16-bit, but it was fed by the JAI's 8-bit output, so it carried only ~8 bits of real information; because the normalized birefringence `|(I+ - I-)/(I+ + I-)|` is scale-invariant, higher-bit inputs do not change the result's shape -- they reduce quantization noise, most visibly in the dark crossed-polarizer regions. The birefringence file stays 16-bit; QuPath's display maps it to the screen. OFF is byte-identical to the previous 8-bit path (no wire flag emitted). Requires a `high_bit_depth` block on the camera's detector entry in `resources_LOCI.yml` (MM PixelFormat property + high value, discovered per-scope); absent, the preference is a safe no-op. Spans the Java extension (`--ppm-high-bit-depth` flag), the command server (per-tile format switch with guaranteed restore, autofocus stays 8-bit), and `ppm_library` (dtype-aware dark-mask + sum normalization). See `documentation/developer/PPM_MODALITY.md`.

## [0.7.1] - 2026-07-06

### Added

**Petri-dish calibration by coverslip corners**
- Coverslipped 35mm dishes are now calibrated by the **four corners of the square coverslip** instead of the circular well edges. A circle has no rotational reference, but the coverslip corners are real fiducials and easy to center in the FOV. In Stage Map > Calibrate..., dishes show four coverslip-corner rows (one "Capture corner" button takes X and Y together, any order) plus a wireframe schematic (dish / coverslip / well) that highlights the corner being captured. The imaging rectangle is the bounding box of the four corners; axis inversion comes from the Stage Polarity setting (so place the dish in the scope's usual orientation). Config schema: `coverslip_c1..c4_x_um/_y_um` (+ `coverslip_size_mm`) on a `dish_holder` insert; the registry injects the aperture rectangle, so the well circle and dish outline still render. Free re-orientation (rigid solve + rotated overlay) remains a deferred follow-up.

**SIFT alignment: focus/exposure warning**
- Every SIFT alignment dialog (microscope-to-microscope alignment, existing-image manual alignment, and sub-acquisition refinement) now shows a bold reminder to confirm the live image is in focus and not saturated before aligning. SIFT matches on image detail, so a blurry or blown-out frame misaligns or fails -- and this is not detected automatically. Centralized in `SiftAutoAlignHelper.buildFocusSaturationWarning()`.

**Register Current Objective utility** (QP Scope > Utilities)
- New menu item to add the objective currently in the light path to the microscope config. It reads the pixel size from MicroManager (the one thing MM knows about an objective -- there is no MM magnification/NA/name), then prompts for the metadata MM cannot provide (id, display name, magnification, NA, working distance, detector). It writes skeleton entries across all the config files (`hardware.objectives` pixel size, an `autofocus_settings` block with `calibrated: false`, `imaging_profiles` placeholders per modality, and the `id_objective_lens` display entry in `resources_LOCI.yml`), then reloads the config so the objective is immediately selectable. Calibration-heavy data stays a placeholder -- the summary points to the Autofocus / White Balance / Background utilities to finish. All writes are comment-preserving (new `ConfigYamlEditor.appendListItem` / `appendMapEntry` primitives) and idempotent, so re-running after a partial failure is safe. Requires a live microscope connection.

### Fixed

**Existing-image: choose annotation class after tissue detection**
- In the no-annotations path, the warning dialog gated "Use Annotations and Continue" on the pre-selected classes and re-read by those classes, so running tissue detection (which classifies as "Tissue") left the user stuck whenever "Tissue" was not pre-selected -- the detected annotations were filtered out with no way to pick the class that was produced. The dialog now counts any annotation (so detection output lets you proceed), and on confirm the annotation-class selection dialog is shown populated with the classes that now exist; the chosen class filters the hierarchy and is stored so the post-routing re-read does not re-prompt.

**Live Viewer Camera tab: stale hardware -> wrong PPM/BF presets**
- The Camera tab resolved its objective + detector once at Live Viewer open, which can run before the socket's live pixel size is available and fall back to a stale objective -- building the PPM/brightfield presets for the wrong objective+detector (incorrect/single exposures, missing per-channel "Uncrossed (Simple WB)" button). It now re-detects automatically when the Camera tab is selected (change-aware: only rebuilds when the objective/detector actually moved), so presets reflect the real hardware without a manual "Refresh from MM" click.

### Documentation

- Clarified that PPM sunburst calibration (hue -> angle) is **objective-independent**: a calibration captured at one objective (e.g. 40x) is valid at any other (e.g. 20x), higher magnification only improves quality. A magnification in the calibration filename/metadata records where it was captured, not a mismatch. (Active Calibration File preference tooltip + `PPM_MODALITY.md`, which also warns against re-adding an objective gate to the stamping.)

## [0.7.0] - 2026-06-25

### Added

**Stage Map**
- The SIFT search-range box now follows the live stage crosshair during a refinement step instead of staying at a fixed center, so the square tracks the objective position as you nudge the stage; it is removed when the alignment step ends. (`StageMapCanvas` follow mode + `StageMapWindow.setSearchRangeFollow`.)
- Click-to-move into the **dish body** is now allowed for petri-dish inserts (any insert with `dish_diameter_mm` set): inside the imaging well moves freely, moving out into the dish body is allowed with a once-per-session caution (needed to calibrate the well edge), and moves past the dish outline are blocked. Plain slides keep the strict aperture gate.

**Acquisition**
- Pre-acquisition warning when the flat-field background was collected at a different lamp/illumination level than the acquisition profile about to be used (the silent cause of per-tile intensity/vignetting seams in brightfield). Compares the profile `illumination_intensity` against the level recorded in `background_settings.yml` and shows a cancelable dialog before the stage locks; scoped to adjustable-lamp collections (`lamp.available: true`). (`BackgroundIlluminationCheck`.)

**Stage direction calibration ("Calibrate Directions" tool)**
- Interactive dialog for calibrating camera orientation (optical flip/rotation) by jogging the stage and observing the Live Viewer image. Available in two places: as an optional Setup Wizard step (Step 5: Stage Calibration) and as a "Calibrate Directions..." button in the Live Viewer's Navigate tab. The dialog physically moves the stage by a small step in +X then +Y, asks which direction the image appeared to pan, and solves for the `Camera orientation` that matches under the user's current `Inverted X/Y stage` polarity setting — polarity is treated as a hardware-wiring fact (verified via direct stage observation or the MicroManager-script procedure in PREFERENCES.md) and is not changed by the tool. Non-blocking: saves your start position and restores it when closing. Manual override panel allows editing both polarity and orientation together for advanced users. Implementation: `StageDirectionCalibrationDialog` with 8-orientation back-solve over a fixed polarity, `StageCalibrationStep` in the Setup Wizard, and `Calibrate Directions...` button in `StageControlPanel`.

**MicroManager MDA export**
- Every QPSC acquisition now auto-writes a MicroManager-loadable file set into each region folder: `MDA_<region>.txt` (a JSON-serialized `SequenceSettings`), `MDA_<region>.pos` (a `PositionList`), and `MDA_NOTES.txt` (provenance + caveats). The MDA files can be loaded directly into MM 2.0 -- MDA window for the settings, Stage Position List window for the positions -- to re-acquire the same channels/Z/positions in MicroManager (useful for laser-scanning modalities that QPSC doesn't drive directly).
- "Save as MicroManager MDA..." button on the existing-image and bounding-box acquisition dialogs for export-only runs (no QPSC acquisition kicked off).
- PPM exports are positions-only (no MM ConfigGroup equivalent for polarization angles); `MDA_NOTES.txt` documents the mismatch. Autofocus is marked off in the MDA file because QPSC's per-tile streaming AF doesn't map to MM's per-position AutofocusManager. New `mm_stage_devices:` YAML block lets you pin the MM device labels for XY/Z stages (falls back to `"XYStage"` / `"ZStage"` with a warn-log when omitted).

**Multi-dimensional progress panel**
- A new panel beneath the acquisition progress bar shows the active plan summary (tiles | channels | Z | T | total) and live per-axis counters (Channel: FITC, Z 3/9, Tile 47/84) inferred client-side from the running tile index via `LiveDimensionDecomposer`. A hidden time-lapse progress bar lights up when timepoints > 1. Drift sentinel collapses the per-axis labels to an italic notice if the inferred state ever drifts from server reality; the aggregate bar continues unaffected.

**Stitch MicroManager Folder**
- Standalone workflow for stitching MicroManager 2.0 OME-TIFF tile folders without a QuPath project. Reads tile positions from MMStack `*_metadata.txt` sidecars, auto-detects pixel size from metadata or TIFF resolution tags, and outputs a single stitched OME-TIFF or OME-ZARR. Preserves channel names, exposure, and MicroManager acquisition metadata in a `.mm-metadata.json` sidecar. Access via Extensions > QP Scope > Utilities > Stitch MicroManager Folder...

**Live Viewer**
- **Snap button**: Capture and save the current live frame as an OME-TIFF with embedded metadata (stage position, pixel size, modality, objective, detector). Right-click context menu for options: save raw bit depth (default ON), apply background correction (default OFF, greyed out unless settings match), open file after save (default OFF), and reset save folder to project.
- **Window title** now shows the current modality and expected objective magnification, e.g. `Live Viewer (Brightfield) (10x)`. Updates live when the Camera-tab modality dropdown changes or the objective is re-detected; degrades to `Live Viewer` when either value is unknown.
- **Cancellable autofocus**: while a streaming autofocus scan is running, the **Autofocus** button becomes a **Cancel Autofocus** button. Clicking it aborts the scan -- the server stops between frames, restores the camera ROI and stage speed, and returns Z to the position autofocus started from. New `ABORTAF` socket command (sent on the auxiliary socket since the primary is blocked inside the STRMAFZ scan); the scan replies `ABORTED` and the button resets without error styling. Best-effort against older servers: a 3 s ACK timeout degrades the cancel to a silent no-op rather than freezing frame polling.

**Socket protocol**
- `CORRECTFRAME` command: Request a flat-field-corrected frame from the server using the configured background settings for the current modality, objective, detector, WB mode, and rotation angle. Same wire format as `GETFRAME` on success; returns `FAILED:<reason>` on configuration mismatches.

**Channel Picker (Widefield IF, BF+IF)**
- **Named presets**: Save, load, and delete named snapshots of channel configurations (selected channels, exposure, intensity, focus channel). Presets persist across QuPath sessions and are listed in a dropdown for quick recall.
- **Test Current Channel**: Apply a single selected channel's hardware state to the microscope and open Live Viewer for verification without starting a full acquisition. Useful for dialing in exposure and intensity settings before multi-tile acquisition.

**Bounded Acquisition**
- **Time-lapse acquisition**: Collapsible **TIME-LAPSE OPTIONS** pane in the acquisition dialog. Enable to repeat the full region acquisition over multiple timepoints at a fixed interval. Includes spinner controls for number of timepoints and interval (seconds between timepoint starts). If a timepoint exceeds the interval, a one-time "falling behind" warning is shown (modal dialog + push notification if configured) and acquisition continues. The feature is optional and backward-compatible; omitting time-lapse settings yields single-timepoint acquisitions identical to pre-time-lapse builds. New socket protocol commands `REQTWARN` (best-effort, auto-disables against older servers) and ACQUIRE flags `--timepoints` / `--interval`.

**Acquisition Workflow Progress dialog**
- The dimension panel's per-annotation positions fill bar was removed: it duplicated the larger `currentProgressBar` directly under the **Current Annotation Progress** header (both tracked positions through the current annotation, just counting completed vs in-progress -- 6/9 vs 7/9). The `Tile n/N` text label stays for granular position visibility; `timepointProgressBar` stays for the time-lapse axis.
- The time-remaining line is now **bold and slightly larger**, with an **estimated completion clock time** ("Est. completion: Wed 16:05") on the line directly below it.

**Saturation handling**
- When the birefringence saturation guard trips on the initial monitoring tiles, the acquisition now **pauses and prompts** instead of hard-aborting. A modal with a red **Continue anyway** button (acquire despite saturation -- useful for faint signal that needs a higher exposure) and a **Cancel acquisition** button lets the operator decide, and a push notification fires when the prompt appears. Choosing Continue suppresses the saturation guard for the rest of that run; Cancel aborts as before. New `REQSAT` / `ACKSAT` socket commands, best-effort against older servers -- an old `microscope_command_server` hard-aborts on saturation as it did previously. Requires the matching server build for the interactive prompt.
- The acquisition-failure dialog no longer truncates the saturation reason mid-word (the server's `FAILED:` status cap was raised from 250 to 500 bytes).

**Source microscope: active scope is now a valid source**
- The active microscope is a first-class `source_microscope` value: it appears at the top of the **Stage Map** source dropdown and is the default when the open entry has no source tag. `source == target` resolves to identity (no preset, no flip). Genuine cross-scope work (e.g. `Ocus40 -> OWS3`) keeps its existing pair-preset path unchanged.
- **Source-mismatch warning** at the Existing Image workflow start: when the open entry's `source_microscope` disagrees with the active microscope, a dialog offers **Fix source to `<active>`** (update the tag and treat as native), **Proceed (cross-scope)** (keep the source and use its saved alignment), or **Cancel**. The body calls out when the tag is internally inconsistent (`acquired_on_microscope == active` but `source != active`) and should be corrected.
- See `documentation/developer/COORDINATE_TRANSFORMS.md` § "Active microscope is a valid `source_microscope`".

**Make Project Portable**
- Now deletes the raw individual tile images (the per-mode acquisition folders alongside `SlideImages`) by default after swapping ZARR-backed entries to TIFF, since those tiles are only needed to re-stitch. A **Keep individual tile images** checkbox preserves them. The dialog shows the tile count and size, warns that the deletions are permanent, and asks for confirmation before doing any work. Acquisition metadata files inside the tile folders are preserved -- only raw tile images are removed.
- **ZARR handling selector**: the dialog now offers three independent ZARR actions instead of always converting. *Convert ZARR to OME-TIFF* (default) converts ZARR files that have no `.ome.tif` yet on the spot (via `StitchingHelper.convertSingleZarrToTiff`) instead of leaving them stuck as `MISSING` -- this fixes the case where re-stitch recovery in OME_ZARR mode produced ZARR images that Make Portable could not act on, blocking the whole operation. *Zip ZARR to .ome.zarr.zip archive* zips each `.ome.zarr` directory to a sibling archive and deletes the directory (the archives must be extracted back to `.ome.zarr` before the project is reopened -- QuPath cannot read a zipped ZARR; the dialog and confirmation warn about this). *Leave ZARR untouched* skips ZARR entirely, so the tile-deletion checkbox can be used on its own to only clean up raw tiles. The warning text and the **Make Portable** button enablement now follow the selected options rather than being all-or-nothing on whether every TIFF already exists.

### Changed

**Z-Stack / Time-Lapse dialog (legacy removal)**
- Removed the legacy `StackTimeLapseWorkflow` dialog and the "Use new Z-Stack / Time-Lapse dialog" preference (`qpsc.experimental.singlePointDialog`) that gated the fallback to it. The Utilities > Z-Stack / Time-Lapse menu now always routes to the unified Single-Point Acquisition dialog (the default since 0.6.0). The unused `qpscStackTimeLapseProfile` / `qpscStackTimeLapseChannel` preferences were dropped as well.

**Stitching concurrency: parallel angles and channels (both OME-TIFF and OME-ZARR)**
- Angles (PPM) and channels (fluorescence) within a single annotation now stitch in parallel, bounded by the **Stitching concurrency** preference (default: 4 concurrent writers). Previously, OME-TIFF required sequential stitching due to BioFormats' `OMEPyramidWriter` concurrency bug; this constraint no longer applies with the new `DirectTiffOutputWriter`. Both OME-TIFF and OME-ZARR now use the same bounded-pool approach: each angle/channel is an independent writer to its own output file, and higher concurrency values accelerate multi-angle PPM and multi-channel fluorescence acquisitions. Set to 1 in **Preferences** for fully sequential stitching if needed for memory or isolation reasons. Does **not** introduce cross-annotation parallelism: different annotations continue stitching one at a time (their acquisition is inherently sequential as the stage moves between regions), so stitching of one annotation overlaps acquisition of the next without unbounded work accumulation.

**Background Collection: three-mode exposure selector (BF + PPM monochrome)**
- The Background Collection dialog's Acquisition Profile, Starting Exposure, and Target Intensity fields used to be three independent levers that all silently combined into the saved background -- selecting a profile **and** typing a target intensity would have the target override the profile's exposure with no indication, and lab members couldn't tell which lever was driving the saved BG. The dialog now shows a "Exposure mode:" radio group above those fields (for brightfield and PPM on monochrome cameras only) that spells out three explicit modes: **Use profile exposure** (no adaptation; profile drives lamp + exposure), **Target intensity (adaptive)** (no profile binding; the LV Camera tab is assumed to have set hardware; server iterates exposure to hit target), and **Override profile with target** (profile bound for record + lamp, target overwrites profile exposure -- confirmation required at Start, saved YAML records `profile.exposure_overridden: true`). Defaults are PROFILE for brightfield (the user's expected behavior) and TARGET for PPM (preserves the existing adaptive behavior). Choices persist per modality family via new `qpscBgExposureMode.brightfield` / `qpscBgExposureMode.ppm` preferences; documented in `documentation/PREFERENCES.md`. The selector is hidden for fluorescence (per-channel exposures driven by the profile's channel table), for RGB cameras (WB mode drives exposure), and for PPM with WB Simple / Per-angle (per-angle exposures live in the WB calibration). Brightfield users will no longer find their backgrounds running at whatever exposure / lamp / condenser the previous workflow happened to leave behind -- the profile's nominal values now drive the BG snap by default.

**Sweep Autofocus (renamed from "Sweep Drift Check")**
- The acquisition-time focus correction is now called **Sweep Autofocus** everywhere -- TitledPane, **Test Sweep Autofocus** button, status messages, tooltips, log lines, all developer + user docs. Lab members switching between the Live Viewer's autofocus controls and the Autofocus Editor were confused by two different names for the same algorithm.
- The Live Viewer's **Sweep** button now invokes the server-side `TESTADAF` path (same as the Editor's Test button) instead of running its own Java-side step-and-snap loop. This removes the hardcoded `NUM_STEPS=30` that silently overrode YAML `sweep_n_steps`, eliminates the pause/resume-per-step frame-timing race, and unifies the focus metric implementation with the rest of the system. Cancellation during Sweep is no longer supported (TESTADAF has no abort channel); the button reads "Sweeping..." and disables for the ~5-10 s run. The deleted `SweepFocusController` source is preserved in `claude-reports/design/2026-05-24_sweep-focus-controller-removal.md` with notes on which of its safety nets could be ported server-side if equivalent failure modes appear on TESTADAF.

**Autofocus range dropdown: "Auto" -> "Config"**
- The Live Viewer toolbar's focus-range dropdown's "Auto" option is now labeled **Config** to make it explicit that it pulls `sweep_range_um` from the autofocus YAML. The explicit um values continue to override the YAML for **Streaming AF only**; **Sweep Autofocus** always reads from the YAML regardless of the dropdown. Stale "Auto" picks from older builds migrate to "Config" on next open.

**Autofocus Editor labels**
- Snake_case parameter names in the dialog are replaced with human-readable labels (`Z samples`, `Search range (um)`, `Texture threshold`, `Score metric`, etc.); the YAML key is shown in the existing gray description line as `(YAML: foo_bar)` so YAML editors and log readers still find it. The Standard Autofocus section now reads "Search range first, then Z samples" to match the Sweep Autofocus section's order ("how big a window, how finely do I sample it").

**Z-Stack / Time-Lapse dialog: modality + profile + channel picker**
- The dialog now has a shared **Setup** pane above the tabs with Modality / Profile / Channel dropdowns. On **Start**, the dialog calls `applyProfile(profile)` (full mode switch: PMT safety, ConfigGroup presets, detector switch, illumination, intensity, mode positions) and -- for channel-based modalities -- `applyChannel(profile, channelId)` before the existing Z-stack / time-lapse run. Replaces the previous hardcoded `"brightfield"` modality and `null` objective/detector wire arguments. **This fixes the brightfield dynamic-range issue**: with a real BF profile applied, the YAML's exposure / lamp intensity / condenser aperture settings reach the hardware before any tiles are captured. Channel row is hidden for modalities with no channels (BF, PPM). Selections persist across dialog re-opens. Empty `acquisition_profiles` config disables the controls with an inline notice rather than throwing.

### Fixed

**Petri-dish insert: acquired image rendered upside down**
- A `dish_holder` insert derived its Y-axis inversion from `slide_top/bottom_y_um` (which dishes do not set, so they defaulted to `0`/`25000` -> `yInverted=false`) instead of the `aperture_top/bottom_y_um` points the dish actually supplies. On stage-inverted scopes this rendered the dish's acquired-image overlay (and well origin) vertically flipped; the well is a centered circle so the flip was invisible on it. `yInverted` now prefers the aperture Y points, parallel to `xInverted` and the origin computation.

**Existing Image Acquisition: alignment age display and recommendation logic**
- Fixed a bug where scope-namespaced alignment files (e.g., `<sample>_OWS3_alignment.json`) were not detected, causing the "Last refined" date to show as "Unknown" and preventing the age-based confidence calculation from working. The system now checks for scope-namespaced files first, then falls back to legacy unscoped files, matching the behavior of the alignment-loading code.
- Aged alignments (older than ~50 days, low confidence) now default to Single-Tile Refinement instead of Full Manual Alignment. Since an existing alignment is available (the refinement section only appears when "Use existing alignment" is active), a quick single-tile verification is more practical than a full re-alignment from scratch. Full Manual remains always available as an explicit choice.
- Alignment timestamp parsing now handles both `Date.toString()` format (new save sites) and legacy ISO-like format (external or old files), so the "Last refined" banner and age-based recommendations work for either.

**Live Viewer Z-bar focus trace and navigation grid layout**
- Focus-metric trace is now drawn inline in the left margin of both coarse and fine bars, anchoring at the track's left edge and extending leftward proportionally to the Brenner gradient metric. This consolidates the trace into the bar canvases rather than rendering it in a separate column, making the panel more compact.
- Navigation grid (joystick and arrow buttons) now centers correctly in the Navigate tab when the Z Focus widget is widened. The VBox containing the grid now has `fillWidth(false)` so its content respects the tab's `Pos.CENTER` alignment.

**Acquisition Workflow Progress dialog**
- The Total / Current Annotation / positions progress bars were created with a width but no height, so inside the auto-sized dialog they collapsed to zero and no bar ever rendered. All bars now have an explicit height and display correctly.
- The dialog is now resizable and grows (never shrinks) to fit its content, so the **Cancel Workflow** button is no longer clipped off the bottom as the dimension panel, completion clock, and stitching list appear during a run.

**Stage Map (scopes without a `stage.inserts` block)**
- When the microscope YAML has only `stage.limits` and no `stage.inserts` calibration block, the Stage Map synthesizes a single-slide holder from the limits. That synthesized holder assumed non-inverted stage axes, so on a scope with an inverted X (or Y) stage -- e.g. OWS3 -- the whole map was mirrored: double-clicking the middle of the slide drove the stage to the X-flipped position and the crosshair tracked the stage backwards. The synthesized holder now takes axis inversion from the stage-polarity preference, so click-to-move and crosshair tracking match the real stage.

**Stage limits bounds check**
- The stage-bounds check (`isWithinStageBounds`, used before every Stage Map / stage move) did a literal `pos >= low && pos <= high`, so a `stage.limits` block that listed `low` greater than `high` -- natural on an inverted axis -- rejected *every* position with "Target position is outside stage limits". The check now normalizes `low`/`high` with min/max, matching the Stage Map's synthesized insert, which already tolerated either ordering. `stage.limits` must be MicroManager coordinates (see the comment in `config_template.yml`).

**`source_microscope` was default-stamped with the wrong scanner**
- `StageMapWindow.onOpenedImageChanged` (and `pickInitialSource`) used to auto-default the persistent scanner pref onto any opened entry that lacked `source_microscope`. On scopes whose only saved preset is a cross-scope alignment (e.g. OWS3 with only `OWS3_Ocus40_Transform`), the default-fill wrongly tagged native slides with the external scanner. The flip path then used that tag to build a spurious `(flipped X)` sibling and `validateAndFlipIfNeeded` applied an `Ocus40 -> OWS3` optical flip to an image that was already in OWS3's frame. The default is now the **active microscope**; the persistent pref only seeds the alignment workflow's initial scanner pick.
- `ImageFlipHelper.validateAndFlipIfNeeded` now short-circuits when `source_microscope == active microscope` OR `acquired_on_microscope == active microscope`. Belt-and-suspenders: even if some legacy entry still carries a stale external-scanner source tag, an image acquired on the active scope is in the active scope's frame and gets no flip.

**Stage Map Source dropdown: persistent selection + no auto-sync**
- The Preset dropdown now prioritizes the user's last explicit pick (persistent default) on Stage Map open, then falls back to active microscope, then available presets. This lets the dropdown persist a cross-scope choice across sessions without needing manual re-selection.
- Removed auto-sync on image switch: `onOpenedImageChanged()` no longer resets the dropdown when the viewer switches to a derived entry with a different ancestor scanner. Users stay on their chosen preset until they manually pick a different one. This fixes a usability regression where the dropdown would jump away when switching between a PPM sub-acquisition and an Ocus40-macro parent on the same slide.
- Fresh-import entries (no `source_microscope` metadata) are no longer auto-stamped with the active microscope by Stage Map. That stamp now happens only via explicit workflows (Microscope Alignment, Existing Image workflow's source-mismatch dialog) so Stage Map can't silently corrupt acquisition-origin metadata.

**Orphaned flipped sibling locked the workflow in manual alignment**
- `(flipped X|Y|XY)` entries created by a previous (now-corrected) wrong-source run carry no `STAGE_BOUNDS_*` metadata of their own. Running the Existing Image workflow on such an entry fell through to `ManualAlignmentPath`, which reaches for the scanner's macro pixel size (e.g. 81 um/px) and assembles a transform off by ~125x against a 10x stitch -- the first refinement tile then targets a stage position hundreds of thousands of microns outside the limits. `ExistingImageWorkflowV2` now refuses at workflow start when the open entry is a flipped sibling whose base's `source_microscope` matches the active microscope, with a dialog pointing at the base entry. Genuine cross-scope siblings (e.g. PPM acquiring on an Ocus40 macro) are unaffected -- the base's source there is the external scanner, not the active microscope, so the orphan condition does not match.

**Stage Map "Show Acquisitions" reported 0 even for projects full of acquired stitches**
- The acquisition-overlay loader (`StageMapWindow.loadAndPaintAcquisitions`) called only `AffineTransformManager.loadSlideAlignment`, which scans `alignmentFiles/` -- the macro-frame directory. Auto-registered per-slide alignments for bounded acquisitions live in `alignmentFiles/derived/` (sub-frame), and many annotation-based acquisitions carry no JSON at all but DO have `STAGE_BOUNDS_*` stamped on the entry. The loader now uses a three-tier fallback: macro-frame JSON, then sub-frame JSON via `loadDerivedAlignment`, then `ImageMetadataManager.buildBoundingBoxPixelToStageTransform` against the entry's own metadata. Clicking the checkbox now also logs an `INFO` line on toggle and a summary line of how many of the project's entries yielded a usable alignment, so a 0-count outcome is visible in the log instead of silently buried in the status label.

**Forward/back propagation falsely reported "no alignment" for stitched bases**
- `ForwardPropagationWorkflow.buildGroups` decided propagation eligibility by calling only `loadSlideAlignment` (macro-frame). A base that is itself a bounded-acquired stitch lives in `alignmentFiles/derived/` (sub-frame, auto-registered) or carries `STAGE_BOUNDS_*` directly on the entry, so the eligibility check returned `false` even though Stage Map navigation worked fine on the same base. Now uses the same three-tier fallback as Show Acquisitions: macro JSON, derived JSON, then any variant entry's `STAGE_BOUNDS_*` metadata.
- The actual back-prop math also only loaded macro-frame JSONs (`PropagationManagerDialog`'s alignment lookup and `ForwardPropagationWorkflow.propagateBackFanOut`'s flip-frame recovery), so even when eligibility said "yes" the run aborted with *"no alignment file found for active scope"* for no-macro chains. Both sites now fall back to a new `AffineTransformManager.loadDerivedAlignmentWithFrame` that reads `alignmentFiles/derived/` for both the transform and the recorded `flipMacroX/Y`. Required for PPM/OWS3 imaging that starts on unstained tissue without an Ocus40 macro, where the very first acquisition is the navigation base.

**Single-tile refinement (missing tiles on flip-needing scopes)**
- Single-tile refinement could open the tile-select dialog with no tiles to pick when the workflow was started from the unflipped base image on a flip-needing scope (PPM). Root cause: the viewer's open entry could drift back to the base between routing and refinement (the slow white-background data-bounds classifier saturates the JavaFX thread, reordering the queued entry switch). Tiles were then created against the base entry's hierarchy and were invisible once the viewer settled on the flipped sibling. `performSingleTileRefinement` now re-asserts the flipped sibling as the open entry and re-reads annotations from it before creating tiles, so the annotations, tiles, and viewer all agree on one entry. Starting from the flipped sibling directly was already unaffected.

**Alignment transform loading (flip bake elimination)**
- Per-slide alignment transforms now load in their saved pixel frame without additional flip baking. This eliminates a PPM refinement bug where the stage would jump to the X/Y-mirror position of the selected tile instead of the intended tile. Root cause: the saved transform was already in the correct frame (the flipped sibling for flip-needing scopes, the unflipped base otherwise), but earlier code applied an additional flip-delta bake both at load and post-flip-switch, double-flipping the transform. `AlignmentHelper.checkForSlideAlignment` now loads transforms as-is, and `ImageFlipHelper.validateAndFlipIfNeeded` ensures the workflow operates on the entry the saved transform was built in.
- See `documentation/developer/COORDINATE_TRANSFORMS.md` § "Step 2" for the architectural shift.

**Re-stitch Tiles**
- Output folder now anchored to `<projectDir>/SlideImages` (matching the regular acquisition path) instead of landing under the imaging-mode folder or tile-selection folder.
- Output filenames now correctly follow the user-configured naming pattern (respecting FilenameIncludeModality, Objective, Annotation, Angle preferences) instead of duplicating angle names and misplacing indices.
- Compression and output-format selectors are now type-safe, and the compression choices are filtered by the selected format: OME-TIFF offers the full compression set, while ZARR-backed formats (OME_ZARR, OME_TIFF_VIA_ZARR) are restricted to LZW, ZLIB, Uncompressed, and Default. Codecs with no real ZARR algorithm can no longer be selected, so invalid combinations that would throw at the writer are now impossible.
- **OME_TIFF_VIA_ZARR format** now available: stitch to ZARR, then automatically queue a background conversion to OME-TIFF.


## [0.5.0] - 2026-05-07

### Added

**SIFT auto-alignment**
- Auto-Align (SIFT) button in the 3-point Microscope Alignment workflow per-tile confirm step (was previously only available in single-tile refinement). Reuses the same dialog and Settings, minus the "save / skip / new alignment" buttons.
- Configurable bit-depth normalization (`PERCENTILE` / `MIN_MAX` / `BIT_SHIFT`) and CLAHE preprocessing for cross-modality matching (16-bit monochrome camera vs 8-bit H&E WSI). Defaults `PERCENTILE` 2/98 + CLAHE on with clipLimit 2.0 fix the OWS3 + H&E "fails near correct tile" failure mode.
- Wire-protocol flags `--mono-norm`, `--pct-low`, `--pct-high`, `--clahe`, `--clahe-clip` on SIFTAL.

**Live Viewer**
- "Always Auto-Scale" checkbox replaces the forced per-tile rescale; drag the Min/Max sliders to opt out of auto-scale.
- Camera Control tab restores last-used Exposure + Intensity on open.
- Focus buttons recover when `restartStreaming` fails (no more deadlocked spinner).

**Autofocus Editor**
- Test Streaming Autofocus button with live curve plots; chart axes auto-fit.
- Surfaced `valid_modalities` and `min_magnification` constraints from the focus manifest.

**Propagation Manager**
- Ground-truth source ROI auto-stamped from parent tile detections; "Stamp source ROI from tiles" retrofit button for older sub-acquisitions.
- Cross-scope back-prop swaps in the source-scope alignment so the xy_offset is interpreted in its own scope's stage frame.
- "Remove existing objects of copied classes" checkbox: when set, deletes existing objects on each target whose class is in the selected set (and unclassified objects when "Unclassified" is also selected) before adding the propagated copies. Use case: refining annotations on the source image and re-propagating without overlapping the old shapes. FORWARD removes per-sub right before each `propagateForward`; BACK removes once per group across all base siblings before any sub fans out (otherwise multiple subs back-prop'ing into the same base would each delete and re-add, dropping earlier subs' contributions).
- Comprehensive diagnostics for back-prop; multi-group UI; "Save & reload viewer" handling.
- Visible warning when a source-scope config is missing instead of silently producing a wrong xy_offset.

**Acquisition Wizard**
- Calibration status pills (white balance, backgrounds, alignment) auto-refresh after sub-workflows complete via explicit completion notifications -- no more closing+reopening to clear yellow warnings after running WB / Background Collection / Microscope Alignment / Save Transform. Replaces the earlier 3-second polling Timeline (which worked but was a constant 6-file disk read for the wizard's entire lifetime). `AcquisitionWizardDialog.notifyCalibrationChanged()` is the public hook; `WhiteBalanceWorkflow`, `BackgroundCollectionWorkflow`, and `MicroscopeAlignmentWorkflow` call it on success. Refresh All button stays as the user-driven backup path.
- Unified `ObjectiveSelector` component that auto-detects from MicroManager pixel size (priority: MM pixel size match -> last-used pref -> first config entry).

**White Balance**
- R/B analog gain ceiling spinner exposed in the WB dialog and plumbed to the server.
- Objective/detector combos seeded from live MicroManager pixel size when opening WB / Background dialogs.

### Fixed

**Flip handling (Step B refactor + 2026-05-07 follow-ups)**
- Step B (2026-05-04): flip source-of-truth moved from per-entry `FLIP_X`/`FLIP_Y` metadata to `TransformPreset.flipMacroX/Y` (per `(source_scanner, target_microscope)` preset) and the per-slide alignment JSON. `AlignmentHelper.checkForSlideAlignment` bakes the alignment-frame flip into the loaded transform so all downstream callers consume unflipped-base pixel coords.
- 2026-05-04: per-slide JSONs auto-registered at BoundingBox stitch import + saved by ExistingImageWorkflow refinement now record `flipMacroX/Y` truthfully (the legacy 5-arg overload wrote `null,null` and was source of an XY-mirror bug on PPM existing-image acquisitions).
- 2026-05-07 (this release): `(flipped X|Y|XY)` companion entries are still created on demand by `ImageFlipHelper.validateAndFlipIfNeeded` for the visual-UX of operator alignment (the unflipped base and the flipped live camera view disagree by a mirror on PPM and cannot be aligned by eye). The 2026-05-04 stub-out of this method was reverted and replaced with a real preset-driven implementation.
- 2026-05-07: `ManualAlignmentPath` now records `flipMacroX/Y` on the per-slide JSON from the active preset (was via `FlipResolver(null,null,null)` which post-Step-B always returned `(false, false)`, mis-stamping the JSON as "no flip" when the alignment was actually done in the flipped sibling's frame).
- 2026-05-07: `StageControlPanel.handleGoToCentroid` (Move to Centroid button) now loads via `loadSlideAlignmentWithFrame` and bakes the flip on the unflipped base entry, mirroring `AlignmentHelper.checkForSlideAlignment`. The legacy raw-load + manual-compensation block was producing XY-mirror motion on PPM.
- 2026-05-07: tile detections in single-tile refinement now overlay the (correctly flipped) annotations on the flipped sibling instead of landing at the XY-mirror -- caused by `state.annotations` being captured against the unflipped base at config-dialog return and never re-fetched after the flip switch. `processSlideSpecificAlignment` now clears `state.annotations` after `validateAndFlipIfNeeded` so `ensureAnnotationsExist` re-reads from the now-current hierarchy.
- 2026-05-09: `ImageFlipHelper.switchOpenEntry` always defers the entry switch via `Platform.runLater` instead of running synchronously when already on the FX thread. The synchronous path threw `IllegalStateException: showAndWait is not allowed during animation or layout processing` followed by an NPE because the open call chain hit `QuPathGUI.checkSaveChanges` -> `Dialogs.showAndWait`, and the caller arrived inside a JavaFX animation pulse (synchronous continuation of `ProjectHelper.setupProject`'s post-creation Timeline). Hit any new user on their first existing-image acquisition with a flip-required preset (e.g. PPM).
- 2026-05-07: forward-prop now resolves the alignment-frame flip from the sub's parent entry's `FLIP_X`/`FLIP_Y` metadata (priority: sub-parent metadata > per-slide JSON > active preset). The previous "load flip from per-slide JSON" path produced 0 propagated objects on projects whose JSON was written by the pre-`45ca489` `ManualAlignmentPath` -- those JSONs claimed `hasFlipFrame=true` while actually storing `(false, false)`, so no pre-flip ran and source pixels mapped through `baseToStage` in the wrong frame. Sub-parent metadata is set at import and was unaffected by the JSON-save bug, so it's a more reliable signal. When the JSON disagrees with the parent metadata a warning is logged recommending re-alignment.

**Alignment-JSON lookup (2026-05-11 restructure, three layers)**
- Layer 1: workflow alignment lookup now resolves the parent macro entry's name from `base_image` metadata before reading the per-slide JSON, mirroring the long-standing `ForwardPropagationWorkflow` pattern. Previously, if the open entry was a stitched sub-image, the workflow loaded that sub-image's auto-registered alignment (scale = camera pixel size) and applied it to macro-frame annotation coords, silently shrinking every stage move by `camera_px / macro_px`. On the 2026-05-10 MH_Colon session this produced a 69% shrink: one tissue happened to fit inside its shrunk box, the other was clipped to half. Applied in `AlignmentHelper.checkForSlideAlignment`, `ExistingImageWorkflowV2.tryComposeCrossScopeAlignment`, and the refinement save in `ExistingImageWorkflowV2.saveRefinedAlignment`.
- Layer 2: alignment JSONs now carry a `pixelFrame` field (`"macro"` | `"sub"`). Sub-image auto-registrations in `StitchingHelper` stamp `"sub"`; every other save defaults to `"macro"`. `AlignmentHelper.checkForSlideAlignment` validates that loaded transforms are `"macro"` and refuses anything else with a hard-cancel dialog (same FX-safe pattern as the camera-ROI and pixel-size gates). Legacy JSONs without the field default to `"macro"` on load -- which is correct, since every shipped project's saved alignments predate the `"sub"` writer.
- Layer 3: sub-frame alignments now write into `alignmentFiles/derived/` instead of the flat `alignmentFiles/` directory, so macro lookups can no longer reach them by prefix-match. `AffineTransformManager.loadDerivedAlignment` reads `derived/` first with a flat-directory fallback for backward compatibility. The Live Viewer's sub-image Go-To-Centroid path opts in via this method.
- See `documentation/developer/WORKFLOW_DATA_FLOW.md` for the updated read/compare/write/gate map and a new `pixelFrame` row in the Sources of Truth table.

**Stage Map**
- Macro overlay flip now resolves from the active `(source, target)` preset, not per-entry metadata.
- Recovers when polling fails instead of hiding the overlay; logs the real failure cause.
- Recovers from stale `acquisitionActive` after cancellation.

**Connection stability**
- User-triggered Reconnect clears the server-unresponsive latch.
- Unified server-unresponsive recovery path across acquisition + heartbeat.
- Cancel runs off the JavaFX thread; CANC read timeout bumped so cancellation doesn't time out under server load.

**Class-filtered alignment tiling**
- `MicroscopeAlignmentWorkflow` (standalone) now prefers `PersistentPreferences.getSelectedAnnotationClasses()` for tiling, with Tissue / valid-class fallback chain. Removed the silent "all annotations regardless of class" fallback that produced noisy / overlapping tile grids.
- Existing Image Workflow's single-tile refinement prompt now reports the count and class names of annotations being tiled.

**Cropped camera ROI is now a hard cancel across all acquisition workflows**
- New `QPScopeChecks.validateCameraRoi(detector)` gate compares the live camera frame dimensions (queried from MicroManager via the existing `GETFRAME` socket call) against the configured sensor dimensions (`width_px` / `height_px` from `resources_LOCI.yml`). If they disagree by more than 5% on either axis, the workflow aborts with a dialog explaining how to fix the ROI in MicroManager.
- Motivating incident: a prior streaming-AF call cropped the JAI camera ROI to the centered 50% (1024x772 from a 2064x1544 sensor) and did not restore on its exit path. The cropped state persisted across QuPath/server restarts (MM remembers the last ROI), and every subsequent acquisition captured tiles at half the planned FoV -- producing stitched mosaics with ~50% empty space between every tile and breaking alignment (single-tile refinement landed at the wrong stage X/Y, with no flip configured to explain it).
- Wired through the same seven workflow gates as the pixel-size check: Existing Image, Bounded, Microscope Alignment, Rapid Scan, WB Comparison, White Balance, Background Collection.
- Root cause -- the streaming-AF restore path that misses some exit branches -- belongs in the Python `microscope_command_server` and is not addressed here. This is a defense-in-depth gate so the symptom is caught before any acquisition or alignment writes corrupted data. The server-side root cause was subsequently fixed on 2026-05-11 (microscope_command_server commit `7f40a47`): the streaming-AF code path now anchors absolutely on the full sensor (clear_roi -> crop -> clear_roi on every exit) so a leaked ROI is unreachable. This Java-side gate stays as belt-and-suspenders against future regressions or unrelated ROI manipulation paths.

**Objective pixel-size mismatch is a hard cancel across all acquisition workflows**
- Threshold lowered from 25% to 5%. Adjacent magnifications differ by 2x, so 5% is wide enough to absorb calibration drift but narrow enough to catch any user-induced mismatch (turret moved without updating the wizard, etc.).
- Removed the "Continue anyway?" confirm. On mismatch the workflow always cancels; user fixes MM or the wizard dropdown and restarts.
- Warning dialog enlarged (620-720 px wide, monospace body) and now lists the wizard objective + expected pixel size, MM-reported pixel size, the closest configured objective for MM's value (so the user immediately sees what MM probably has active), modality, detector, diff percent, plain-language consequences, and the fix.
- Gate now wired through Existing Image, Bounded Acquisition, Forward Propagation (offline-only -- gate is N/A there but documented), Microscope Alignment, Rapid Scan, WB Comparison, White Balance, and Background Collection. The previous code only checked Existing Image and Bounded; the others could silently produce gap-mosaics or write per-objective calibrations under the wrong magnification key.

**Other**
- Tile-handling preference (Delete / Zip / Keep) now actually runs after the Existing Image Workflow finishes stitching. The cleanup was previously only wired into `BoundedAcquisitionWorkflow`, so existing-image runs left their `tempTileDirectory` in place regardless of the preference. Both workflows now share `TileCleanupHelper.performCleanup`, and the Zip path only deletes originals if zipping succeeded. Background-correction tiles live in a separate config-specified folder and are unaffected.
- Tile cleanup ("Delete" and the post-zip step) now removes only `.tif` / `.tiff` files and prunes empty subdirectories. Previously it nuked the entire `tempTileDirectory` tree, taking `TileConfiguration.txt`, `TileConfiguration_QP.txt`, and `acquisition_command_*.txt` with it. Stitched OME-TIFFs in `SlideImages/` were unaffected, but per-region metadata needed for re-stitching or auditing was lost. Metadata files now survive in place; raw tiles still get removed.
- Live Viewer focus range dropdown options 6-40um (was truncated 1-20um).
- Sweep Focus rejects boundary peaks and requires 2 flanking samples for parabolic interpolation; suppresses early-stop on flat metric / peak-at-start.
- Test Standard/Adaptive Autofocus stops live view before running (no more "AF metric is whatever the camera last streamed" artifacts).
- Multichannel merged filenames follow the standard naming scheme.
- Camera tab Apply Profile rebuilds the panel and restores saved exposure/illumination prefs to hardware so UI matches.

### Documentation

- New `documentation/developer/WORKFLOW_DATA_FLOW.md` maps every acquisition workflow's read/compare/write/gate behavior. Required reading before changing any workflow's information flow.
- New `documentation/developer/COORDINATE_TRANSFORMS.md` is now authoritative for the flip pipeline.
- `documentation/developer/SOCKET_PROTOCOL.md` includes a full SIFTAL flag reference.
- `documentation/PREFERENCES.md` covers all SIFT bit-depth normalization knobs.
- `documentation/TROUBLESHOOTING.md` adds entries for SIFT failure modes (close-but-failing on PPM/H&E + stage-too-far-from-tile).
- `documentation/tools/microscope-alignment.md` and `documentation/tools/existing-image-acquisition.md` updated for SIFT-in-3-point alignment + class-filtered tiling.

## [0.3.0] - 2026-03-02

### Added

**Live Camera Viewer**
- Real-time camera feed window with streaming from MicroManager circular buffer
- 256-bin luminance histogram with per-channel saturation percentage (turns red when any channel exceeds 1%)
- Min/max contrast sliders with auto-scale, Fit mode, display scale selector
- RGB readouts for calibration diagnostics
- Double-click-to-center feature for stage navigation
- Integrated Stage Control panel (see below)

**Stage Control (integrated into Live Viewer)**
- Virtual joystick with quadratic response curve
- FOV-based step size dropdown for intuitive stage movement
- Two-tab layout: Movement tab and Saved Points tab
- Double-step arrows for coarse/fine movement
- Saved stage positions (named, persisted via JSON preferences)
- Sample movement mode with correct axis conventions
- Rate-limited joystick commands (prevents command flooding)

**White Balance System (JAI/Prism Cameras)**
- Full per-angle white balance calibration with iterative R/G/B exposure adjustment
- Four explicit WB modes: Off, Camera AWB, Simple, Per-angle
- Per-angle target intensity editing
- Advanced settings: max analog gain, gain threshold ratio, black level calibration
- Objective/detector selection with automatic calibration profile loading
- WB Comparison Test workflow for side-by-side WB mode comparison
- Gain values displayed in calibration results with Open Folder button

**Camera Control Dialog**
- Card-based layout with per-angle cards and color-coded exposure/gain rows
- Real-time gain validation with range tooltips
- Unified gain model (R/G/B shows analog gains, unified gain for all channels)
- Singleton dialog (brings to front if already open)

**Sunburst Calibration (PPM Reference Slide)**
- Hue-to-angle mapping workflow with radial spoke detection
- Advanced radial settings (inner/outer radius)
- Manual center selection UI for retry on detection failure
- Debug mask display in failure dialog
- All dialog settings persisted between sessions
- Redo callback and Go Back button in result dialogs

**JAI Noise Characterization**
- Quick/Full/Custom presets for noise measurement
- NoiseStatsPanel in Live Viewer (R/G/B Mean/StdDev/SNR grid)
- Persistent preferences, non-modal progress display

**CONFIG Command Handshake**
- Extension sends microscope configuration path to server on connection
- Server validates configuration before accepting commands
- User-friendly error handling for CONFIG failures

**Other New Features**
- Auxiliary socket for non-blocking Live Viewer and stage control operations during acquisition
- Objective pixel size mismatch warning before acquisition (queries MicroManager active pixel size)
- "No Manual Autofocus (Danger)" preference for unattended overnight acquisition
- CI workflow and Dependabot for automated build/dependency tracking
- Acquisition preview tile count now matches actual tiling logic

### Changed

**Menu Structure**
- Utilities menu reorganized by function (camera calibration, autofocus, PPM)
- "Starburst Calibration" renamed to "PPM Reference Slide..."
- "rectangles" renamed to "spokes" in all calibration dialogs
- Conditional "JAI Camera" submenu appears only when JAI camera detected
- Stage Control removed as separate menu item (integrated into Live Viewer)

**White Balance UI**
- Old boolean checkboxes ("White Balance" + "Per-Angle WB") replaced with single WB Mode ComboBox (Off / Camera AWB / Simple / Per-angle)
- WB mode applies on combo box selection directly (removed Apply button)
- Target Intensity moved from Shared Settings to Simple WB section
- Objective selection moved to Shared Settings (both Simple and PPM need it)

**Camera/Gain Model**
- Unified gain model replaces per-channel gain throughout
- Camera Control redesigned with card-based layout
- Gain mode radio buttons removed (gain is always unified; R/G/B shows analog gains)

**Dialog Behavior**
- Multiple dialogs made non-modal: calibration, birefringence progress, workflow progress, stage move
- Camera Control and Stage Control dialogs made singleton
- Exposure time fields removed from PPM angle selection dialog (exposures now auto-determined)
- Stage Map window doubled in size (840x760) with hollow crosshair circle and ScrollPane

**Configuration**
- Default calibration folder name changed to "ppm_reference_slide"
- Project logs moved to `<project>/logs/` subdirectory with config-based fallback
- Macro image crop bounds are now fully config-driven

### Fixed

**Stage Map / Macro Overlay**
- Fix macro overlay misplacement on flipped (inverted XY) image entries
- Fix macro overlay positioning, scale, aspect ratio, and flip handling (extensive series of fixes)
- Backward-compatible format marker for old vs new alignment files

**Autofocus**
- Fix focus Z position persistence across WB comparison modes
- Fix manual focus cancel deadlock (sends SKIPAF before CANCEL to unblock server)
- Fix manual focus skip preventing stitching in BoundedAcquisitionWorkflow

**Camera / White Balance**
- Fix camera_awb calibration lifecycle and gain handling
- Fix Camera Control showing stale WB values from cached config
- Fix per-angle WB not loading calibrated exposures
- Fix WB output path and singleton socket client usage
- Fix Continuous WB mode not applying in Camera Control

**Stitching**
- Fix birefringence stitching with directory isolation to prevent angle cross-matching
- Fix WB Comparison stitching (tile paths, Z position, monitoring, bounds nesting)

**Stage Movement**
- Fix stage movement to match MicroManager conventions
- Fix joystick direction in sample movement mode
- Fix joystick command flooding with rate limiting and latest-target-wins

**Annotations**
- CRITICAL: Fix annotation class filtering removing ALL annotations during alignment when no classes selected

**Live Viewer**
- Fix canvas expanding and frame poller race condition
- Fix camera mode changes failing when Live Viewer is streaming
- Fix Live Viewer image display sizing and display scale dropdown

**Other**
- Fix UI freeze when stage controls used during long operations
- Fix init-order NPE risk in ExistingImageAcquisitionController
- Fix birefringence optimization socket timeout during calibration
- Guard against setting BRIGHTFIELD_H_E on non-RGB images

### Removed
- "Apply & Go Snap Test" workflow and RAWSNAP command
- Standalone Stage Control menu item (integrated into Live Viewer)
- Legacy WB mode preferences: jaiWhiteBalanceMode, PPMPerAngleWBEnabled
- Gain mode radio buttons from Camera Control (gain always unified)

### Breaking Changes
- **CONFIG handshake required**: Extension now sends CONFIG command on connection; requires microscope-command-server v1.1.0+
- **WB mode parameter changed**: Old `--white-balance` and `--wb-per-angle` boolean flags replaced with `--wb-mode` enum (camera_awb|simple|per_angle|off)
- **Unified gain model**: GainsResult and CameraModeResult records changed; `setGains()` with count=3 now means [unified, analog_red, analog_blue]

## [0.2.0] - 2026-01-12

First release of a mostly working version, with code dedicated to working on one particular polychromatic polarization microscope. Expect significant changes as we migrate to more instruments.

## Guidelines for Release Notes

When creating a new release, add a section above with the version number and date:

```markdown
## [1.0.0] - 2026-01-XX

### Added
- Feature descriptions

### Changed
- Modification descriptions

### Fixed
- Bug fix descriptions

### Removed
- Removed feature descriptions
```

### Categories

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Now removed features
- **Fixed** - Bug fixes
- **Security** - Security improvements

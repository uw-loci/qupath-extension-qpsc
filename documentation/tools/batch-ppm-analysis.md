# Batch PPM Analysis
> Menu: Extensions > QP Scope > PPM > Batch PPM Analysis...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Batch PPM Analysis discovers all qualified PPM analysis sets across a QuPath
project and runs polarity and/or perpendicularity analysis on all annotations,
storing results as QuPath measurements and exporting a consolidated CSV file.

Use this tool to:
- Analyze fiber orientation across an entire project in one operation.
- Generate per-annotation measurements visible in QuPath's measurement table.
- Export results to CSV for statistical analysis in external tools (R, Python,
  Excel, etc.).
- Combine polarity and perpendicularity analyses in a single batch run.

## Prerequisites

- A **QuPath project** must be open.
- The project must contain **PPM modality images** organized into analysis sets
  (sum image + calibration, optionally birefringence sibling).
- **Annotations** must exist on the sum images.
- For perpendicularity analysis: annotations must have **assigned classes**, and
  a boundary class must be specified.
- **Python** must be available on the system PATH with `ppm_library` installed.

## Options

### Analysis Set Selection

The tool automatically discovers all qualified PPM analysis sets in the project.
A set qualifies when it has:
- A **sum image** (identified by "sum" in the angle metadata or "_sum" in the
  image name).
- A **PPM calibration** (from image metadata or the active calibration in
  preferences).

Each set is displayed with:
- Image collection number
- Image name
- Sample name and annotation name (if available)
- Annotation count
- Whether a birefringence sibling is available (+biref)

| Control | Description |
|---------|-------------|
| Check All / Check None | Select or deselect all analysis sets. |
| Individual checkboxes | Select specific sets to include in the batch. |

### Analysis Types

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Polarity Plot | Checkbox | Checked | Runs polarity analysis (histogram + circular statistics) on ALL annotations in each selected set. |
| Surface Perpendicularity | Checkbox | Unchecked | Runs perpendicularity analysis (simple + PS-TACS) on annotations of the specified boundary class only. |

At least one analysis type must be selected.

### Perpendicularity Parameters

These options are only active when "Surface Perpendicularity" is checked:

| Option | Type | Range | Default | Description |
|--------|------|-------|---------|-------------|
| Boundary class | Choice | Available classes | First class | The annotation class defining tissue boundaries. Only annotations of this class are analyzed for perpendicularity. |
| Dilation (um) | Spinner | 1-500 | 50 | Width of the analysis zone around the boundary in micrometers. |
| Zone mode | Choice | outside/inside/both | outside | Which side of the boundary to analyze. |
| TACS threshold (deg) | Spinner | 5-85 | 30 | Angular threshold for TACS-2/TACS-3 classification. |
| Fill holes in boundary | Checkbox | -- | Checked | Whether to fill holes in boundary annotations before analysis. |

## Workflow

1. Open a QuPath project containing PPM images.
2. Launch the tool from the menu.
3. Review the discovered analysis sets and check/uncheck as needed.
4. Select the analysis types to run (Polarity, Perpendicularity, or both).
5. If perpendicularity is selected, configure the boundary class and parameters.
6. Click "Run Batch Analysis".
7. A progress window shows the current set and annotation being processed,
   with a progress bar and a Cancel button.

**Processing order:**
- For each selected analysis set:
  - Pass 1 (if Polarity selected): Runs polarity analysis on every annotation.
  - Pass 2 (if Perpendicularity selected): Runs perpendicularity analysis on
    annotations matching the boundary class only.
  - Saves measurements back to the QuPath project.

8. When complete, a summary dialog shows the number of annotations processed,
   CSV rows written, and errors encountered.

## Output

### QuPath Measurements

Measurements are stored directly on annotations, visible in QuPath's measurement
table. All measurement keys are prefixed with "PPM: " to avoid collisions with
other measurement sources.

**Polarity measurements (on all annotations):**

| Measurement Key | Description |
|----------------|-------------|
| PPM: circular_mean | Circular mean fiber angle (deg). |
| PPM: circular_std | Circular standard deviation (deg). |
| PPM: resultant_length | Mean resultant length (0-1, alignment strength). |
| PPM: arithmetic_mean | Arithmetic mean angle (deg). |
| PPM: arithmetic_std | Arithmetic standard deviation (deg). |
| PPM: n_valid_pixels | Number of valid pixels analyzed. |
| PPM: dominant_bin_low | Lower bound of the dominant angular bin (deg). |
| PPM: dominant_bin_high | Upper bound of the dominant angular bin (deg). |

**Perpendicularity measurements (on boundary-class annotations):**

| Measurement Key | Description |
|----------------|-------------|
| PPM: mean_deviation_deg | Mean deviation from boundary normal (deg). |
| PPM: std_deviation_deg | Std deviation of deviation angles (deg). |
| PPM: pct_parallel | Percentage of fibers parallel to boundary. |
| PPM: pct_oblique | Percentage of fibers at oblique angles. |
| PPM: pct_perpendicular | Percentage of fibers perpendicular to boundary. |
| PPM: pct_tacs2 | PS-TACS2 percentage (parallel to boundary). |
| PPM: pct_tacs3 | PS-TACS3 percentage (perpendicular to boundary). |
| PPM: n_tacs3_clusters | Number of TACS-3 clusters along contour. |
| PPM: contour_length_um | Boundary contour length (um). |
| PPM: dilation_um | Border zone width used for analysis (um). |

### CSV Export

A consolidated CSV file is written to:
```
{project_dir}/analysis/batch_ppm/batch_results.csv
```

Each row represents one annotation-analysis pair. Columns include:

| Column | Description |
|--------|-------------|
| image_name | Name of the sum image. |
| image_collection | Image collection number. |
| sample_name | Sample name from metadata. |
| annotation_name | Display name of the annotation. |
| annotation_class | Classification of the annotation. |
| analysis_type | "polarity" or "perpendicularity". |
| (measurement columns) | All applicable PPM measurement values. |

## Tips & Troubleshooting

- **"No qualified PPM analysis sets found"**: The project must contain images
  with PPM modality metadata and either per-image calibration or an active
  calibration in PPM preferences. Check that images are properly imported with
  metadata.
- **Cancellation**: Click "Cancel" on the progress window to stop after the
  current annotation finishes. Partial results are still saved.
- **Errors on individual annotations**: If one annotation fails (e.g., Python
  error, empty region), the batch continues with the remaining annotations.
  Check the QuPath log for details.
- **Large projects**: Batch analysis can be slow for projects with many images
  and annotations. Each annotation requires reading image data and calling
  Python. Consider selecting only a subset of analysis sets for initial testing.
- **Measurement table not updating**: After batch analysis, close and reopen
  the measurement table (Measure > Show detection measurements or
  Show annotation measurements) to see the new PPM measurements.
- **CSV file location**: The CSV is always written to `analysis/batch_ppm/`
  inside the QuPath project directory. Previous CSV files are overwritten.

## See Also

- [PPM Polarity Plot](ppm-polarity-plot.md) -- Interactive polarity analysis
  for a single annotation
- [Surface Perpendicularity](surface-perpendicularity.md) -- Interactive
  perpendicularity analysis
- [Back-Propagate Annotations](back-propagate-annotations.md) -- Transfer
  annotations to parent images

# Surface Perpendicularity Analysis
> Menu: Extensions > QP Scope > PPM > Surface Perpendicularity Analysis...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Surface Perpendicularity Analysis measures the orientation of collagen fibers
relative to annotation boundaries (e.g., tumor-stroma interfaces). It implements
two complementary analysis approaches:

1. **Simple perpendicularity**: Uses distance-transform-based surface normals
   to compute the average deviation angle of fibers from the boundary normal at
   each pixel.
2. **PS-TACS scoring**: Implements the Tumor-Associated Collagen Signatures
   (TACS) scoring method from Qian et al. (2025), using per-contour-pixel
   analysis with Gaussian distance weighting.

This tool is designed for studying how collagen fiber alignment changes near
tissue boundaries, which is relevant to tumor invasion and metastasis research.

**Reference**: Qian et al., "Computationally Enabled Polychromatic Polarized
Imaging Enables Mapping of Matrix Architectures that Promote Pancreatic Ductal
Adenocarcinoma Dissemination", Am J Pathol 2025; 195:1242-1253.
DOI: 10.1016/j.ajpath.2025.04.017

## Prerequisites

- A PPM **sum image** must be open in QuPath.
- A **QuPath project** must be open.
- **Classified annotations** must exist on the image. At least one annotation
  class must be present (e.g., "Tumor", "Stroma boundary").
- A **PPM calibration** (sunburst calibration file) must be available.
- **Python** must be available on the system PATH with `ppm_library` installed.
- If the image lacks pixel size metadata, you will be prompted to enter it manually.

## Options

The configuration dialog presents the following parameters:

| Option | Type | Range | Default | Description |
|--------|------|-------|---------|-------------|
| Boundary annotation class | Choice | Available classes | First class | The annotation class that defines the tissue boundaries for analysis. All annotations of this class will be analyzed. |
| Border zone width (um) | Spinner | 1-500 | 50 | Width of the analysis zone around the boundary, in micrometers. Controls how far from the boundary fibers are analyzed. |
| Zone mode | Choice | outside/inside/both | outside | Where to analyze relative to the boundary. "outside" = exterior zone only, "inside" = interior zone only, "both" = both sides. |
| TACS threshold (deg from normal) | Spinner | 5-85 | 30 | Angular threshold for TACS classification. Fibers within this angle of the surface normal are classified as TACS-3 (perpendicular). Fibers within this angle of the surface tangent are classified as TACS-2 (parallel). |
| Fill holes in boundary | Checkbox | -- | Checked | Whether to fill holes in the boundary annotation shape before computing surface normals. |
| Pixel size | Display | -- | From image metadata | Pixel size in um/px. If the image has no pixel calibration, you are prompted to enter it. |

The dialog also shows the count of annotations matching the selected class
and a clickable DOI link to the reference paper.

## Workflow

1. Open a PPM sum image in a QuPath project.
2. Draw and classify boundary annotations (e.g., annotate tumor boundaries and
   assign them the "Tumor" class).
3. Launch the tool from the menu.
4. Select the boundary annotation class from the dropdown.
5. Configure the border zone width, zone mode, and TACS threshold.
6. Click "Run Analysis".
7. The tool processes each matching annotation sequentially:
   - Extracts the image region around the annotation (padded by the border zone
     width plus a safety margin).
   - Exports the annotation boundary as GeoJSON with adjusted coordinates.
   - If a birefringence sibling image exists, it is also extracted.
   - Calls the Python `ppm_library.analysis.cli` in perpendicularity mode.
   - Saves JSON results to the project's `analysis/perpendicularity/` directory.
   - Displays results in the results panel.

## Output

### Results Panel

The results panel shows analysis results for each processed annotation,
including progress updates during processing.

### JSON Results

For each annotation, a `results.json` file is saved to:
```
{project_dir}/analysis/perpendicularity/{image_name}/annotation_{N}/results.json
```

### Result Metrics

**Simple perpendicularity results:**

| Metric | Description |
|--------|-------------|
| mean_deviation_deg | Average angle between fiber orientation and boundary normal (0 = perfectly perpendicular, 90 = perfectly parallel). |
| std_deviation_deg | Standard deviation of the deviation angles. |
| n_valid_pixels | Number of valid pixels in the analysis zone. |
| pct_parallel | Percentage of fibers classified as parallel to the boundary. |
| pct_oblique | Percentage of fibers at oblique angles to the boundary. |
| pct_perpendicular | Percentage of fibers classified as perpendicular to the boundary. |

**PS-TACS scoring results:**

| Metric | Description |
|--------|-------------|
| pct_tacs2 | Percentage of boundary contour pixels with TACS-2 signature (fibers aligned parallel to boundary). |
| pct_tacs3 | Percentage of boundary contour pixels with TACS-3 signature (fibers aligned perpendicular to boundary). |
| n_tacs3_clusters | Number of distinct clusters of TACS-3 signatures along the contour. |

**Additional metrics:**

| Metric | Description |
|--------|-------------|
| contour_length_um | Total length of the analyzed boundary contour in micrometers. |

## Tips & Troubleshooting

- **"No classified annotations found"**: Annotations must have a class assigned.
  Right-click an annotation in QuPath and use "Set class" to assign one.
- **Border zone width**: Start with 50 um and adjust based on your tissue scale.
  Too large a zone dilutes the boundary-specific signal; too small may not
  capture enough fibers.
- **Zone mode "outside" vs "both"**: For tumor invasion analysis, "outside"
  analyzes only the stromal side of the boundary. "Both" includes fibers on
  both sides, which may be appropriate for non-tumor boundaries.
- **TACS threshold**: The default 30 deg threshold classifies fibers within
  30 deg of the normal as TACS-3 (perpendicular) and within 30 deg of the
  tangent as TACS-2 (parallel). The remaining 30 deg zone is "oblique".
  Adjust based on your classification criteria.
- **Fill holes**: Leave checked unless you specifically want to analyze fibers
  within holes of the boundary annotation.
- **Large annotations take longer**: The analysis region is expanded by the
  border zone width plus a safety margin. Very large annotations with wide
  border zones create large image extracts.
- **Python errors**: Check the QuPath log for detailed error output from the
  `ppm_library.analysis.cli` process.

## See Also

- [PPM Polarity Plot](ppm-polarity-plot.md) -- Angle distribution for a single
  annotation
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Run perpendicularity analysis
  across multiple images
- [Back-Propagate Annotations](back-propagate-annotations.md) -- Transfer
  annotations between coordinate spaces

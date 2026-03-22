# Autofocus Configuration Editor

> Menu: Extensions > QP Scope > Utilities > Autofocus Configuration Editor...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Configure per-objective autofocus parameters in an easy-to-use GUI. This tool allows
customization of focus search behavior for different objectives, controlling how many
Z positions are sampled, how wide the search range is, and how frequently autofocus
runs during tiled acquisition.

Use this tool when setting up a new objective for acquisition or when adjusting
autofocus behavior for different sample types (e.g., thick vs. thin sections).

## Prerequisites

- Microscope configuration YAML loaded with objective definitions
- At least one objective defined in the configuration

## Options

| Parameter | Type | Typical Range | Description |
|-----------|------|---------------|-------------|
| Objective | ComboBox | - | Select the objective to configure |
| n_steps | Spinner | 5-20 | Number of Z positions to sample during autofocus |
| search_range_um | Spinner | 10-50 | Total Z range to search in micrometers |
| n_tiles | Spinner | 3-10 | Autofocus runs every N tiles during acquisition |

### Buttons

| Button | Action |
|--------|--------|
| **Write to File** | Save all settings to YAML file |
| **OK** | Save and close dialog |
| **Cancel** | Discard unsaved changes |

## Workflow

1. Open the Autofocus Configuration Editor from the menu.
2. Select the objective you want to configure from the dropdown.
3. Adjust **n_steps** -- the number of Z positions sampled during each autofocus run.
4. Adjust **search_range_um** -- the total Z range (in um) over which focus is searched.
5. Adjust **n_tiles** -- how often autofocus triggers (every N tiles).
6. Click **Write to File** to save settings, or **OK** to save and close.

## Output

Settings are saved to `autofocus_{microscope}.yml` in the microscope configuration
directory. Each objective gets its own section in the file.

## Parameter Guidelines

| Objective | n_steps | search_range_um | n_tiles |
|-----------|---------|-----------------|---------|
| 10X | 9 | 15 | 5 |
| 20X | 11 | 15 | 5 |
| 40X | 15 | 10 | 7 |

## Tips & Troubleshooting

- **Higher magnification objectives** need more steps and a smaller search range
  because depth of field is narrower.
- **Lower n_tiles** means more frequent autofocus, which is slower but more reliable
  for uneven samples.
- **Thick samples** may need a larger search_range_um to accommodate Z variation.
- If autofocus consistently fails, try increasing n_steps or widening search_range_um.
- Use the [Autofocus Parameter Benchmark](autofocus-benchmark.md) to systematically
  find optimal settings for your sample type.

## See Also

- [Autofocus Parameter Benchmark](autofocus-benchmark.md) -- Systematically find optimal autofocus settings
- [All Tools](../UTILITIES.md) -- Complete utilities reference

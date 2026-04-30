#!/usr/bin/env python3
"""Migrate legacy per-slide alignment JSONs to scope-namespaced files.

Background: before commit 33e5e74 the per-slide alignment files in
<project>/alignmentFiles/ were named <sample>_alignment.json with no
microscope identifier inside the JSON or the filename. Same project
re-opened on a different scope would silently load the wrong scope's
transform. The current code looks for <sample>_<scope>_alignment.json
first and refuses to load a legacy file whose JSON lacks a "microscope"
field.

This script stamps a microscope name into each legacy file and renames
it to the scoped form, so existing alignments built on a known scope
keep working without re-running alignment.

Usage:
    python migrate_alignment_files.py <alignmentFiles dir> <microscope>

Examples:
    # Stamp all unscoped files in this folder as PPM alignments
    python migrate_alignment_files.py C:/QPSC/Data/MH_PPM/alignmentFiles PPM

    # Walk a parent dir and migrate every alignmentFiles subfolder
    python migrate_alignment_files.py --recursive C:/QPSC/Data PPM

The script is idempotent and safe: it only touches files whose JSON has
no "microscope" field; files already stamped (by a previous run or by
the current code) are skipped. The companion <sample>_alignment.png is
renamed alongside its JSON if present.
"""

import argparse
import json
import sys
from pathlib import Path


def migrate_file(json_path: Path, scope: str, dry_run: bool = False) -> str:
    """Process a single legacy alignment JSON. Returns one of:
       'skipped-already-scoped', 'skipped-different-scope', 'migrated', 'error'.
    """
    try:
        with json_path.open('r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        print(f"  ERROR reading {json_path.name}: {e}")
        return 'error'

    existing_scope = data.get('microscope')
    if existing_scope:
        if existing_scope == scope:
            print(f"  SKIP  {json_path.name} (already scoped to '{scope}')")
            return 'skipped-already-scoped'
        else:
            print(f"  SKIP  {json_path.name} (already scoped to '{existing_scope}', not '{scope}')")
            return 'skipped-different-scope'

    sample = data.get('sampleName')
    if not sample:
        # Fall back to filename: <sample>_alignment.json
        stem = json_path.stem
        if stem.endswith('_alignment'):
            sample = stem[: -len('_alignment')]
        else:
            print(f"  ERROR {json_path.name}: cannot determine sampleName")
            return 'error'

    new_name = f"{sample}_{scope}_alignment.json"
    new_json = json_path.parent / new_name

    if new_json.exists():
        print(f"  ERROR {json_path.name}: target {new_name} already exists; refusing to overwrite")
        return 'error'

    data['microscope'] = scope
    data.setdefault('migrated_from_legacy', True)

    if dry_run:
        print(f"  DRY   {json_path.name} -> {new_name}")
        return 'migrated'

    # Write scoped JSON
    with new_json.open('w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)

    # Rename companion PNG if present
    legacy_png = json_path.with_suffix('.png')
    if legacy_png.exists():
        new_png = json_path.parent / f"{sample}_{scope}_alignment.png"
        if not new_png.exists():
            legacy_png.rename(new_png)
            print(f"  PNG   {legacy_png.name} -> {new_png.name}")

    # Remove the legacy JSON only after the scoped JSON exists
    json_path.unlink()
    print(f"  OK    {json_path.name} -> {new_name}")
    return 'migrated'


def find_alignment_dirs(root: Path):
    """Yield every alignmentFiles directory under root (recursive)."""
    for p in root.rglob('alignmentFiles'):
        if p.is_dir():
            yield p


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('path', help="alignmentFiles directory, or a parent dir if --recursive")
    ap.add_argument('microscope', help="Scope name to stamp (e.g. PPM, OWS3, Ocus40)")
    ap.add_argument('--recursive', '-r', action='store_true',
                    help="Walk path recursively for every alignmentFiles subfolder")
    ap.add_argument('--dry-run', '-n', action='store_true',
                    help="Show what would change without modifying any files")
    args = ap.parse_args()

    root = Path(args.path)
    if not root.exists():
        print(f"Path not found: {root}", file=sys.stderr)
        sys.exit(2)

    if args.recursive:
        dirs = list(find_alignment_dirs(root))
        if not dirs:
            print(f"No alignmentFiles directories found under {root}")
            sys.exit(0)
    else:
        if root.name != 'alignmentFiles':
            print(f"Warning: '{root.name}' is not named 'alignmentFiles'; processing it anyway.")
        dirs = [root]

    counts = {'migrated': 0, 'skipped-already-scoped': 0,
              'skipped-different-scope': 0, 'error': 0}

    for d in dirs:
        legacy = sorted(p for p in d.glob('*_alignment.json')
                        if not p.stem.endswith(f"_{args.microscope}_alignment"))
        # Filter out files that ARE already scoped (e.g. <sample>_<otherScope>_alignment.json)
        # only the truly-unscoped <sample>_alignment.json matter
        legacy = [p for p in legacy if not _looks_scoped(p)]

        if not legacy:
            print(f"\n[{d}] no legacy unscoped files")
            continue

        print(f"\n[{d}] {len(legacy)} candidate(s) to migrate -> {args.microscope}")
        for jp in legacy:
            result = migrate_file(jp, args.microscope, dry_run=args.dry_run)
            counts[result] = counts.get(result, 0) + 1

    print("\nSummary:")
    for k, v in counts.items():
        print(f"  {k:30s} {v}")
    if args.dry_run:
        print("\n(dry run; no files were modified)")


def _looks_scoped(path: Path) -> bool:
    """Heuristic: if filename has at least 2 underscores before _alignment.json
    AND the second-to-last token looks like a scope name (alphanumeric, no spaces),
    treat it as already scoped. Conservative: anything ambiguous is treated as
    legacy and considered for migration (the migrate_file pass will skip it
    if the JSON already has a microscope field)."""
    stem = path.stem
    if not stem.endswith('_alignment'):
        return False
    base = stem[: -len('_alignment')]
    # base is like "<sample>" or "<sample>_<scope>". We can't reliably distinguish
    # samples that themselves contain underscores from sample_scope, so the
    # JSON-level check inside migrate_file is the real gate. This heuristic is
    # advisory only.
    return False


if __name__ == '__main__':
    main()

#!/bin/bash
# check-doc-images.sh
# Checks if any Java UI source files tracked in IMAGE_REGISTRY.md have been
# modified more recently than their corresponding screenshot.
#
# Usage: ./tools/check-doc-images.sh [--ci]
#   --ci: Exit with code 1 if stale images found (for CI integration)
#
# This script parses IMAGE_REGISTRY.md for the image-to-source mapping,
# then compares git modification dates.

set -e

REGISTRY="documentation/images/IMAGE_REGISTRY.md"
IMAGES_DIR="documentation/images"
SRC_DIR="src/main/java/qupath/ext/qpsc"
CI_MODE=false
STALE_COUNT=0

if [ "$1" = "--ci" ]; then
    CI_MODE=true
fi

if [ ! -f "$REGISTRY" ]; then
    echo "ERROR: Registry file not found: $REGISTRY"
    exit 1
fi

echo "=== Documentation Image Freshness Check ==="
echo ""

# Parse the registry table: extract lines with .png and .java
while IFS='|' read -r _ screenshot sources _ _ _; do
    # Clean whitespace
    screenshot=$(echo "$screenshot" | xargs)
    sources=$(echo "$sources" | xargs)

    # Skip non-data lines
    [[ "$screenshot" != *".png"* ]] && continue
    [[ "$sources" != *".java"* ]] && continue

    # Strip backticks
    screenshot="${screenshot//\`/}"

    # Get the image file path
    img_path="$IMAGES_DIR/$screenshot"
    if [ ! -f "$img_path" ]; then
        echo "  MISSING: $screenshot"
        STALE_COUNT=$((STALE_COUNT + 1))
        continue
    fi

    # Get last commit date for the image
    img_date=$(git log -1 --format="%aI" -- "$img_path" 2>/dev/null || echo "")
    if [ -z "$img_date" ]; then
        echo "  UNTRACKED: $screenshot (not in git)"
        continue
    fi

    # Check each source file
    # Split by comma and handle multiple files
    IFS=',' read -ra source_files <<< "$sources"
    for src in "${source_files[@]}"; do
        src=$(echo "$src" | xargs | tr -d '`')
        # Resolve to actual path
        src_path=$(find "$SRC_DIR" -name "$(basename "$src")" -type f 2>/dev/null | head -1)
        if [ -z "$src_path" ]; then
            continue
        fi

        src_date=$(git log -1 --format="%aI" -- "$src_path" 2>/dev/null || echo "")
        if [ -z "$src_date" ]; then
            continue
        fi

        # Compare dates
        if [[ "$src_date" > "$img_date" ]]; then
            echo "  STALE: $screenshot"
            echo "         Source: $src_path (modified $(echo $src_date | cut -dT -f1))"
            echo "         Image last updated: $(echo $img_date | cut -dT -f1)"
            STALE_COUNT=$((STALE_COUNT + 1))
            break  # One stale source is enough to flag
        fi
    done

done < "$REGISTRY"

echo ""
if [ $STALE_COUNT -gt 0 ]; then
    echo "=== $STALE_COUNT screenshot(s) may need updating ==="
    echo "Re-capture on the Windows workstation and commit updated images."
    if $CI_MODE; then
        exit 1
    fi
else
    echo "=== All tracked screenshots appear up-to-date ==="
fi

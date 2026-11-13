/*
 * fix_source_microscope.groovy
 *
 * QuPath 0.7 script: relabel slide-scanner images that a project records as coming from a
 * QPSC microscope.
 *
 * What it fixes:
 *   Each project image carries a 'source_microscope' metadata value naming the scanner that
 *   made its macro image (for example Ocus40). Older QPSC versions could record the
 *   microscope QPSC was running on instead (for example PPM). With the wrong value:
 *     - the multi-slide Stage Map shows that slide's macro uncropped, with the scanner's
 *       border pushing the slide out of place;
 *     - the alignment preset and macro flip are looked up for the wrong (scanner,
 *       microscope) pair.
 *   A QPSC microscope cannot make a macro image, so an image file that has one came from a
 *   scanner.
 *
 * What it changes:
 *   An entry is relabeled from WRONG_SOURCE to CORRECT_SOURCE when all of these hold:
 *     - its source_microscope is WRONG_SOURCE;
 *     - it has no acquired_on_microscope value (nothing says a microscope acquired it);
 *     - its image file has a macro associated image.
 *   The "(rotated N)", "(flipped ...)" and "(Camera View)" copies of a scanner image point at
 *   the same file, so they are relabeled with it. Tiles acquired on the microscope point at
 *   their own OME-TIFF files, which have no macro, so they are left alone.
 *
 * How to use:
 *   1. Open the project in QuPath. Close the Stage Map and any QPSC workflow first.
 *   2. Edit the three values under USER SETTINGS:
 *        WRONG_SOURCE   - the microscope name that was recorded by mistake (PPM, OWS3, ...)
 *        CORRECT_SOURCE - the scanner that made the slides (Ocus40, ...); it must match the
 *                         source name of your alignment preset exactly
 *        DRY_RUN        - leave true for the first run: it only lists what would change
 *   3. Automate > Script editor > paste > Run. Read the list in the output pane.
 *   4. Set DRY_RUN = false and run again to write the changes and save the project.
 *
 * Each image file is opened once to look for the macro, so a large project takes a few
 * seconds per scanner image.
 */

import qupath.lib.images.servers.ImageServers

// ===================== USER SETTINGS =====================
String WRONG_SOURCE = "PPM"
String CORRECT_SOURCE = "Ocus40"
boolean DRY_RUN = true
// =========================================================

def project = getProject()
if (project == null) {
    println "No project is open."
    return
}

// Whether one image file has a macro associated image, cached per file.
Map<URI, Boolean> hasMacroByFile = [:]
def fileHasMacro = { URI uri ->
    if (!hasMacroByFile.containsKey(uri)) {
        boolean found = false
        try {
            def server = ImageServers.buildServer(uri)
            try {
                found = server.getAssociatedImageList().any { it.toLowerCase(Locale.ROOT).contains("macro") }
            } finally {
                server.close()
            }
        } catch (Exception e) {
            println "  could not open ${uri}: ${e.getMessage()}"
        }
        hasMacroByFile[uri] = found
    }
    return hasMacroByFile[uri]
}

int changed = 0
int skippedAcquired = 0
int skippedNoMacro = 0
for (entry in project.getImageList()) {
    def md = entry.getMetadata()
    if (md.get("source_microscope") != WRONG_SOURCE) {
        continue
    }
    String name = entry.getImageName()
    String acquiredOn = md.get("acquired_on_microscope")
    if (acquiredOn != null && !acquiredOn.isBlank()) {
        skippedAcquired++
        continue
    }
    def uris = entry.getURIs()
    if (!uris.any { fileHasMacro(it) }) {
        skippedNoMacro++
        continue
    }
    println "${DRY_RUN ? 'Would relabel' : 'Relabeled'}: ${name}  (${WRONG_SOURCE} -> ${CORRECT_SOURCE})"
    if (!DRY_RUN) {
        md.put("source_microscope", CORRECT_SOURCE)
    }
    changed++
}

if (!DRY_RUN && changed > 0) {
    project.syncChanges()
}
println ""
println "${DRY_RUN ? 'Dry run' : 'Done'}: ${changed} entr${changed == 1 ? 'y' : 'ies'} " +
        "${DRY_RUN ? 'would be' : 'were'} relabeled."
println "Left alone with source_microscope=${WRONG_SOURCE}: ${skippedAcquired} acquired on a microscope, " +
        "${skippedNoMacro} with no macro image."
if (DRY_RUN && changed > 0) {
    println "Set DRY_RUN = false and run again to apply."
}

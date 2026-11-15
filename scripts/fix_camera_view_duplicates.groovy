/*
 * fix_camera_view_duplicates.groovy
 *
 * QuPath 0.7 script: remove the extra "(Camera View)" entries that older QPSC versions built
 * for rotated slides.
 *
 * What it fixes:
 *   A "(Camera View)" entry is the slide image turned to match the camera. QPSC finds an
 *   existing one by its base image, its rotation and its flip, all read from metadata. Older
 *   versions built the Camera View of a "(rotated N)" entry without recording the rotation,
 *   so that copy was never found again, and each run built another one (for example three
 *   "MetroHealth_370.svs (rotated 270) (Camera View)" entries in one project). A copy
 *   without its rotation can also be picked up for the unrotated slide, the wrong way round.
 *
 * What it changes:
 *   1. A Camera View entry with no rotation recorded gets the rotation of the entry it was
 *      made from (its original_image_id), when that entry has one.
 *   2. Camera View entries with the same base image, rotation and flip are duplicates. One
 *      is kept: the one with the most acquisitions made from it (then the first in the
 *      project). Acquisitions made from the others are moved onto the kept one (their
 *      original_image_id), and the others are deleted from the project. The copies show the
 *      same pixels, and the slide file itself is never touched.
 *
 * How to use:
 *   1. Open the project in QuPath. Close the Stage Map and any QPSC workflow, and open an
 *      image that is not a Camera View entry (a deleted entry cannot be the open one).
 *   2. Leave DRY_RUN = true for the first run: it only lists what would change.
 *   3. Automate > Script editor > paste > Run. Read the list in the output pane.
 *   4. Set DRY_RUN = false and run again to apply the changes and save the project.
 */

// ===================== USER SETTINGS =====================
boolean DRY_RUN = true
// =========================================================

def project = getProject()
if (project == null) {
    println "No project is open."
    return
}

String CAMERA_VIEW = "camera_view"
String ROTATION = "lp_rotation_deg"
String BASE_IMAGE = "base_image"
String ORIGINAL_ID = "original_image_id"
String PARITY_X = "lp_baked_parity_x"
String PARITY_Y = "lp_baked_parity_y"

def entries = project.getImageList()
def byId = entries.collectEntries { [(it.getID()): it] }
def cameraViews = entries.findAll { it.getMetadata().get(CAMERA_VIEW) == "1" }
String tag = DRY_RUN ? "Would" : "Will"

// 1. Record the missing rotation, taken from the entry the copy was made from.
Map<Object, String> rotationOf = [:]
int stamped = 0
for (e in cameraViews) {
    String rot = e.getMetadata().get(ROTATION)
    if (rot == null || rot.isBlank()) {
        def parent = byId[e.getMetadata().get(ORIGINAL_ID)]
        String parentRot = parent?.getMetadata()?.get(ROTATION)
        if (parentRot != null && !parentRot.isBlank()) {
            println "${tag} record rotation ${parentRot} on '${e.getImageName()}' (made from '${parent.getImageName()}')"
            rot = parentRot
            if (!DRY_RUN) {
                e.getMetadata().put(ROTATION, parentRot)
            }
            stamped++
        }
    }
    rotationOf[e] = (rot == null || rot.isBlank()) ? "0" : rot
}

// 2. Group the copies of one view and keep one.
def childrenOf = { entry ->
    entries.findAll { it != entry && it.getMetadata().get(ORIGINAL_ID) == entry.getID() }
}
def groups = cameraViews.groupBy { e ->
    def md = e.getMetadata()
    [md.get(BASE_IMAGE), rotationOf[e], md.get(PARITY_X) == "1", md.get(PARITY_Y) == "1"]
}
int removed = 0
int moved = 0
groups.each { key, copies ->
    if (copies.size() < 2) {
        return
    }
    def keep = copies.max { childrenOf(it).size() }
    println ""
    println "${copies.size()} copies of base '${key[0]}' at rotation ${key[1]}: keeping '${keep.getImageName()}' (id ${keep.getID()})"
    for (extra in copies) {
        if (extra == keep) {
            continue
        }
        def children = childrenOf(extra)
        for (child in children) {
            println "  ${tag} move '${child.getImageName()}' onto the kept copy"
            if (!DRY_RUN) {
                child.getMetadata().put(ORIGINAL_ID, keep.getID())
            }
            moved++
        }
        println "  ${tag} delete '${extra.getImageName()}' (id ${extra.getID()})"
        if (!DRY_RUN) {
            project.removeImage(extra, true)
        }
        removed++
    }
}

if (!DRY_RUN && (stamped + moved + removed) > 0) {
    project.syncChanges()
    getQuPath()?.refreshProject()
}
println ""
println "${DRY_RUN ? 'Dry run' : 'Done'}: ${stamped} rotation(s) recorded, ${moved} acquisition(s) moved, " +
        "${removed} duplicate(s) ${DRY_RUN ? 'would be deleted' : 'deleted'}."
if (DRY_RUN && (stamped + moved + removed) > 0) {
    println "Set DRY_RUN = false and run again to apply."
}

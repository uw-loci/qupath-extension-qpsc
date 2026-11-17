/*
 * migrate_lcpolscope_merge.groovy
 *
 * QuPath 0.7 script: move a project's LC-PolScope records from the old two-config setup to
 * the single LC-PolScope config.
 *
 * Why:
 *   The LC-PolScope had one config per camera: config_LCPolScope.yml (FLIR Oryx) and
 *   config_LCPolScope_Hamamatsu.yml. They are now one config, named "LCPolScope", that
 *   holds both cameras and records which camera each alignment was made with. Records made
 *   under the Hamamatsu config still carry the old name "LCPolScope_Hamamatsu", so QPSC no
 *   longer finds them.
 *
 * What it changes, in the open project only:
 *   1. Each alignmentFiles/<name>_LCPolScope_Hamamatsu_alignment.json becomes
 *      <name>_LCPolScope_alignment.json, with "microscope": "LCPolScope" and, when missing,
 *      "detector": the Hamamatsu. If a <name>_LCPolScope_alignment.json from the Oryx is
 *      already there, it is kept beside it as <name>_LCPolScope_alignment.json.oryx-bak
 *      (QPSC would ignore it anyway while the Hamamatsu is in use).
 *   2. Image metadata source_microscope / acquired_on_microscope "LCPolScope_Hamamatsu"
 *      becomes "LCPolScope".
 *   The scanner alignment presets (saved_transforms.json) are migrated in the
 *   microscope_configurations repository itself; this script does not touch them.
 *
 * How to use:
 *   1. Open the project in QuPath. Close the Stage Map and any QPSC workflow.
 *   2. Leave DRY_RUN = true for the first run: it only lists what would change.
 *   3. Automate > Script editor > paste > Run. Read the list in the output pane.
 *   4. Set DRY_RUN = false and run again to apply the changes and save the project.
 */

// ===================== USER SETTINGS =====================
boolean DRY_RUN = true
// =========================================================

String OLD_NAME = "LCPolScope_Hamamatsu"
String NEW_NAME = "LCPolScope"
String HAMAMATSU = "LCPS_DETECTOR_HAMAMATSU_FLASH_001"
String SUFFIX_OLD = "_" + OLD_NAME + "_alignment.json"
String SUFFIX_NEW = "_" + NEW_NAME + "_alignment.json"

def project = getProject()
if (project == null) {
    println "No project is open."
    return
}
String tag = DRY_RUN ? "Would" : "Will"
def gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create()

// 1. Slide alignment files.
def projectDir = project.getPath().toFile().getParentFile()
def alignmentDir = new File(projectDir, "alignmentFiles")
int files = 0
if (alignmentDir.isDirectory()) {
    for (File f in (alignmentDir.listFiles() ?: new File[0]).findAll { it.name.endsWith(SUFFIX_OLD) }.sort { it.name }) {
        String stem = f.name.substring(0, f.name.length() - SUFFIX_OLD.length())
        File target = new File(alignmentDir, stem + SUFFIX_NEW)
        Map data = gson.fromJson(f.getText("UTF-8"), Map)
        data.microscope = NEW_NAME
        if (!data.detector) {
            data.detector = HAMAMATSU
        }
        println "${tag} rename ${f.name} -> ${target.name}" + (target.exists() ? " (the existing Oryx file is kept as ${target.name}.oryx-bak)" : "")
        files++
        if (!DRY_RUN) {
            if (target.exists()) {
                File bak = new File(alignmentDir, target.name + ".oryx-bak")
                if (bak.exists()) {
                    bak.delete()
                }
                assert target.renameTo(bak)
            }
            target.setText(gson.toJson(data), "UTF-8")
            assert f.delete()
        }
    }
}

// 2. Image metadata.
int entries = 0
for (e in project.getImageList()) {
    def md = e.getMetadata()
    for (String key in ["source_microscope", "acquired_on_microscope"]) {
        if (md.get(key) == OLD_NAME) {
            println "${tag} set ${key} = ${NEW_NAME} on '${e.getImageName()}'"
            entries++
            if (!DRY_RUN) {
                md.put(key, NEW_NAME)
            }
        }
    }
}

if (!DRY_RUN && entries > 0) {
    project.syncChanges()
}
println "${files} alignment file(s) and ${entries} metadata value(s) ${DRY_RUN ? 'would change (dry run; set DRY_RUN = false to apply)' : 'changed'}."

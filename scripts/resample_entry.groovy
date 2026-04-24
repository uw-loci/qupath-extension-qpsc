/*
 * resample_entry.groovy
 *
 * QuPath 0.7.0 script: duplicate the currently-open project image at 2x/4x up or down,
 * matching the source format (BMP -> BMP, TIF -> TIF), and copy annotations onto the new
 * entry with ROI coordinates scaled by the same factor.
 *
 * How to use:
 *   1. Edit FACTOR and DIRECTION constants below.
 *   2. Open the BMP/TIF entry you want to resample in a QuPath project.
 *   3. Automate > Script editor > paste > Run.
 *
 * Assumptions:
 *   - Source is a non-pyramidal BMP/TIF small enough to fit in memory as one BufferedImage.
 *     The script aborts if the resampled image would exceed MAX_OUTPUT_BYTES.
 *   - Only annotation objects are copied (not detections / cells / TMA cores).
 */

import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

import qupath.lib.common.GeneralTools
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.ImageServers
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjectTools
import qupath.lib.objects.hierarchy.PathObjectHierarchy
import qupath.lib.projects.Project
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.regions.RegionRequest

// ===================== USER CONFIG =====================
int    FACTOR    = 2           // 2 or 4
String DIRECTION = "down"      // "up" or "down"
long   MAX_OUTPUT_BYTES = 2L * 1024L * 1024L * 1024L   // 2 GB safety cap
// =======================================================

// ---------- preconditions ----------
if (FACTOR != 2 && FACTOR != 4)
    throw new IllegalArgumentException("FACTOR must be 2 or 4, got ${FACTOR}")
if (!(DIRECTION in ["up", "down"]))
    throw new IllegalArgumentException("DIRECTION must be 'up' or 'down', got '${DIRECTION}'")

Project<BufferedImage> project = getProject()
if (project == null)
    throw new IllegalStateException("No project open -- this script requires a QuPath project.")

ProjectImageEntry<BufferedImage> srcEntry = getProjectEntry()
if (srcEntry == null)
    throw new IllegalStateException("No current project entry -- open an image first.")

def imageData  = getCurrentImageData()
def srcServer  = imageData.getServer() as ImageServer<BufferedImage>
def srcHier    = imageData.getHierarchy() as PathObjectHierarchy

int srcW = srcServer.getWidth()
int srcH = srcServer.getHeight()

// ---------- compute scale + new dims ----------
double scale = DIRECTION == "up" ? (double) FACTOR : 1.0d / FACTOR
int newW = (int) Math.round(srcW * scale)
int newH = (int) Math.round(srcH * scale)

if (newW <= 0 || newH <= 0)
    throw new IllegalStateException("Computed output dims invalid: ${newW} x ${newH}")

long approxBytes = (long) newW * (long) newH * 4L  // assume 4 bytes per pixel (RGBA)
if (approxBytes > MAX_OUTPUT_BYTES) {
    throw new IllegalStateException(
        "Aborting: output would be ~${approxBytes / (1024 * 1024)} MB " +
        "(${newW} x ${newH}), exceeds MAX_OUTPUT_BYTES=${MAX_OUTPUT_BYTES / (1024 * 1024)} MB. " +
        "Raise the cap or pick a smaller FACTOR.")
}

print "Resampling ${srcW} x ${srcH} -> ${newW} x ${newH}  (scale=${scale})"

// ---------- read full source image ----------
RegionRequest request = RegionRequest.createInstance(srcServer.getPath(), 1.0d, 0, 0, srcW, srcH)
BufferedImage srcImg = srcServer.readRegion(request)
if (srcImg == null)
    throw new IllegalStateException("Failed to read source image region.")

// ---------- resample with bilinear ----------
int dstType = srcImg.getType() != 0 ? srcImg.getType() : BufferedImage.TYPE_INT_RGB
BufferedImage dstImg = new BufferedImage(newW, newH, dstType)
Graphics2D g = dstImg.createGraphics()
try {
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(srcImg, 0, 0, newW, newH, null)
} finally {
    g.dispose()
}

// ---------- resolve output path (keep source extension) ----------
String origName = srcEntry.getImageName()
String ext = GeneralTools.getExtension(new File(origName)).orElse(".tif")  // ".bmp" / ".tif" / ".tiff"
String baseName = GeneralTools.stripExtension(origName) + "_" + (DIRECTION == "up" ? "up" : "down") + FACTOR

Path projectDir = project.getPath().getParent()
Path imgsDir = projectDir.resolve("imgs")
Files.createDirectories(imgsDir)

// Unique file name if this script is re-run on the same source
Path outFile = imgsDir.resolve(baseName + ext)
int dupe = 1
while (Files.exists(outFile)) {
    outFile = imgsDir.resolve(baseName + "(" + dupe + ")" + ext)
    dupe++
}

// ---------- write via ImageIO, matching source format ----------
String extLower = ext.toLowerCase()
String formatTag = extLower == ".bmp" ? "bmp" : "tiff"   // .tif / .tiff both map to "tiff"
boolean wrote = ImageIO.write(dstImg, formatTag, outFile.toFile())
if (!wrote) {
    // Fallback: if "tiff" writer isn't registered, try "tif" and then bmp as a last resort
    if (formatTag == "tiff")
        wrote = ImageIO.write(dstImg, "tif", outFile.toFile())
    if (!wrote)
        throw new IllegalStateException("No ImageIO writer found for format '${formatTag}'. " +
            "Check QuPath's ImageIO providers.")
}
print "Wrote resampled file: ${outFile}"

// ---------- add as a new project entry ----------
ImageServer<BufferedImage> newServer = ImageServers.buildServer(outFile.toUri().toString())
ProjectImageEntry<BufferedImage> newEntry = project.addImage(newServer.getBuilder())
newEntry.setImageName(outFile.getFileName().toString())

// ---------- copy + scale annotations ----------
def newImageData = newEntry.readImageData()
def newHier = newImageData.getHierarchy()

AffineTransform xform = AffineTransform.getScaleInstance(scale, scale)
List<PathObject> scaled = []
for (PathObject anno : srcHier.getAnnotationObjects()) {
    PathObject copy = PathObjectTools.transformObject(anno, xform, true, true)
    if (copy != null)
        scaled.add(copy)
}
if (!scaled.isEmpty()) {
    newHier.addObjects(scaled)
    newHier.fireHierarchyChangedEvent(newHier.getRootObject())
}

// Propagate image type so downstream analysis doesn't need a re-classify
newImageData.setImageType(imageData.getImageType())

newEntry.saveImageData(newImageData)

// ---------- persist + refresh ----------
project.syncChanges()
try {
    def gui = getQuPath()
    if (gui != null)
        gui.refreshProject()
} catch (Throwable ignored) {
    // running from a non-GUI context -- project.syncChanges() is enough
}

print "Added project entry '${newEntry.getImageName()}' with ${scaled.size()} annotation(s) " +
      "scaled by ${scale}."
print "Done."

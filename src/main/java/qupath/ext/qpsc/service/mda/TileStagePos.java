package qupath.ext.qpsc.service.mda;

/**
 * One exported MDA stage position, in stage micrometers.
 *
 * <p>{@code zUm} is null when the focal plane is not known yet. MDA files are written
 * up-front, before autofocus has run, so the initial export carries no Z; the achieved
 * plane is backfilled afterwards by
 * {@link MdaSettingsWriter#updatePositionListZ}. A null Z means the written position
 * list has no Z device position at all, which is what stops Micro-Manager from driving
 * the focus drive to absolute zero when the list is loaded.
 */
public record TileStagePos(String label, double xUm, double yUm, Double zUm) {}

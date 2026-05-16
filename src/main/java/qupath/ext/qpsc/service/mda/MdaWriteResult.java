package qupath.ext.qpsc.service.mda;

import java.nio.file.Path;

public record MdaWriteResult(Path settingsFile, Path positionsFile, Path notesFile) {}

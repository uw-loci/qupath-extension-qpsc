package qupath.ext.qpsc.model;

import java.io.File;

/** Holds the user's choices from the "sample setup" dialog. */
public record SampleSetupResult(
        String sampleName, File projectsFolder, String modality, String objective, String detector) {}

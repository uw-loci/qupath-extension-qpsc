package qupath.ext.qpsc.service.mda;

record MdaChannelSpec(
        String channelGroup,
        String config,
        double exposure,
        double zOffset,
        boolean doZStack,
        MdaColor color,
        int skipFactorFrame,
        boolean useChannel,
        String camera) {}

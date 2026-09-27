package com.stove.common.event.payload;

/** One validated artifact in a submission or published release. */
public record BuildVariant(
        Long buildId,
        String platform,
        String architecture,
        String version,
        long fileSize,
        String checksum,
        String storagePath,
        String deltaFromVersion
) {
    public boolean delta() {
        return deltaFromVersion != null;
    }
}

package io.studentcloud.drive.application;

/** Local file and temporary-disk admission, separate from a user's persistent storage quota. */
public record UploadLimits(long localMaxFileBytes, long actorStagingBytes, long globalStagingBytes) {
    public static final long HARD_MAX_FILE_BYTES = 1024L * 1024 * 1024;
    public static final int PUBLICATION_COPIES = 4;
    public UploadLimits {
        if (localMaxFileBytes < 1 || localMaxFileBytes > HARD_MAX_FILE_BYTES
                || actorStagingBytes < Math.multiplyExact(localMaxFileBytes, PUBLICATION_COPIES)
                || globalStagingBytes < actorStagingBytes) {
            throw new IllegalArgumentException("Configure a local file limit of at most 1 GiB and staging budgets covering the publication peak");
        }
    }
    public long reservationBytes(long fileBytes) {
        if (fileBytes < 1 || fileBytes > HARD_MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid upload size");
        return Math.multiplyExact(fileBytes, PUBLICATION_COPIES);
    }
    public boolean canAdmitToDisk(long usableBytes, long reservedBytes, long fileBytes) {
        if (usableBytes < 0 || reservedBytes < 0) return false;
        try { return usableBytes >= Math.addExact(reservedBytes,reservationBytes(fileBytes)); }
        catch (ArithmeticException overflow) { return false; }
    }
    public boolean canAdmitPublication(long usableBytes,long reservedBytes,long fileBytes) {
        if(usableBytes<0||reservedBytes<0)return false;
        try {
            long alreadyPromised=reservedBytes-reservedBytes/2;
            return usableBytes>=Math.addExact(alreadyPromised,Math.multiplyExact(fileBytes,2L));
        } catch(ArithmeticException overflow) { return false; }
    }
    public static UploadLimits defaults() {
        return new UploadLimits(MountService.MAX_TRANSFER_BYTES, 400L * 1024 * 1024, 4L * HARD_MAX_FILE_BYTES);
    }
}

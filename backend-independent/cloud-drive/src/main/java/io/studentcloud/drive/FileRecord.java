package io.studentcloud.drive;

import java.time.Instant;

/** Public metadata, without a physical host path or a pre-authorized download URL. */
public record FileRecord(DrivePath path, String name, boolean directory, long size, Instant modifiedAt) { }

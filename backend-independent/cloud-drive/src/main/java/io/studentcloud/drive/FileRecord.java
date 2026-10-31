package io.studentcloud.drive;

import java.time.Instant;

/**
 * 承载文件或目录的对外元数据，供目录浏览与内容读取使用。
 * Public metadata, without a physical host path or a pre-authorized download URL.
 */
public record FileRecord(DrivePath path, String name, boolean directory, long size, Instant modifiedAt) { }

package com.dtcomg.dio_up.internal;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "download_record")
public final class DownloadRecord {
    // 每个目标任务只保留一个断点下载快照。
    @PrimaryKey
    public final long targetId;
    public final long packageId;
    public final String packageSha256;
    // partPath 指向未完成文件，finalPath 指向校验通过后的 APK。
    public final String partPath;
    public final String finalPath;
    public final long downloadedBytes;
    public final long updatedAt;

    public DownloadRecord(
            long targetId,
            long packageId,
            String packageSha256,
            String partPath,
            String finalPath,
            long downloadedBytes,
            long updatedAt
    ) {
        this.targetId = targetId;
        this.packageId = packageId;
        this.packageSha256 = packageSha256;
        this.partPath = partPath;
        this.finalPath = finalPath;
        this.downloadedBytes = downloadedBytes;
        this.updatedAt = updatedAt;
    }
}

package com.dtcomg.dio_up.internal;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "task_record")
public final class TaskRecord {
    // 服务端 targetId 是设备任务的稳定唯一键。
    @PrimaryKey
    public final long targetId;
    public final long taskId;
    public final String status;
    public final long packageId;
    public final String packageSha256;
    public final String packageName;
    public final Long packageVersionCode;
    public final String filePath;
    public final String message;
    public final long updatedAt;

    public TaskRecord(
            long targetId,
            long taskId,
            String status,
            long packageId,
            String packageSha256,
            String packageName,
            Long packageVersionCode,
            String filePath,
            String message,
            long updatedAt
    ) {
        this.targetId = targetId;
        this.taskId = taskId;
        this.status = status;
        this.packageId = packageId;
        this.packageSha256 = packageSha256;
        this.packageName = packageName;
        this.packageVersionCode = packageVersionCode;
        this.filePath = filePath;
        this.message = message;
        this.updatedAt = updatedAt;
    }

    public TaskRecord withStatus(String newStatus, String newMessage) {
        // Room 实体保持不可变，状态变化时复制一条新记录并刷新时间戳。
        return new TaskRecord(
                targetId,
                taskId,
                newStatus,
                packageId,
                packageSha256,
                packageName,
                packageVersionCode,
                filePath,
                newMessage,
                System.currentTimeMillis()
        );
    }
}

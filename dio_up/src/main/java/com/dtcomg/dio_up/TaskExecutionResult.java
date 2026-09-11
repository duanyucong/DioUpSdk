package com.dtcomg.dio_up;

import java.util.Objects;

public final class TaskExecutionResult {
    // 原始部署任务 ID。
    private final long taskId;
    // 当前设备任务目标 ID。
    private final long targetId;
    // SUCCEEDED、FAILED 或 WAITING_FOR_USER。
    private final String status;
    // APK 类任务的最终文件路径；FRP 任务通常为空。
    private final String filePath;
    // 面向管理端和宿主展示的执行说明。
    private final String message;

    public TaskExecutionResult(long taskId, long targetId, String status, String filePath, String message) {
        this.taskId = taskId;
        this.targetId = targetId;
        this.status = Objects.requireNonNull(status, "status");
        this.filePath = filePath;
        this.message = message;
    }

    public long getTaskId() {
        return taskId;
    }

    public long getTargetId() {
        return targetId;
    }

    public String getStatus() {
        return status;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getMessage() {
        return message;
    }
}

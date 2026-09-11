package com.dtcomg.dio_up;

import java.util.Objects;

public final class PendingTask {
    // 部署任务 ID，用于日志和结果聚合。
    private final long taskId;
    // 设备任务目标 ID，所有 ACK、进度和结果均以此字段上报。
    private final long targetId;
    // 任务类型，例如 APK 安装或 FRP_COMMAND。
    private final String taskType;
    // 以下 package* 字段仅在 APK 类任务中存在。
    private final Long packageId;
    private final String packageOriginalFilename;
    private final Long packageFileSize;
    // 下载完成后必须与该 SHA-256 完全一致。
    private final String packageSha256;
    private final String packageName;
    private final String packageVersionName;
    private final Long packageVersionCode;
    private final Integer packageMinSdk;
    private final String packageSignatureSha256;
    private final String packageAbiList;
    // ISO-8601 过期时间，FRP 命令执行前会强制校验。
    private final String expiresAt;
    // 以下 frp* 字段组成服务端下发的加密 FRP 命令信封。
    private final String frpUid;
    private final String frpNonce;
    private final String frpEncryptedKey;
    private final String frpEncryptedPayload;
    private final String frpIv;
    private final String frpTag;
    private final String frpSignature;

    public PendingTask(
            long taskId,
            long targetId,
            String taskType,
            Long packageId,
            String packageOriginalFilename,
            Long packageFileSize,
            String packageSha256,
            String packageName,
            String packageVersionName,
            Long packageVersionCode,
            Integer packageMinSdk,
            String packageSignatureSha256,
            String packageAbiList,
            String expiresAt,
            String frpUid,
            String frpNonce,
            String frpEncryptedKey,
            String frpEncryptedPayload,
            String frpIv,
            String frpTag,
            String frpSignature
    ) {
        this.taskId = taskId;
        this.targetId = targetId;
        this.taskType = Objects.requireNonNull(taskType, "taskType");
        this.packageId = packageId;
        this.packageOriginalFilename = packageOriginalFilename;
        this.packageFileSize = packageFileSize;
        this.packageSha256 = packageSha256;
        this.packageName = packageName;
        this.packageVersionName = packageVersionName;
        this.packageVersionCode = packageVersionCode;
        this.packageMinSdk = packageMinSdk;
        this.packageSignatureSha256 = packageSignatureSha256;
        this.packageAbiList = packageAbiList;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.frpUid = frpUid;
        this.frpNonce = frpNonce;
        this.frpEncryptedKey = frpEncryptedKey;
        this.frpEncryptedPayload = frpEncryptedPayload;
        this.frpIv = frpIv;
        this.frpTag = frpTag;
        this.frpSignature = frpSignature;
    }

    public long getTaskId() {
        return taskId;
    }

    public long getTargetId() {
        return targetId;
    }

    public String getTaskType() {
        return taskType;
    }

    public Long getPackageId() {
        return packageId;
    }

    public String getPackageOriginalFilename() {
        return packageOriginalFilename;
    }

    public Long getPackageFileSize() {
        return packageFileSize;
    }

    public String getPackageSha256() {
        return packageSha256;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getPackageVersionName() {
        return packageVersionName;
    }

    public Long getPackageVersionCode() {
        return packageVersionCode;
    }

    public Integer getPackageMinSdk() {
        return packageMinSdk;
    }

    public String getPackageSignatureSha256() {
        return packageSignatureSha256;
    }

    public String getPackageAbiList() {
        return packageAbiList;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public String getFrpUid() {
        return frpUid;
    }

    public String getFrpNonce() {
        return frpNonce;
    }

    public String getFrpEncryptedKey() {
        return frpEncryptedKey;
    }

    public String getFrpEncryptedPayload() {
        return frpEncryptedPayload;
    }

    public String getFrpIv() {
        return frpIv;
    }

    public String getFrpTag() {
        return frpTag;
    }

    public String getFrpSignature() {
        return frpSignature;
    }
}

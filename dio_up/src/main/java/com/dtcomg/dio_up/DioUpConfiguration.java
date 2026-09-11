package com.dtcomg.dio_up;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.Objects;

/** Immutable configuration for the DioUp SDK. */
public final class DioUpConfiguration {
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 10_000L;
    // 该值只用于本地开发；生产环境必须通过 Builder 显式替换。
    private static final String DEFAULT_FRP_SIGNATURE_KEY = "dev-only-frp-master-key-change-me";

    // 服务端根地址，不包含具体 API 路径。
    private final String serverUrl;
    // 项目唯一标识，用于隔离本地凭证、任务和 FRP 状态。
    private final String projectKey;
    // 宿主可覆盖的设备展示名称。
    private final String customDeviceName;
    // 宿主提供的硬件名称；为空时使用 Build.MODEL。
    private final String hardwareName;
    // 决定 APK 与断点文件的落盘目录。
    private final DownloadDirectoryProvider downloadDirectoryProvider;
    // 宿主直接注入的安装器，优先级高于反射安装器。
    private final DioPackageInstaller packageInstaller;
    // 无法直接注入实例时，可按类名创建安装器。
    private final String reflectionInstallerClassName;
    // 向服务端声明当前宿主是否接受较低版本的 APK。
    private final boolean allowDowngrade;
    // 用于验证服务端 FRP 命令 HMAC 签名的共享密钥。
    private final String frpCommandSignatureKey;
    // 设备心跳由 SDK 自己调度；0 表示禁用心跳，但 initialize 仍会完成设备注册。
    private final long heartbeatIntervalMillis;
    // 可选的下载进度回调；为 null 时 SDK 静默下载，由宿主自行选择 UI 方式。
    private final DownloadProgressListener downloadProgressListener;
    // 仅供 SDK 运行时使用，必须是 applicationContext。
    private final Context applicationContext;

    public DioUpConfiguration(String serverUrl, String projectKey) {
        this(new Builder(serverUrl, projectKey));
    }

    private DioUpConfiguration(Builder builder) {
        this(
                builder.serverUrl,
                builder.projectKey,
                builder.customDeviceName,
                builder.hardwareName,
                builder.downloadDirectoryProvider,
                builder.packageInstaller,
                builder.reflectionInstallerClassName,
                builder.allowDowngrade,
                builder.frpCommandSignatureKey,
                builder.heartbeatIntervalMillis,
                builder.downloadProgressListener,
                builder.applicationContext
        );
    }

    private DioUpConfiguration(
            String serverUrl,
            String projectKey,
            String customDeviceName,
            String hardwareName,
            DownloadDirectoryProvider downloadDirectoryProvider,
            DioPackageInstaller packageInstaller,
            String reflectionInstallerClassName,
            boolean allowDowngrade,
            String frpCommandSignatureKey,
            long heartbeatIntervalMillis,
            DownloadProgressListener downloadProgressListener,
            Context applicationContext
    ) {
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        this.customDeviceName = customDeviceName;
        this.hardwareName = hardwareName;
        this.downloadDirectoryProvider = Objects.requireNonNull(downloadDirectoryProvider, "downloadDirectoryProvider");
        this.packageInstaller = packageInstaller;
        this.reflectionInstallerClassName = reflectionInstallerClassName;
        this.allowDowngrade = allowDowngrade;
        this.frpCommandSignatureKey = Objects.requireNonNull(frpCommandSignatureKey, "frpCommandSignatureKey");
        if (heartbeatIntervalMillis < 0) {
            throw new IllegalArgumentException("heartbeatIntervalMillis must be >= 0");
        }
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.downloadProgressListener = downloadProgressListener;
        this.applicationContext = applicationContext;
    }

    public static Builder builder(String serverUrl, String projectKey) {
        return new Builder(serverUrl, projectKey);
    }

    /** Returns a copy bound to the process application context. Used by SDK infrastructure. */
    public DioUpConfiguration withApplicationContext(Context context) {
        // 配置对象保持不可变，通过复制方式绑定运行上下文。
        Context appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        return new DioUpConfiguration(
                serverUrl,
                projectKey,
                customDeviceName,
                hardwareName,
                downloadDirectoryProvider,
                packageInstaller,
                reflectionInstallerClassName,
                allowDowngrade,
                frpCommandSignatureKey,
                heartbeatIntervalMillis,
                downloadProgressListener,
                appContext
        );
    }

    /** Returns a copy with the device display name replaced. Used by runtime name overrides. */
    public DioUpConfiguration withCustomDeviceName(String customDeviceName) {
        // 单例运行时设置的名称优先于初始化配置中的名称，其余字段原样保留。
        return new DioUpConfiguration(
                serverUrl,
                projectKey,
                customDeviceName,
                hardwareName,
                downloadDirectoryProvider,
                packageInstaller,
                reflectionInstallerClassName,
                allowDowngrade,
                frpCommandSignatureKey,
                heartbeatIntervalMillis,
                downloadProgressListener,
                applicationContext
        );
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getCustomDeviceName() {
        return customDeviceName;
    }

    public String getHardwareName() {
        return hardwareName;
    }

    public DownloadDirectoryProvider getDownloadDirectoryProvider() {
        return downloadDirectoryProvider;
    }

    public DioPackageInstaller getPackageInstaller() {
        return packageInstaller;
    }

    public String getReflectionInstallerClassName() {
        return reflectionInstallerClassName;
    }

    public boolean isAllowDowngrade() {
        return allowDowngrade;
    }

    public String getFrpCommandSignatureKey() {
        return frpCommandSignatureKey;
    }

    public long getHeartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    public DownloadProgressListener getDownloadProgressListener() {
        return downloadProgressListener;
    }

    public Context getApplicationContext() {
        return applicationContext;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DioUpConfiguration)) {
            return false;
        }
        DioUpConfiguration that = (DioUpConfiguration) other;
        return allowDowngrade == that.allowDowngrade
                && heartbeatIntervalMillis == that.heartbeatIntervalMillis
                && Objects.equals(serverUrl, that.serverUrl)
                && Objects.equals(projectKey, that.projectKey)
                && Objects.equals(customDeviceName, that.customDeviceName)
                && Objects.equals(hardwareName, that.hardwareName)
                && Objects.equals(downloadDirectoryProvider, that.downloadDirectoryProvider)
                && Objects.equals(packageInstaller, that.packageInstaller)
                && Objects.equals(reflectionInstallerClassName, that.reflectionInstallerClassName)
                && Objects.equals(frpCommandSignatureKey, that.frpCommandSignatureKey)
                && Objects.equals(downloadProgressListener, that.downloadProgressListener)
                && Objects.equals(applicationContext, that.applicationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                serverUrl,
                projectKey,
                customDeviceName,
                hardwareName,
                downloadDirectoryProvider,
                packageInstaller,
                reflectionInstallerClassName,
                allowDowngrade,
                frpCommandSignatureKey,
                heartbeatIntervalMillis,
                downloadProgressListener,
                applicationContext
        );
    }

    public static final class Builder {
        private final String serverUrl;
        private final String projectKey;
        private String customDeviceName;
        private String hardwareName;
        // 默认目录位于公共外部存储的 dioup 目录。
        private DownloadDirectoryProvider downloadDirectoryProvider =
                context -> new File(Environment.getExternalStorageDirectory(), "dioup");
        private DioPackageInstaller packageInstaller;
        private String reflectionInstallerClassName;
        private boolean allowDowngrade = true;
        private String frpCommandSignatureKey = DEFAULT_FRP_SIGNATURE_KEY;
        private long heartbeatIntervalMillis = DEFAULT_HEARTBEAT_INTERVAL_MILLIS;
        private DownloadProgressListener downloadProgressListener;
        private Context applicationContext;

        private Builder(String serverUrl, String projectKey) {
            this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
            this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        }

        public Builder customDeviceName(String value) {
            customDeviceName = value;
            return this;
        }

        public Builder hardwareName(String value) {
            hardwareName = value;
            return this;
        }

        public Builder downloadDirectoryProvider(DownloadDirectoryProvider value) {
            downloadDirectoryProvider = Objects.requireNonNull(value, "downloadDirectoryProvider");
            return this;
        }

        public Builder packageInstaller(DioPackageInstaller value) {
            packageInstaller = value;
            return this;
        }

        public Builder reflectionInstallerClassName(String value) {
            reflectionInstallerClassName = value;
            return this;
        }

        public Builder allowDowngrade(boolean value) {
            allowDowngrade = value;
            return this;
        }

        public Builder frpCommandSignatureKey(String value) {
            frpCommandSignatureKey = Objects.requireNonNull(value, "frpCommandSignatureKey");
            return this;
        }

        /** Sets the SDK-managed heartbeat interval in milliseconds. Use 0 to disable heartbeats. */
        public Builder heartbeatIntervalMillis(long value) {
            if (value < 0) {
                throw new IllegalArgumentException("heartbeatIntervalMillis must be >= 0");
            }
            heartbeatIntervalMillis = value;
            return this;
        }

        /**
         * Sets a listener that will receive download progress callbacks so the host
         * can show its own progress UI (e.g. a system AlertDialog with a ProgressBar).
         */
        public Builder downloadProgressListener(DownloadProgressListener value) {
            downloadProgressListener = value;
            return this;
        }

        public DioUpConfiguration build() {
            // 所有可选项在此冻结，之后只能通过复制生成新配置。
            return new DioUpConfiguration(this);
        }
    }
}

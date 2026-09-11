package com.dtcomg.dio_up.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.RegistrationState;

import java.io.File;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.UUID;

public final class DioUpStore {
    // 注册凭证、最小运行配置和 FRP 恢复状态共用同一私有偏好文件。
    private final SharedPreferences preferences;
    // 所有项目级键都带此前缀，防止同一宿主中的多个项目相互覆盖。
    private final String projectKey;

    public DioUpStore(Context context, String projectKey) {
        preferences = context.getSharedPreferences("dio_up", Context.MODE_PRIVATE);
        this.projectKey = projectKey;
    }

    public String installationId() {
        // installation_id 不随项目变化，表示当前 App 安装实例。
        String existing = preferences.getString("installation_id", null);
        if (existing != null) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        preferences.edit().putString("installation_id", created).apply();
        return created;
    }

    public RegistrationState read() {
        // ID 和密钥必须同时有效，缺少任意一项都视为未注册。
        long id = preferences.getLong(key("project_device_id"), 0);
        String secret = preferences.getString(key("device_secret"), null);
        return id > 0 && secret != null ? new RegistrationState(id, secret) : null;
    }

    public void write(RegistrationState state) {
        preferences.edit()
                .putLong(key("project_device_id"), state.getProjectDeviceId())
                .putString(key("device_secret"), state.getDeviceSecret())
                .apply();
    }

    /** 清除本项目的注册凭证；服务端设备被删除后调用，使下次 register 走完整注册流程。 */
    public void clearRegistration() {
        preferences.edit()
                .remove(key("project_device_id"))
                .remove(key("device_secret"))
                .apply();
    }

    public String completedTaskPath(long targetId) {
        return preferences.getString(key("task_" + targetId + "_file"), null);
    }

    public void markTaskCompleted(long targetId, File file) {
        preferences.edit()
                .putString(key("task_" + targetId + "_file"), file.getAbsolutePath())
                .apply();
    }

    public void writeRuntimeConfig(DioUpConfiguration config) {
        // 仅保存后台组件恢复所需的最小字段，不持久化宿主回调实例。
        preferences.edit()
                .putString("runtime_project_key", config.getProjectKey())
                .putString("runtime_server_url", config.getServerUrl())
                .putLong("runtime_heartbeat_interval_millis", config.getHeartbeatIntervalMillis())
                .apply();
    }

    public void writeFrpState(String uid, String content, String version, boolean stopped) {
        // 前台服务被系统重建时依赖这组字段恢复隧道。
        preferences.edit()
                .putString(key("frp_uid"), uid)
                .putString(key("frp_content"), content)
                .putString(key("frp_version"), version)
                .putBoolean(key("frp_stopped"), stopped)
                .apply();
    }

    public void markFrpStopped(String uid) {
        preferences.edit()
                .putString(key("frp_uid"), uid)
                .putBoolean(key("frp_stopped"), true)
                .apply();
    }

    public long localFrpConfigVersion() { return preferences.getLong(key("frp_config_version"), 0); }

    public void markManagedFrpConfig(long version, long expiresAt) {
        preferences.edit().putLong(key("frp_config_version"), version)
                .putLong(key("frp_expires_at"), expiresAt).putBoolean(key("frp_stop_requested"), false).apply();
    }

    public boolean isFrpStopRequested() { return preferences.getBoolean(key("frp_stop_requested"), false); }

    public void clearFrpStopRequested() { preferences.edit().remove(key("frp_stop_requested")).apply(); }

    public void clearManagedFrp(boolean keepStopRequested) {
        SharedPreferences.Editor editor = preferences.edit()
                .remove(key("frp_uid")).remove(key("frp_content")).remove(key("frp_version"))
                .remove(key("frp_stopped")).remove(key("frp_config_version")).remove(key("frp_expires_at"));
        if (keepStopRequested) editor.putBoolean(key("frp_stop_requested"), true);
        else editor.remove(key("frp_stop_requested"));
        editor.apply();
    }

    public FrpState readFrpState() {
        String uid = preferences.getString(key("frp_uid"), null);
        String content = preferences.getString(key("frp_content"), null);
        if (uid == null || content == null) {
            return null;
        }
        String version = preferences.getString(key("frp_version"), FrpRuntime.VERSION);
        boolean stopped = preferences.getBoolean(key("frp_stopped"), true);
        return new FrpState(uid, content, version, stopped);
    }

    public String commandPublicKey() {
        // RSA 密钥对只生成一次；公钥注册到服务端，私钥始终保留在设备本地。
        String existing = preferences.getString("command_public_key", null);
        if (existing != null) {
            return existing;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String publicKey = Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP);
            preferences.edit()
                    .putString("command_public_key", publicKey)
                    .putString(
                            "command_private_key",
                            Base64.encodeToString(pair.getPrivate().getEncoded(), Base64.NO_WRAP)
                    )
                    .apply();
            return publicKey;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot create command key pair", exception);
        }
    }

    public PrivateKey commandPrivateKey() {
        // 兼容公钥尚未初始化的路径，先触发完整密钥对生成。
        String encoded = preferences.getString("command_private_key", null);
        if (encoded == null) {
            commandPublicKey();
            encoded = preferences.getString("command_private_key", null);
        }
        if (encoded == null) {
            throw new IllegalStateException("Command private key missing");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP))
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot read command private key", exception);
        }
    }

    public boolean hasSeenNonce(String nonce) {
        // nonce 按项目隔离，用于拒绝重复下发的 FRP 命令。
        return preferences.getBoolean(key("frp_nonce_" + nonce), false);
    }

    public void markNonceSeen(String nonce) {
        preferences.edit().putBoolean(key("frp_nonce_" + nonce), true).apply();
    }

    public static RuntimeConfig readRuntimeConfig(Context context) {
        // Worker、Service 和开机广播都可在 SDK 尚未显式初始化时调用该方法。
        SharedPreferences preferences =
                context.getSharedPreferences("dio_up", Context.MODE_PRIVATE);
        String projectKey = preferences.getString("runtime_project_key", null);
        String serverUrl = preferences.getString("runtime_server_url", null);
        long heartbeatIntervalMillis = preferences.getLong(
                "runtime_heartbeat_interval_millis",
                DioUpConfiguration.DEFAULT_HEARTBEAT_INTERVAL_MILLIS
        );
        return projectKey != null && serverUrl != null
                ? new RuntimeConfig(serverUrl, projectKey, heartbeatIntervalMillis)
                : null;
    }

    private String key(String suffix) {
        return projectKey + "_" + suffix;
    }

    public static final class RuntimeConfig {
        private final String serverUrl;
        private final String projectKey;
        private final long heartbeatIntervalMillis;

        RuntimeConfig(String serverUrl, String projectKey, long heartbeatIntervalMillis) {
            this.serverUrl = serverUrl;
            this.projectKey = projectKey;
            this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public String getProjectKey() {
            return projectKey;
        }

        public long getHeartbeatIntervalMillis() {
            return heartbeatIntervalMillis;
        }
    }

    public static final class FrpState {
        private final String uid;
        private final String content;
        private final String version;
        private final boolean stopped;

        FrpState(String uid, String content, String version, boolean stopped) {
            this.uid = uid;
            this.content = content;
            this.version = version;
            this.stopped = stopped;
        }

        public String getUid() {
            return uid;
        }

        public String getContent() {
            return content;
        }

        public String getVersion() {
            return version;
        }

        public boolean isStopped() {
            return stopped;
        }
    }
}

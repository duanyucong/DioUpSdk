package com.dtcomg.dio_up.internal;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.dtcomg.dio_up.DeviceInfo;
import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.DioUpLocation;
import com.dtcomg.dio_up.HeartbeatState;
import com.dtcomg.dio_up.PendingTask;
import com.dtcomg.dio_up.RegistrationState;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class DioUpClient {
    private static final String TAG = "DioUp.Heartbeat";
    // 所有设备端接口均使用 UTF-8 JSON 请求体。
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // OkHttpClient 可从测试或宿主注入，以复用连接池和替换网络实现。
    private final OkHttpClient client;

    public DioUpClient() {
        this(new OkHttpClient());
    }

    public DioUpClient(OkHttpClient client) {
        this.client = client;
    }

    public RegisterResponse register(DioUpConfiguration config, String installationId) {
        return json(() -> {
        // 注册请求只上报设备身份、安装能力和命令加密公钥；位置由独立接口上报。
        Context context = requireContext(config);
        DeviceInfo deviceInfo = DeviceInfo.collect(context);
        JSONObject payload = new JSONObject()
                .put("projectKey", config.getProjectKey())
                .put("installationId", installationId)
                .put("customDeviceName", config.getCustomDeviceName())
                .put(
                        "hardwareName",
                        config.getHardwareName() == null ? Build.MODEL : config.getHardwareName()
                )
                .put("installCapabilities", installCapabilities(config))
                .put(
                        "commandPublicKey",
                        new DioUpStore(context, config.getProjectKey()).commandPublicKey()
                )
                .put("deviceInfo", new JSONObject(toDeviceInfoJson(deviceInfo)));

        // 调试：打印注册请求体，便于排查 deviceInfo 上报链路。生产环境可在 ProGuard 中移除。
        String body = payload.toString();
        Log.i(TAG, "DioUp.Register payload (length=" + body.length() + "): " + body);
        JSONObject data = post(config.getServerUrl(), "/api/device/register", body);
        Log.i(TAG, "DioUp.Register response: " + data);
        return new RegisterResponse(
                data.getLong("projectDeviceId"),
                nullableString(data, "deviceSecret")
        );
        });
    }

    public HeartbeatState heartbeat(DioUpConfiguration config, RegistrationState state) {
        return json(() -> {
        // 心跳身份由项目设备 ID 与设备密钥共同确认。
        String body = new JSONObject()
                .put("projectDeviceId", state.getProjectDeviceId())
                .put("deviceSecret", state.getDeviceSecret())
                .toString();
        Log.i(TAG, "Heartbeat request deviceId=" + state.getProjectDeviceId());
        JSONObject data = post(config.getServerUrl(), "/api/device/heartbeat", body);
        HeartbeatState result = new HeartbeatState(
                data.getString("onlineStatus"),
                toPendingTasks(data.optJSONArray("pendingTasks")),
                data.optLong("configVersion", 0),
                parseTimestamp(data.optString("configExpiresAt", null)),
                data.optBoolean("pendingStop", false)
        );
        Log.i(TAG, "Heartbeat response onlineStatus=" + result.getOnlineStatus()
                + " pendingTasks=" + result.getPendingTasks().size()
                + " configVersion=" + result.getConfigVersion());
        return result;
        });
    }

    /** 每次 SDK 启动时把最新的设备硬件信息同步到服务端，保证 profile 始终最新。 */
    public void reportProfile(DioUpConfiguration config, RegistrationState state, DeviceInfo deviceInfo) {
        json(() -> {
            String body = new JSONObject()
                    .put("projectDeviceId", state.getProjectDeviceId())
                    .put("deviceSecret", state.getDeviceSecret())
                    .put("deviceInfo", new JSONObject(toDeviceInfoJson(deviceInfo)))
                    .toString();
            Log.i(TAG, "DioUp.profile update payload (length=" + body.length() + ")");
            JSONObject data = post(config.getServerUrl(), "/api/device/profile", body);
            Log.i(TAG, "DioUp.profile update response: " + data);
            return null;
        });
    }

    /** 单独修改设备展示名称；服务端仅在名称变化时写库。 */
    public void updateCustomName(DioUpConfiguration config, RegistrationState state, String customDeviceName) {
        json(() -> {
            String body = new JSONObject()
                    .put("projectDeviceId", state.getProjectDeviceId())
                    .put("deviceSecret", state.getDeviceSecret())
                    .put("customDeviceName", customDeviceName)
                    .toString();
            Log.i(TAG, "DioUp.name update payload: " + body);
            post(config.getServerUrl(), "/api/device/name", body);
            return null;
        });
    }

    public JSONObject fetchFrpConfig(DioUpConfiguration config, RegistrationState state) {
        Request request = new Request.Builder()
                .url(trimTrailingSlash(config.getServerUrl()) + "/api/device/config")
                .header("X-Device-ID", String.valueOf(state.getProjectDeviceId()))
                .header("X-Device-Secret", state.getDeviceSecret())
                .get().build();
        try (Response response = client.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("FRP config HTTP " + response.code());
            JSONObject root = new JSONObject(text);
            if (!"SUCCESS".equals(root.getString("code"))) throw new IllegalStateException(root.optString("message"));
            return root.getJSONObject("data");
        } catch (IOException | JSONException exception) {
            throw new IllegalStateException("Cannot fetch FRP config", exception);
        }
    }

    public void confirmFrpStop(DioUpConfiguration config, RegistrationState state) {
        json(() -> {
            post(config.getServerUrl(), "/api/device/stop-confirm", new JSONObject()
                    .put("projectDeviceId", state.getProjectDeviceId())
                    .put("deviceSecret", state.getDeviceSecret()).toString());
            return null;
        });
    }

    public void reportLocation(
            DioUpConfiguration config,
            RegistrationState state,
            DioUpLocation location
    ) {
        json(() -> {
            // 位置上报复用注册凭证，服务端只覆盖设备的最后位置快照。
            String body = new JSONObject()
                    .put("projectDeviceId", state.getProjectDeviceId())
                    .put("deviceSecret", state.getDeviceSecret())
                    .put("latitude", location.getLatitude())
                    .put("longitude", location.getLongitude())
                    .put("accuracyMeters", location.getAccuracyMeters())
                    .put(
                            "capturedAt",
                            location.getCapturedAt() == null
                                    ? null
                                    : formatTimestamp(location.getCapturedAt())
                    )
                    .toString();
            post(config.getServerUrl(), "/api/device/location", body);
            return null;
        });
    }

    public void ack(
            DioUpConfiguration config,
            RegistrationState state,
            long targetId
    ) {
        ack(config, state, targetId, "ACK");
    }

    public void ack(
            DioUpConfiguration config,
            RegistrationState state,
            long targetId,
            String message
    ) {
        // ACK 只表示设备已收到任务，不代表任务已执行成功。
        Log.i(TAG, "ACK targetId=" + targetId + " message=" + message);
        report(config, state, "/api/device/tasks/ack", targetId, 0, null, message);
    }

    public void progress(
            DioUpConfiguration config,
            RegistrationState state,
            long targetId,
            int progressPercent,
            String message
    ) {
        // 下载和命令执行过程通过统一接口增量上报进度。
        report(
                config,
                state,
                "/api/device/tasks/progress",
                targetId,
                progressPercent,
                null,
                message
        );
    }

    public void result(
            DioUpConfiguration config,
            RegistrationState state,
            long targetId,
            int progressPercent,
            String result,
            String message
    ) {
        // 最终结果必须携带明确的 SUCCEEDED 或 FAILED 状态。
        report(
                config,
                state,
                "/api/device/tasks/result",
                targetId,
                progressPercent,
                result,
                message
        );
    }

    public Response downloadPackage(
            DioUpConfiguration config,
            RegistrationState state,
            PendingTask task,
            long rangeStart
    ) {
        return json(() -> {
        // 下载接口仍以 POST 请求携带设备凭证，避免凭证出现在 URL 中。
        String body = new JSONObject()
                .put("projectDeviceId", state.getProjectDeviceId())
                .put("deviceSecret", state.getDeviceSecret())
                .put("targetId", task.getTargetId())
                .put("packageId", task.getPackageId())
                .toString();
        Request.Builder request = new Request.Builder()
                .url(trimTrailingSlash(config.getServerUrl())
                        + "/api/device/packages/"
                        + task.getPackageId()
                        + "/download")
                .post(RequestBody.create(body, JSON));
        if (rangeStart > 0) {
            // 本地已有 .part 文件时请求剩余字节，服务端应返回 206。
            request.header("Range", "bytes=" + rangeStart + "-");
        }
        Log.i(TAG, "downloadPackage targetId=" + task.getTargetId()
                + " packageId=" + task.getPackageId()
                + " rangeStart=" + rangeStart);
        try {
            Response response = client.newCall(request.build()).execute();
            Log.i(TAG, "downloadPackage response code=" + response.code()
                    + " targetId=" + task.getTargetId());
            return response;
        } catch (IOException exception) {
            Log.e(TAG, "downloadPackage request failed targetId=" + task.getTargetId(), exception);
            throw new IllegalStateException("DioUp download request failed", exception);
        }
        });
    }

    private void report(
            DioUpConfiguration config,
            RegistrationState state,
            String path,
            long targetId,
            int progressPercent,
            String result,
            String message
    ) {
        json(() -> {
        // 三类上报共享相同身份与目标字段，仅路径和结果内容不同。
        String body = new JSONObject()
                .put("projectDeviceId", state.getProjectDeviceId())
                .put("deviceSecret", state.getDeviceSecret())
                .put("targetId", targetId)
                .put("progressPercent", progressPercent)
                .put("result", result)
                .put("message", message)
                .toString();
        post(config.getServerUrl(), path, body);
        return null;
        });
    }

    private JSONObject post(String serverUrl, String path, String body) {
        // 每个响应都由此方法统一检查 HTTP 状态和业务状态码。
        Request request = new Request.Builder()
                .url(trimTrailingSlash(serverUrl) + path)
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                // 404 表示设备在服务端已不存在（通常是被管理端删除）：
                // 调用方应清除本地凭证并重新注册，而不是无限重试。
                if (response.code() == 404) {
                    throw new DeviceNotFoundException(
                            "DioUp device not found on server (HTTP 404)"
                    );
                }
                throw new IllegalStateException(
                        "DioUp server returned HTTP " + response.code()
                );
            }
            JSONObject root = new JSONObject(text);
            // HTTP 2xx 仍可能是业务失败，必须继续检查统一响应 code。
            if (!"SUCCESS".equals(root.getString("code"))) {
                throw new IllegalStateException(
                        root.optString("message", "DioUp request failed")
                );
            }
            // data 可为 null（Void 接口）、对象或基本类型（如 name 接口的 Boolean）；
            // 非对象 data 统一归一化为空对象，调用方忽略响应体或只读对象字段。
            Object data = root.opt("data");
            return data instanceof JSONObject ? (JSONObject) data : new JSONObject();
        } catch (IOException | JSONException exception) {
            throw new IllegalStateException("DioUp request failed", exception);
        }
    }

    private String installCapabilities(DioUpConfiguration config) {
        return json(() -> {
        // Android 8.0 起系统提供“允许未知来源安装”的运行状态。
        boolean canRequest = true;
        if (config.getApplicationContext() != null && Build.VERSION.SDK_INT >= 26) {
            canRequest = config.getApplicationContext()
                    .getPackageManager()
                    .canRequestPackageInstalls();
        }
        return new JSONObject()
                .put("customInstaller", config.getPackageInstaller() != null)
                .put("reflectionInstaller", config.getReflectionInstallerClassName() != null)
                .put("standardIntent", true)
                .put("canRequestPackageInstalls", canRequest)
                .put("allowDowngrade", config.isAllowDowngrade())
                .toString();
        });
    }

    private static String toDeviceInfoJson(DeviceInfo info) {
        // 手动拼接 JSON 字符串以避免 JSONObject.put 抛 checked JSONException；
        // DeviceInfo.collect 已经把可空字段归一化为 null，null 会被序列化为 JSON null。
        return '{' +
                // 硬件身份
                "\"manufacturer\":" + jsonString(info.getManufacturer()) + ',' +
                "\"model\":" + jsonString(info.getModel()) + ',' +
                "\"brand\":" + jsonString(info.getBrand()) + ',' +
                "\"device\":" + jsonString(info.getDevice()) + ',' +
                "\"product\":" + jsonString(info.getProduct()) + ',' +
                "\"hardware\":" + jsonString(info.getHardware()) + ',' +
                "\"board\":" + jsonString(info.getBoard()) + ',' +
                "\"bootloader\":" + jsonString(info.getBootloader()) + ',' +
                "\"serial\":" + jsonString(info.getSerial()) + ',' +
                "\"androidId\":" + jsonString(info.getAndroidId()) + ',' +
                // 软件版本
                "\"osVersion\":" + jsonString(info.getOsVersion()) + ',' +
                "\"sdkInt\":" + jsonInt(info.getSdkInt()) + ',' +
                "\"releaseSdkInt\":" + jsonInt(info.getReleaseSdkInt()) + ',' +
                "\"securityPatch\":" + jsonString(info.getSecurityPatch()) + ',' +
                "\"buildFingerprint\":" + jsonString(info.getBuildFingerprint()) + ',' +
                "\"buildId\":" + jsonString(info.getBuildId()) + ',' +
                "\"buildDisplayId\":" + jsonString(info.getBuildDisplayId()) + ',' +
                "\"buildTimeMillis\":" + jsonLong(info.getBuildTimeMillis()) + ',' +
                "\"buildType\":" + jsonString(info.getBuildType()) + ',' +
                "\"buildTags\":" + jsonString(info.getBuildTags()) + ',' +
                // 运行时
                "\"abi\":" + jsonString(info.getAbi()) + ',' +
                "\"abi2\":" + jsonString(info.getAbi2()) + ',' +
                "\"cpuAbiCount\":" + jsonInt(info.getCpuAbiCount()) + ',' +
                "\"totalMemoryBytes\":" + jsonLong(info.getTotalMemoryBytes()) + ',' +
                "\"availableMemoryBytes\":" + jsonLong(info.getAvailableMemoryBytes()) + ',' +
                "\"totalStorageBytes\":" + jsonLong(info.getTotalStorageBytes()) + ',' +
                "\"availableStorageBytes\":" + jsonLong(info.getAvailableStorageBytes()) + ',' +
                // 屏幕
                "\"screenWidthPixels\":" + jsonInt(info.getScreenWidthPixels()) + ',' +
                "\"screenHeightPixels\":" + jsonInt(info.getScreenHeightPixels()) + ',' +
                "\"screenDensity\":" + jsonFloat(info.getScreenDensity()) + ',' +
                "\"screenXdpi\":" + jsonFloat(info.getScreenXdpi()) + ',' +
                "\"screenYdpi\":" + jsonFloat(info.getScreenYdpi()) + ',' +
                "\"screenDensityDpi\":" + jsonInt(info.getScreenDensityDpi()) + ',' +
                // 电池
                "\"batteryLevel\":" + jsonInt(info.getBatteryLevel()) + ',' +
                "\"batteryStatus\":" + jsonString(info.getBatteryStatus()) + ',' +
                "\"batteryHealth\":" + jsonString(info.getBatteryHealth()) + ',' +
                // 网络
                "\"networkType\":" + jsonString(info.getNetworkType()) + ',' +
                "\"networkConnected\":" + info.isNetworkConnected() + ',' +
                "\"wifiSsid\":" + jsonString(info.getWifiSsid()) + ',' +
                "\"timezoneId\":" + jsonString(info.getTimezoneId()) + ',' +
                // 宿主 App
                "\"appPackageName\":" + jsonString(info.getAppPackageName()) + ',' +
                "\"appVersionName\":" + jsonString(info.getAppVersionName()) + ',' +
                "\"appVersionCode\":" + jsonLong(info.getAppVersionCode()) + ',' +
                "\"appFirstInstallTime\":" + jsonString(info.getAppFirstInstallTime()) + ',' +
                "\"appLastUpdateTime\":" + jsonString(info.getAppLastUpdateTime()) +
                '}';
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        // 简易转义：覆盖 JSON 必须转义的字符即可。
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String jsonInt(Integer value) {
        return value == null ? "null" : value.toString();
    }

    private static String jsonLong(Long value) {
        return value == null ? "null" : value.toString();
    }

    private static String jsonFloat(Float value) {
        return value == null ? "null" : value.toString();
    }

    private static List<PendingTask> toPendingTasks(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        return json(() -> {
        // 服务端字段允许为空，因此逐项使用 nullable* 辅助方法读取。
        List<PendingTask> tasks = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            tasks.add(new PendingTask(
                    item.getLong("taskId"),
                    item.getLong("targetId"),
                    item.getString("taskType"),
                    nullableLong(item, "packageId"),
                    nullableString(item, "packageOriginalFilename"),
                    nullableLong(item, "packageFileSize"),
                    nullableString(item, "packageSha256"),
                    nullableString(item, "packageName"),
                    nullableString(item, "packageVersionName"),
                    nullableLong(item, "packageVersionCode"),
                    nullableInteger(item, "packageMinSdk"),
                    nullableString(item, "packageSignatureSha256"),
                    nullableString(item, "packageAbiList"),
                    item.getString("expiresAt"),
                    nullableString(item, "frpUid"),
                    nullableString(item, "frpNonce"),
                    nullableString(item, "frpEncryptedKey"),
                    nullableString(item, "frpEncryptedPayload"),
                    nullableString(item, "frpIv"),
                    nullableString(item, "frpTag"),
                    nullableString(item, "frpSignature")
            ));
        }
        return tasks;
        });
    }

    private static Long nullableLong(JSONObject object, String key) throws JSONException {
        return object.isNull(key) ? null : object.getLong(key);
    }

    private static Integer nullableInteger(JSONObject object, String key) throws JSONException {
        return object.isNull(key) ? null : object.getInt(key);
    }

    private static String nullableString(JSONObject object, String key) {
        if (object.isNull(key)) {
            return null;
        }
        String value = object.optString(key, "");
        return value.trim().isEmpty() ? null : value;
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String formatTimestamp(Date value) {
        // 协议统一使用 UTC ISO-8601，避免设备本地时区影响注册数据。
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                Locale.US
        );
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(value);
    }

    private static long parseTimestamp(String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) return 0;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
            Date parsed = format.parse(value);
            return parsed == null ? 0 : parsed.getTime();
        } catch (Exception ignored) { return 0; }
    }

    private static <T> T json(JsonOperation<T> operation) {
        try {
            return operation.run();
        } catch (JSONException exception) {
            // 不把 Android 的受检 JSONException 泄漏到 SDK 公开 API。
            throw new IllegalStateException("Invalid DioUp JSON payload", exception);
        }
    }

    @FunctionalInterface
    private interface JsonOperation<T> {
        T run() throws JSONException;
    }

    private static android.content.Context requireContext(DioUpConfiguration config) {
        if (config.getApplicationContext() == null) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        return config.getApplicationContext();
    }

    public static final class RegisterResponse {
        private final long projectDeviceId;
        private final String deviceSecret;

        public RegisterResponse(long projectDeviceId, String deviceSecret) {
            this.projectDeviceId = projectDeviceId;
            this.deviceSecret = deviceSecret;
        }

        public long getProjectDeviceId() {
            return projectDeviceId;
        }

        public String getDeviceSecret() {
            return deviceSecret;
        }
    }
}

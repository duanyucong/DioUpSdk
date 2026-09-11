package com.dtcomg.dio_up;

import android.content.Context;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.dtcomg.dio_up.internal.DioUpClient;
import com.dtcomg.dio_up.internal.DioUpDao;
import com.dtcomg.dio_up.internal.DioUpDatabase;
import com.dtcomg.dio_up.internal.DioUpDownloader;
import com.dtcomg.dio_up.internal.DeviceNotFoundException;
import com.dtcomg.dio_up.internal.DioUpFrpCommandExecutor;
import com.dtcomg.dio_up.internal.DioUpStore;
import com.dtcomg.dio_up.internal.DioUpTaskWorker;
import com.dtcomg.dio_up.internal.ManagedFrpSynchronizer;
import com.dtcomg.dio_up.internal.TaskRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Entry point for the DioUp device-management SDK. */
public final class DioUp {
    private static final String TAG = "DioUp";
    // 进程内只允许存在一份配置；AtomicReference 同时保证初始化过程的线程安全。
    private static final AtomicReference<DioUpConfiguration> CONFIGURATION = new AtomicReference<>();
    // 宿主可在运行时单独设置的设备展示名称；未设置时使用 DioUpConfiguration 中的名称。
    private static final AtomicReference<String> CUSTOM_DEVICE_NAME = new AtomicReference<>();
    // 最后一次成功同步到服务端的名称，用于跳过无变化的重复写库。
    private static final AtomicReference<String> SYNCED_CUSTOM_DEVICE_NAME = new AtomicReference<>();
    private static final Object REGISTRATION_LOCK = new Object();
    private static final ScheduledExecutorService LIFECYCLE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dio-up-lifecycle");
                // 非 daemon：保证宿主 App 进入后台后心跳线程不被 JVM 回收。
                thread.setDaemon(false);
                return thread;
            });
    // 名称同步独立于注册/心跳线程，避免慢网络阻塞生命周期调度。
    private static final ExecutorService NAME_SYNC_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dio-up-name-sync");
                thread.setDaemon(false);
                return thread;
            });

    private DioUp() {
    }

    /** Initializes the SDK once for the current process. */
    public static void initialize(Context context, DioUpConfiguration config) {
        // 本地调试允许 HTTP；生产环境建议在 DioUpConfiguration 中自行校验或网关终结 TLS。
        if (config.getProjectKey().trim().isEmpty()) {
            throw new IllegalArgumentException("projectKey must not be blank");
        }

        // 始终保存 applicationContext，避免 SDK 长期持有 Activity 或 Service。
        DioUpConfiguration normalized = config.withApplicationContext(context);
        DioUpConfiguration existing = CONFIGURATION.get();
        // 首次调用尝试原子写入；并发失败时读取另一个线程已经写入的配置。
        boolean initializedNow = false;
        if (existing == null && CONFIGURATION.compareAndSet(null, normalized)) {
            existing = normalized;
            initializedNow = true;
        } else if (existing == null) {
            existing = CONFIGURATION.get();
        }
        // 同一配置允许幂等初始化，不同配置则拒绝覆盖已有运行状态。
        if (!normalized.equals(existing)) {
            throw new IllegalStateException(
                    "DioUp has already been initialized with a different configuration"
            );
        }

        // WorkManager 或开机广播重建进程时，只需从本地恢复最小运行配置。
        Context appContext = requireApplicationContext(normalized);
        new DioUpStore(appContext, normalized.getProjectKey()).writeRuntimeConfig(normalized);
        if (initializedNow) {
            startManagedLifecycle(normalized);
        }
    }

    public static boolean isInitialized() {
        return CONFIGURATION.get() != null;
    }

    public static DioUpConfiguration configuration() {
        DioUpConfiguration config = CONFIGURATION.get();
        if (config == null) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        return config;
    }

    /** Performs registration synchronously. Call from a background thread. */
    public static RegistrationState register() {
        DioUpConfiguration config = configuration();
        Context context = requireApplicationContext(config);
        synchronized (REGISTRATION_LOCK) {
            DioUpStore store = new DioUpStore(context, config.getProjectKey());
            // 已注册的安装直接复用本地凭证，避免并发初始化产生重复设备。
            RegistrationState existing = store.read();
            if (existing != null) {
                Log.i(TAG, "DioUp.register cache hit projectDeviceId=" + existing.getProjectDeviceId()
                    + ", refreshing deviceInfo via /api/device/profile");
                // 每次启动都同步刷新一次硬件信息，让服务端 profile 始终是最新值。
                boolean deviceRemoved = false;
                try {
                    new DioUpClient().reportProfile(
                            config, existing, DeviceInfo.collect(context));
                } catch (DeviceNotFoundException e) {
                    // 服务端设备已被删除：清除本地凭证，直接走完整注册重新登记。
                    deviceRemoved = true;
                } catch (Throwable t) {
                    Log.w(TAG, "DioUp.profile refresh failed; will retry on next init", t);
                }
                // 单独设置的名称尚未同步成功时，借注册机会补发。
                String runtimeName = CUSTOM_DEVICE_NAME.get();
                if (!deviceRemoved && runtimeName != null && !runtimeName.equals(SYNCED_CUSTOM_DEVICE_NAME.get())) {
                    try {
                        new DioUpClient().updateCustomName(config, existing, runtimeName);
                        SYNCED_CUSTOM_DEVICE_NAME.set(runtimeName);
                    } catch (DeviceNotFoundException e) {
                        deviceRemoved = true;
                    } catch (Throwable t) {
                        Log.w(TAG, "DioUp.name sync failed during register; will retry", t);
                    }
                }
                if (!deviceRemoved) {
                    return existing;
                }
                // 服务端不再识别该设备（已被管理端删除）：清除本地凭证后重新注册，
                // 否则 register 永远走 cache hit，设备将无法重新出现在设备列表。
                Log.w(TAG, "DioUp device removed on server; clearing credentials and re-registering");
                store.clearRegistration();
            }
            Log.i(TAG, "DioUp.register cache miss, posting /api/device/register with deviceInfo");

            // 单例单独设置的名称优先于初始化配置中的名称。
            String runtimeName = CUSTOM_DEVICE_NAME.get();
            DioUpConfiguration registerConfig = runtimeName == null
                    ? config
                    : config.withCustomDeviceName(runtimeName);
            DioUpClient.RegisterResponse response =
                    new DioUpClient().register(registerConfig, store.installationId());
            String deviceSecret = response.getDeviceSecret();
            if (deviceSecret == null) {
                throw new IllegalStateException("Server did not issue a device secret");
            }
            RegistrationState state = new RegistrationState(response.getProjectDeviceId(), deviceSecret);
            store.write(state);
            if (runtimeName != null) {
                SYNCED_CUSTOM_DEVICE_NAME.set(runtimeName);
            }
            return state;
        }
    }

    /**
     * 运行时设置设备展示名称，可在任意线程（含主线程）调用。
     * 参数校验与进程内保存同步完成，网络同步自动在 SDK 后台线程执行；
     * 名称与已同步值一致时不发起请求；未初始化或未注册时仅保存，稍后随注册上报。
     */
    public static void setCustomDeviceName(String customDeviceName) {
        if (customDeviceName == null || customDeviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("customDeviceName must not be blank");
        }
        CUSTOM_DEVICE_NAME.set(customDeviceName);
        if (!isInitialized()) {
            return;
        }
        // 与已同步名称一致时跳过请求，服务端也不会写库。
        if (customDeviceName.equals(SYNCED_CUSTOM_DEVICE_NAME.get())) {
            return;
        }
        NAME_SYNC_EXECUTOR.execute(() -> syncCustomDeviceName(customDeviceName));
    }

    private static void syncCustomDeviceName(String customDeviceName) {
        // 已有更新的名称在排队或已生效时，跳过本次过期同步，避免乱序写库。
        if (!customDeviceName.equals(CUSTOM_DEVICE_NAME.get())) {
            return;
        }
        // 注册流程可能已借机补发成功，无需重复请求。
        if (customDeviceName.equals(SYNCED_CUSTOM_DEVICE_NAME.get())) {
            return;
        }
        try {
            DioUpConfiguration config = configuration();
            Context context = requireApplicationContext(config);
            RegistrationState state = new DioUpStore(context, config.getProjectKey()).read();
            if (state == null) {
                // 尚未注册，名称会在 register 时随注册上报。
                return;
            }
            new DioUpClient().updateCustomName(config, state, customDeviceName);
            SYNCED_CUSTOM_DEVICE_NAME.set(customDeviceName);
        } catch (RuntimeException e) {
            Log.w(TAG, "DioUp.setCustomDeviceName sync failed; will retry on next register", e);
        }
    }

    /** Returns the runtime device display name set via setCustomDeviceName, or null. */
    public static String getCustomDeviceName() {
        return CUSTOM_DEVICE_NAME.get();
    }

    /** Sends a heartbeat synchronously. Call from a background thread. */
    public static HeartbeatState heartbeat() {
        DioUpConfiguration config = configuration();
        Context context = requireApplicationContext(config);
        RegistrationState state = new DioUpStore(context, config.getProjectKey()).read();
        if (state == null) {
            throw new IllegalStateException("DioUp.register must complete before heartbeat");
        }
        try {
            // 心跳响应只负责提示有任务；真正执行交给唯一 WorkManager 任务。
            HeartbeatState heartbeat = new DioUpClient().heartbeat(config, state);
            Log.i(TAG, "Heartbeat dispatch pendingTasks=" + heartbeat.getPendingTasks().size()
                    + " pendingStop=" + heartbeat.isPendingStop()
                    + " configVersion=" + heartbeat.getConfigVersion());
            ManagedFrpSynchronizer.apply(config, state, new DioUpStore(context, config.getProjectKey()),
                    new DioUpClient(), heartbeat);
            if (!heartbeat.getPendingTasks().isEmpty()) {
                Log.i(TAG, "Heartbeat has pending tasks, enqueue worker");
                enqueuePendingTaskExecution();
            }
            return heartbeat;
        } catch (DeviceNotFoundException e) {
            // 服务端设备已被删除：清除本地凭证，下一次 register 会自动重新登记。
            Log.w(TAG, "Heartbeat rejected: device removed on server; clearing credentials", e);
            new DioUpStore(context, config.getProjectKey()).clearRegistration();
            throw e;
        }
    }

    /** Reports a location snapshot synchronously. Call from a background thread. */
    public static void reportLocation(DioUpLocation location) {
        if (location == null) {
            throw new NullPointerException("location");
        }
        DioUpConfiguration config = configuration();
        Context context = requireApplicationContext(config);
        RegistrationState state = new DioUpStore(context, config.getProjectKey()).read();
        if (state == null) {
            throw new IllegalStateException(
                    "DioUp.register must complete before reporting location"
            );
        }
        // 位置由宿主在获得权限和定位结果后主动上报，不参与 SDK 初始化或注册。
        new DioUpClient().reportLocation(config, state, location);
    }

    /** Fetches and executes pending tasks synchronously. Call from a background thread. */
    public static TaskExecutionSummary executePendingTasks() {
        DioUpConfiguration config = configuration();
        Context context = requireApplicationContext(config);
        DioUpStore store = new DioUpStore(context, config.getProjectKey());
        RegistrationState state = store.read();
        if (state == null) {
            throw new IllegalStateException("DioUp.register must complete before executing tasks");
        }

        // 一次心跳取得当前任务快照，再按任务类型分派给独立执行器。
        HeartbeatState heartbeat = new DioUpClient().heartbeat(config, state);
        ManagedFrpSynchronizer.apply(config, state, store, new DioUpClient(), heartbeat);
        DioUpDownloader downloader = new DioUpDownloader(config, state, store);
        DioUpFrpCommandExecutor frpExecutor = new DioUpFrpCommandExecutor(config, state, store);
        List<TaskExecutionResult> results = new ArrayList<>();
        for (PendingTask task : heartbeat.getPendingTasks()) {
            // FRP 命令包含加密载荷；其他任务按 APK 下载和安装流程处理。
            if ("FRP_COMMAND".equals(task.getTaskType())) {
                results.add(frpExecutor.execute(task));
            } else {
                results.add(downloader.execute(task));
            }
        }
        return new TaskExecutionSummary(results);
    }

    public static void enqueuePendingTaskExecution() {
        DioUpConfiguration config = configuration();
        Context context = requireApplicationContext(config);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DioUpTaskWorker.class).build();
        // KEEP 保证同一项目同时最多只有一个待处理任务执行链。
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                    "dio_up_pending_tasks_" + config.getProjectKey(),
                    ExistingWorkPolicy.KEEP,
                    request
            );
            Log.i(TAG, "enqueuePendingTaskExecution OK projectKey=" + config.getProjectKey());
        } catch (RuntimeException e) {
            Log.e(TAG, "enqueuePendingTaskExecution failed", e);
            throw e;
        }
    }

    /**
     * 宿主在确认某个 targetId 的安装结果时调用本方法上报。
     * 这是 SDK 唯一的上报完成入口：可由自定义 DioPackageInstaller 内部、
     * 系统安装 Intent 的 onActivityResult、ACTION_PACKAGE_REPLACED 广播回调等触发。
     *
     * @param targetId 来自 PendingTask 的设备目标 ID
     * @param success  true 上报 SUCCEEDED；false 上报 FAILED；null 上报 WAITING_FOR_USER
     *                 （用于"已发系统安装弹窗但未完成"的中间态，让后台状态机推进）
     * @param message  上报时附带的人类可读说明，可为 null
     */
    public static void reportInstallResult(Long targetId, Boolean success, String message) {
        if (targetId == null) {
            throw new NullPointerException("targetId");
        }
        if (!isInitialized()) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        DioUpConfiguration config = configuration();
        Context context = requireApplicationContext(config);
        RegistrationState state = new DioUpStore(context, config.getProjectKey()).read();
        if (state == null) {
            throw new IllegalStateException(
                    "DioUp.register must complete before reporting install result"
            );
        }
        DioUpDao dao = DioUpDatabase.get(context).dao();
        TaskRecord record = dao.findByTargetId(targetId);
        if (record == null) {
            Log.w(TAG, "reportInstallResult: no task record for targetId=" + targetId);
            return;
        }
        String status;
        int progress;
        if (success == null) {
            // 三态：成功 = null 表示"WAITING_FOR_USER"中间态。
            status = "WAITING_FOR_USER";
            progress = 99;
        } else if (success) {
            status = "SUCCEEDED";
            progress = 100;
        } else {
            status = "FAILED";
            progress = 0;
        }
        String finalMessage = message == null || message.isEmpty()
                ? defaultMessageFor(status)
                : message;
        Log.i(TAG, "reportInstallResult targetId=" + targetId
                + " status=" + status
                + " progress=" + progress
                + " message=" + finalMessage);
        new DioUpClient().result(
                config,
                state,
                targetId,
                progress,
                status,
                finalMessage
        );
        dao.upsertTask(record.withStatus(status, finalMessage));
    }

    /** 兼容老的两态重载：传 success=true 走 SUCCEEDED，false 走 FAILED。 */
    public static void reportInstallResult(long targetId, boolean success, String message) {
        reportInstallResult(Long.valueOf(targetId), Boolean.valueOf(success), message);
    }

    private static String defaultMessageFor(String status) {
        if ("SUCCEEDED".equals(status)) return "Installed";
        if ("FAILED".equals(status)) return "Install failed";
        return "Waiting for user confirmation";
    }

    private static Context requireApplicationContext(DioUpConfiguration config) {
        Context context = config.getApplicationContext();
        if (context == null) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        return context;
    }

    private static void startManagedLifecycle(DioUpConfiguration config) {
        // SDK 内部负责心跳与注册的调度：宿主只需在 Configuration 中声明间隔。
        // 0 表示仅执行一次注册，之后不再发送心跳；< 0 已在 Builder 中拒绝。
        long intervalMillis = config.getHeartbeatIntervalMillis();
        if (intervalMillis == 0) {
            // 仍需保证设备已注册；失败时按当前间隔（此时为 0）退避，等价于“立即重试”循环。
            LIFECYCLE_EXECUTOR.execute(() -> registerUntilSuccessful(0L));
            return;
        }
        LIFECYCLE_EXECUTOR.scheduleWithFixedDelay(
                DioUp::registerAndHeartbeatSafely,
                0,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private static void registerUntilSuccessful(long retryDelayMillis) {
        try {
            register();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Automatic device registration failed", exception);
            // 注册重试沿用宿主在 Configuration 中声明的间隔；0 表示宿主禁用心跳，仅做注册。
            long delay = retryDelayMillis > 0
                    ? retryDelayMillis
                    : DioUpConfiguration.DEFAULT_HEARTBEAT_INTERVAL_MILLIS;
            LIFECYCLE_EXECUTOR.schedule(
                    () -> registerUntilSuccessful(retryDelayMillis),
                    delay,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private static void registerAndHeartbeatSafely() {
        try {
            Log.d(TAG, "Automatic heartbeat tick");
            register();
            heartbeat();
        } catch (RuntimeException exception) {
            // 捕获异常，避免 ScheduledExecutorService 因单次网络失败永久停止后续心跳。
            Log.w(TAG, "Automatic registration or heartbeat failed", exception);
        }
    }
}

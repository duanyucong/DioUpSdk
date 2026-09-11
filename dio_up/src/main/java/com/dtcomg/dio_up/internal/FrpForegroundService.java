package com.dtcomg.dio_up.internal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FrpForegroundService extends Service {
    // 通知渠道在整个 SDK 生命周期内保持稳定，避免重复创建用户设置。
    private static final String CHANNEL_ID = "dio_up_frp";
    private static final int NOTIFICATION_ID = 8301;

    // 单线程执行器串行处理多次 startService，避免并发启动同一 UID。
    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        ensureNotificationChannel();
        // Android 要求前台服务启动后尽快展示通知，不能等待动态库加载完成。
        startForeground(NOTIFICATION_ID, notification("FRP runtime is starting"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 动态 AAR 解压和反射调用可能阻塞，全部移到工作线程。
        executor.execute(() -> startRuntime(startId));
        // START_STICKY 允许系统回收后重建服务并从本地状态恢复。
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private void startRuntime(int startId) {
        DioUpStore.FrpState state = readState();
        if (state == null || state.isStopped()) {
            // 已停止或缺少状态时结束对应的本次启动请求。
            stopSelf(startId);
            return;
        }
        FrpRuntime runtime = new FrpRuntime(getApplicationContext());
        String version = runtime.ensureLoaded(state.getVersion());
        // 动态库已运行时不重复提交相同配置。
        if (!runtime.isRunning(state.getUid())) {
            runtime.runContent(state.getUid(), state.getContent());
        }
        startForeground(
                NOTIFICATION_ID,
                notification("FRP " + state.getUid() + " running (" + version + ")")
        );
    }

    private DioUpStore.FrpState readState() {
        DioUpStore.RuntimeConfig runtime =
                DioUpStore.readRuntimeConfig(getApplicationContext());
        if (runtime == null) {
            return null;
        }
        return new DioUpStore(getApplicationContext(), runtime.getProjectKey()).readFrpState();
    }

    private Notification notification(String text) {
        // ongoing 通知表明隧道是持续运行的前台任务。
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("DioUp FRP")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void ensureNotificationChannel() {
        // Android 8.0 以下没有 NotificationChannel API。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "DioUp FRP Runtime",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps DioUp FRP tunnels running");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
}

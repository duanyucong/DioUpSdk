package com.dtcomg.dio_up.internal;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.dtcomg.dio_up.DioUp;
import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.HeartbeatState;
import com.dtcomg.dio_up.PendingTask;
import com.dtcomg.dio_up.RegistrationState;

import java.util.List;

public final class DioUpTaskWorker extends Worker {
    private static final String TAG = "DioUp.Worker";

    public DioUpTaskWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "doWork begin runId=" + getRunAttemptCount());
        // Worker 可能在新进程中启动，因此先从 SharedPreferences 恢复最小配置。
        DioUpStore.RuntimeConfig runtime =
                DioUpStore.readRuntimeConfig(getApplicationContext());
        if (runtime == null) {
            Log.w(TAG, "doWork abort: runtime config missing");
            return Result.failure();
        }
        if (!DioUp.isInitialized()) {
            // 后台恢复只包含服务地址和项目 Key；宿主回调无法跨进程持久化。
            Log.i(TAG, "doWork re-initialize from runtime config");
            DioUp.initialize(
                    getApplicationContext(),
                    DioUpConfiguration.builder(runtime.getServerUrl(), runtime.getProjectKey())
                            .heartbeatIntervalMillis(runtime.getHeartbeatIntervalMillis())
                            .build()
            );
        }

        DioUpConfiguration config = DioUp.configuration();
        DioUpStore store = new DioUpStore(
                getApplicationContext(),
                runtime.getProjectKey()
        );
        RegistrationState state = store.read();
        if (state == null) {
            // 尚未注册通常是临时状态，让 WorkManager 按策略稍后重试。
            Log.w(TAG, "doWork abort: registration missing");
            return Result.retry();
        }
        try {
            HeartbeatState heartbeat = new DioUpClient().heartbeat(config, state);
            Log.i(TAG, "doWork heartbeat pendingTasks=" + heartbeat.getPendingTasks().size());
            ManagedFrpSynchronizer.apply(config, state, store, new DioUpClient(), heartbeat);
            DioUpDownloader downloader = new DioUpDownloader(config, state, store);
            DioUpFrpCommandExecutor frpExecutor =
                    new DioUpFrpCommandExecutor(config, state, store);
            List<PendingTask> tasks = heartbeat.getPendingTasks();
            if (tasks.isEmpty()) {
                Log.i(TAG, "doWork no pending tasks, exit");
            }
            for (PendingTask task : tasks) {
                Log.i(TAG, "doWork dispatch task=" + task.getTaskId()
                        + " targetId=" + task.getTargetId()
                        + " type=" + task.getTaskType()
                        + " package=" + task.getPackageName()
                        + " size=" + task.getPackageFileSize());
                // 与前台主动执行保持相同的任务分派规则。
                if ("FRP_COMMAND".equals(task.getTaskType())) {
                    frpExecutor.execute(task);
                } else {
                    downloader.execute(task);
                }
            }
            return Result.success();
        } catch (RuntimeException exception) {
            Log.e(TAG, "doWork failed", exception);
            // 网络或临时运行错误交给 WorkManager 重试，不在后台线程崩溃进程。
            return Result.retry();
        }
    }
}

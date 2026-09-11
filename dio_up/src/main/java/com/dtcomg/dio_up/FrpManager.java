package com.dtcomg.dio_up;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.dtcomg.dio_up.internal.DioUpStore;
import com.dtcomg.dio_up.internal.FrpForegroundService;
import com.dtcomg.dio_up.internal.FrpRuntime;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Controls the embedded FRP runtime. Methods perform blocking I/O. */
public final class FrpManager {
    private FrpManager() {
    }

    public static FrpStatus start(FrpConfig config) {
        Context appContext = applicationContext();
        // 先把指定版本 AAR 解压并加载到独立 DexClassLoader。
        FrpRuntime runtime = new FrpRuntime(appContext);
        String version = runtime.ensureLoaded(config.getVersion());
        // 动态库 API 为同步调用，返回值作为启动信息透传给调用方。
        String error;
        try {
            File directory = new File(appContext.getFilesDir(), "dio_up_frp");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create FRP config directory");
            File target = new File(directory, config.getUid() + ".toml");
            File temporary = new File(directory, config.getUid() + ".toml.tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(config.getContent().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace FRP config");
            if (!temporary.renameTo(target)) throw new IllegalStateException("Cannot publish FRP config");
            error = runtime.supportsRunFile()
                    ? runtime.runFile(config.getUid(), target.getAbsolutePath())
                    : runtime.runContent(config.getUid(), config.getContent());
        } catch (Exception fileError) {
            error = runtime.runContent(config.getUid(), config.getContent());
        }
        // 先持久化配置，前台服务及设备重启后才能恢复同一隧道。
        new DioUpStore(appContext, DioUp.configuration().getProjectKey()).writeFrpState(
                config.getUid(),
                config.getContent(),
                version,
                false
        );
        // Android 8.0 起必须通过前台服务维持长时间运行的隧道。
        ContextCompat.startForegroundService(
                appContext,
                new Intent(appContext, FrpForegroundService.class)
        );
        return new FrpStatus(config.getUid(), runtime.isRunning(config.getUid()), version, error);
    }

    public static FrpStatus stop(String uid) {
        Context appContext = applicationContext();
        FrpRuntime runtime = new FrpRuntime(appContext);
        String version = runtime.ensureLoaded();
        runtime.close(uid);
        // 标记 stopped，防止开机广播或粘性 Service 再次自动启动。
        new DioUpStore(appContext, DioUp.configuration().getProjectKey()).markFrpStopped(uid);
        return new FrpStatus(uid, runtime.isRunning(uid), version, "Stopped");
    }

    public static FrpStatus status(String uid) {
        Context appContext = applicationContext();
        FrpRuntime runtime = new FrpRuntime(appContext);
        // 查询前仍需加载动态库，确保反射入口已准备就绪。
        String version = runtime.ensureLoaded();
        return new FrpStatus(uid, runtime.isRunning(uid), version);
    }

    private static Context applicationContext() {
        Context context = DioUp.configuration().getApplicationContext();
        if (context == null) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        return context;
    }
}

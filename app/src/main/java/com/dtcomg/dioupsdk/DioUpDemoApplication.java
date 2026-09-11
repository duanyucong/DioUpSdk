package com.dtcomg.dioupsdk;

import android.app.Application;

import com.dtcomg.dio_up.DioUp;
import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.InstallOutcome;
import com.dtcomg.dio_up.InstallStatus;

/** Demonstrates that SDK initialization does not depend on Activity or location permission. */
public final class DioUpDemoApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DioUpConfiguration config = DioUpConfiguration.builder(
                        getString(R.string.dio_up_server_url),
                        getString(R.string.dio_up_project_key)
                )
                .customDeviceName(getString(R.string.dio_up_device_name))
                // 可省略，默认 10 秒；设置为 0 时只自动注册，不发送心跳。
                .heartbeatIntervalMillis(10_000)
//                .packageInstaller((context, apkFile, task) ->
//                        new InstallOutcome(
//                                InstallStatus.WAITING_FOR_USER,
//                                "示例安装器收到 " + apkFile.getName()
//                        ))
                .build();
        DioUp.initialize(this, config);
    }
}

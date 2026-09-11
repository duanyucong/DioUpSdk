package com.dtcomg.dio_up.internal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public final class FrpBootReceiver extends BroadcastReceiver {
    // 部分 Android 厂商使用 QUICKBOOT_POWERON 代替标准开机广播。
    private static final String QUICKBOOT_ACTION = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 仅处理明确支持的两种开机动作，忽略组件收到的其他广播。
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !QUICKBOOT_ACTION.equals(action)) {
            return;
        }
        // 没有运行配置或 FRP 状态表示宿主从未启动过隧道。
        DioUpStore.RuntimeConfig runtime = DioUpStore.readRuntimeConfig(context);
        if (runtime == null) {
            return;
        }
        DioUpStore.FrpState state =
                new DioUpStore(context, runtime.getProjectKey()).readFrpState();
        if (state != null && !state.isStopped()) {
            // 仅恢复用户未主动停止的隧道。
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, FrpForegroundService.class)
            );
        }
    }
}

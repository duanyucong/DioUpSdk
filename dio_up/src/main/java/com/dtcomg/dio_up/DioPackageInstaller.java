package com.dtcomg.dio_up;

import android.content.Context;

import java.io.File;

@FunctionalInterface
public interface DioPackageInstaller {
    // 返回 INSTALLED 前应完成安装；需要用户确认时返回 WAITING_FOR_USER。
    InstallOutcome install(Context context, File apkFile, PendingTask task);
}

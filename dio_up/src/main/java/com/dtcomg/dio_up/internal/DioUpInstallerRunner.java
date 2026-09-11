package com.dtcomg.dio_up.internal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.dtcomg.dio_up.DioPackageInstaller;
import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.InstallOutcome;
import com.dtcomg.dio_up.InstallStatus;
import com.dtcomg.dio_up.PendingTask;

import java.io.File;

public final class DioUpInstallerRunner {
    private final DioUpConfiguration config;

    public DioUpInstallerRunner(DioUpConfiguration config) {
        this.config = config;
    }

    public InstallOutcome install(File apkFile, PendingTask task) {
        Context context = requireContext();
        // 路径优先级：宿主实例 > 反射实例 > 系统安装 Intent。
        DioPackageInstaller custom = config.getPackageInstaller();
        if (custom != null) {
            return verifySuccessfulInstall(custom.install(context, apkFile, task), context, apkFile, task);
        }

        String className = config.getReflectionInstallerClassName();
        DioPackageInstaller reflected = className == null
                ? null
                : InstallPathSelector.reflectedInstaller(className);
        if (reflected != null) {
            return verifySuccessfulInstall(
                    reflected.install(context, apkFile, task),
                    context,
                    apkFile,
                    task
            );
        }
        return standardInstall(context, apkFile);
    }

    public InstallOutcome verifyInstalled(File apkFile, String packageName, Long versionCode) {
        // 将数据库中的最小安装信息转换为统一任务模型，复用完整校验逻辑。
        PendingTask task = new PendingTask(
                0,
                0,
                "VERIFY_ONLY",
                0L,
                apkFile.getName(),
                apkFile.length(),
                "",
                packageName,
                null,
                versionCode,
                null,
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        return verifyInstalled(requireContext(), apkFile, task);
    }

    private InstallOutcome verifySuccessfulInstall(
            InstallOutcome outcome,
            Context context,
            File apkFile,
            PendingTask task
    ) {
        // 安装器声称成功仍不可信，必须用 PackageManager 验证实际安装结果。
        if (outcome.getStatus() == InstallStatus.INSTALLED) {
            return verifyInstalled(context, apkFile, task);
        }
        return outcome;
    }

    private InstallOutcome verifyInstalled(Context context, File apkFile, PendingTask task) {
        // 先解析下载 APK 自身的清单，取得真实包名和版本。
        PackageInfo archive = context.getPackageManager()
                .getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
        if (archive == null) {
            return new InstallOutcome(InstallStatus.FAILED, "Cannot inspect downloaded APK");
        }
        // 任务声明包名时必须与 APK 清单完全一致，防止错误包被安装。
        String expectedPackage = task.getPackageName() == null
                ? archive.packageName
                : task.getPackageName();
        if (!archive.packageName.equals(expectedPackage)) {
            return new InstallOutcome(
                    InstallStatus.FAILED,
                    "Downloaded APK package mismatch"
            );
        }

        // 再查询设备当前已安装包，确认安装动作确实完成。
        PackageInfo installed;
        try {
            installed = context.getPackageManager().getPackageInfo(expectedPackage, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            return new InstallOutcome(InstallStatus.FAILED, "Package is not installed");
        }
        if (!matchesExpectedVersion(installed, task)) {
            return new InstallOutcome(
                    InstallStatus.FAILED,
                    "Installed package version does not match task"
            );
        }
        return new InstallOutcome(InstallStatus.INSTALLED, "Installed and verified");
    }

    @SuppressWarnings("deprecation")
    private boolean matchesExpectedVersion(PackageInfo installed, PendingTask task) {
        Long expected = task.getPackageVersionCode();
        if (expected == null) {
            return true;
        }
        // Android 9 起使用 longVersionCode；旧系统继续读取 int versionCode。
        long actual = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode()
                : installed.versionCode;
        return actual == expected;
    }

    private InstallOutcome standardInstall(Context context, File apkFile) {
        // FileProvider 将私有文件转换为一次性可授权的 content:// URI。
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".dioup.fileprovider",
                apkFile
        );
        // NEW_TASK 允许从 Worker/Service 上下文启动系统安装界面。
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return new InstallOutcome(
                InstallStatus.WAITING_FOR_USER,
                "Waiting for user confirmation"
        );
    }

    private Context requireContext() {
        Context context = config.getApplicationContext();
        if (context == null) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        return context;
    }
}

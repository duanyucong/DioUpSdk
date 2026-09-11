package com.dtcomg.dio_up.internal;

import android.util.Log;

import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.DownloadProgressListener;
import com.dtcomg.dio_up.InstallOutcome;
import com.dtcomg.dio_up.InstallStatus;
import com.dtcomg.dio_up.PendingTask;
import com.dtcomg.dio_up.RegistrationState;
import com.dtcomg.dio_up.TaskExecutionResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DioUpDownloader {
    private static final String TAG = "DioUp.Downloader";
    // 文件与网络流统一使用 8 KiB 缓冲区，兼顾内存占用与吞吐。
    private static final int BUFFER_SIZE = 8 * 1024;

    private final DioUpConfiguration config;
    private final RegistrationState state;
    private final DioUpClient client;
    private final DioUpDao dao;

    public DioUpDownloader(
            DioUpConfiguration config,
            RegistrationState state,
            DioUpStore store
    ) {
        this(config, state, store, new DioUpClient());
    }

    public DioUpDownloader(
            DioUpConfiguration config,
            RegistrationState state,
            DioUpStore store,
            DioUpClient client
    ) {
        this.config = config;
        this.state = state;
        this.client = client;
        if (config.getApplicationContext() == null) {
            throw new IllegalStateException("DioUp.initialize must be called first");
        }
        dao = DioUpDatabase.get(config.getApplicationContext()).dao();
    }

    public TaskExecutionResult execute(PendingTask task) {
        // FRP 命令必须经过独立的解密和签名校验流程，禁止误走下载器。
        if ("FRP_COMMAND".equals(task.getTaskType())) {
            throw new IllegalArgumentException(
                    "FRP command cannot be executed by APK downloader"
            );
        }
        // APK 任务的三项核心元数据缺一不可，尽早失败可避免产生无效文件。
        long packageId = require(task.getPackageId(), "Package id missing");
        String packageSha256 = require(task.getPackageSha256(), "Package SHA-256 missing");
        long packageFileSize = require(task.getPackageFileSize(), "Package size missing");
        Log.i(TAG, "Start download task=" + task.getTaskId()
                + " targetId=" + task.getTargetId()
                + " packageId=" + packageId
                + " size=" + packageFileSize);
        DownloadProgressListener listener = config.getDownloadProgressListener();
        if (listener != null) {
            try {
                String display = task.getPackageName() != null
                        ? task.getPackageName()
                        : task.getPackageOriginalFilename();
                listener.onDownloadStarted(display, packageFileSize);
            } catch (RuntimeException ignored) {
                // UI 回调异常不应阻塞下载主流程。
            }
        }
        // 先落库 PENDING，即使进程被杀也能看到任务已经开始处理。
        dao.upsertTask(record(task, "PENDING", null, null));

        // 判断是否需要真正执行：必须同时满足"taskId 已处理过"且"已成功安装"。
        // 文件存在、part 残留等都不算"完成"，否则会导致重装/升级/补装任务被静默吞掉。
        TaskRecord existingRecord = dao.findByTaskId(task.getTaskId());
        if (existingRecord != null && "SUCCEEDED".equals(existingRecord.status)) {
            // 任务已成功且文件依然可信，可直接按幂等成功处理。
            String existingPath = existingRecord.filePath;
            if (existingPath != null) {
                File existingFile = new File(existingPath);
                if (existingFile.isFile() && sha256(existingFile).equals(packageSha256)) {
                    Log.i(TAG, "Downloader short-circuit: task already succeeded taskId="
                            + task.getTaskId() + " targetId=" + task.getTargetId()
                            + " file=" + existingPath);
                    com.dtcomg.dio_up.DioUp.reportInstallResult(
                            task.getTargetId(), true, "Already downloaded");
                    return new TaskExecutionResult(
                            task.getTaskId(),
                            task.getTargetId(),
                            "SUCCEEDED",
                            existingPath,
                            "Already downloaded"
                    );
                }
            }
        }

        try {
            // 下载前先向服务端确认设备已经接收该任务。
            Log.i(TAG, "Downloader.ack sending targetId=" + task.getTargetId());
            client.ack(config, state, task.getTargetId());
            Log.i(TAG, "Downloader.ack done targetId=" + task.getTargetId());
            File directory = config.getDownloadDirectoryProvider()
                    .getDownloadDirectory(config.getApplicationContext());
            Log.i(TAG, "Downloader dir=" + directory.getAbsolutePath());
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create download directory");
            }
            if (!directory.isDirectory() || !directory.canWrite()) {
                throw new IllegalStateException("Download directory is not writable");
            }

            // 最终文件名包含 packageId 和摘要，可避免不同版本发生文件名冲突。
            File finalFile = new File(directory, packageId + "-" + packageSha256 + ".apk");
            File partFile = new File(directory, finalFile.getName() + ".part");
            Log.i(TAG, "Downloader finalFile=" + finalFile.getAbsolutePath()
                    + " partFile=" + partFile.getAbsolutePath()
                    + " partExists=" + partFile.exists()
                    + " partLen=" + (partFile.exists() ? partFile.length() : 0));
            // 任务是否已下载完成仅作为断点续传的参考依据，不能据此跳过安装。
            // 即便文件已存在，只要该 taskId 此前未走到 SUCCEEDED，就必须继续走安装流程。
            if (finalFile.isFile() && sha256(finalFile).equals(packageSha256)) {
                Log.i(TAG, "Downloader reusing existing file targetId="
                        + task.getTargetId() + " file=" + finalFile.getAbsolutePath());
                return handleInstall(task, finalFile);
            }

            // 完整下载后同时校验长度与 SHA-256，任一不匹配都不得进入安装流程。
            download(task, partFile, finalFile);
            if (partFile.length() != packageFileSize) {
                throw new IllegalStateException("Downloaded size mismatch");
            }
            if (!sha256(partFile).equals(packageSha256)) {
                throw new IllegalStateException("Downloaded SHA-256 mismatch");
            }
            // renameTo 只在所有校验通过后执行，确保 finalFile 始终表示可信 APK。
            if (finalFile.exists() && !finalFile.delete()) {
                throw new IllegalStateException("Cannot replace downloaded package");
            }
            if (!partFile.renameTo(finalFile)) {
                throw new IllegalStateException("Cannot finalize downloaded package");
            }
            Log.i(TAG, "Download completed task=" + task.getTaskId()
                    + " targetId=" + task.getTargetId()
                    + " file=" + finalFile.getAbsolutePath());
            DownloadProgressListener completedListener = config.getDownloadProgressListener();
            if (completedListener != null) {
                try {
                    completedListener.onDownloadCompleted(finalFile.getAbsolutePath());
                } catch (RuntimeException ignored) {
                }
            }
            return handleInstall(task, finalFile);
        } catch (Exception error) {
            Log.e(TAG, "Downloader error targetId=" + task.getTargetId()
                    + " type=" + task.getTaskType()
                    + " msg=" + error.getMessage(), error);
            DownloadProgressListener failedListener = config.getDownloadProgressListener();
            if (failedListener != null) {
                try {
                    failedListener.onDownloadFailed(error.getMessage());
                } catch (RuntimeException ignored) {
                }
            }
            // 下载或校验异常统一通过 DioUp.reportInstallResult 上报 FAILED。
            com.dtcomg.dio_up.DioUp.reportInstallResult(
                    task.getTargetId(), false, error.getMessage());
            return new TaskExecutionResult(
                    task.getTaskId(),
                    task.getTargetId(),
                    "FAILED",
                    null,
                    error.getMessage()
            );
        }
    }

    private TaskExecutionResult handleInstall(PendingTask task, File finalFile) {
        InstallOutcome outcome = new DioUpInstallerRunner(config).install(finalFile, task);
        String path = finalFile.getAbsolutePath();
        // 同步类安装器（自定义安装器内部已经 await 装好）当场报结果。
        // 这一步等价于安装器"调用 DioUp.reportInstallResult"，走完全相同的上报路径。
        if (outcome.getStatus() == InstallStatus.INSTALLED) {
            String message = defaultMessage(outcome.getMessage(), "Installed");
            com.dtcomg.dio_up.DioUp.reportInstallResult(
                    task.getTargetId(), true, message);
            return new TaskExecutionResult(
                    task.getTaskId(),
                    task.getTargetId(),
                    "SUCCEEDED",
                    path,
                    message
            );
        }
        // 标准系统安装 Intent 没有结果回调，保留 APK 等宿主后续报告。
        // 同时立即向服务端上报 WAITING_FOR_USER 让后台状态机推进；
        // 后续宿主在 ACTION_PACKAGE_REPLACED 广播中调
        // DioUp.reportInstallResult(targetId, true, message) 推进到 SUCCEEDED。
        if (outcome.getStatus() == InstallStatus.WAITING_FOR_USER) {
            String message = defaultMessage(outcome.getMessage(), "Waiting for user confirmation");
            com.dtcomg.dio_up.DioUp.reportInstallResult(
                    task.getTargetId(), null, message);
            return new TaskExecutionResult(
                    task.getTaskId(),
                    task.getTargetId(),
                    "WAITING_FOR_USER",
                    path,
                    message
            );
        }

        // 安装器返回 FAILED：走和成功路径相同的 DioUp.reportInstallResult 上报。
        com.dtcomg.dio_up.DioUp.reportInstallResult(
                task.getTargetId(), false, outcome.getMessage());
        return new TaskExecutionResult(
                task.getTaskId(),
                task.getTargetId(),
                "FAILED",
                path,
                outcome.getMessage()
        );
    }

    private void download(PendingTask task, File partFile, File finalFile) throws IOException {
        // .part 文件长度即下一次 Range 请求的起始偏移。
        long downloaded = partFile.exists() ? partFile.length() : 0;
        try (Response response = client.downloadPackage(config, state, task, downloaded)) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException(
                        "Download failed with HTTP " + response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException("Download response has no body");
            }
            try (RandomAccessFile output = new RandomAccessFile(partFile, "rw")) {
                // 只有服务端明确返回 206 才能在断点后追加；200 表示必须从头覆盖。
                if (downloaded > 0 && response.code() == 206) {
                    output.seek(downloaded);
                } else {
                    downloaded = 0;
                    output.setLength(0);
                }
                copyDownload(task, partFile, finalFile, body.byteStream(), output, downloaded);
            }
        }
    }

    private void copyDownload(
            PendingTask task,
            File partFile,
            File finalFile,
            InputStream input,
            RandomAccessFile output,
            long initialBytes
    ) throws IOException {
        long packageFileSize = require(task.getPackageFileSize(), "Package size missing");
        long downloaded = initialBytes;
        DownloadProgressListener listener = config.getDownloadProgressListener();
        // 记录最后一次上报值，默认每增加 10% 才发送一次网络请求。
        int lastProgress = (int) ((downloaded * 100) / packageFileSize);
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream source = input) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                downloaded += read;
                // 每个数据块记录进度快照；进程中断后仍以真实文件长度继续。
                dao.upsertDownload(downloadRecord(task, partFile, finalFile, downloaded));
                int progress = (int) ((downloaded * 100) / packageFileSize);
                progress = Math.max(1, Math.min(99, progress));
                if (progress >= lastProgress + 10 || progress == 99) {
                    Log.i(TAG, "Download progress task=" + task.getTaskId()
                            + " targetId=" + task.getTargetId()
                            + " progress=" + progress + "%"
                            + " (" + downloaded + "/" + packageFileSize + " bytes)");
                    client.progress(
                            config,
                            state,
                            task.getTargetId(),
                            progress,
                            "Downloaded " + downloaded + "/" + packageFileSize
                    );
                    if (listener != null) {
                        try {
                            listener.onDownloadProgress(progress, downloaded, packageFileSize);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    lastProgress = progress;
                }
            }
        }
    }

    private String sha256(File file) {
        try {
            // 流式计算摘要，不把大型 APK 一次性读入内存。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot hash downloaded package", exception);
        }
    }

    private TaskRecord record(
            PendingTask task,
            String status,
            String filePath,
            String message
    ) {
        // 将网络任务模型压缩为本地执行状态快照。
        return new TaskRecord(
                task.getTargetId(),
                task.getTaskId(),
                status,
                require(task.getPackageId(), "Package id missing"),
                require(task.getPackageSha256(), "Package SHA-256 missing"),
                task.getPackageName(),
                task.getPackageVersionCode(),
                filePath,
                message,
                System.currentTimeMillis()
        );
    }

    private DownloadRecord downloadRecord(
            PendingTask task,
            File partFile,
            File finalFile,
            long downloaded
    ) {
        // 保存 part/final 两个路径与当前字节数，便于诊断断点状态。
        return new DownloadRecord(
                task.getTargetId(),
                require(task.getPackageId(), "Package id missing"),
                require(task.getPackageSha256(), "Package SHA-256 missing"),
                partFile.getAbsolutePath(),
                finalFile.getAbsolutePath(),
                downloaded,
                System.currentTimeMillis()
        );
    }

    private static String defaultMessage(String message, String fallback) {
        return message == null ? fallback : message;
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}

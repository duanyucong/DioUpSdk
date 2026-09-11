package com.dtcomg.dio_up;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Default implementation of {@link DownloadProgressListener} that shows a
 * system AlertDialog with a progress bar. The host just passes an instance
 * of this class into {@link DioUpConfiguration.Builder#downloadProgressListener}.
 *
 * <p>The dialog is dismissed automatically when the download completes or fails.
 * Call {@link #setPostDownloadAction(Runnable)} if you want to chain an action
 * (such as the system install intent) after the dialog closes.
 */
public final class DefaultDownloadProgressDialog implements DownloadProgressListener {
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private ProgressBar progressBar;
    private TextView messageView;
    private TextView percentView;
    private String title = "正在更新";
    private long lastShownPercent = -1;
    private Runnable postDownloadAction;

    public DefaultDownloadProgressDialog(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Sets the title shown on the dialog. */
    public DefaultDownloadProgressDialog setTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     * Sets a callback that runs on the UI thread after the dialog is dismissed
     * (e.g. to launch the system install intent).
     */
    public DefaultDownloadProgressDialog setPostDownloadAction(Runnable action) {
        this.postDownloadAction = action;
        return this;
    }

    @Override
    public void onDownloadStarted(String taskName, long totalBytes) {
        mainHandler.post(() -> {
            lastShownPercent = -1;
            showDialog(taskName, totalBytes, 0, 0);
        });
    }

    @Override
    public void onDownloadProgress(int percent, long downloadedBytes, long totalBytes) {
        mainHandler.post(() -> {
            if (dialog == null || !dialog.isShowing()) {
                showDialog(null, totalBytes, downloadedBytes, percent);
            } else {
                updateProgress(percent, downloadedBytes, totalBytes);
            }
        });
    }

    @Override
    public void onDownloadCompleted(final String apkFilePath) {
        mainHandler.post(() -> {
            if (dialog != null && dialog.isShowing()) {
                dialog.setMessage("下载完成，正在准备安装…");
            }
            dismissDialog();
            if (postDownloadAction != null) {
                postDownloadAction.run();
            }
        });
    }

    @Override
    public void onDownloadFailed(final String message) {
        mainHandler.post(() -> {
            if (dialog != null && dialog.isShowing()) {
                dialog.setMessage("更新失败：" + (message == null ? "未知错误" : message));
                // 失败时保留 1.5 秒提示用户，然后关闭。
                mainHandler.postDelayed(this::dismissDialog, 1500);
            }
        });
    }

    private void showDialog(String taskName, long totalBytes, long downloadedBytes, int percent) {
        // 优先尝试获取 Activity 作为 Window 上下文，否则退化为系统对话框。
        Context windowContext = appContext;
        Activity activity = findActivity(appContext);
        if (activity != null && !activity.isFinishing()) {
            windowContext = activity;
        }

        // 复用已有对话框以避免重复创建。
        if (dialog == null) {
            ProgressBar bar = new ProgressBar(windowContext, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setIndeterminate(false);
            messageView = new TextView(windowContext);
            messageView.setTextSize(14f);
            percentView = new TextView(windowContext);
            percentView.setTextSize(14f);

            int padding = (int) (16 * windowContext.getResources().getDisplayMetrics().density);
            android.widget.LinearLayout container = new android.widget.LinearLayout(windowContext);
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
            container.setPadding(padding, padding, padding, padding);
            container.addView(messageView);
            container.addView(bar);
            container.addView(percentView);

            AlertDialog.Builder builder = new AlertDialog.Builder(windowContext)
                    .setTitle(title)
                    .setView(container)
                    .setCancelable(false);
            dialog = builder.create();
            progressBar = bar;
        }
        updateProgress(percent, downloadedBytes, totalBytes);
        if (taskName != null) {
            messageView.setText(taskName);
        }
        if (dialog != null && !dialog.isShowing()) {
            dialog.show();
        }
    }

    private void updateProgress(int percent, long downloadedBytes, long totalBytes) {
        if (progressBar != null) {
            progressBar.setProgress(percent);
        }
        if (percentView != null) {
            if (percent <= lastShownPercent && percent < 100) {
                return;
            }
            lastShownPercent = percent;
            percentView.setText(String.format(Locale.getDefault(),
                    "%d%%  %s / %s",
                    percent,
                    humanReadable(downloadedBytes),
                    humanReadable(totalBytes)));
        }
    }

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String humanReadable(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = new String[]{"B", "KB", "MB", "GB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    private static Activity findActivity(Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        }
        // 简单回退：宿主若自行管理 Activity，可通过构造器传 Activity。
        return null;
    }
}

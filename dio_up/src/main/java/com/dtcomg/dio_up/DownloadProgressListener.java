package com.dtcomg.dio_up;

/** Callback for UI updates during an in-app download/install. */
public interface DownloadProgressListener {
    /** Called when a download starts. */
    void onDownloadStarted(String taskName, long totalBytes);

    /**
     * Called as bytes arrive. {@code percent} is in [0, 100]; values may stay
     * the same between calls when the change is below the reporting threshold.
     */
    void onDownloadProgress(int percent, long downloadedBytes, long totalBytes);

    /** Called once the APK has been downloaded and verified. */
    void onDownloadCompleted(String apkFilePath);

    /** Called when the download or install fails. */
    void onDownloadFailed(String message);
}

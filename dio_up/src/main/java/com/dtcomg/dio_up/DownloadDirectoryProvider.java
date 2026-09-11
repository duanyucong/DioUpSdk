package com.dtcomg.dio_up;

import android.content.Context;

import java.io.File;

@FunctionalInterface
public interface DownloadDirectoryProvider {
    // 每次执行下载前调用，返回值必须是可创建或可写的目录。
    File getDownloadDirectory(Context context);
}

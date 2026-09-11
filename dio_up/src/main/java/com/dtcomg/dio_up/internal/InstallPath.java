package com.dtcomg.dio_up.internal;

public enum InstallPath {
    // 宿主直接传入 DioPackageInstaller 实例。
    INSTANCE,
    // 通过配置的类名反射创建安装器。
    REFLECTION,
    // 使用 Android 标准安装界面。
    STANDARD_INTENT
}

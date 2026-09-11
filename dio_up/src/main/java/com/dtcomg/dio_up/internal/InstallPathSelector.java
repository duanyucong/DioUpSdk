package com.dtcomg.dio_up.internal;

import com.dtcomg.dio_up.DioPackageInstaller;
import com.dtcomg.dio_up.DioUpConfiguration;

public final class InstallPathSelector {
    private InstallPathSelector() {
    }

    public static InstallPath select(DioUpConfiguration config) {
        // 显式实例最可靠，始终拥有最高优先级。
        if (config.getPackageInstaller() != null) {
            return InstallPath.INSTANCE;
        }
        // 只有类存在、可构造且实现接口时，反射路径才算可用。
        String className = config.getReflectionInstallerClassName();
        if (className != null && reflectedInstaller(className) != null) {
            return InstallPath.REFLECTION;
        }
        return InstallPath.STANDARD_INTENT;
    }

    public static DioPackageInstaller reflectedInstaller(String className) {
        try {
            // 要求公开或可访问的无参构造器，便于 SDK 独立创建实例。
            Object instance = Class.forName(className).getDeclaredConstructor().newInstance();
            return instance instanceof DioPackageInstaller
                    ? (DioPackageInstaller) instance
                    : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            // 反射失败不是致命错误，调用方会安全回退到标准 Intent。
            return null;
        }
    }
}

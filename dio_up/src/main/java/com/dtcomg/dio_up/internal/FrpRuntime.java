package com.dtcomg.dio_up.internal;

import android.annotation.SuppressLint;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

public final class FrpRuntime {
    // SDK assets 中内置的默认 FRP AAR 版本。
    public static final String VERSION = "0.69.1";

    private static final int BUFFER_SIZE = 8 * 1024;

    // 只保存 applicationContext，避免动态运行库间接持有短生命周期组件。
    private final Context context;
    // 每个 FrpRuntime 实例缓存当前加载的类入口和版本。
    private DexClassLoader classLoader;
    private Class<?> clientClass;
    private String loadedVersion;

    public FrpRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    public String ensureLoaded() {
        return ensureLoaded(VERSION);
    }

    @SuppressLint("PrivateApi")
    public synchronized String ensureLoaded(String version) {
        // 同一实例重复请求相同版本时直接复用已解析的反射入口。
        if (version.equals(loadedVersion) && clientClass != null) {
            return version;
        }

        // 首次使用时把只读 assets AAR 复制到 App 私有目录。
        File aar = installAsset(version);
        File extracted = new File(versionDir(version), "extracted");
        // 每次重新加载都清理旧解压目录，防止版本内容残留。
        if (extracted.exists()) {
            deleteRecursively(extracted);
        }
        if (!extracted.mkdirs() && !extracted.isDirectory()) {
            throw new IllegalStateException("Cannot create FRP extraction directory");
        }
        extractArchive(aar, extracted);

        // 优先加载 Android dex；部分构建产物只包含 classes.jar，因此提供回退。
        File dex = new File(extracted, "classes.dex");
        if (!dex.exists()) {
            dex = new File(extracted, "classes.jar");
        }
        if (!dex.exists()) {
            throw new IllegalStateException("FRP AAR has no classes.dex/classes.jar");
        }
        // jni 目录交给 DexClassLoader 解析动态库；不存在时显式传 null。
        File nativeDirectory = new File(extracted, "jni");
        String nativePath = nativeDirectory.exists()
                ? nativeDirectory.getAbsolutePath()
                : null;
        // 优化后的 dex 同样写入 App 私有目录，不依赖外部存储权限。
        classLoader = new DexClassLoader(
                dex.getAbsolutePath(),
                context.getDir("frp_dex", Context.MODE_PRIVATE).getAbsolutePath(),
                nativePath,
                context.getClassLoader()
        );
        try {
            // 该类来自内置 FRP AAR，并非 Android framework 隐藏 API。
            clientClass = classLoader.loadClass("com.android.http.client.Client");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("FRP Client class is missing", exception);
        }
        // 加载时先验证最小反射契约，让缺失方法尽早暴露。
        if (!hasMethod("runContent", 2) && !hasMethod("runFile", 2)) {
            throw new IllegalStateException("FRP Client missing runContent/runFile");
        }
        if (!hasMethod("isRunning", 1)) {
            throw new IllegalStateException("FRP Client missing isRunning");
        }
        if (!hasMethod("close", 1)) {
            throw new IllegalStateException("FRP Client missing close");
        }
        loadedVersion = version;
        return version;
    }

    public String runContent(String uid, String content) {
        // 动态库约定使用静态入口，返回值为空表示没有附加信息。
        Object value = invoke("runContent", uid, content);
        return value == null ? null : value.toString();
    }

    public String runFile(String uid, String path) {
        if (!hasMethod("runFile", 2)) return null;
        Object value = invoke("runFile", uid, path);
        return value == null ? null : value.toString();
    }

    public boolean supportsRunFile() {
        return hasMethod("runFile", 2);
    }

    public boolean isRunning(String uid) {
        Object value = invoke("isRunning", uid);
        return value instanceof Boolean && (Boolean) value;
    }

    public void close(String uid) {
        invoke("close", uid);
    }

    public String sha256() {
        return sha256(VERSION);
    }

    public String sha256(String version) {
        return hash(installAsset(version));
    }

    private File installAsset(String version) {
        File directory = versionDir(version);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create FRP version directory");
        }
        File target = new File(directory, "frp.aar");
        // 已复制的版本文件可长期复用，避免每次启动重复写盘。
        if (target.exists()) {
            return target;
        }
        try (InputStream input = context.getAssets()
                .open("lib_versions/" + version + "/frp.aar");
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot install FRP runtime asset", exception);
        }
        return target;
    }

    private File versionDir(String version) {
        return new File(context.getFilesDir(), "lib_versions/" + version);
    }

    private Object invoke(String name, Object... arguments) {
        Class<?> clazz = clientClass;
        if (clazz == null) {
            throw new IllegalStateException("FRP runtime not loaded");
        }
        // 按方法名和参数数量匹配，兼容动态库未公开具体参数类型的情况。
        Method selected = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == arguments.length) {
                selected = method;
                break;
            }
        }
        if (selected == null) {
            return null;
        }
        try {
            // FRP AAR 的入口可能不是 public，因此在受控范围内开启访问。
            selected.setAccessible(true);
            return selected.invoke(null, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access FRP method " + name, exception);
        } catch (InvocationTargetException exception) {
            // 尽量保留动态库抛出的原始运行时异常，方便宿主定位问题。
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("FRP method failed: " + name, cause);
        }
    }

    private boolean hasMethod(String name, int argumentCount) {
        if (clientClass == null) {
            return false;
        }
        for (Method method : clientClass.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == argumentCount) {
                return true;
            }
        }
        return false;
    }

    private void extractArchive(File archive, File destination) {
        try (ZipFile zip = new ZipFile(archive)) {
            // canonicalPath 前缀检查用于阻止 ../ 形式的 Zip Slip 路径穿越。
            String destinationPath = destination.getCanonicalPath() + File.separator;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File output = new File(destination, entry.getName().replace('\\', '/'));
                if (!output.getCanonicalPath().startsWith(destinationPath)) {
                    throw new IllegalStateException("Unsafe entry in FRP AAR: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IllegalStateException("Cannot create FRP archive directory");
                    }
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("Cannot create FRP archive directory");
                }
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream target = new FileOutputStream(output)) {
                    copy(input, target);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot extract FRP runtime", exception);
        }
    }

    private String hash(File file) {
        try {
            // FRP 运行库摘要通过流式读取计算，避免 AAR 体积影响内存。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
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
            throw new IllegalStateException("Cannot hash FRP runtime", exception);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteRecursively(File target) {
        // 仅由 versionDir 下的固定 extracted 目录调用，逐层删除旧版本内容。
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!target.delete() && target.exists()) {
            throw new IllegalStateException("Cannot clear FRP extraction directory");
        }
    }
}

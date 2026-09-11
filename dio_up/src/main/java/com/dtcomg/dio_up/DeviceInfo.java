package com.dtcomg.dio_up;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 设备信息快照，由 SDK 在激活/注册时一次性收集并上报给服务端。
 *
 * <p>所有字段都尽量使用 Android 公共 API 读取，<strong>不申请任何运行时权限</strong>：
 * 任何因 Android 8+ 隐私限制、宿主未授权或设备不支持而无法读取的字段一律返回 {@code null}。
 *
 * <p>不可变值对象；线程安全。
 */
public final class DeviceInfo {
    private static final String TAG = "DioUp.DeviceInfo";

    private final String manufacturer;
    private final String model;
    private final String brand;
    private final String device;
    private final String product;
    private final String hardware;
    private final String board;
    private final String bootloader;
    private final String serial;
    private final String androidId;

    private final String osVersion;
    private final int sdkInt;
    private final int releaseSdkInt;
    private final String securityPatch;
    private final String buildFingerprint;
    private final String buildId;
    private final String buildDisplayId;
    private final long buildTimeMillis;
    private final String buildType;
    private final String buildTags;

    private final String abi;
    private final String abi2;
    private final int cpuAbiCount;
    private final long totalMemoryBytes;
    private final long availableMemoryBytes;
    private final long totalStorageBytes;
    private final long availableStorageBytes;

    private final int screenWidthPixels;
    private final int screenHeightPixels;
    private final float screenDensity;
    private final float screenXdpi;
    private final float screenYdpi;
    private final int screenDensityDpi;

    private final Integer batteryLevel;
    private final String batteryStatus;
    private final String batteryHealth;

    private final String networkType;
    private final boolean networkConnected;
    private final String wifiSsid;
    private final String timezoneId;

    private final String appPackageName;
    private final String appVersionName;
    private final long appVersionCode;
    private final String appFirstInstallTime;
    private final String appLastUpdateTime;

    private DeviceInfo(Builder builder) {
        this.manufacturer = builder.manufacturer;
        this.model = builder.model;
        this.brand = builder.brand;
        this.device = builder.device;
        this.product = builder.product;
        this.hardware = builder.hardware;
        this.board = builder.board;
        this.bootloader = builder.bootloader;
        this.serial = builder.serial;
        this.androidId = builder.androidId;

        this.osVersion = builder.osVersion;
        this.sdkInt = builder.sdkInt;
        this.releaseSdkInt = builder.releaseSdkInt;
        this.securityPatch = builder.securityPatch;
        this.buildFingerprint = builder.buildFingerprint;
        this.buildId = builder.buildId;
        this.buildDisplayId = builder.buildDisplayId;
        this.buildTimeMillis = builder.buildTimeMillis;
        this.buildType = builder.buildType;
        this.buildTags = builder.buildTags;

        this.abi = builder.abi;
        this.abi2 = builder.abi2;
        this.cpuAbiCount = builder.cpuAbiCount;
        this.totalMemoryBytes = builder.totalMemoryBytes;
        this.availableMemoryBytes = builder.availableMemoryBytes;
        this.totalStorageBytes = builder.totalStorageBytes;
        this.availableStorageBytes = builder.availableStorageBytes;

        this.screenWidthPixels = builder.screenWidthPixels;
        this.screenHeightPixels = builder.screenHeightPixels;
        this.screenDensity = builder.screenDensity;
        this.screenXdpi = builder.screenXdpi;
        this.screenYdpi = builder.screenYdpi;
        this.screenDensityDpi = builder.screenDensityDpi;

        this.batteryLevel = builder.batteryLevel;
        this.batteryStatus = builder.batteryStatus;
        this.batteryHealth = builder.batteryHealth;

        this.networkType = builder.networkType;
        this.networkConnected = builder.networkConnected;
        this.wifiSsid = builder.wifiSsid;
        this.timezoneId = builder.timezoneId;

        this.appPackageName = builder.appPackageName;
        this.appVersionName = builder.appVersionName;
        this.appVersionCode = builder.appVersionCode;
        this.appFirstInstallTime = builder.appFirstInstallTime;
        this.appLastUpdateTime = builder.appLastUpdateTime;
    }

    /**
     * 在当前进程上下文里构造一份 {@link DeviceInfo} 快照。
     *
     * @param context SDK 保存的 application context；不能为空
     * @return 包含全部可读字段的快照，读取失败字段为 {@code null} 或 0
     */
    public static DeviceInfo collect(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Builder b = new Builder();
        b.context = context.getApplicationContext();

        // 硬件身份字段：Build 公共 API 全部免权限。
        b.manufacturer = nullSafe(Build.MANUFACTURER);
        b.model = nullSafe(Build.MODEL);
        b.brand = nullSafe(Build.BRAND);
        b.device = nullSafe(Build.DEVICE);
        b.product = nullSafe(Build.PRODUCT);
        b.hardware = nullSafe(Build.HARDWARE);
        b.board = nullSafe(Build.BOARD);
        b.bootloader = nullSafe(Build.BOOTLOADER);

        // 序列号：API 26-27 可直接读取物理序列号；28+ 多数设备仅返回 "unknown"，
        // 因此即便不抛异常结果也几乎无意义，但仍然按要求读取公共 API。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                String serial = Build.getSerial();
                b.serial = nullSafe(serial);
            } catch (SecurityException ignored) {
                // 未声明 READ_PHONE_STATE 时会抛 SecurityException，置 null。
                b.serial = null;
            }
        } else {
            b.serial = nullSafe(Build.SERIAL);
        }

        // Android ID 不需要任何权限；但 Android 8+ 每用户/每签名不同。
        try {
            b.androidId = Settings.Secure.getString(b.context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
            b.androidId = null;
        }

        // 软件版本字段。
        b.osVersion = nullSafe(Build.VERSION.RELEASE);
        b.sdkInt = Build.VERSION.SDK_INT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.releaseSdkInt = Build.VERSION.PREVIEW_SDK_INT > 0 ? Build.VERSION.PREVIEW_SDK_INT : Build.VERSION.SDK_INT;
        } else {
            b.releaseSdkInt = Build.VERSION.SDK_INT;
        }
        b.securityPatch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? nullSafe(Build.VERSION.SECURITY_PATCH) : null;
        b.buildFingerprint = nullSafe(Build.FINGERPRINT);
        b.buildId = nullSafe(Build.ID);
        b.buildDisplayId = nullSafe(Build.DISPLAY);
        b.buildTimeMillis = Build.TIME;
        b.buildType = nullSafe(Build.TYPE);
        b.buildTags = nullSafe(Build.TAGS);

        // CPU ABI：API 21+ 支持 Build.SUPPORTED_ABIS。
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            b.abi = nullSafe(abis[0]);
            b.abi2 = abis.length > 1 ? nullSafe(abis[1]) : null;
        } else {
            b.abi = null;
            b.abi2 = null;
        }
        b.cpuAbiCount = Runtime.getRuntime().availableProcessors();

        // 内存：ActivityManager.MemoryInfo 公共 API，但 SDK 不希望引入 Activity 依赖；
        // 这里直接读 /proc/meminfo，失败时回退到 Runtime 估算。
        long[] mem = readProcMemInfo();
        b.totalMemoryBytes = mem[0];
        b.availableMemoryBytes = mem[1];

        // 存储：内部存储 StatFs。
        long[] storage = readInternalStorage();
        b.totalStorageBytes = storage[0];
        b.availableStorageBytes = storage[1];

        // 屏幕：DisplayMetrics 免权限。
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) b.context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(metrics);
            } else {
                // fallback：从 Resources 拿一份系统 DisplayMetrics，
                // 注意这不是 RealMetrics，多窗口分屏下分辨率会偏小，但作为兜底足够。
                DisplayMetrics dm = b.context.getResources().getDisplayMetrics();
                metrics.setTo(dm);
            }
        } catch (Throwable ignored) {
            // 某些设备或受限环境可能抛异常，置零后继续。
        }
        b.screenWidthPixels = metrics.widthPixels;
        b.screenHeightPixels = metrics.heightPixels;
        b.screenDensity = metrics.density;
        b.screenXdpi = metrics.xdpi;
        b.screenYdpi = metrics.ydpi;
        b.screenDensityDpi = metrics.densityDpi;

        // 电池：免权限 Intent 风格。
        try {
            BatteryManager bm = (BatteryManager) b.context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                b.batteryLevel = level >= 0 ? level : null;
            } else {
                b.batteryLevel = null;
            }
        } catch (Throwable ignored) {
            b.batteryLevel = null;
        }
        b.batteryStatus = readBatteryStatus(b.context);
        b.batteryHealth = readBatteryHealth(b.context);

        // 网络：ConnectivityManager 公共 API；SSID 在 8.1+ 需要定位权限才返回真实值，
        // 未授权时会返回 "<unknown ssid>"，我们做一次去噪。
        try {
            ConnectivityManager cm = (ConnectivityManager) b.context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                if (active != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                    b.networkConnected = caps != null;
                    b.networkType = describeNetworkType(caps);
                } else {
                    b.networkConnected = false;
                    b.networkType = "NONE";
                }
            } else {
                b.networkConnected = false;
                b.networkType = "NONE";
            }
        } catch (Throwable ignored) {
            b.networkConnected = false;
            b.networkType = null;
        }
        b.wifiSsid = readWifiSsid(b.context);
        b.timezoneId = TimeZone.getDefault().getID();

        // 宿主 App 信息：从 PackageManager 拿自身包信息即可。
        try {
            PackageManager pm = b.context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(b.context.getPackageName(), 0);
            b.appPackageName = b.context.getPackageName();
            b.appVersionName = nullSafe(info.versionName);
            b.appVersionCode = info.versionCode >= 0
                    ? info.versionCode
                    : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : 0L);
            b.appFirstInstallTime = isoOrNull(info.firstInstallTime);
            b.appLastUpdateTime = isoOrNull(info.lastUpdateTime);
        } catch (PackageManager.NameNotFoundException exception) {
            Log.w(TAG, "Cannot read host package info", exception);
            b.appPackageName = b.context.getPackageName();
            b.appVersionName = null;
            b.appVersionCode = 0L;
            b.appFirstInstallTime = null;
            b.appLastUpdateTime = null;
        } catch (Throwable ignored) {
            b.appPackageName = b.context.getPackageName();
            b.appVersionName = null;
            b.appVersionCode = 0L;
            b.appFirstInstallTime = null;
            b.appLastUpdateTime = null;
        }

        return b.build();
    }

    public String getManufacturer() { return manufacturer; }
    public String getModel() { return model; }
    public String getBrand() { return brand; }
    public String getDevice() { return device; }
    public String getProduct() { return product; }
    public String getHardware() { return hardware; }
    public String getBoard() { return board; }
    public String getBootloader() { return bootloader; }
    public String getSerial() { return serial; }
    public String getAndroidId() { return androidId; }

    public String getOsVersion() { return osVersion; }
    public int getSdkInt() { return sdkInt; }
    public int getReleaseSdkInt() { return releaseSdkInt; }
    public String getSecurityPatch() { return securityPatch; }
    public String getBuildFingerprint() { return buildFingerprint; }
    public String getBuildId() { return buildId; }
    public String getBuildDisplayId() { return buildDisplayId; }
    public long getBuildTimeMillis() { return buildTimeMillis; }
    public String getBuildType() { return buildType; }
    public String getBuildTags() { return buildTags; }

    public String getAbi() { return abi; }
    public String getAbi2() { return abi2; }
    public int getCpuAbiCount() { return cpuAbiCount; }
    public long getTotalMemoryBytes() { return totalMemoryBytes; }
    public long getAvailableMemoryBytes() { return availableMemoryBytes; }
    public long getTotalStorageBytes() { return totalStorageBytes; }
    public long getAvailableStorageBytes() { return availableStorageBytes; }

    public int getScreenWidthPixels() { return screenWidthPixels; }
    public int getScreenHeightPixels() { return screenHeightPixels; }
    public float getScreenDensity() { return screenDensity; }
    public float getScreenXdpi() { return screenXdpi; }
    public float getScreenYdpi() { return screenYdpi; }
    public int getScreenDensityDpi() { return screenDensityDpi; }

    public Integer getBatteryLevel() { return batteryLevel; }
    public String getBatteryStatus() { return batteryStatus; }
    public String getBatteryHealth() { return batteryHealth; }

    public String getNetworkType() { return networkType; }
    public boolean isNetworkConnected() { return networkConnected; }
    public String getWifiSsid() { return wifiSsid; }
    public String getTimezoneId() { return timezoneId; }

    public String getAppPackageName() { return appPackageName; }
    public String getAppVersionName() { return appVersionName; }
    public long getAppVersionCode() { return appVersionCode; }
    public String getAppFirstInstallTime() { return appFirstInstallTime; }
    public String getAppLastUpdateTime() { return appLastUpdateTime; }

    private static String nullSafe(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long[] readProcMemInfo() {
        long total = 0L;
        long available = 0L;
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/meminfo"));
            try {
                String line;
                int read = 0;
                while ((line = reader.readLine()) != null && read < 2) {
                    if (line.startsWith("MemTotal")) {
                        total = parseKilobytes(line);
                        read++;
                    } else if (line.startsWith("MemAvailable")) {
                        available = parseKilobytes(line);
                        read++;
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
            // 读取失败保持 0。
        }
        if (available == 0L) {
            long max = Runtime.getRuntime().maxMemory();
            if (max > 0) available = max;
        }
        return new long[] { total, available };
    }

    private static long parseKilobytes(String line) {
        // /proc/meminfo 形如 "MemTotal:        3906492 kB"
        int colon = line.indexOf(':');
        if (colon < 0) return 0L;
        String tail = line.substring(colon + 1).trim().toLowerCase(Locale.US);
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c >= '0' && c <= '9') {
                number.append(c);
            } else if (number.length() > 0) {
                break;
            }
        }
        if (number.length() == 0) return 0L;
        long kilobytes = Long.parseLong(number.toString());
        return kilobytes * 1024L;
    }

    private static long[] readInternalStorage() {
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();
            return new long[] { blockSize * totalBlocks, blockSize * availableBlocks };
        } catch (Throwable ignored) {
            return new long[] { 0L, 0L };
        }
    }

    private static String readBatteryStatus(Context context) {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent battery = context.registerReceiver(null, filter);
            if (battery == null) return null;
            int status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            if (status == -1) return null;
            switch (status) {
                case android.os.BatteryManager.BATTERY_STATUS_CHARGING: return "CHARGING";
                case android.os.BatteryManager.BATTERY_STATUS_DISCHARGING: return "DISCHARGING";
                case android.os.BatteryManager.BATTERY_STATUS_FULL: return "FULL";
                case android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "NOT_CHARGING";
                default: return "UNKNOWN";
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readBatteryHealth(Context context) {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent battery = context.registerReceiver(null, filter);
            if (battery == null) return null;
            int health = battery.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, -1);
            if (health == -1) return null;
            switch (health) {
                case android.os.BatteryManager.BATTERY_HEALTH_GOOD: return "GOOD";
                case android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT: return "OVERHEAT";
                case android.os.BatteryManager.BATTERY_HEALTH_DEAD: return "DEAD";
                case android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "OVER_VOLTAGE";
                case android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "FAILURE";
                case android.os.BatteryManager.BATTERY_HEALTH_COLD: return "COLD";
                default: return "UNKNOWN";
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeNetworkType(NetworkCapabilities caps) {
        if (caps == null) return "NONE";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WIFI";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            // API 31 (Android 12) 起才暴露 getSubscriptionIds()；其它设备只需返回 CELLULAR 即可。
            return "CELLULAR";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        return "OTHER";
    }

    private static String readWifiSsid(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network active = cm.getActiveNetwork();
            if (active == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) context.getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null) return null;
            String ssid = info.getSSID();
            if (ssid == null) return null;
            // Android 把 SSID 包在双引号里；权限不足时返回 "<unknown ssid>"。
            String trimmed = ssid.trim();
            if (trimmed.isEmpty() || "<unknown ssid>".equalsIgnoreCase(trimmed)) return null;
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String isoOrNull(long millis) {
        if (millis <= 0L) return null;
        return new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .format(new java.util.Date(millis));
    }

    public static final class Builder {
        private Context context;
        private String manufacturer;
        private String model;
        private String brand;
        private String device;
        private String product;
        private String hardware;
        private String board;
        private String bootloader;
        private String serial;
        private String androidId;

        private String osVersion;
        private int sdkInt;
        private int releaseSdkInt;
        private String securityPatch;
        private String buildFingerprint;
        private String buildId;
        private String buildDisplayId;
        private long buildTimeMillis;
        private String buildType;
        private String buildTags;

        private String abi;
        private String abi2;
        private int cpuAbiCount;
        private long totalMemoryBytes;
        private long availableMemoryBytes;
        private long totalStorageBytes;
        private long availableStorageBytes;

        private int screenWidthPixels;
        private int screenHeightPixels;
        private float screenDensity;
        private float screenXdpi;
        private float screenYdpi;
        private int screenDensityDpi;

        private Integer batteryLevel;
        private String batteryStatus;
        private String batteryHealth;

        private String networkType;
        private boolean networkConnected;
        private String wifiSsid;
        private String timezoneId;

        private String appPackageName;
        private String appVersionName;
        private long appVersionCode;
        private String appFirstInstallTime;
        private String appLastUpdateTime;

        private Builder() {}

        DeviceInfo build() {
            return new DeviceInfo(this);
        }
    }
}

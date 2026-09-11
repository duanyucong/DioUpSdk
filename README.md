# DioUp Android SDK

[![](https://jitpack.io/v/com.github.duanyucong/DioUpSdk.svg)](https://jitpack.io/#com.github.duanyucong/DioUpSdk)

`DioUpSdk` 是 DioUp 设备管理平台的 Android Java SDK。核心库位于 `dio_up` 模块，提供设备注册、心跳、后台任务、APK 下载与安装、安装结果验证以及安全 FRP 命令执行能力。

## 环境要求

- Android `minSdk 24`
- Android `compileSdk 35`
- Java 11
- 服务端地址建议使用 HTTPS；本地调试可使用 HTTP，需自行在 Manifest 配置明文流量许可
- 所有网络、文件、安装和 FRP API 均为同步阻塞调用，必须在后台线程执行

## 引入 SDK

### 方式一：通过 JitPack 引入（推荐）

在宿主工程的 `settings.gradle` 中添加 JitPack 仓库：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

在宿主 App 的 `build.gradle` 中加入：

```groovy
dependencies {
    implementation 'com.github.duanyucong:DioUpSdk:dio_up:v1.0.0'
}
```

> 版本号替换为实际 tag，如 `v1.0.0`。JitPack 会自动构建并发布 AAR。

### 方式二：作为 Gradle 模块引入

将 `dio_up` 模块放入宿主工程，在宿主工程的 `settings.gradle` 中加入：

```groovy
include ':dio_up'

// 模块不在宿主根目录时指定实际路径。
project(':dio_up').projectDir = new File(rootDir, '../DioUpSdk/dio_up')
```

在宿主 App 的 `build.gradle` 中加入：

```groovy
dependencies {
    implementation project(':dio_up')
}
```

## 初始化

建议在宿主 `Application.onCreate()` 中尽早初始化。SDK 内部只保存 `applicationContext`，不会持有 Activity。

```java
import android.app.Application;

import com.dtcomg.dio_up.DioUp;
import com.dtcomg.dio_up.DioUpConfiguration;

public final class HostApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        DioUpConfiguration config = DioUpConfiguration.builder(
                        "https://your-server.example",
                        "prj_xxx"
                )
                .customDeviceName("Warehouse Phone 01")
                .hardwareName("Android Handheld")
                // 可省略，默认 10 秒；设置为 0 时禁用心跳。
                .heartbeatIntervalMillis(10_000)
                // 生产环境必须与服务端 DIOUP_FRP_MASTER_KEY 对应。
                .frpCommandSignatureKey("replace-with-production-key")
                .allowDowngrade(false)
                .build();

        DioUp.initialize(this, config);
    }
}
```

同时在宿主 `AndroidManifest.xml` 中注册该 Application：

```xml
<application
    android:name=".HostApplication"
    ... >
</application>
```

`DioUp.initialize()` 在同一进程内是幂等的，但使用不同配置重复初始化会抛出 `IllegalStateException`。

初始化会在 SDK 自己的后台线程中自动注册设备，并立即发送第一次心跳；之后按 `heartbeatIntervalMillis` 周期发送。未设置时默认间隔为 `10000` 毫秒，设置为 `0` 时仍会注册设备，但不会发送心跳。单次注册或心跳失败会在下一个周期重试，不会阻塞 `Application.onCreate()`。

初始化不读取位置、不申请位置权限，也不依赖 Activity，因此可以安全地放在 `Application.onCreate()` 中。

## 后台线程调用

位置上报、手动注册/心跳、任务执行、安装验证和 FRP 控制等同步 API 不能在 Android 主线程调用。自动注册和自动心跳由 SDK 自己的后台线程负责。宿主调用其他同步 API 时可以使用自己的 `ExecutorService`：

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

private final ExecutorService dioUpExecutor = Executors.newSingleThreadExecutor();
```

Activity 或 Service 不再使用执行器时，应调用 `shutdownNow()` 释放线程。

## 设备注册

宿主不需要主动注册。`initialize()` 会异步完成注册，并把安装实例 ID、项目设备 ID 和设备密钥保存在宿主私有 `SharedPreferences` 中。保留的 `register()` API 可用于需要同步等待注册结果的兼容场景，重复调用会复用本地注册状态。

## 运行时修改设备名称

设备展示名称可以在初始化时通过 `DioUpConfiguration.Builder.customDeviceName()` 设置，也可以在运行过程中单独修改：

```java
import com.dtcomg.dio_up.DioUp;

// 设置新的设备展示名称；网络同步由 SDK 自动在后台线程完成，可直接在主线程调用。
DioUp.setCustomDeviceName("Warehouse Phone 02");
```

`DioUp.setCustomDeviceName()` 的校验与进程内保存同步完成，网络同步自动在 SDK 后台线程异步执行，语义如下：

| 场景 | 行为 |
|---|---|
| 名称为 null 或空白 | 同步抛出 `IllegalArgumentException` |
| 与已同步到服务端的名称一致 | 直接返回，不发请求，服务端也不写库 |
| 尚未初始化或设备尚未注册 | 仅保存在进程内，注册时随注册请求上报（优先于初始化配置中的名称） |
| 设备已注册 | 后台线程调用独立接口更新服务端名称 |
| 同步失败 | 记录日志，不抛给调用方；期望值会保留，并在下次 `register()` 时自动补发 |

多次连续设置时按调用顺序串行同步，旧名称不会被覆盖到服务端。

读取当前进程内设置的名称：

```java
String runtimeName = DioUp.getCustomDeviceName();
```

运行时名称只保存在进程内，进程重启后会丢失；宿主应在每次启动后按需重新调用 `DioUp.setCustomDeviceName()`（与 `DioUp.initialize()` 恢复配置的模式一致）。

## 位置上报

位置上报与初始化、注册完全分离。宿主在获得位置权限和定位结果后，自行构造快照并上报：

```java
import com.dtcomg.dio_up.DioUpLocation;

import java.util.Date;

dioUpExecutor.execute(() -> {
    DioUp.reportLocation(
            new DioUpLocation(
                    31.2304,
                    121.4737,
                    12.5,
                    new Date()
            )
    );
});
```

调用 `reportLocation()` 前必须等待自动注册完成；也可以在同一后台任务中先调用幂等的 `DioUp.register()` 来确保注册完成。SDK 不会自行获取位置，也不会定时上报；上报频率、权限请求和定位策略完全由宿主控制。服务端只保留设备的最后位置快照，不保存轨迹。

`DioUpLocation` 会校验：

- 纬度范围为 `-90` 到 `90`
- 经度范围为 `-180` 到 `180`
- 精度必须为空或非负数，单位为米
- `capturedAt` 可为空；为空时服务端使用接收时间

## 心跳

心跳由 SDK 按配置间隔自动发送，宿主不需要创建定时器或调用 `heartbeat()`。心跳返回待处理任务时，SDK 会使用 WorkManager 自动入队唯一后台任务。保留的 `heartbeat()` API 仅用于需要立即触发一次心跳的兼容场景。

## APK 任务执行

宿主也可以主动拉取并执行待处理任务：

```java
import com.dtcomg.dio_up.TaskExecutionResult;
import com.dtcomg.dio_up.TaskExecutionSummary;

dioUpExecutor.execute(() -> {
    TaskExecutionSummary summary = DioUp.executePendingTasks();
    for (TaskExecutionResult result : summary.getResults()) {
        String status = result.getStatus();
        String message = result.getMessage();
    }
});
```

APK 下载支持：

- `.part` 文件断点续传和 HTTP Range
- 文件大小校验
- SHA-256 校验
- 已成功任务的幂等复用
- Room 本地任务及下载进度记录

任务结果状态包括：

- `SUCCEEDED`：下载和安装验证成功
- `WAITING_FOR_USER`：已打开系统安装界面，等待用户确认
- `FAILED`：下载、校验或安装失败

> `WAITING_FOR_USER` 是合法的中间态：SDK 在标准安装 Intent 路径下会自动上报一次，但**不会**自动结到 `SUCCEEDED`。需要由宿主在合适的时机（系统弹窗完成、`ACTION_PACKAGE_REPLACED` 广播、用户取消等）调 `DioUp.reportInstallResult(targetId, true|false|null, ...)` 推进。具体参见 [安装结果上报](#安装结果上报) 章节。

如需显式请求 WorkManager 执行任务：

```java
DioUp.enqueuePendingTaskExecution();
```

## 安装 APK

安装路径优先级为：

1. `packageInstaller`：宿主直接传入的安装器实例。
2. `reflectionInstallerClassName`：通过类名和无参构造器创建的安装器。
3. Android 标准安装 Intent。

### 自定义安装器

`DioPackageInstaller.install()` 内部负责真实安装（企业设备管理 API、`PackageInstaller.Session` 等），并把"结果"以 `InstallOutcome` 形式返回给 SDK：

| 返回值 | SDK 内部行为 |
|---|---|
| `InstallStatus.INSTALLED` | SDK 自动调用 `DioUp.reportInstallResult(targetId, true, ...)` |
| `InstallStatus.WAITING_FOR_USER` | SDK 自动调用 `DioUp.reportInstallResult(targetId, null, ...)` |
| `InstallStatus.FAILED` | SDK 自动调用 `DioUp.reportInstallResult(targetId, false, ...)` |

`InstallOutcome` **不**直接发网络请求，也不告诉后台——它只是给 SDK 用的内部协议。真正"告诉后台"的动作是 `DioUp.reportInstallResult`，由 SDK 在收到 `InstallOutcome` 之后自动触发；消息内容由 SDK 决定（"Installed"/"Install failed"/"Waiting for user confirmation"），`InstallOutcome.message` 字段仅写本地日志。

```java
import com.dtcomg.dio_up.DioUp;
import com.dtcomg.dio_up.InstallOutcome;
import com.dtcomg.dio_up.InstallStatus;

DioUpConfiguration config = DioUpConfiguration.builder(serverUrl, projectKey)
        .packageInstaller((context, apkFile, task) -> {
            // 在此调用企业设备管理 API 或自有静默安装能力。
            boolean installed = installApk(apkFile);
            return installed
                    ? new InstallOutcome(InstallStatus.INSTALLED, "Installed")
                    : new InstallOutcome(InstallStatus.FAILED, "Install failed");
        })
        .build();
```

自定义安装器返回 `INSTALLED` 后，SDK 仍会使用 `PackageManager` 校验实际包名和版本。

如果需要在返回 `InstallOutcome` 的同时立即向后台报告（不等 SDK 内部自动上报），可以同步调一次 `DioUp.reportInstallResult`，两个调用并存、幂等：

```java
.packageInstaller((context, apkFile, task) -> {
    boolean ok = installApk(apkFile);
    DioUp.reportInstallResult(task.getTargetId(), ok, "Custom installer result");
    return new InstallOutcome(ok ? InstallStatus.INSTALLED : InstallStatus.FAILED, "ok");
})
```

需要系统安装界面或其他用户操作时，返回：

```java
return new InstallOutcome(
        InstallStatus.WAITING_FOR_USER,
        "Waiting for user confirmation"
);
```

`WAITING_FOR_USER` 路径下 SDK 只会先把状态推进到 "等待用户确认"，**不会**自动结到 SUCCEEDED——必须由宿主在合适的时机调 `DioUp.reportInstallResult(targetId, true, ...)` 推进成功，或 `DioUp.reportInstallResult(targetId, false, ...)` 上报失败。

### 反射安装器

反射安装器必须是实现 `DioPackageInstaller` 的可构造类，并提供无参构造器：

```java
import android.content.Context;

import com.dtcomg.dio_up.DioInstaller;
import com.dtcomg.dio_up.InstallOutcome;
import com.dtcomg.dio_up.InstallStatus;
import com.dtcomg.dio_up.PendingTask;

import java.io.File;

public final class EnterpriseInstaller extends DioInstaller {
    public EnterpriseInstaller() {
    }

    @Override
    public InstallOutcome install(Context context, File apkFile, PendingTask task) {
        return new InstallOutcome(InstallStatus.WAITING_FOR_USER);
    }
}
```

配置类名：

```java
.reflectionInstallerClassName(EnterpriseInstaller.class.getName())
```

启用 R8/ProGuard 时，需要保留反射安装器：

```proguard
-keep class com.example.EnterpriseInstaller {
    public <init>();
    *;
}
```

### 安装结果上报

`DioUp.reportInstallResult` 是 SDK **唯一**的"告诉后台安装完成"入口。SDK 不会主动扫描已装应用、不监听广播、不轮询 PackageManager；什么时机上报、安装成功还是失败、由谁上报，完全由宿主决定。

```java
import com.dtcomg.dio_up.DioUp;

// 上报成功
DioUp.reportInstallResult(targetId, true,  "已装好 v1.2.3");

// 上报失败
DioUp.reportInstallResult(targetId, false, "用户取消 / 校验失败 / 安装异常");

// 上报中间态：已发起系统安装弹窗，等待用户确认
DioUp.reportInstallResult(targetId, null, "已拉起系统安装界面");
```

参数语义：

| 参数 | 含义 |
|---|---|
| `targetId` | 来自 `PendingTask.getTargetId()`，对应后台任务目标 ID |
| `success = true`  | 上报 SUCCEEDED，progress=100 |
| `success = false` | 上报 FAILED，progress=0 |
| `success = null`  | 上报 WAITING_FOR_USER，progress=99 |
| `message`        | 后台可见的说明；为 null 时使用 SDK 默认值（"Installed"/"Install failed"/"Waiting for user confirmation"） |

#### 三种典型接入

**1. 自定义安装器（推荐）**

在 `DioPackageInstaller.install()` 内部等异步安装结果后调：

```java
.packageInstaller((ctx, apk, task) -> {
    // 例如 PackageInstaller.Session.commit 异步回调里
    session.commit(intentSender -> {
        int status = intentSender.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        DioUp.reportInstallResult(task.getTargetId(), ok, "status=" + status);
    });
    return new InstallOutcome(InstallStatus.WAITING_FOR_USER, "已发起");
})
```

**2. 系统标准 Intent（拉起系统安装弹窗）**

`Intent.ACTION_VIEW` 没有回调，必须监听 `ACTION_PACKAGE_REPLACED` 广播：

```java
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

BroadcastReceiver replaced = new BroadcastReceiver() {
    @Override
    public void onReceive(android.content.Context c, Intent intent) {
        if (!Intent.ACTION_PACKAGE_REPLACED.equals(intent.getAction())
                && !Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String pkg = uri.getSchemeSpecificPart();
        // 用你持有的 mapping 找到该包名对应的 targetId 后调
        // DioUp.reportInstallResult(targetId, true, "系统安装完成");
    }
};
IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
filter.addDataScheme("package");
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(replaced, filter, android.content.Context.RECEIVER_EXPORTED);
} else {
    registerReceiver(replaced, filter);
}
```

**3. 取消/超时上报**

用户在系统弹窗返回、取消、长时间无响应时，宿主应主动上报 `FAILED` 以便后台结束任务：

```java
DioUp.reportInstallResult(targetId, false, "用户取消安装");
```

#### 行为约束

- `reportInstallResult` 内部会写本地 Room + 调 `DioUpClient.result(...)` 上报服务端，两者皆为同步阻塞调用，**必须**在后台线程执行。
- 多次调用同一 `targetId` 幂等覆盖，**后调用的状态生效**。
- 调用前必须先 `DioUp.initialize(...)` 并完成 `DioUp.register()`。
- 目标 ID 对应的本地记录不存在时仅记 warn 日志，不抛异常。
- `WAITING_FOR_USER` 是合法的中间态；SDK 会在 `standardInstall` 路径自动上报一次，但 **不会**自动结到 SUCCEEDED，必须由宿主在系统弹窗完成后调 `reportInstallResult` 推进。

## 自定义下载目录

```java
import java.io.File;

DioUpConfiguration config = DioUpConfiguration.builder(serverUrl, projectKey)
        .downloadDirectoryProvider(context ->
                new File(context.getFilesDir(), "managed_apks"))
        .build();
```

返回目录必须可以创建和写入。使用默认配置时，文件保存在：

```text
<external storage root>/dioup/
```

## FRP

### 安全命令流程

设备首次注册时，SDK 会生成 RSA 命令密钥对并向服务端上报公钥。收到 `FRP_COMMAND` 后，SDK 会依次执行：

1. 校验命令过期时间和 nonce 防重放状态。
2. 使用设备 RSA 私钥解密一次性 AES 密钥。
3. 使用 AES-GCM 解密命令载荷。
4. 使用 `frpCommandSignatureKey` 校验 HMAC-SHA256 签名。
5. 校验项目设备 ID、目标 ID 和 nonce 绑定。
6. 加载指定版本的 FRP AAR，并启动前台服务。

生产环境必须为服务端配置 `DIOUP_FRP_MASTER_KEY`，并在 SDK 初始化时设置对应的 `frpCommandSignatureKey`。默认开发密钥不能用于生产环境。

### 手动控制 FRP

手动方式建议仅用于调试，并且必须在后台线程调用：

```java
import com.dtcomg.dio_up.FrpConfig;
import com.dtcomg.dio_up.FrpManager;
import com.dtcomg.dio_up.FrpStatus;

String content =
        "serverAddr = \"frp.example.com\"\n" +
        "serverPort = 7000\n" +
        "auth.token = \"token\"\n\n" +
        "[[proxies]]\n" +
        "name = \"manual-debug\"\n" +
        "type = \"tcp\"\n" +
        "localIP = \"127.0.0.1\"\n" +
        "localPort = 22\n" +
        "remotePort = 20022\n";

dioUpExecutor.execute(() -> {
    FrpStatus started = FrpManager.start(
            new FrpConfig("manual-debug", content)
    );

    FrpStatus current = FrpManager.status(started.getUid());
    FrpStatus stopped = FrpManager.stop(current.getUid());
});
```

## Manifest 与权限

作为模块或 AAR 引入时，SDK Manifest 会自动合并以下权限和组件：

- `INTERNET`
- `REQUEST_INSTALL_PACKAGES`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `RECEIVE_BOOT_COMPLETED`
- FRP 前台 Service
- 开机恢复 BroadcastReceiver
- APK FileProvider

注意事项：

- Android 8.0 及以上使用标准安装 Intent 时，用户必须允许宿主“安装未知应用”。
- Android 13 及以上如需正常展示前台服务通知，宿主应按业务需要申请通知权限。
- 新版 Android 对后台启动前台服务有限制，手动启动 FRP 时建议保证宿主处于允许启动前台服务的状态。
- SDK 不声明位置权限；宿主需要定位时，应自行声明并申请相应权限。

## 进程恢复说明

SDK 会持久化服务端地址、项目 Key、注册凭证和 FRP 状态，使 WorkManager、前台服务及开机广播能够在进程重建后恢复最小运行环境。

Java 回调对象不能跨进程持久化，包括：

- `packageInstaller`
- `downloadDirectoryProvider`

因此宿主应在每次进程启动时尽早调用 `DioUp.initialize()`，恢复完整配置。

## 构建与验证

Windows：

```powershell
.\gradlew.bat :dio_up:testDebugUnitTest :dio_up:lintDebug :dio_up:assembleRelease
```

macOS/Linux：

```bash
./gradlew :dio_up:testDebugUnitTest :dio_up:lintDebug :dio_up:assembleRelease
```

示例工程：

- `app`：完整 Java 接入示例，可执行注册、心跳和任务处理。
- `app_alt`：最小化宿主接入示例。

## 常见错误

### `DioUp.initialize must be called first`

在调用注册、心跳、任务或 FRP API 前，先在当前进程完成初始化。

### `DioUp.register must complete before heartbeat`

当前项目还没有有效的本地注册凭证。通常只需等待 `initialize()` 的自动注册完成；如果业务必须立即发送手动心跳，可在同一后台任务中先调用幂等的 `DioUp.register()`。

### `DioUp has already been initialized with a different configuration`

同一进程已经使用其他配置初始化。请保证宿主只创建并复用一份等价配置。

### `NetworkOnMainThreadException`

SDK 操作为同步阻塞 API，请使用 `ExecutorService`、WorkManager 或宿主已有的后台执行框架调用。

### `DioUp.register must complete before reporting install result`

调用 `DioUp.reportInstallResult(...)` 之前，设备必须先完成自动注册。请确保 `DioUp.initialize(...)` 已经触发过 `DioUp.register()`；如手动管理注册时机，可在同一后台任务中先调用幂等的 `DioUp.register()` 再上报。

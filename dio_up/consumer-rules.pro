# DioUp SDK 公共 API 全部保留，避免宿主开启 R8/ProGuard 后反射/equals 链断裂。
-keep public class com.dtcomg.dio_up.DioUp { *; }
-keep public class com.dtcomg.dio_up.DioUpConfiguration { *; }
-keep public class com.dtcomg.dio_up.DioUpConfiguration$Builder { *; }
-keep public class com.dtcomg.dio_up.DioPackageInstaller { *; }
-keep public class com.dtcomg.dio_up.DioInstaller { *; }
-keep public class com.dtcomg.dio_up.DownloadDirectoryProvider { *; }
-keep public class com.dtcomg.dio_up.DownloadProgressListener { *; }
-keep public class com.dtcomg.dio_up.PendingTask { *; }
-keep public class com.dtcomg.dio_up.HeartbeatState { *; }
-keep public class com.dtcomg.dio_up.RegistrationState { *; }
-keep public class com.dtcomg.dio_up.TaskExecutionResult { *; }
-keep public class com.dtcomg.dio_up.TaskExecutionSummary { *; }
-keep public class com.dtcomg.dio_up.DioUpLocation { *; }
-keep public class com.dtcomg.dio_up.InstallOutcome { *; }
-keep public class com.dtcomg.dio_up.InstallStatus { *; }
-keep public class com.dtcomg.dio_up.DeviceInfo { *; }
-keep public class com.dtcomg.dio_up.FrpConfig { *; }
-keep public class com.dtcomg.dio_up.FrpManager { *; }
-keep public class com.dtcomg.dio_up.FrpStatus { *; }
-keep public class com.dtcomg.dio_up.DefaultDownloadProgressDialog { *; }

# internal 包虽然理论上是实现细节，但被心跳/FRP/下载链路反射调用，统一保留。
-keep class com.dtcomg.dio_up.internal.** { *; }

# Room 生成的实现类是运行时通过注解反射装载的，混淆会直接导致数据库打不开。
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# OkHttp / OkIO 自带规则，但保守起见显式声明。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 保留行号信息，便于宿主侧排查 SDK 内部崩溃堆栈。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

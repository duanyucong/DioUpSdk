package com.dtcomg.dio_up.internal;

import android.util.Base64;

import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.FrpConfig;
import com.dtcomg.dio_up.FrpManager;
import com.dtcomg.dio_up.FrpStatus;
import com.dtcomg.dio_up.PendingTask;
import com.dtcomg.dio_up.RegistrationState;
import com.dtcomg.dio_up.TaskExecutionResult;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class DioUpFrpCommandExecutor {
    // 配置中包含命令签名密钥和服务端地址。
    private final DioUpConfiguration config;
    // 注册状态用于绑定命令目标并完成结果上报。
    private final RegistrationState state;
    // 本地存储提供 RSA 私钥与 nonce 防重放记录。
    private final DioUpStore store;
    private final DioUpClient client;

    public DioUpFrpCommandExecutor(
            DioUpConfiguration config,
            RegistrationState state,
            DioUpStore store
    ) {
        this(config, state, store, new DioUpClient());
    }

    public DioUpFrpCommandExecutor(
            DioUpConfiguration config,
            RegistrationState state,
            DioUpStore store,
            DioUpClient client
    ) {
        this.config = config;
        this.state = state;
        this.store = store;
        this.client = client;
    }

    public TaskExecutionResult execute(PendingTask task) {
        try {
            // 先 ACK 让服务端知道密文已经到达设备，后续安全校验仍可能失败。
            client.ack(config, state, task.getTargetId(), "FRP command received");
            String nonce = require(task.getFrpNonce(), "FRP nonce missing");
            // 已处理过的 nonce 永远不允许再次执行。
            if (store.hasSeenNonce(nonce)) {
                throw new IllegalArgumentException("FRP nonce replay rejected");
            }
            // 过期检查在解密前完成，减少无效密文的计算开销。
            if (parseTimestamp(task.getExpiresAt()).getTime() <= System.currentTimeMillis()) {
                throw new IllegalArgumentException("FRP command expired");
            }

            // 先用设备私钥解封 AES 密钥，再用 AES-GCM 解密规范化 JSON。
            String canonical = decryptCanonical(task);
            // HMAC 校验必须在解析和使用任何命令字段之前完成。
            verifySignature(
                    canonical,
                    require(task.getFrpSignature(), "FRP signature missing")
            );
            JSONObject payload = new JSONObject(canonical);
            // 三重绑定设备、目标和 nonce，防止合法密文被转发到其他任务。
            if (payload.getLong("projectDeviceId") != state.getProjectDeviceId()) {
                throw new IllegalArgumentException("FRP command device mismatch");
            }
            if (payload.getLong("targetId") != task.getTargetId()) {
                throw new IllegalArgumentException("FRP command target mismatch");
            }
            if (!nonce.equals(payload.getString("nonce"))) {
                throw new IllegalArgumentException("FRP command nonce mismatch");
            }

            // 完成所有密码学与上下文校验后，才允许读取并执行 FRP 配置。
            String uid = payload.getString("uid");
            String content = payload.getString("content");
            client.progress(
                    config,
                    state,
                    task.getTargetId(),
                    20,
                    "FRP command verified"
            );
            FrpStatus status = FrpManager.start(new FrpConfig(uid, content));
            // 仅在命令成功启动后写入 nonce，失败任务仍可按服务端策略重试。
            store.markNonceSeen(nonce);
            String message = status.getMessage() == null ? "FRP started" : status.getMessage();
            client.result(
                    config,
                    state,
                    task.getTargetId(),
                    100,
                    "SUCCEEDED",
                    message
            );
            return new TaskExecutionResult(
                    task.getTaskId(),
                    task.getTargetId(),
                    "SUCCEEDED",
                    null,
                    message
            );
        } catch (Exception exception) {
            // 所有解析、密码学和运行时异常都转换为任务失败结果。
            client.result(
                    config,
                    state,
                    task.getTargetId(),
                    0,
                    "FAILED",
                    exception.getMessage()
            );
            return new TaskExecutionResult(
                    task.getTaskId(),
                    task.getTargetId(),
                    "FAILED",
                    null,
                    exception.getMessage()
            );
        }
    }

    private String decryptCanonical(PendingTask task) throws GeneralSecurityException {
        // encryptedKey 使用注册时上报公钥对应的 RSA 私钥解密。
        byte[] encryptedKey = Base64.decode(
                require(task.getFrpEncryptedKey(), "FRP encrypted key missing"),
                Base64.NO_WRAP
        );
        Cipher keyCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        keyCipher.init(Cipher.DECRYPT_MODE, store.commandPrivateKey());
        byte[] dataKey = keyCipher.doFinal(encryptedKey);

        // 服务端将 GCM authentication tag 与密文分字段传输，解密前需要拼接。
        byte[] body = Base64.decode(
                require(task.getFrpEncryptedPayload(), "FRP payload missing"),
                Base64.NO_WRAP
        );
        byte[] tag = Base64.decode(
                require(task.getFrpTag(), "FRP tag missing"),
                Base64.NO_WRAP
        );
        byte[] combined = new byte[body.length + tag.length];
        System.arraycopy(body, 0, combined, 0, body.length);
        System.arraycopy(tag, 0, combined, body.length, tag.length);

        // GCMParameterSpec 同时指定 128 位认证标签和本次命令的随机 IV。
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(dataKey, "AES"),
                new GCMParameterSpec(
                        128,
                        Base64.decode(
                                require(task.getFrpIv(), "FRP IV missing"),
                                Base64.NO_WRAP
                        )
                )
        );
        return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
    }

    private void verifySignature(String canonical, String signature)
            throws GeneralSecurityException {
        // 先哈希配置密钥得到固定长度 HMAC key，与服务端派生规则保持一致。
        byte[] master = MessageDigest.getInstance("SHA-256").digest(
                config.getFrpCommandSignatureKey().getBytes(StandardCharsets.UTF_8)
        );
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        String expected = Base64.encodeToString(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP
        );
        // MessageDigest.isEqual 使用近似常量时间比较，降低时序侧信道风险。
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new IllegalArgumentException("FRP command signature invalid");
        }
    }

    private static Date parseTimestamp(String value) {
        // Date/SimpleDateFormat 可在 minSdk 24 使用；这里兼容 Z 和显式时区偏移。
        int timeIndex = value.indexOf('T');
        int zoneIndex = value.endsWith("Z") ? value.length() - 1 : -1;
        if (zoneIndex < 0) {
            int plus = value.lastIndexOf('+');
            int minus = value.lastIndexOf('-');
            zoneIndex = Math.max(plus, minus);
            if (zoneIndex < timeIndex) {
                zoneIndex = -1;
            }
        }
        if (timeIndex < 0 || zoneIndex < 0) {
            throw new IllegalArgumentException("Invalid FRP expiration timestamp");
        }

        int fractionIndex = value.indexOf('.', timeIndex);
        String normalized;
        // SimpleDateFormat 固定读取三位毫秒，缺失时补零，过长时截断。
        if (fractionIndex < 0 || fractionIndex > zoneIndex) {
            normalized = value.substring(0, zoneIndex) + ".000" + value.substring(zoneIndex);
        } else {
            String fraction = value.substring(fractionIndex + 1, zoneIndex);
            String milliseconds = (fraction + "000").substring(0, 3);
            normalized = value.substring(0, fractionIndex + 1)
                    + milliseconds
                    + value.substring(zoneIndex);
        }

        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                Locale.US
        );
        // 禁用宽松模式，拒绝自动滚动的非法日期，例如 13 月或 61 秒。
        format.setLenient(false);
        try {
            Date parsed = format.parse(normalized);
            if (parsed == null) {
                throw new IllegalArgumentException("Invalid FRP expiration timestamp");
            }
            return parsed;
        } catch (ParseException exception) {
            throw new IllegalArgumentException("Invalid FRP expiration timestamp", exception);
        }
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}

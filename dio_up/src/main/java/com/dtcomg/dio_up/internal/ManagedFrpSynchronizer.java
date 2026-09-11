package com.dtcomg.dio_up.internal;

import android.content.Intent;
import android.util.Base64;

import com.dtcomg.dio_up.DioUpConfiguration;
import com.dtcomg.dio_up.FrpConfig;
import com.dtcomg.dio_up.FrpManager;
import com.dtcomg.dio_up.HeartbeatState;
import com.dtcomg.dio_up.RegistrationState;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ManagedFrpSynchronizer {
    public static final String UID = "managed_frpc_config";

    private ManagedFrpSynchronizer() {}

    public static void apply(DioUpConfiguration config, RegistrationState registration, DioUpStore store,
                             DioUpClient client, HeartbeatState heartbeat) {
        if (heartbeat.isPendingStop()) {
            stop(config, store, true);
            client.confirmFrpStop(config, registration);
            return;
        }
        if (heartbeat.getConfigExpiresAt() > 0 && heartbeat.getConfigExpiresAt() <= System.currentTimeMillis()) {
            stop(config, store, false);
            return;
        }
        if (heartbeat.getConfigVersion() > store.localFrpConfigVersion()
                && heartbeat.getConfigExpiresAt() > System.currentTimeMillis()) {
            // 服务端“再次启动”会生成新版本和新的未来有效期；它应显式解除之前远程停止留下的保护标记。
            store.clearFrpStopRequested();
            JSONObject payload = client.fetchFrpConfig(config, registration);
            String content = decrypt(registration.getDeviceSecret(), payload);
            FrpManager.start(new FrpConfig(UID, content));
            store.markManagedFrpConfig(payload.optLong("configVersion"),
                    parseInstant(payload.optString("expiresAt")));
            return;
        }
        DioUpStore.FrpState state = store.readFrpState();
        if (state != null && !state.isStopped() && !store.isFrpStopRequested()
                && !FrpManager.status(UID).isRunning()) {
            FrpManager.start(new FrpConfig(UID, state.getContent(), state.getVersion()));
        }
    }

    private static void stop(DioUpConfiguration config, DioUpStore store, boolean remote) {
        try { FrpManager.stop(UID); } catch (RuntimeException ignored) { }
        config.getApplicationContext().stopService(new Intent(config.getApplicationContext(), FrpForegroundService.class));
        store.clearManagedFrp(remote);
    }

    private static String decrypt(String secret, JSONObject payload) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            byte[] body = Base64.decode(payload.getString("encryptedContent"), Base64.NO_WRAP);
            byte[] tag = Base64.decode(payload.getString("tag"), Base64.NO_WRAP);
            byte[] combined = new byte[body.length + tag.length];
            System.arraycopy(body, 0, combined, 0, body.length);
            System.arraycopy(tag, 0, combined, body.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.decode(payload.getString("iv"), Base64.NO_WRAP)));
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("Cannot decrypt FRP config", exception); }
    }

    private static long parseInstant(String value) {
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(value);
            return parsed == null ? 0 : parsed.getTime();
        } catch (Exception exception) { return 0; }
    }
}

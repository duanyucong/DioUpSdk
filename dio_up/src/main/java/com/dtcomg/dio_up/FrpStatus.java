package com.dtcomg.dio_up;

import java.util.Objects;

public final class FrpStatus {
    // 被查询或操作的 FRP 实例标识。
    private final String uid;
    // 动态运行库返回的实时运行状态。
    private final boolean running;
    // 当前加载的 FRP 运行库版本。
    private final String version;
    // 启停过程返回的可选信息。
    private final String message;

    public FrpStatus(String uid, boolean running, String version) {
        this(uid, running, version, null);
    }

    public FrpStatus(String uid, boolean running, String version, String message) {
        this.uid = Objects.requireNonNull(uid, "uid");
        this.running = running;
        this.version = version;
        this.message = message;
    }

    public String getUid() {
        return uid;
    }

    public boolean isRunning() {
        return running;
    }

    public String getVersion() {
        return version;
    }

    public String getMessage() {
        return message;
    }
}

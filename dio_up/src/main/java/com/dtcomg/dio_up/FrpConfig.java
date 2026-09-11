package com.dtcomg.dio_up;

import java.util.Objects;

public final class FrpConfig {
    // FRP 运行实例标识，用于查询和关闭指定隧道。
    private final String uid;
    // 完整 FRP 配置文本。
    private final String content;
    // assets/lib_versions 下对应的运行库版本。
    private final String version;

    public FrpConfig(String uid, String content) {
        this(uid, content, "0.69.1");
    }

    public FrpConfig(String uid, String content, String version) {
        this.uid = Objects.requireNonNull(uid, "uid");
        this.content = Objects.requireNonNull(content, "content");
        this.version = Objects.requireNonNull(version, "version");
    }

    public String getUid() {
        return uid;
    }

    public String getContent() {
        return content;
    }

    public String getVersion() {
        return version;
    }
}

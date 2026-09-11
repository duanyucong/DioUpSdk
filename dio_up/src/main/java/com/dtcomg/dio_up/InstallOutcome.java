package com.dtcomg.dio_up;

import java.util.Objects;

public final class InstallOutcome {
    // 安装器必须明确返回三态之一。
    private final InstallStatus status;
    // 可选的人类可读说明，会同步上报服务端。
    private final String message;

    public InstallOutcome(InstallStatus status) {
        this(status, null);
    }

    public InstallOutcome(InstallStatus status, String message) {
        this.status = Objects.requireNonNull(status, "status");
        this.message = message;
    }

    public InstallStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}

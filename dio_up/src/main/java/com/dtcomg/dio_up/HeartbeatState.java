package com.dtcomg.dio_up;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class HeartbeatState {
    // 服务端确认的当前在线状态。
    private final String onlineStatus;
    // 本次心跳返回的待处理任务快照。
    private final List<PendingTask> pendingTasks;
    private final long configVersion;
    private final long configExpiresAt;
    private final boolean pendingStop;

    public HeartbeatState(String onlineStatus) {
        this(onlineStatus, Collections.emptyList(), 0, 0, false);
    }

    public HeartbeatState(String onlineStatus, List<PendingTask> pendingTasks) {
        this(onlineStatus, pendingTasks, 0, 0, false);
    }

    public HeartbeatState(String onlineStatus, List<PendingTask> pendingTasks, long configVersion,
                          long configExpiresAt, boolean pendingStop) {
        this.onlineStatus = Objects.requireNonNull(onlineStatus, "onlineStatus");
        // 防御性复制，避免调用方修改 SDK 已接收的任务列表。
        this.pendingTasks = Collections.unmodifiableList(new ArrayList<>(pendingTasks));
        this.configVersion = configVersion;
        this.configExpiresAt = configExpiresAt;
        this.pendingStop = pendingStop;
    }

    public String getOnlineStatus() {
        return onlineStatus;
    }

    public List<PendingTask> getPendingTasks() {
        return pendingTasks;
    }

    public long getConfigVersion() { return configVersion; }
    public long getConfigExpiresAt() { return configExpiresAt; }
    public boolean isPendingStop() { return pendingStop; }
}

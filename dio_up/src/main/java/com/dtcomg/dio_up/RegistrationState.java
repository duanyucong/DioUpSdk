package com.dtcomg.dio_up;

import java.util.Objects;

public final class RegistrationState {
    // 服务端分配的项目设备主键。
    private final long projectDeviceId;
    // 设备后续请求使用的私密凭证，不应记录到日志。
    private final String deviceSecret;

    public RegistrationState(long projectDeviceId, String deviceSecret) {
        this.projectDeviceId = projectDeviceId;
        this.deviceSecret = Objects.requireNonNull(deviceSecret, "deviceSecret");
    }

    public long getProjectDeviceId() {
        return projectDeviceId;
    }

    public String getDeviceSecret() {
        return deviceSecret;
    }
}

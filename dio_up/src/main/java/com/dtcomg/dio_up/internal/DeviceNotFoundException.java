package com.dtcomg.dio_up.internal;

/** 服务端返回 HTTP 404 表示设备凭证已失效（通常是被管理端删除），
 * 调用方应清除本地凭证并走完整注册流程重新登记设备。 */
public final class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String message) {
        super(message);
    }
}

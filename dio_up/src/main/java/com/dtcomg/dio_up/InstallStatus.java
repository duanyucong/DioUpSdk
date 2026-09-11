package com.dtcomg.dio_up;

public enum InstallStatus {
    // 安装完成且可立即校验包信息。
    INSTALLED,
    // 已拉起系统安装界面，等待用户操作。
    WAITING_FOR_USER,
    // 安装器确认无法完成安装。
    FAILED
}

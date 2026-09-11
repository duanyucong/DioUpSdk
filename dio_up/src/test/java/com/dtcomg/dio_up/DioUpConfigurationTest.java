package com.dtcomg.dio_up;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DioUpConfigurationTest {
    @Test
    public void storesRegistrationParameters() {
        DioUpConfiguration config =
                new DioUpConfiguration("https://server.example", "project-a");
        assertEquals("project-a", config.getProjectKey());
        assertEquals(10_000L, config.getHeartbeatIntervalMillis());
    }

    @Test
    public void storesCustomHeartbeatIntervalAndAllowsZero() {
        DioUpConfiguration disabled = DioUpConfiguration.builder(
                        "https://server.example", "project-a")
                .heartbeatIntervalMillis(0)
                .build();
        DioUpConfiguration custom = DioUpConfiguration.builder(
                        "https://server.example", "project-a")
                .heartbeatIntervalMillis(2_500)
                .build();

        assertEquals(0L, disabled.getHeartbeatIntervalMillis());
        assertEquals(2_500L, custom.getHeartbeatIntervalMillis());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeHeartbeatInterval() {
        DioUpConfiguration.builder("https://server.example", "project-a")
                .heartbeatIntervalMillis(-1);
    }

    @Test
    public void withCustomDeviceNameReplacesOnlyTheDisplayName() {
        DioUpConfiguration original = DioUpConfiguration.builder("https://server.example", "project-a")
                .customDeviceName("Original")
                .hardwareName("Pixel-9")
                .heartbeatIntervalMillis(2_500)
                .build();

        DioUpConfiguration renamed = original.withCustomDeviceName("Runtime Name");

        assertEquals("Runtime Name", renamed.getCustomDeviceName());
        assertEquals("Pixel-9", renamed.getHardwareName());
        assertEquals(2_500L, renamed.getHeartbeatIntervalMillis());
        assertEquals("https://server.example", renamed.getServerUrl());
        // 原配置保持不可变。
        assertEquals("Original", original.getCustomDeviceName());
    }
}

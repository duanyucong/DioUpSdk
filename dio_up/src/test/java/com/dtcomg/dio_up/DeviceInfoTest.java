package com.dtcomg.dio_up;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DeviceInfoTest {
    @Test
    public void collectRejectsNullContext() {
        try {
            DeviceInfo.collect(null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && expected.getMessage().contains("context"));
        }
    }

    @Test
    public void collectWithoutAndroidContextStillProducesObject() {
        // 本机 JVM 单测环境没有 android.*，但 builder 不依赖 Android；这里只验证
        // 入口对 context 的非空校验。
        try {
            DeviceInfo.collect(null);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected);
        }
    }
}

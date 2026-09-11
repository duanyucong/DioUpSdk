package com.dtcomg.dio_up;

import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class DioUpLocationTest {
    @Test
    public void storesValidLocationAndDefensivelyCopiesDate() {
        Date capturedAt = new Date(1_700_000_000_000L);
        DioUpLocation location = new DioUpLocation(31.2304, 121.4737, 12.5, capturedAt);

        capturedAt.setTime(0);

        assertEquals(31.2304, location.getLatitude(), 0.0);
        assertEquals(1_700_000_000_000L, location.getCapturedAt().getTime());
    }

    @Test
    public void rejectsInvalidCoordinatesAndAccuracy() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DioUpLocation(90.1, 121.4737)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DioUpLocation(31.2304, -180.1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DioUpLocation(31.2304, 121.4737, -1.0)
        );
    }
}

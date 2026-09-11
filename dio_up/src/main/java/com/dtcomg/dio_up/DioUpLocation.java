package com.dtcomg.dio_up;

import java.util.Date;

public final class DioUpLocation {
    // WGS84 纬度。
    private final double latitude;
    // WGS84 经度。
    private final double longitude;
    // 可选的定位精度半径，单位为米。
    private final Double accuracyMeters;
    // 位置采集时间；使用 Date 以兼容 minSdk 24。
    private final Date capturedAt;

    public DioUpLocation(double latitude, double longitude) {
        this(latitude, longitude, null, null);
    }

    public DioUpLocation(double latitude, double longitude, Double accuracyMeters) {
        this(latitude, longitude, accuracyMeters, null);
    }

    public DioUpLocation(double latitude, double longitude, Double accuracyMeters, Date capturedAt) {
        if (Double.isNaN(latitude) || Double.isInfinite(latitude)
                || latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (Double.isNaN(longitude) || Double.isInfinite(longitude)
                || longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
        if (accuracyMeters != null
                && (Double.isNaN(accuracyMeters)
                || Double.isInfinite(accuracyMeters)
                || accuracyMeters < 0.0)) {
            throw new IllegalArgumentException("accuracyMeters must be non-negative");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        // Date 可变，因此构造时复制，避免调用方修改已保存的位置时间。
        this.capturedAt = capturedAt == null ? null : new Date(capturedAt.getTime());
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Double getAccuracyMeters() {
        return accuracyMeters;
    }

    public Date getCapturedAt() {
        // 返回副本，保持 DioUpLocation 的不可变语义。
        return capturedAt == null ? null : new Date(capturedAt.getTime());
    }
}

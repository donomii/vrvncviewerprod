package com.praeceptamachinae.vrvncviewer;

import android.graphics.Matrix;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

class SensorPanController implements SensorEventListener, FrameView.FrameTransform {
    private static final float FILTER_ALPHA = 0.98f;
    private static final float YAW_RANGE = 0.6f;
    private static final float PITCH_RANGE = 0.6f;

    private final float density;
    private float yaw;
    private float pitch;
    private float centerYaw;
    private float centerPitch;
    private long lastGyroTimestamp;

    SensorPanController(float density) {
        this.density = density;
    }

    void reset() {
        lastGyroTimestamp = 0;
    }

    void recenter() {
        centerYaw = yaw;
        centerPitch = pitch;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (lastGyroTimestamp != 0) {
                float dt = (event.timestamp - lastGyroTimestamp) * 1e-9f;
                yaw += event.values[2] * dt;
                pitch += event.values[0] * dt;
            }
            lastGyroTimestamp = event.timestamp;
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float ax = event.values[0];
            float ay = event.values[1];
            float az = event.values[2];
            float accelPitch = (float) Math.atan2(-ax, Math.sqrt(ay * ay + az * az));
            pitch = FILTER_ALPHA * pitch + (1.0f - FILTER_ALPHA) * accelPitch;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op
    }

    @Override
    public void updateMatrix(Matrix outMatrix, int viewW, int viewH, int frameW, int frameH) {
        if (frameW <= 0 || frameH <= 0 || viewW <= 0 || viewH <= 0) {
            return;
        }

        float scale;
        float panX = 0.0f;
        float panY = 0.0f;

        float dpW = viewW / density;
        float dpH = viewH / density;
        boolean largeScreen = dpW >= 600.0f || dpH >= 600.0f;
        float scaleToFit = Math.min((float) viewW / frameW, (float) viewH / frameH);

        if (!largeScreen && scaleToFit < 1.0f) {
            scale = scaleToFit;
        } else {
            scale = 1.0f;
            if (frameW > viewW || frameH > viewH) {
                float maxOffsetX = Math.max(0.0f, frameW - viewW);
                float maxOffsetY = Math.max(0.0f, frameH - viewH);
                panX = clamp((yaw - centerYaw) / YAW_RANGE, -1.0f, 1.0f) * (maxOffsetX / 2.0f);
                panY = clamp((pitch - centerPitch) / PITCH_RANGE, -1.0f, 1.0f) * (maxOffsetY / 2.0f);
            }
        }

        float scaledW = frameW * scale;
        float scaledH = frameH * scale;
        float baseX = (viewW - scaledW) / 2.0f;
        float baseY = (viewH - scaledH) / 2.0f;

        outMatrix.reset();
        outMatrix.postScale(scale, scale);
        outMatrix.postTranslate(baseX - panX, baseY - panY);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

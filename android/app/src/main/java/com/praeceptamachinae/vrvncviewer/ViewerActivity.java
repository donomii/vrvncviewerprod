package com.praeceptamachinae.vrvncviewer;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

public class ViewerActivity extends Activity implements StreamManager.StatusListener {
    static final String EXTRA_SPLIT_EYES = "splitEyes";
    private FrameView frameView;
    private SensorManager sensorManager;
    private SensorPanController panController;
    private int selectionIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        frameView = findViewById(R.id.fullscreen_view);
        frameView.setSplitEyes(getIntent().getBooleanExtra(EXTRA_SPLIT_EYES, false));
        frameView.setFrameStore(StreamManager.getInstance().getFrameStore());

        float density = getResources().getDisplayMetrics().density;
        panController = new SensorPanController(density);
        frameView.setFrameTransform(panController);
        frameView.setFlipHorizontal(StreamManager.getInstance().isFlipHorizontal());

        frameView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    panController.recenter();
                    selectionIndex++;
                }
                return true;
            }
        });

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        StreamManager manager = StreamManager.getInstance();
        manager.setStatusListener(this);
        if (!manager.isConnected()) {
            Toast.makeText(this, getString(R.string.error_no_stream), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        panController.reset();
        frameView.start();
        registerSensors();
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        StreamManager.getInstance().setStatusListener(null);
        unregisterSensors();
        frameView.stop();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void onStatus(String message) {}

    @Override
    public void onConnected(String url) {}

    @Override
    public void onError(String message) {
        finish();
    }

    @Override
    public void onDisconnected() {
        finish();
    }

    private void registerSensors() {
        if (sensorManager == null) {
            return;
        }
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gyro != null) {
            sensorManager.registerListener(panController, gyro, SensorManager.SENSOR_DELAY_GAME);
        }
        if (accel != null) {
            sensorManager.registerListener(panController, accel, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(panController);
        }
    }

    private void hideSystemUi() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}

package com.praeceptamachinae.vrvncviewer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements StreamManager.StatusListener {
    private EditText urlInput;
    private EditText passwordInput;
    private CheckBox flipCheckbox;
    private TextView statusText;
    private FrameView previewView;
    private ListView urlList;
    private ArrayAdapter<String> urlAdapter;
    private final UrlStore urlStore = new UrlStore();
    private final StreamManager streamManager = StreamManager.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.url_input);
        passwordInput = findViewById(R.id.password_input);
        flipCheckbox = findViewById(R.id.flip_checkbox);
        statusText = findViewById(R.id.status_text);
        previewView = findViewById(R.id.preview_view);
        urlList = findViewById(R.id.url_list);
        Button connectVnc = findViewById(R.id.connect_vnc);
        Button connectMjpeg = findViewById(R.id.connect_mjpeg);
        Button startFullscreen = findViewById(R.id.start_fullscreen);

        List<String> urls = urlStore.load(this);
        urlAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, urls);
        urlList.setAdapter(urlAdapter);
        urlList.setOnItemClickListener((parent, view, position, id) -> {
            String selected = urlAdapter.getItem(position);
            if (selected != null) {
                urlInput.setText(selected);
                String cached = streamManager.getCachedPassword(selected);
                if (cached != null) {
                    passwordInput.setText(cached);
                } else {
                    passwordInput.setText("");
                }
            }
        });

        flipCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            streamManager.setFlipHorizontal(isChecked);
            previewView.setFlipHorizontal(isChecked);
        });

        connectVnc.setOnClickListener(v -> connect(StreamManager.StreamType.VNC));
        connectMjpeg.setOnClickListener(v -> connect(StreamManager.StreamType.MJPEG));
        startFullscreen.setOnClickListener(v -> openFullscreen(false));
        findViewById(R.id.start_cardboard).setOnClickListener(v -> openFullscreen(true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        flipCheckbox.setChecked(streamManager.isFlipHorizontal());
        previewView.setFrameStore(streamManager.getFrameStore());
        previewView.setFrameTransform(null);
        previewView.setFlipHorizontal(streamManager.isFlipHorizontal());
        previewView.start();
        streamManager.setStatusListener(this);
    }

    @Override
    protected void onPause() {
        streamManager.setStatusListener(null);
        previewView.stop();
        super.onPause();
    }

    private void connect(StreamManager.StreamType type) {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_enter_url), Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText(getString(R.string.status_connecting));
        String password = null;
        if (type == StreamManager.StreamType.VNC) {
            String inputPassword = passwordInput.getText().toString();
            if (inputPassword.isEmpty()) {
                String cached = streamManager.getCachedPassword(url);
                if (cached != null && !cached.isEmpty()) {
                    password = cached;
                    passwordInput.setText(cached);
                }
            } else {
                password = inputPassword;
                streamManager.cachePassword(url, inputPassword);
            }
        }
        streamManager.connect(type, url, password);
    }

    private void openFullscreen(boolean splitEyes) {
        if (!streamManager.isConnected()) {
            Toast.makeText(this, getString(R.string.error_no_stream), Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new android.content.Intent(this, ViewerActivity.class)
                .putExtra(ViewerActivity.EXTRA_SPLIT_EYES, splitEyes));
    }

    @Override
    public void onStatus(String message) {
        statusText.setText(message);
    }

    @Override
    public void onError(String message) {
        statusText.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onConnected() {
        statusText.setText(getString(R.string.status_connected));
        String url = urlInput.getText().toString().trim();
        urlStore.add(this, url);
        refreshUrls();
    }

    @Override
    public void onDisconnected() {
        statusText.setText(getString(R.string.status_disconnected));
    }

    private void refreshUrls() {
        urlAdapter.clear();
        urlAdapter.addAll(urlStore.load(this));
        urlAdapter.notifyDataSetChanged();
    }
}

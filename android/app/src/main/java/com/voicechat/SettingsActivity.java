package com.voicechat;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "voicechat_prefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_AUTO_PLAY_AUDIO = "auto_play_audio";
    public static final String DEFAULT_SERVER_URL = "http://123.58.210.65:18881";

    private EditText etServerUrl;
    private Switch switchAutoPlay;
    private TextView tvSaveStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etServerUrl = findViewById(R.id.etServerUrl);
        switchAutoPlay = findViewById(R.id.switchAutoPlay);
        tvSaveStatus = findViewById(R.id.tvSaveStatus);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnModelManagement = findViewById(R.id.btnModelManagement);

        loadSettings();

        btnSave.setOnClickListener(v -> saveSettings());
        btnBack.setOnClickListener(v -> finish());
        btnModelManagement.setOnClickListener(v -> {
            startActivity(new Intent(this, ModelManagementActivity.class));
        });
    }

    private void loadSettings() {
        var prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
        boolean autoPlay = prefs.getBoolean(KEY_AUTO_PLAY_AUDIO, true);
        etServerUrl.setText(serverUrl);
        switchAutoPlay.setChecked(autoPlay);
    }

    private void saveSettings() {
        String serverUrl = etServerUrl.getText().toString().trim();
        if (serverUrl.isEmpty()) {
            serverUrl = DEFAULT_SERVER_URL;
        }
        // 确保 URL 格式正确
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://" + serverUrl;
        }
        // 去掉末尾斜杠
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }

        boolean autoPlay = switchAutoPlay.isChecked();

        var prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putBoolean(KEY_AUTO_PLAY_AUDIO, autoPlay)
                .apply();

        tvSaveStatus.setText("✓ 设置已保存");
    }
}

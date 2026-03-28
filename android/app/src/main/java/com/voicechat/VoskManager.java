package com.voicechat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.IOException;

/**
 * Vosk 离线语音识别管理器
 * 支持多模型管理和动态切换
 */
public class VoskManager {

    private static final String TAG = "VoskManager";
    private static final float SAMPLE_RATE = 16000.0f;

    private final Context context;
    private Model model;
    private boolean modelReady = false;
    private String currentModelDir = "vosk-model-cn";

    // 待切换模型（用于通知 MainActivity）
    private static String pendingModelDir = null;

    public interface InitCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public interface RecognizeCallback {
        void onResult(String text);
        void onError(String error);
    }

    public VoskManager(Context context) {
        this.context = context.getApplicationContext();
        LibVosk.setLogLevel(LogLevel.INFO);
    }

    /**
     * 初始化模型：优先从内部存储加载，否则从 assets 加载
     */
    public void initModel(InitCallback callback) {
        if (modelReady && model != null) {
            callback.onSuccess();
            return;
        }

        // 检查是否有自定义模型在 filesDir
        File filesDir = context.getFilesDir();
        String[] modelDirs = filesDir.list((dir, name) -> name.startsWith("vosk-model-"));
        String targetDir = "vosk-model-cn"; // 默认

        // 检查是否有待切换模型
        if (pendingModelDir != null) {
            targetDir = pendingModelDir;
            pendingModelDir = null;
        } else if (modelDirs != null && modelDirs.length > 0) {
            // 优先使用用户下载的模型
            targetDir = modelDirs[0];
        }

        File modelFile = new File(filesDir, targetDir + "/final.mdl");
        if (modelFile.exists()) {
            // 从内部存储加载
            loadModelFromDir(targetDir, callback);
        } else {
            // 从 assets 加载
            loadModelFromAssets(callback);
        }
    }

    /**
     * 从内部存储目录加载模型
     */
    private void loadModelFromDir(String dirName, InitCallback callback) {
        Log.i(TAG, "从内部存储加载模型: " + dirName);
        currentModelDir = dirName;

        new Thread(() -> {
            try {
                File modelPath = new File(context.getFilesDir(), dirName);
                model = new Model(modelPath.getAbsolutePath());
                modelReady = true;
                Log.i(TAG, "模型加载成功: " + dirName);
                postCallback(() -> callback.onSuccess());
            } catch (Exception e) {
                Log.e(TAG, "模型加载失败: " + dirName, e);
                // 回退到 assets
                loadModelFromAssets(callback);
            }
        }).start();
    }

    /**
     * 从 assets 加载模型（通过 StorageService）
     */
    private void loadModelFromAssets(InitCallback callback) {
        Log.i(TAG, "从 assets 加载模型...");
        currentModelDir = "vosk-model-cn";

        StorageService.unpack(
            context,
            currentModelDir,
            "model",
            (loadedModel) -> {
                model = loadedModel;
                modelReady = true;
                Log.i(TAG, "模型加载成功（assets）！");
                postCallback(() -> callback.onSuccess());
            },
            (exception) -> {
                Log.e(TAG, "模型加载失败", exception);
                postCallback(() -> callback.onFailure("模型加载失败: " + exception.getMessage()));
            }
        );
    }

    /**
     * 切换到指定模型（从内部存储）
     */
    public void switchModel(String dirName, InitCallback callback) {
        Log.i(TAG, "切换模型到: " + dirName);

        // 关闭旧模型
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭旧模型失败", e);
            }
            model = null;
            modelReady = false;
        }

        File modelPath = new File(context.getFilesDir(), dirName);
        File modelFile = new File(modelPath, "final.mdl");

        if (!modelFile.exists()) {
            postCallback(() -> callback.onFailure("模型未下载: " + dirName));
            return;
        }

        currentModelDir = dirName;

        new Thread(() -> {
            try {
                model = new Model(modelPath.getAbsolutePath());
                modelReady = true;
                Log.i(TAG, "模型切换成功: " + dirName);
                postCallback(() -> callback.onSuccess());
            } catch (Exception e) {
                Log.e(TAG, "模型切换失败: " + dirName, e);
                postCallback(() -> callback.onFailure("切换失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 识别 WAV 文件（16kHz Mono PCM）
     */
    public void recognizeFile(String wavFilePath, RecognizeCallback callback) {
        if (!modelReady || model == null) {
            callback.onError("模型未加载");
            return;
        }

        new Thread(() -> {
            try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(wavFilePath)) {
                    // 跳过 WAV header
                    byte[] header = new byte[44];
                    if (fis.read(header) < 44) {
                        postCallback(() -> callback.onError("无效的WAV文件"));
                        return;
                    }

                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        recognizer.acceptWaveForm(buf, len);
                    }
                }

                String text = parseResultText(recognizer.getResult());
                postCallback(() -> callback.onResult(text));

            } catch (Exception e) {
                Log.e(TAG, "识别异常", e);
                postCallback(() -> callback.onError("识别失败: " + e.getMessage()));
            }
        }).start();
    }

    private String parseResultText(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            int i = json.indexOf("\"text\"");
            if (i < 0) return "";
            int colon = json.indexOf(":", i);
            int open = json.indexOf("\"", colon + 1);
            int close = json.indexOf("\"", open + 1);
            return json.substring(open + 1, close).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void postCallback(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    public boolean isModelLoaded() {
        return modelReady;
    }

    public String getCurrentModelDir() {
        return currentModelDir;
    }

    public static void setPendingModelDir(String dir) {
        pendingModelDir = dir;
    }

    public void shutdown() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {}
            model = null;
            modelReady = false;
        }
    }
}

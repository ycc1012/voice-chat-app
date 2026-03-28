package com.voicechat;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 16kHz Mono PCM WAV 录音器
 * 专门为 Vosk 离线识别优化
 */
public class WavAudioRecorder {

    private static final String TAG = "WavAudioRecorder";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private int bufferSize;
    private boolean isRecording = false;
    private Thread recordingThread;
    private String outputFilePath;

    public interface RecordingCallback {
        void onRecordingStarted();
        void onRecordingStopped(String filePath);
        void onError(String error);
    }

    private RecordingCallback callback;

    public WavAudioRecorder() {
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2; // fallback
        }
        // 增大 buffer 避免录到杂音
        bufferSize = Math.max(bufferSize, 4096);
        Log.d(TAG, "AudioRecord buffer size: " + bufferSize);
    }

    public void setCallback(RecordingCallback callback) {
        this.callback = callback;
    }

    /**
     * 开始录音
     * @param outputFile 输出 WAV 文件路径
     */
    public void startRecording(String outputFile) {
        if (isRecording) {
            Log.w(TAG, "已经在录音中");
            return;
        }

        this.outputFilePath = outputFile;

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                if (callback != null) {
                    callback.onError("AudioRecord 初始化失败");
                }
                return;
            }

            isRecording = true;
            audioRecord.startRecording();

            if (callback != null) {
                callback.onRecordingStarted();
            }

            recordingThread = new Thread(() -> writeAudioDataToWav(), "WavRecorder");
            recordingThread.start();

            Log.d(TAG, "WAV 录音开始: " + outputFile);

        } catch (Exception e) {
            Log.e(TAG, "启动录音失败", e);
            isRecording = false;
            if (callback != null) {
                callback.onError("启动录音失败: " + e.getMessage());
            }
        }
    }

    /**
     * 停止录音
     */
    public void stopRecording() {
        if (!isRecording) return;

        isRecording = false;

        if (recordingThread != null) {
            try {
                recordingThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待录音线程结束被中断", e);
            }
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "停止 AudioRecord 失败", e);
            }
            audioRecord = null;
        }

        if (callback != null && outputFilePath != null) {
            callback.onRecordingStopped(outputFilePath);
        }

        Log.d(TAG, "WAV 录音停止: " + outputFilePath);
    }

    /**
     * 写入 WAV 文件（后台线程）
     */
    private void writeAudioDataToWav() {
        File tempFile = new File(outputFilePath);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            // 写入占位的 WAV header（稍后更新）
            byte[] header = new byte[44];
            fos.write(header);

            byte[] buffer = new byte[bufferSize];
            long totalBytesWritten = 0;

            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, bufferSize);
                if (bytesRead > 0) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesWritten += bytesRead;
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: " + bytesRead);
                    break;
                }
            }

            // 更新 WAV header
            updateWavHeader(tempFile, totalBytesWritten);
            Log.d(TAG, "WAV 文件写入完成: " + totalBytesWritten + " bytes");

        } catch (IOException e) {
            Log.e(TAG, "WAV 文件写入失败", e);
            if (callback != null) {
                callback.onError("WAV 写入失败: " + e.getMessage());
            }
        }
    }

    /**
     * 更新 WAV header
     */
    private void updateWavHeader(File file, long totalAudioLen) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            long totalDataLen = totalAudioLen + 36;
            int byteRate = SAMPLE_RATE * 1 * 16 / 8; // sampleRate * channels * bitsPerSample/8

            raf.seek(0);
            raf.writeBytes("RIFF");
            raf.write(intToByteArray((int) totalDataLen), 0, 4);
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            raf.write(intToByteArray(16), 0, 4); // Subchunk1Size for PCM
            raf.write(shortToByteArray((short) 1), 0, 2); // AudioFormat = 1 (PCM)
            raf.write(shortToByteArray((short) 1), 0, 2); // NumChannels = 1 (Mono)
            raf.write(intToByteArray(SAMPLE_RATE), 0, 4); // SampleRate
            raf.write(intToByteArray(byteRate), 0, 4);    // ByteRate
            raf.write(shortToByteArray((short) 2), 0, 2); // BlockAlign
            raf.write(shortToByteArray((short) 16), 0, 2); // BitsPerSample
            raf.writeBytes("data");
            raf.write(intToByteArray((int) totalAudioLen), 0, 4);
        } catch (IOException e) {
            Log.e(TAG, "更新 WAV header 失败", e);
        }
    }

    private byte[] intToByteArray(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] shortToByteArray(short value) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array();
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void release() {
        stopRecording();
    }
}

package com.fultograph.app.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance 44.1 kHz 16-bit Mono microphone capture using AudioRecord.
 * Runs on a dedicated background thread and computes real-time signal telemetry
 * (RMS, Peak amplitude, Clipping, and Low Signal detection).
 */
public class LiveAudioRecorder {
    public static final int SAMPLE_RATE = 44100;
    public static final int CHUNK_SIZE = 2048; // ~46.4 ms per chunk, optimal for FFT & demodulator

    public interface AudioRecordListener {
        void onAudioChunk(short[] chunk, int length, double rms, boolean isClipping, boolean isTooQuiet);
        void onError(String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AudioRecord audioRecord;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private AudioRecordListener listener;

    public void setAudioRecordListener(AudioRecordListener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean startRecording() {
        if (isRecording.get()) return true;

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        if (minBuf <= 0) {
            notifyError("Audio hardware does not support 44.1 kHz 16-bit Mono recording");
            return false;
        }

        int bufferSize = Math.max(minBuf * 2, CHUNK_SIZE * 4);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                notifyError("AudioRecord initialization failed (permission or device busy)");
                if (audioRecord != null) {
                    audioRecord.release();
                    audioRecord = null;
                }
                return false;
            }

            audioRecord.startRecording();
            isRecording.set(true);

            executor.execute(this::recordingLoop);
            return true;
        } catch (Exception e) {
            notifyError("Failed to start audio recording: " + e.getMessage());
            return false;
        }
    }

    public synchronized void stopRecording() {
        if (!isRecording.get()) return;

        isRecording.set(false);

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }
    }

    public void release() {
        stopRecording();
        executor.shutdownNow();
    }

    private void recordingLoop() {
        short[] buffer = new short[CHUNK_SIZE];

        while (isRecording.get() && audioRecord != null) {
            int read = audioRecord.read(buffer, 0, CHUNK_SIZE);
            if (read > 0) {
                // Compute telemetry metrics
                long sumSquares = 0;
                int peak = 0;

                for (int i = 0; i < read; i++) {
                    short sample = buffer[i];
                    int abs = Math.abs(sample);
                    if (abs > peak) {
                        peak = abs;
                    }
                    sumSquares += (long) sample * sample;
                }

                double rms = Math.sqrt((double) sumSquares / read);
                boolean isClipping = (peak >= 31500);
                boolean isTooQuiet = (rms < 200.0);

                // Clone buffer for dispatch to avoid race conditions
                short[] chunkCopy = new short[read];
                System.arraycopy(buffer, 0, chunkCopy, 0, read);

                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onAudioChunk(chunkCopy, read, rms, isClipping, isTooQuiet);
                    }
                });
            } else if (read < 0) {
                if (isRecording.get()) {
                    notifyError("AudioRecord read error code: " + read);
                }
                break;
            }
        }
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(error);
            }
        });
    }
}

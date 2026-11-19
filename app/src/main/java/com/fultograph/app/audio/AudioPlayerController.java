package com.fultograph.app.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Threaded AudioTrack player for 16-bit 44.1 kHz Mono PCM SSTV audio.
 * Supports non-blocking Play, Pause, Resume, Stop, and Seek operations
 * with progress updates delivered to the main thread.
 */
public class AudioPlayerController {
    public static final int SAMPLE_RATE = 44100;

    public interface PlayerListener {
        void onPlaybackProgress(int currentMs, int totalMs, float fraction);
        void onPlaybackStateChanged(boolean isPlaying);
        void onPlaybackCompleted();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AudioTrack audioTrack;
    private short[] audioBuffer;
    private int playPositionSamples = 0;

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isStopped = new AtomicBoolean(true);

    private PlayerListener listener;

    public void setPlayerListener(PlayerListener listener) {
        this.listener = listener;
    }

    /**
     * Loads a PCM buffer into the player.
     */
    public synchronized void loadAudio(short[] pcm) {
        stop();
        this.audioBuffer = pcm;
        this.playPositionSamples = 0;
        notifyProgress(0, getDurationMs(), 0.0f);
    }

    public synchronized void play() {
        if (audioBuffer == null || audioBuffer.length == 0) return;

        if (isPaused.get()) {
            isPaused.set(false);
            isPlaying.set(true);
            if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play();
            }
            notifyState(true);
            return;
        }

        stop(); // Ensure any previous worker is terminated

        isPlaying.set(true);
        isPaused.set(false);
        isStopped.set(false);
        notifyState(true);

        executor.execute(this::playbackLoop);
    }

    public synchronized void pause() {
        if (!isPlaying.get() || isPaused.get()) return;

        isPaused.set(true);
        isPlaying.set(false);
        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.pause();
        }
        notifyState(false);
    }

    public synchronized void stop() {
        isPlaying.set(false);
        isPaused.set(false);
        isStopped.set(true);

        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }

        playPositionSamples = 0;
        notifyState(false);
        notifyProgress(0, getDurationMs(), 0.0f);
    }

    public synchronized void seekToFraction(float fraction) {
        if (audioBuffer == null || audioBuffer.length == 0) return;
        fraction = Math.max(0.0f, Math.min(1.0f, fraction));
        int targetSample = (int) (fraction * audioBuffer.length);
        seekToSample(targetSample);
    }

    public synchronized void seekToSample(int sampleIndex) {
        if (audioBuffer == null || audioBuffer.length == 0) return;
        this.playPositionSamples = Math.max(0, Math.min(audioBuffer.length - 1, sampleIndex));

        int currentMs = (int) ((playPositionSamples / (double) SAMPLE_RATE) * 1000.0);
        int totalMs = getDurationMs();
        float fraction = (float) playPositionSamples / audioBuffer.length;
        notifyProgress(currentMs, totalMs, fraction);

        if (isPlaying.get() && audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.play();
            } catch (Exception ignored) {}
        }
    }

    public boolean isPlaying() {
        return isPlaying.get();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    public int getDurationMs() {
        if (audioBuffer == null || audioBuffer.length == 0) return 0;
        return (int) ((audioBuffer.length / (double) SAMPLE_RATE) * 1000.0);
    }

    public int getCurrentPositionMs() {
        return (int) ((playPositionSamples / (double) SAMPLE_RATE) * 1000.0);
    }

    public void release() {
        stop();
        executor.shutdownNow();
    }

    private void playbackLoop() {
        int minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufSize = Math.max(minBufSize * 2, 4096);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } else {
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM
            );
        }

        audioTrack.play();

        int chunkSize = 2048; // ~46 ms chunks for smooth progress updates
        int totalSamples = audioBuffer.length;
        int totalMs = getDurationMs();

        while (!isStopped.get() && playPositionSamples < totalSamples) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            int toWrite = Math.min(chunkSize, totalSamples - playPositionSamples);
            int written = audioTrack.write(audioBuffer, playPositionSamples, toWrite);
            if (written > 0) {
                playPositionSamples += written;
                int currentMs = (int) ((playPositionSamples / (double) SAMPLE_RATE) * 1000.0);
                float fraction = (float) playPositionSamples / totalSamples;
                notifyProgress(currentMs, totalMs, fraction);
            } else if (written < 0) {
                break;
            }
        }

        if (playPositionSamples >= totalSamples && !isStopped.get()) {
            isPlaying.set(false);
            isStopped.set(true);
            notifyState(false);
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onPlaybackCompleted();
                }
            });
        }
    }

    private void notifyProgress(int currentMs, int totalMs, float fraction) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPlaybackProgress(currentMs, totalMs, fraction);
            }
        });
    }

    private void notifyState(boolean playing) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onPlaybackStateChanged(playing);
            }
        });
    }
}

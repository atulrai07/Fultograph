package com.fultograph.app.dsp;

import android.graphics.Bitmap;

/**
 * Continuous real-time streaming SSTV decoder for Raw Mode.
 * Designed with no time limit: decodes audio chunk-by-chunk in real time as the user
 * listens to the microphone, or decodes long multi-frame files (e.g. 53s classic transmissions)
 * with Phase-Locked Loop (PLL) horizontal and vertical sync tracking.
 */
public class RawSstvStreamDecoder {
    public static final double SAMPLE_RATE = 44100.0;
    public static final double DEFAULT_LINE_MS = 66.667; // Classic 8-second SSTV (15 lines/sec)
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 240;

    public interface StreamListener {
        /**
         * Called when a scanline is updated or completed.
         */
        void onScanlineRendered(int currentLine, int totalLines, int[] canvasPixels);

        /**
         * Called when a frame boundary (vertical sync or canvas wrap) occurs.
         */
        void onFrameSync(int frameIndex);
    }

    private final FmDemodulator demodulator = new FmDemodulator();

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private int initialHeight = DEFAULT_HEIGHT;
    private double lineDurationMs = DEFAULT_LINE_MS;
    private int samplesPerLine;
    private int syncSamples;
    private int vSyncMinSamples;

    private int[] canvasPixels;
    private Bitmap canvasBitmap;

    // Stream decoding state
    private int currentLine = 0;
    private int samplesSinceLineStart = 0;
    private int syncToneDuration = 0;
    private boolean syncActive = false;
    private int frameCount = 0;
    private double smoothedFreq = 1900.0;

    private StreamListener listener;

    public RawSstvStreamDecoder() {
        this(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_LINE_MS);
    }

    public RawSstvStreamDecoder(int width, int height, double lineDurationMs) {
        setConfiguration(width, height, lineDurationMs);
    }

    public synchronized void setConfiguration(int width, int height, double lineDurationMs) {
        this.width = width;
        this.height = height;
        this.initialHeight = height;
        this.lineDurationMs = lineDurationMs;

        this.samplesPerLine = (int) Math.round(lineDurationMs * (SAMPLE_RATE / 1000.0));
        this.syncSamples = (int) Math.round(8.0 * (SAMPLE_RATE / 1000.0)); // ~8 ms sync
        this.vSyncMinSamples = (int) Math.round(22.0 * (SAMPLE_RATE / 1000.0)); // ~22+ ms vertical sync

        this.canvasPixels = new int[width * height];
        clearCanvas();
    }

    public void setStreamListener(StreamListener listener) {
        this.listener = listener;
    }

    public synchronized void clearCanvas() {
        this.height = initialHeight;
        this.canvasPixels = new int[width * height];
        for (int i = 0; i < canvasPixels.length; i++) {
            canvasPixels[i] = 0xFF121316; // Dark background
        }
        currentLine = 0;
        samplesSinceLineStart = 0;
        syncToneDuration = 0;
        syncActive = false;
        frameCount = 0;
        smoothedFreq = 1900.0;
        demodulator.reset();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public double getLineDurationMs() {
        return lineDurationMs;
    }

    public synchronized int[] getCanvasPixels() {
        return canvasPixels;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getCurrentLine() {
        return currentLine;
    }

    public synchronized Bitmap getCanvasBitmap() {
        int visibleHeight = Math.max(initialHeight, currentLine + 1);
        if (canvasBitmap == null || canvasBitmap.getWidth() != width || canvasBitmap.getHeight() != visibleHeight) {
            canvasBitmap = Bitmap.createBitmap(width, visibleHeight, Bitmap.Config.ARGB_8888);
        }
        canvasBitmap.setPixels(canvasPixels, 0, width, 0, 0, width, visibleHeight);
        return canvasBitmap;
    }

    /**
     * Processes a single 16-bit PCM audio sample in real-time.
     */
    public synchronized void processSample(short sample) {
        double rawFreq = demodulator.processSample(sample);
        // Fast 1st-order smoothing (~350 Hz cutoff) to eliminate baseband mix ripple around 1200/1500 Hz
        smoothedFreq += 0.06 * (rawFreq - smoothedFreq);
        samplesSinceLineStart++;

        // Sync tone detector (1200 Hz tone: <= 1350 Hz)
        if (smoothedFreq <= 1350.0) {
            syncToneDuration++;
        } else {
            // Tone transitioned away from 1200 Hz
            if (syncToneDuration >= vSyncMinSamples) {
                // Vertical Sync Detected (long ~22+ ms 1200 Hz pulse)
                // Start a brand new frame!
                frameCount++;
                if (syncActive) {
                    // Frame boundary between consecutive frames:
                    // Advance line to append the new frame below previous frame!
                    advanceLine();
                } else {
                    currentLine = 0;
                }
                syncActive = true;
                samplesSinceLineStart = 0;
                if (listener != null) {
                    listener.onFrameSync(frameCount);
                }
            } else if (syncToneDuration >= (int) (3.5 * 44.1) &&
                    (samplesSinceLineStart >= samplesPerLine * 0.70 || !syncActive)) {
                // Horizontal Sync Detected: End of line sync pulse
                advanceLine();
            }
            syncToneDuration = 0;
        }

        // Free-running line fallback: if no sync pulse is detected within 125% of line time,
        // advance automatically to ensure the raster beam never stalls
        if (samplesSinceLineStart >= (int) (samplesPerLine * 1.25)) {
            advanceLine();
        }

        // Active video portion (starts at 0 after sync pulse trailing edge)
        int videoDuration = samplesPerLine - syncSamples;

        if (samplesSinceLineStart < videoDuration) {
            int x = (int) Math.round((double) samplesSinceLineStart * width / videoDuration);

            if (x >= 0 && x < width && currentLine >= 0 && currentLine < height) {
                // Map frequency (1500 Hz Black - 2300 Hz White) to [0, 255]
                int val = freqToPixel(smoothedFreq);
                int argb = 0xFF000000 | (val << 16) | (val << 8) | val;
                canvasPixels[currentLine * width + x] = argb;
            }
        }
    }

    /**
     * Bulk processes a chunk of 16-bit PCM samples in real-time or offline.
     */
    public synchronized void processChunk(short[] pcm, int offset, int length) {
        int end = Math.min(pcm.length, offset + length);
        int prevLine = currentLine;

        for (int i = offset; i < end; i++) {
            processSample(pcm[i]);
        }

        if (listener != null && (currentLine != prevLine || length > 1024)) {
            listener.onScanlineRendered(currentLine, height, canvasPixels);
        }
    }

    /**
     * Advances to the next scanline. Continuous roll mode: appends scanlines downwards.
     */
    private void advanceLine() {
        syncActive = true;
        samplesSinceLineStart = 0;

        currentLine++;
        ensureCapacity(currentLine + 1);

        // Clear next line ahead of the scanning beam
        int nextRowStart = currentLine * width;
        for (int x = 0; x < width; x++) {
            canvasPixels[nextRowStart + x] = 0xFF121316;
        }

        if (listener != null) {
            listener.onScanlineRendered(currentLine, height, canvasPixels);
        }
    }

    private void ensureCapacity(int neededLines) {
        if (neededLines >= height) {
            int newHeight = Math.max(neededLines + 120, height + 480);
            int[] newPixels = new int[width * newHeight];
            System.arraycopy(canvasPixels, 0, newPixels, 0, width * height);
            for (int i = width * height; i < newPixels.length; i++) {
                newPixels[i] = 0xFF121316;
            }
            canvasPixels = newPixels;
            height = newHeight;
        }
    }

    private static int freqToPixel(double freq) {
        double val = (freq - 1500.0) / 800.0 * 255.0;
        if (val < 0.0) return 0;
        if (val > 255.0) return 255;
        return (int) Math.round(val);
    }
}

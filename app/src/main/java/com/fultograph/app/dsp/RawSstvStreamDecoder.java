package com.fultograph.app.dsp;

import android.graphics.Bitmap;

/**
 * Continuous real-time streaming SSTV decoder for Raw Mode with full COLOR decoding support.
 * Designed with no time limit: decodes audio chunk-by-chunk in real time as the user
 * listens to the microphone, or decodes long multi-frame files (e.g. 53s classic transmissions)
 * with Phase-Locked Loop (PLL) horizontal and vertical sync tracking.
 * Supports Auto Color, Robot 36 Color, Martin Color, Scottie Color, and Robot 8 B&W.
 */
public class RawSstvStreamDecoder {
    public static final double SAMPLE_RATE = 44100.0;
    public static final double DEFAULT_LINE_MS = 150.0; // Default to Robot 36 Color (150ms)
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 240;

    public enum RawColorMode {
        AUTO,        // Automatically determines color format from line timing
        ROBOT_36,    // Robot 36 YCrCb Color (150ms/line: 88ms Y, 44ms Cr/Cb alternating)
        MARTIN,      // Martin 1 / Martin 2 RGB Color (Green, Blue, Red channels)
        SCOTTIE,     // Scottie 1 / Scottie 2 RGB Color (Red, Green, Blue channels)
        ROBOT_8_BW   // Classic 8-second Black & White (66.7ms/line)
    }

    public interface StreamListener {
        void onScanlineRendered(int currentLine, int totalLines, int[] canvasPixels);
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
    private RawColorMode colorMode = RawColorMode.AUTO;

    private int[] canvasPixels;
    private Bitmap canvasBitmap;

    // Buffer for active video demodulated frequencies of the current line
    private final double[] lineFreqBuffer = new double[44100];
    private int lineBufferWriteIdx = 0;

    // Chroma storage for alternating modes (Robot 36 / Lightweight / Fast 12)
    private int[][] pairCr;
    private int[][] pairCb;
    private int[][] pairYEven;

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
        this.syncSamples = (int) Math.round(9.0 * (SAMPLE_RATE / 1000.0)); // ~9 ms sync
        this.vSyncMinSamples = (int) Math.round(22.0 * (SAMPLE_RATE / 1000.0)); // ~22+ ms vertical sync

        clearCanvas();
    }

    public synchronized void setColorMode(RawColorMode mode) {
        this.colorMode = mode;
        if (mode == RawColorMode.ROBOT_36) {
            setConfiguration(width, initialHeight, 150.0);
        } else if (mode == RawColorMode.MARTIN) {
            setConfiguration(width, initialHeight, 226.8);
        } else if (mode == RawColorMode.SCOTTIE) {
            setConfiguration(width, initialHeight, 226.0);
        } else if (mode == RawColorMode.ROBOT_8_BW) {
            setConfiguration(width, initialHeight, 66.667);
        } else {
            // AUTO
            setConfiguration(width, initialHeight, 150.0);
        }
    }

    public RawColorMode getColorMode() {
        return colorMode;
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
        lineBufferWriteIdx = 0;
        demodulator.reset();
        ensureChromaPairCapacity(height / 2 + 100);
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
        try {
            if (canvasBitmap == null || canvasBitmap.getWidth() != width || canvasBitmap.getHeight() != visibleHeight) {
                canvasBitmap = Bitmap.createBitmap(width, visibleHeight, Bitmap.Config.ARGB_8888);
            }
            if (canvasBitmap != null) {
                canvasBitmap.setPixels(canvasPixels, 0, width, 0, 0, width, visibleHeight);
            }
        } catch (Throwable ignored) {
            canvasBitmap = null;
        }
        return canvasBitmap;
    }

    /**
     * Processes a single 16-bit PCM audio sample in real-time.
     */
    public synchronized void processSample(short sample) {
        double rawFreq = demodulator.processSample(sample);
        // Fast 1st-order smoothing to suppress mix ripple
        smoothedFreq += 0.08 * (rawFreq - smoothedFreq);
        samplesSinceLineStart++;

        // Sync tone detector (1200 Hz tone: <= 1350 Hz)
        if (smoothedFreq <= 1350.0) {
            syncToneDuration++;
        } else {
            // Trailing edge of 1200 Hz sync pulse
            if (syncToneDuration >= vSyncMinSamples) {
                // Vertical Sync Detected (long ~22+ ms 1200 Hz pulse)
                frameCount++;
                if (syncActive) {
                    advanceLine();
                } else {
                    currentLine = 0;
                }
                syncActive = true;
                samplesSinceLineStart = 0;
                lineBufferWriteIdx = 0;
                if (listener != null) {
                    listener.onFrameSync(frameCount);
                }
            } else if (syncToneDuration >= (int) (3.5 * 44.1) &&
                    (samplesSinceLineStart >= samplesPerLine * 0.70 || !syncActive)) {
                // Horizontal Sync Detected: end of line sync pulse
                advanceLine();
            }
            syncToneDuration = 0;

            // Buffer active video frequency
            if (lineBufferWriteIdx < lineFreqBuffer.length) {
                lineFreqBuffer[lineBufferWriteIdx++] = smoothedFreq;
            }

            // Real-time beam raster preview on current line
            int activeVideoDuration = samplesPerLine - syncSamples;
            if (samplesSinceLineStart < activeVideoDuration && currentLine < height) {
                int x = (int) Math.round((double) samplesSinceLineStart * width / activeVideoDuration);
                if (x >= 0 && x < width) {
                    int val = freqToPixel(smoothedFreq);
                    canvasPixels[currentLine * width + x] = 0xFF000000 | (val << 16) | (val << 8) | val;
                }
            }
        }

        // Free-running line fallback: if no sync pulse is detected within 125% of line time,
        // advance automatically to ensure the raster beam never stalls
        if (samplesSinceLineStart >= (int) (samplesPerLine * 1.25)) {
            advanceLine();
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
     * Advances to the next scanline and renders the completed line in full color.
     */
    private void advanceLine() {
        syncActive = true;

        if (lineBufferWriteIdx > 50) {
            renderCompletedLine(lineFreqBuffer, lineBufferWriteIdx);
        }

        lineBufferWriteIdx = 0;
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

    /**
     * Decodes the completed scanline buffer into full COLOR pixels based on color mode.
     */
    private void renderCompletedLine(double[] freqs, int length) {
        if (currentLine >= height) {
            ensureCapacity(currentLine + 1);
        }
        int rowOffset = currentLine * width;

        RawColorMode effectiveMode = colorMode;
        if (effectiveMode == RawColorMode.AUTO) {
            double lineMs = (length / SAMPLE_RATE) * 1000.0;
            if (lineMs < 100.0) {
                effectiveMode = RawColorMode.ROBOT_8_BW;
            } else if (lineMs < 190.0) {
                effectiveMode = RawColorMode.ROBOT_36;
            } else if (lineMs < 360.0) {
                effectiveMode = RawColorMode.MARTIN;
            } else {
                effectiveMode = RawColorMode.MARTIN;
            }
        }

        if (effectiveMode == RawColorMode.ROBOT_36) {
            // Robot 36 Color Active Video:
            // Porch 1 (3.0ms) -> Y scan (88.0ms) -> Sep (4.5ms) -> Porch 2 (1.5ms) -> Chroma (44.0ms)
            double nominalActiveMs = 3.0 + 88.0 + 4.5 + 1.5 + 44.0; // 141.0 ms
            double scale = (length / (nominalActiveMs * 44.1));
            if (scale < 0.6 || scale > 1.5) scale = 1.0;

            double yStart = 3.0 * 44.1 * scale;
            double yScanSamples = 88.0 * 44.1 * scale;
            double cStart = (3.0 + 88.0 + 4.5 + 1.5) * 44.1 * scale;
            double cScanSamples = 44.0 * 44.1 * scale;

            int pairIdx = currentLine / 2;
            ensureChromaPairCapacity(pairIdx + 1);
            boolean isEven = (currentLine % 2 == 0);

            // Extract Y
            int[] currentY = new int[width];
            for (int x = 0; x < width; x++) {
                double s0 = yStart + (double) x * yScanSamples / width;
                double s1 = yStart + (double) (x + 1) * yScanSamples / width;
                currentY[x] = freqToPixel(getAverageFreq(freqs, s0, s1, length));
            }

            // Extract Chroma (Cr on even lines, Cb on odd lines)
            int[] currentC = isEven ? pairCr[pairIdx] : pairCb[pairIdx];
            for (int x = 0; x < width; x++) {
                double s0 = cStart + (double) x * cScanSamples / width;
                double s1 = cStart + (double) (x + 1) * cScanSamples / width;
                currentC[x] = freqToPixel(getAverageFreq(freqs, s0, s1, length));
            }

            if (isEven) {
                System.arraycopy(currentY, 0, pairYEven[pairIdx], 0, width);
                for (int x = 0; x < width; x++) {
                    int yVal = currentY[x];
                    int cr = pairCr[pairIdx][x];
                    int cb = 128;
                    canvasPixels[rowOffset + x] = SstvDecoder.ycbcrToRgb(yVal, cr, cb);
                }
            } else {
                int prevRowOffset = (currentLine - 1) * width;
                for (int x = 0; x < width; x++) {
                    int cr = pairCr[pairIdx][x];
                    int cb = pairCb[pairIdx][x];

                    // Backfill previous even line with true Cb
                    if (currentLine > 0) {
                        int yEven = pairYEven[pairIdx][x];
                        canvasPixels[prevRowOffset + x] = SstvDecoder.ycbcrToRgb(yEven, cr, cb);
                    }
                    // Render current odd line
                    int yOdd = currentY[x];
                    canvasPixels[rowOffset + x] = SstvDecoder.ycbcrToRgb(yOdd, cr, cb);
                }
            }
        } else if (effectiveMode == RawColorMode.MARTIN) {
            // Martin RGB: Porch 0.57ms, Green, Sep 0.57ms, Blue, Sep 0.57ms, Red
            double channelSamples = (length - (3 * 0.57 * 44.1)) / 3.0;
            if (channelSamples < 50) channelSamples = length / 3.0;

            double gStart = 0.57 * 44.1;
            double bStart = gStart + channelSamples + (0.57 * 44.1);
            double rStart = bStart + channelSamples + (0.57 * 44.1);

            for (int x = 0; x < width; x++) {
                double g0 = gStart + (double) x * channelSamples / width;
                double g1 = gStart + (double) (x + 1) * channelSamples / width;
                int g = freqToPixel(getAverageFreq(freqs, g0, g1, length));

                double b0 = bStart + (double) x * channelSamples / width;
                double b1 = bStart + (double) (x + 1) * channelSamples / width;
                int b = freqToPixel(getAverageFreq(freqs, b0, b1, length));

                double r0 = rStart + (double) x * channelSamples / width;
                double r1 = rStart + (double) (x + 1) * channelSamples / width;
                int r = freqToPixel(getAverageFreq(freqs, r0, r1, length));

                canvasPixels[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (effectiveMode == RawColorMode.SCOTTIE) {
            // Scottie RGB: Porch 1.5ms, Red, Sep 1.5ms, Green, Sep 1.5ms, Blue
            double channelSamples = (length - (3 * 1.5 * 44.1)) / 3.0;
            if (channelSamples < 50) channelSamples = length / 3.0;

            double rStart = 1.5 * 44.1;
            double gStart = rStart + channelSamples + (1.5 * 44.1);
            double bStart = gStart + channelSamples + (1.5 * 44.1);

            for (int x = 0; x < width; x++) {
                double r0 = rStart + (double) x * channelSamples / width;
                double r1 = rStart + (double) (x + 1) * channelSamples / width;
                int r = freqToPixel(getAverageFreq(freqs, r0, r1, length));

                double g0 = gStart + (double) x * channelSamples / width;
                double g1 = gStart + (double) (x + 1) * channelSamples / width;
                int g = freqToPixel(getAverageFreq(freqs, g0, g1, length));

                double b0 = bStart + (double) x * channelSamples / width;
                double b1 = bStart + (double) (x + 1) * channelSamples / width;
                int b = freqToPixel(getAverageFreq(freqs, b0, b1, length));

                canvasPixels[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            // Classic Robot 8 Grayscale
            for (int x = 0; x < width; x++) {
                double s0 = (double) x * length / width;
                double s1 = (double) (x + 1) * length / width;
                int val = freqToPixel(getAverageFreq(freqs, s0, s1, length));
                canvasPixels[rowOffset + x] = 0xFF000000 | (val << 16) | (val << 8) | val;
            }
        }
    }

    private double getAverageFreq(double[] freqs, double startPos, double endPos, int maxLen) {
        double span = endPos - startPos;
        double s0 = startPos + span * 0.1;
        double s1 = endPos - span * 0.1;

        int i0 = (int) Math.floor(s0);
        int i1 = (int) Math.ceil(s1);

        if (i0 < 0) i0 = 0;
        if (i1 >= maxLen) i1 = maxLen - 1;
        if (i1 <= i0) {
            return (i0 < maxLen) ? freqs[i0] : 1500.0;
        }

        double sum = 0.0;
        int count = 0;
        for (int i = i0; i <= i1; i++) {
            sum += freqs[i];
            count++;
        }
        return (count > 0) ? (sum / count) : 1500.0;
    }

    private void ensureChromaPairCapacity(int neededPairs) {
        if (pairCr == null || neededPairs >= pairCr.length) {
            int newCap = Math.max(neededPairs + 200, (pairCr != null ? pairCr.length * 2 : 500));
            int[][] newCr = new int[newCap][width];
            int[][] newCb = new int[newCap][width];
            int[][] newYEven = new int[newCap][width];
            if (pairCr != null) {
                System.arraycopy(pairCr, 0, newCr, 0, pairCr.length);
                System.arraycopy(pairCb, 0, newCb, 0, pairCb.length);
                System.arraycopy(pairYEven, 0, newYEven, 0, pairYEven.length);
            }
            pairCr = newCr;
            pairCb = newCb;
            pairYEven = newYEven;
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
            ensureChromaPairCapacity(height / 2 + 100);
        }
    }

    private static int freqToPixel(double freq) {
        double val = (freq - 1500.0) / 800.0 * 255.0;
        if (val < 0.0) return 0;
        if (val > 255.0) return 255;
        return (int) Math.round(val);
    }
}

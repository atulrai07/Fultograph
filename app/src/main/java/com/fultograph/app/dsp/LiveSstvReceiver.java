package com.fultograph.app.dsp;

import android.graphics.Bitmap;

/**
 * Universal Real-Time SSTV Live Receiver Engine.
 * Operates continuously with zero time limits, automatically identifying and decoding
 * all standard color SSTV modes (Martin 1, Martin 2, Scottie 1, Scottie 2, Scottie DX,
 * Robot 72, Robot 36, Wraase SC2-120, PD 90, Robot 8 B&W, etc.) as audio arrives from
 * the microphone in real time.
 */
public class LiveSstvReceiver {
    public static final double SAMPLE_RATE = 44100.0;
    private static final double SAMPLES_PER_MS = 44.1;

    public interface ReceiverListener {
        void onModeDetected(SstvMode mode);
        void onLineRendered(int currentLine, int totalLines, Bitmap currentBitmap);
        void onImageCompleted(SstvMode mode, Bitmap completedBitmap);
        void onStatusUpdate(String status);
    }

    private enum State {
        SEARCHING_LEADER,
        CHECKING_START_BIT,
        VIS_BITS,
        RECEIVING_LINES
    }

    private final FmDemodulator demodulator = new FmDemodulator();
    private ReceiverListener listener;

    private State state = State.SEARCHING_LEADER;
    private double smoothedFreq = 1900.0;

    // Detection counters
    private int leaderCount = 0;
    private int nonLeaderCount = 0;
    private int startBitSampleCount = 0;
    private int startBitLowCount = 0;

    private int bitSampleCount = 0;
    private int bitIndex = 0;
    private double bitFreqSum = 0.0;
    private int bitCenterCount = 0;
    private int visCode = 0;

    // Active reception
    private SstvMode currentMode;
    private int width;
    private int height;
    private int[] canvasPixels;
    private Bitmap canvasBitmap;

    // Line sample buffer
    private double[] lineFreqs;
    private int lineSampleWriteIdx = 0;
    private int currentLine = 0;
    private int lineSamplesNeeded = 0;

    // Alternating Chroma buffers for Robot 36 / Lightweight / Fast 12
    private int[][] pairCr;
    private int[][] pairCb;
    private int[][] pairYEven;

    public LiveSstvReceiver() {
        reset();
    }

    public void setReceiverListener(ReceiverListener listener) {
        this.listener = listener;
    }

    public synchronized void reset() {
        state = State.SEARCHING_LEADER;
        smoothedFreq = 1900.0;
        demodulator.reset();
        leaderCount = 0;
        nonLeaderCount = 0;
        startBitSampleCount = 0;
        startBitLowCount = 0;
        bitSampleCount = 0;
        bitIndex = 0;
        visCode = 0;
        currentMode = null;
        currentLine = 0;
        lineSampleWriteIdx = 0;
    }

    public synchronized void forceMode(SstvMode mode) {
        if (mode != null) {
            lockOntoMode(mode);
        } else {
            reset();
        }
    }

    public SstvMode getCurrentMode() {
        return currentMode;
    }

    public int getCurrentLine() {
        return currentLine;
    }

    public int getTotalLines() {
        return (currentMode != null) ? currentMode.getHeight() : 0;
    }

    public synchronized Bitmap getCanvasBitmap() {
        return canvasBitmap;
    }

    public synchronized int[] getCanvasPixels() {
        return canvasPixels;
    }

    /**
     * Process incoming 16-bit PCM chunk from microphone or audio file.
     */
    public synchronized void processChunk(short[] pcm, int offset, int length) {
        int end = Math.min(pcm.length, offset + length);
        for (int i = offset; i < end; i++) {
            processSample(pcm[i]);
        }
    }

    private void processSample(short sample) {
        double rawFreq = demodulator.processSample(sample);
        smoothedFreq += 0.12 * (rawFreq - smoothedFreq);

        switch (state) {
            case SEARCHING_LEADER:
                if (smoothedFreq >= 1750.0 && smoothedFreq <= 2050.0) {
                    leaderCount++;
                    nonLeaderCount = 0;
                } else {
                    nonLeaderCount++;
                    if (leaderCount >= (int) (80 * SAMPLES_PER_MS) && smoothedFreq <= 1400.0) {
                        state = State.CHECKING_START_BIT;
                        startBitSampleCount = 1;
                        startBitLowCount = (smoothedFreq <= 1400.0) ? 1 : 0;
                    } else if (nonLeaderCount > (int) (25 * SAMPLES_PER_MS)) {
                        leaderCount = 0;
                    }
                }
                break;

            case CHECKING_START_BIT:
                startBitSampleCount++;
                if (smoothedFreq <= 1400.0) {
                    startBitLowCount++;
                }

                if (startBitSampleCount >= (int) (30.0 * SAMPLES_PER_MS)) {
                    // Must be sustained <= 1400 Hz for at least ~18 ms
                    if (startBitLowCount >= (int) (18.0 * SAMPLES_PER_MS)) {
                        // Confirmed 30 ms VIS start bit
                        state = State.VIS_BITS;
                        bitIndex = 0;
                        bitSampleCount = 0;
                        bitFreqSum = 0.0;
                        bitCenterCount = 0;
                        visCode = 0;
                    } else {
                        // Was only the 10 ms break pulse or noise, reset for Leader 2
                        state = State.SEARCHING_LEADER;
                        leaderCount = 0;
                        nonLeaderCount = 0;
                    }
                }
                break;

            case VIS_BITS:
                bitSampleCount++;
                int samplesPerBit = (int) Math.round(30.0 * SAMPLES_PER_MS);

                // Sample central 15 ms of each 30 ms bit
                if (bitSampleCount >= (int) (8 * SAMPLES_PER_MS) && bitSampleCount <= (int) (22 * SAMPLES_PER_MS)) {
                    bitFreqSum += smoothedFreq;
                    bitCenterCount++;
                }

                if (bitSampleCount >= samplesPerBit) {
                    double avgBitFreq = (bitCenterCount > 0) ? (bitFreqSum / bitCenterCount) : 1200.0;
                    int bitVal = (avgBitFreq < 1200.0) ? 1 : 0; // 1100 Hz = 1, 1300 Hz = 0

                    if (bitIndex < 7) {
                        // Data bits 0..6 (LSB first)
                        if (bitVal == 1) {
                            visCode |= (1 << bitIndex);
                        }
                    }

                    bitIndex++;
                    bitSampleCount = 0;
                    bitFreqSum = 0.0;
                    bitCenterCount = 0;

                    // After 8 bits (7 data + 1 parity) + 1 stop bit (bitIndex 8):
                    if (bitIndex == 9) {
                        SstvMode detected = SstvMode.fromVisCode(visCode);
                        if (detected != null) {
                            lockOntoMode(detected);
                        } else {
                            state = State.SEARCHING_LEADER;
                            leaderCount = 0;
                            nonLeaderCount = 0;
                            if (listener != null) {
                                listener.onStatusUpdate(String.format("Unknown VIS Code %d", visCode));
                            }
                        }
                    }
                }
                break;

            case RECEIVING_LINES:
                // Accumulate demodulated frequencies for active scanline
                if (lineSampleWriteIdx < lineFreqs.length) {
                    lineFreqs[lineSampleWriteIdx++] = smoothedFreq;
                }

                if (lineSampleWriteIdx >= lineSamplesNeeded) {
                    decodeAndRenderCurrentLine();
                    lineSampleWriteIdx = 0;
                    currentLine++;

                    if (currentLine >= height) {
                        // Image completed!
                        if (listener != null) {
                            listener.onImageCompleted(currentMode, canvasBitmap);
                            listener.onStatusUpdate(String.format("%s Complete (%d×%d)", currentMode.name(), width, height));
                        }
                        // Re-arm receiver immediately for next transmission!
                        state = State.SEARCHING_LEADER;
                        leaderCount = 0;
                        nonLeaderCount = 0;
                    }
                }
                break;
        }
    }

    private void lockOntoMode(SstvMode mode) {
        currentMode = mode;
        width = mode.getWidth();
        height = mode.getHeight();
        currentLine = 0;
        lineSampleWriteIdx = 0;
        lineSamplesNeeded = mode.getLineSamples();

        int bufferSize = lineSamplesNeeded + 500;
        lineFreqs = new double[bufferSize];

        canvasPixels = new int[width * height];
        for (int i = 0; i < canvasPixels.length; i++) {
            canvasPixels[i] = 0xFF101014; // Clean dark canvas
        }

        try {
            canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (canvasBitmap != null) {
                canvasBitmap.setPixels(canvasPixels, 0, width, 0, 0, width, height);
            }
        } catch (Throwable ignored) {
            canvasBitmap = null;
        }

        pairCr = new int[height / 2 + 1][width];
        pairCb = new int[height / 2 + 1][width];
        pairYEven = new int[height / 2 + 1][width];

        state = State.RECEIVING_LINES;

        if (listener != null) {
            listener.onModeDetected(mode);
            listener.onStatusUpdate(String.format("Locked: %s (%d×%d)", mode.name(), width, height));
        }
    }

    private void decodeAndRenderCurrentLine() {
        if (currentMode == null || currentLine >= height) return;

        int rowOffset = currentLine * width;
        int syncSamples = currentMode.getSyncSamples();
        int porchSamples = currentMode.getPorchSamples();
        int yScanTotalSamples = currentMode.getYScanSamples();
        int sepSamples = currentMode.getSeparatorSamples();
        int porch2Samples = currentMode.getPorch2Samples();
        int cScanTotalSamples = currentMode.getCScanSamples();

        SstvMode.ColorFormat format = currentMode.getColorFormat();
        int yStart = syncSamples + porchSamples;

        if (format == SstvMode.ColorFormat.BW_Y) {
            for (int x = 0; x < width; x++) {
                double s0 = yStart + (double) x * yScanTotalSamples / width;
                double s1 = yStart + (double) (x + 1) * yScanTotalSamples / width;
                int val = freqToPixel(getAverageFreq(s0, s1));
                canvasPixels[rowOffset + x] = 0xFF000000 | (val << 16) | (val << 8) | val;
            }
        } else if (format == SstvMode.ColorFormat.RGB_MARTIN) {
            int gStart = yStart;
            int bStart = gStart + yScanTotalSamples + sepSamples;
            int rStart = bStart + yScanTotalSamples + sepSamples;

            for (int x = 0; x < width; x++) {
                double g0 = gStart + (double) x * yScanTotalSamples / width;
                double g1 = gStart + (double) (x + 1) * yScanTotalSamples / width;
                int g = freqToPixel(getAverageFreq(g0, g1));

                double b0 = bStart + (double) x * yScanTotalSamples / width;
                double b1 = bStart + (double) (x + 1) * yScanTotalSamples / width;
                int b = freqToPixel(getAverageFreq(b0, b1));

                double r0 = rStart + (double) x * yScanTotalSamples / width;
                double r1 = rStart + (double) (x + 1) * yScanTotalSamples / width;
                int r = freqToPixel(getAverageFreq(r0, r1));

                canvasPixels[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (format == SstvMode.ColorFormat.RGB_SCOTTIE) {
            int gStart = sepSamples;
            int bStart = gStart + yScanTotalSamples + sepSamples;
            int rStart = bStart + yScanTotalSamples + syncSamples + porchSamples;

            for (int x = 0; x < width; x++) {
                double g0 = gStart + (double) x * yScanTotalSamples / width;
                double g1 = gStart + (double) (x + 1) * yScanTotalSamples / width;
                int g = freqToPixel(getAverageFreq(g0, g1));

                double b0 = bStart + (double) x * yScanTotalSamples / width;
                double b1 = bStart + (double) (x + 1) * yScanTotalSamples / width;
                int b = freqToPixel(getAverageFreq(b0, b1));

                double r0 = rStart + (double) x * yScanTotalSamples / width;
                double r1 = rStart + (double) (x + 1) * yScanTotalSamples / width;
                int r = freqToPixel(getAverageFreq(r0, r1));

                canvasPixels[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (format == SstvMode.ColorFormat.YCRCB_ROBOT72) {
            int crStart = yStart + yScanTotalSamples + sepSamples + porch2Samples;
            int cbStart = crStart + cScanTotalSamples + sepSamples + porch2Samples;

            for (int x = 0; x < width; x++) {
                double y0 = yStart + (double) x * yScanTotalSamples / width;
                double y1 = yStart + (double) (x + 1) * yScanTotalSamples / width;
                int yVal = freqToPixel(getAverageFreq(y0, y1));

                double cr0 = crStart + (double) x * cScanTotalSamples / width;
                double cr1 = crStart + (double) (x + 1) * cScanTotalSamples / width;
                int cr = freqToPixel(getAverageFreq(cr0, cr1));

                double cb0 = cbStart + (double) x * cScanTotalSamples / width;
                double cb1 = cbStart + (double) (x + 1) * cScanTotalSamples / width;
                int cb = freqToPixel(getAverageFreq(cb0, cb1));

                canvasPixels[rowOffset + x] = SstvDecoder.ycbcrToRgb(yVal, cr, cb);
            }
        } else if (format == SstvMode.ColorFormat.RGB_WRAASE) {
            int rStart = yStart;
            int gStart = rStart + yScanTotalSamples;
            int bStart = gStart + yScanTotalSamples;

            for (int x = 0; x < width; x++) {
                double r0 = rStart + (double) x * yScanTotalSamples / width;
                double r1 = rStart + (double) (x + 1) * yScanTotalSamples / width;
                int r = freqToPixel(getAverageFreq(r0, r1));

                double g0 = gStart + (double) x * yScanTotalSamples / width;
                double g1 = gStart + (double) (x + 1) * yScanTotalSamples / width;
                int g = freqToPixel(getAverageFreq(g0, g1));

                double b0 = bStart + (double) x * yScanTotalSamples / width;
                double b1 = bStart + (double) (x + 1) * yScanTotalSamples / width;
                int b = freqToPixel(getAverageFreq(b0, b1));

                canvasPixels[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (format == SstvMode.ColorFormat.YCRCB_PD) {
            int k = currentLine;
            int y0Start = yStart;
            int crStart = y0Start + yScanTotalSamples;
            int cbStart = crStart + yScanTotalSamples;
            int y1Start = cbStart + yScanTotalSamples;

            int row0 = (2 * k) * width;
            int row1 = (2 * k + 1) * width;

            for (int x = 0; x < width; x++) {
                double y0_0 = y0Start + (double) x * yScanTotalSamples / width;
                double y0_1 = y0Start + (double) (x + 1) * yScanTotalSamples / width;
                int y0 = freqToPixel(getAverageFreq(y0_0, y0_1));

                double cr0 = crStart + (double) x * yScanTotalSamples / width;
                double cr1 = crStart + (double) (x + 1) * yScanTotalSamples / width;
                int cr = freqToPixel(getAverageFreq(cr0, cr1));

                double cb0 = cbStart + (double) x * yScanTotalSamples / width;
                double cb1 = cbStart + (double) (x + 1) * yScanTotalSamples / width;
                int cb = freqToPixel(getAverageFreq(cb0, cb1));

                double y1_0 = y1Start + (double) x * yScanTotalSamples / width;
                double y1_1 = y1Start + (double) (x + 1) * yScanTotalSamples / width;
                int y1 = freqToPixel(getAverageFreq(y1_0, y1_1));

                if (row0 + x < canvasPixels.length) {
                    canvasPixels[row0 + x] = SstvDecoder.ycbcrToRgb(y0, cr, cb);
                }
                if (row1 + x < canvasPixels.length) {
                    canvasPixels[row1 + x] = SstvDecoder.ycbcrToRgb(y1, cr, cb);
                }
            }
        } else {
            // Robot 36 / Lightweight / Fast 12
            boolean isEven = (currentLine % 2 == 0);
            int pairIdx = currentLine / 2;

            int[] currentY = new int[width];
            for (int x = 0; x < width; x++) {
                double s0 = yStart + (double) x * yScanTotalSamples / width;
                double s1 = yStart + (double) (x + 1) * yScanTotalSamples / width;
                currentY[x] = freqToPixel(getAverageFreq(s0, s1));
            }

            int cStart = yStart + yScanTotalSamples + sepSamples + porch2Samples;
            int[] currentC = isEven ? pairCr[pairIdx] : pairCb[pairIdx];
            for (int x = 0; x < width; x++) {
                double s0 = cStart + (double) x * cScanTotalSamples / width;
                double s1 = cStart + (double) (x + 1) * cScanTotalSamples / width;
                currentC[x] = freqToPixel(getAverageFreq(s0, s1));
            }

            if (isEven) {
                System.arraycopy(currentY, 0, pairYEven[pairIdx], 0, width);
                for (int x = 0; x < width; x++) {
                    canvasPixels[rowOffset + x] = SstvDecoder.ycbcrToRgb(currentY[x], pairCr[pairIdx][x], 128);
                }
            } else {
                int prevRowOffset = (currentLine - 1) * width;
                for (int x = 0; x < width; x++) {
                    int cr = pairCr[pairIdx][x];
                    int cb = pairCb[pairIdx][x];
                    canvasPixels[prevRowOffset + x] = SstvDecoder.ycbcrToRgb(pairYEven[pairIdx][x], cr, cb);
                    canvasPixels[rowOffset + x] = SstvDecoder.ycbcrToRgb(currentY[x], cr, cb);
                }
            }
        }

        if (canvasBitmap != null) {
            try {
                canvasBitmap.setPixels(canvasPixels, 0, width, 0, 0, width, height);
            } catch (Throwable ignored) {}
        }

        if (listener != null) {
            listener.onLineRendered(currentLine, height, canvasBitmap);
        }
    }

    private double getAverageFreq(double startPos, double endPos) {
        int i0 = (int) Math.floor(startPos);
        int i1 = (int) Math.ceil(endPos);
        if (i0 < 0) i0 = 0;
        if (i1 >= lineFreqs.length) i1 = lineFreqs.length - 1;
        if (i1 <= i0) return (i0 < lineFreqs.length) ? lineFreqs[i0] : 1500.0;

        double sum = 0.0;
        int count = 0;
        for (int i = i0; i <= i1; i++) {
            sum += lineFreqs[i];
            count++;
        }
        return (count > 0) ? (sum / count) : 1500.0;
    }

    private static int freqToPixel(double freq) {
        double val = (freq - 1500.0) / 800.0 * 255.0;
        if (val < 0.0) return 0;
        if (val > 255.0) return 255;
        return (int) Math.round(val);
    }
}

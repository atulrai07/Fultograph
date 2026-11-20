package com.fultograph.app.dsp;

import android.graphics.Bitmap;

/**
 * High-performance SSTV Audio Encoder.
 * Converts Bitmap or ARGB pixel array into 16-bit 44,100 Hz PCM audio using
 * continuous-phase tone generation to eliminate inter-tone clicks.
 *
 * Supported modes:
 * - ROBOT_8_BW:  160x120, ~8s
 * - LIGHTWEIGHT: 160x120, ~6s (Ultra-fast color)
 * - FAST_12:     320x240, ~12s (Fast phone-to-phone color)
 * - ROBOT_36:    320x240, ~36s (Standard Robot 36 color)
 */
public class SstvEncoder {
    public static final double SAMPLE_RATE = 44100.0;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double AMPLITUDE = 28000.0; // Clean headroom for 16-bit signed PCM

    private double phase = 0.0;

    public interface ProgressListener {
        void onProgress(int currentLine, int totalLines);
    }

    /**
     * Encodes an Android Bitmap to 16-bit 44.1 kHz PCM audio.
     */
    public short[] encode(Bitmap bitmap, SstvMode mode, ProgressListener listener) {
        int targetW = mode.getWidth();
        int targetH = mode.getHeight();

        Bitmap scaled;
        if (bitmap.getWidth() == targetW && bitmap.getHeight() == targetH) {
            scaled = bitmap;
        } else {
            scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
        }

        int[] pixels = new int[targetW * targetH];
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH);

        return encodePixels(pixels, targetW, targetH, mode, listener);
    }

    /**
     * Core encoder: Encodes an ARGB pixel array to 16-bit 44.1 kHz PCM audio.
     * Pure Java implementation suitable for zero-dependency JVM unit testing.
     */
    public short[] encodePixels(int[] pixels, int width, int height, SstvMode mode, ProgressListener listener) {
        phase = 0.0;

        int headerSamples = 40131; // Exact header samples
        int numCycles = (mode.getColorFormat() == SstvMode.ColorFormat.YCRCB_PD) ? (height / 2) : height;
        int totalSamples = headerSamples + 4000 + (numCycles * mode.getLineSamples()) + 5000;

        short[] pcm = new short[totalSamples];
        int writeIndex = 0;

        // 1. Calibration Header & VIS Code Prefix
        writeIndex = writeCalibrationHeader(pcm, writeIndex, mode.getVisCode());

        // 2. Scanline Encoding according to mode color format
        switch (mode.getColorFormat()) {
            case BW_Y:
                writeIndex = encodeRobot8Bw(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
            case YCRCB_LINE_ALT:
                writeIndex = encodeColorMode(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
            case YCRCB_ROBOT72:
                writeIndex = encodeRobot72(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
            case RGB_MARTIN:
                writeIndex = encodeMartin(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
            case RGB_SCOTTIE:
                writeIndex = encodeScottie(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
            case RGB_WRAASE:
                writeIndex = encodeWraase(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
            case YCRCB_PD:
                writeIndex = encodePd(pixels, width, height, mode, pcm, writeIndex, listener);
                break;
        }

        // Trim buffer to exact written samples
        if (writeIndex < pcm.length) {
            short[] exactPcm = new short[writeIndex];
            System.arraycopy(pcm, 0, exactPcm, 0, writeIndex);
            return exactPcm;
        }

        return pcm;
    }

    /**
     * Writes standard SSTV calibration header + 8-bit VIS code:
     * 1. 300 ms @ 1900 Hz (Leader tone)
     * 2.  10 ms @ 1200 Hz (Break pulse)
     * 3. 300 ms @ 1900 Hz (Leader tone)
     * 4.  30 ms @ 1200 Hz (VIS start bit)
     * 5. 7 data bits (LSB first) + 1 even parity bit: 30 ms each (1100 Hz = 1, 1300 Hz = 0)
     * 6.  30 ms @ 1200 Hz (VIS stop bit)
     */
    private int writeCalibrationHeader(short[] pcm, int offset, int visCode) {
        int ms300 = (int) Math.round(300.0 * 44.1);
        int ms10 = (int) Math.round(10.0 * 44.1);
        int ms30 = (int) Math.round(30.0 * 44.1);

        // 1. Leader tone
        offset = writeToneSamples(pcm, offset, 1900.0, ms300);
        // 2. Break
        offset = writeToneSamples(pcm, offset, 1200.0, ms10);
        // 3. Leader tone
        offset = writeToneSamples(pcm, offset, 1900.0, ms300);
        // 4. Start bit
        offset = writeToneSamples(pcm, offset, 1200.0, ms30);

        // 5. 7 data bits + 1 even parity bit (30 ms each)
        int onesCount = 0;
        for (int b = 0; b < 7; b++) {
            int bit = (visCode >> b) & 1;
            if (bit == 1) {
                onesCount++;
                offset = writeToneSamples(pcm, offset, 1100.0, ms30);
            } else {
                offset = writeToneSamples(pcm, offset, 1300.0, ms30);
            }
        }

        // Even parity bit: 1 if count of 1s in the 7 data bits is odd, else 0
        int parityBit = (onesCount % 2 != 0) ? 1 : 0;
        if (parityBit == 1) {
            offset = writeToneSamples(pcm, offset, 1100.0, ms30);
        } else {
            offset = writeToneSamples(pcm, offset, 1300.0, ms30);
        }

        // 6. Stop bit
        offset = writeToneSamples(pcm, offset, 1200.0, ms30);

        return offset;
    }

    /**
     * Encodes Robot 8 B&W:
     * 120 lines total.
     * Each line:
     * - Sync pulse: 10.0 ms @ 1200 Hz
     * - Y Luminance scan: 56.889 ms (160 pixels)
     */
    private int encodeRobot8Bw(int[] pixels, int width, int height, SstvMode mode,
                               short[] pcm, int offset, ProgressListener listener) {
        int lineCount = mode.getHeight();
        int pixelsPerLine = mode.getWidth();
        int yScanTotalSamples = mode.getYScanSamples();

        for (int y = 0; y < lineCount; y++) {
            if (listener != null) {
                listener.onProgress(y, lineCount);
            }

            // Sync pulse: 10.0 ms @ 1200 Hz
            offset = writeToneSamples(pcm, offset, 1200.0, mode.getSyncSamples());

            // Y Luminance scanline with exact cumulative sample boundaries
            int rowStart = y * width;
            int prevPixelSample = 0;
            for (int x = 0; x < pixelsPerLine; x++) {
                int nextPixelSample = (int) Math.round((double) (x + 1) * yScanTotalSamples / pixelsPerLine);
                int sampleCount = nextPixelSample - prevPixelSample;
                prevPixelSample = nextPixelSample;

                int argb = pixels[rowStart + x];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                // Full-range BT.601 Grayscale: Y = 0.299*R + 0.587*G + 0.114*B
                int yVal = clamp((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b));
                double freq = 1500.0 + (yVal / 255.0) * 800.0;

                offset = writeToneSamples(pcm, offset, freq, sampleCount);
            }
        }

        if (listener != null) {
            listener.onProgress(lineCount, lineCount);
        }

        return offset;
    }

    /**
     * Encodes Color Modes (LIGHTWEIGHT, FAST_12, ROBOT_36):
     * Each line:
     * - Sync pulse: Sync ms @ 1200 Hz
     * - Porch 1: Porch ms @ 1500 Hz
     * - Y scan: Y ms (width px)
     * - Separator: Separator ms (1500 Hz even lines, 2300 Hz odd lines)
     * - Porch 2: Porch 2 ms @ 1900 Hz
     * - Chroma scan: Chroma ms (Cr even lines, Cb odd lines)
     * Average Cr/Cb across each even/odd row pair.
     */
    private int encodeColorMode(int[] pixels, int width, int height, SstvMode mode,
                                short[] pcm, int offset, ProgressListener listener) {
        int lineCount = mode.getHeight();
        int pixelsPerLine = mode.getWidth();
        int yScanTotalSamples = mode.getYScanSamples();
        int cScanTotalSamples = mode.getCScanSamples();

        // Precompute Chroma (Cr / Cb) averaged across each even/odd row pair
        int numPairs = lineCount / 2;
        int[][] pairCr = new int[numPairs][pixelsPerLine];
        int[][] pairCb = new int[numPairs][pixelsPerLine];

        for (int p = 0; p < numPairs; p++) {
            int yEven = p * 2;
            int yOdd = yEven + 1;
            int rowEvenStart = yEven * width;
            int rowOddStart = (yOdd < height) ? (yOdd * width) : rowEvenStart;

            for (int x = 0; x < pixelsPerLine; x++) {
                int argbEven = pixels[rowEvenStart + x];
                int rE = (argbEven >> 16) & 0xFF;
                int gE = (argbEven >> 8) & 0xFF;
                int bE = argbEven & 0xFF;

                int argbOdd = pixels[rowOddStart + x];
                int rO = (argbOdd >> 16) & 0xFF;
                int gO = (argbOdd >> 8) & 0xFF;
                int bO = argbOdd & 0xFF;

                // Average R, G, B across the pair for chroma calculation
                double rAvg = (rE + rO) * 0.5;
                double gAvg = (gE + gO) * 0.5;
                double bAvg = (bE + bO) * 0.5;

                // Full-range BT.601 RGB to YCrCb:
                int cr = clamp((int) Math.round(128.0 + 0.5 * rAvg - 0.418688 * gAvg - 0.081312 * bAvg));
                int cb = clamp((int) Math.round(128.0 - 0.168736 * rAvg - 0.331264 * gAvg + 0.5 * bAvg));

                pairCr[p][x] = cr;
                pairCb[p][x] = cb;
            }
        }

        for (int y = 0; y < lineCount; y++) {
            if (listener != null) {
                listener.onProgress(y, lineCount);
            }

            boolean isEven = (y % 2 == 0);
            int pairIdx = y / 2;

            // 1. Sync pulse @ 1200 Hz
            offset = writeToneSamples(pcm, offset, 1200.0, mode.getSyncSamples());

            // 2. Porch 1 @ 1500 Hz
            if (mode.getPorchSamples() > 0) {
                offset = writeToneSamples(pcm, offset, 1500.0, mode.getPorchSamples());
            }

            // 3. Y (Luminance) scanline with exact cumulative sample boundaries
            int rowStart = y * width;
            int prevYSample = 0;
            for (int x = 0; x < pixelsPerLine; x++) {
                int nextYSample = (int) Math.round((double) (x + 1) * yScanTotalSamples / pixelsPerLine);
                int sampleCount = nextYSample - prevYSample;
                prevYSample = nextYSample;

                int argb = pixels[rowStart + x];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                // Y = 0.299 * R + 0.587 * G + 0.114 * B
                int yVal = clamp((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b));
                double freq = 1500.0 + (yVal / 255.0) * 800.0;
                offset = writeToneSamples(pcm, offset, freq, sampleCount);
            }

            // 4. Separator pulse (1500 Hz on even lines, 2300 Hz on odd lines)
            if (mode.getSeparatorSamples() > 0) {
                double sepFreq = isEven ? 1500.0 : 2300.0;
                offset = writeToneSamples(pcm, offset, sepFreq, mode.getSeparatorSamples());
            }

            // 5. Porch 2 @ 1900 Hz
            if (mode.getPorch2Samples() > 0) {
                offset = writeToneSamples(pcm, offset, 1900.0, mode.getPorch2Samples());
            }

            // 6. Chroma scanline (Cr on even lines, Cb on odd lines)
            if (mode.getCScanSamples() > 0) {
                int[] chromaRow = isEven ? pairCr[pairIdx] : pairCb[pairIdx];
                int prevCSample = 0;
                for (int x = 0; x < pixelsPerLine; x++) {
                    int nextCSample = (int) Math.round((double) (x + 1) * cScanTotalSamples / pixelsPerLine);
                    int sampleCount = nextCSample - prevCSample;
                    prevCSample = nextCSample;

                    int cVal = chromaRow[x];
                    double freq = 1500.0 + (cVal / 255.0) * 800.0;
                    offset = writeToneSamples(pcm, offset, freq, sampleCount);
                }
            }
        }

        if (listener != null) {
            listener.onProgress(lineCount, lineCount);
        }

        return offset;
    }

    /**
     * Encodes Robot 72 Color (72s • 320×240):
     * Each of the 240 lines transmits Y, Cr, and Cb in full:
     * - Sync pulse: 9.0 ms @ 1200 Hz
     * - Porch 1: 3.0 ms @ 1500 Hz
     * - Y scan: 138.0 ms (320 px)
     * - Separator 1: 4.5 ms @ 1500 Hz
     * - Porch 2: 1.5 ms @ 1900 Hz
     * - Cr scan: 69.0 ms (320 px)
     * - Separator 2: 4.5 ms @ 2300 Hz
     * - Porch 3: 1.5 ms @ 1900 Hz
     * - Cb scan: 69.0 ms (320 px)
     */
    private int encodeRobot72(int[] pixels, int width, int height, SstvMode mode,
                              short[] pcm, int offset, ProgressListener listener) {
        int lineCount = mode.getHeight();
        int pixelsPerLine = mode.getWidth();
        int ySamples = mode.getYScanSamples();
        int cSamples = mode.getCScanSamples();
        int syncSamples = mode.getSyncSamples();
        int porchSamples = mode.getPorchSamples();
        int sepSamples = mode.getSeparatorSamples();
        int porch2Samples = mode.getPorch2Samples();

        int[] yRow = new int[pixelsPerLine];
        int[] crRow = new int[pixelsPerLine];
        int[] cbRow = new int[pixelsPerLine];

        for (int y = 0; y < lineCount; y++) {
            if (listener != null) listener.onProgress(y, lineCount);

            int rowStart = y * width;
            for (int x = 0; x < pixelsPerLine; x++) {
                int argb = pixels[rowStart + x];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                yRow[x] = clamp((int) Math.round(0.299 * r + 0.587 * g + 0.114 * b));
                crRow[x] = clamp((int) Math.round(128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b));
                cbRow[x] = clamp((int) Math.round(128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b));
            }

            // 1. Sync pulse @ 1200 Hz
            offset = writeToneSamples(pcm, offset, 1200.0, syncSamples);
            // 2. Porch 1 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, porchSamples);
            // 3. Y scan (138 ms)
            offset = encodeScanlineChannel(pcm, offset, yRow, ySamples);
            // 4. Separator 1 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, sepSamples);
            // 5. Porch 2 @ 1900 Hz
            offset = writeToneSamples(pcm, offset, 1900.0, porch2Samples);
            // 6. Cr scan (69 ms)
            offset = encodeScanlineChannel(pcm, offset, crRow, cSamples);
            // 7. Separator 2 @ 2300 Hz
            offset = writeToneSamples(pcm, offset, 2300.0, sepSamples);
            // 8. Porch 3 @ 1900 Hz
            offset = writeToneSamples(pcm, offset, 1900.0, porch2Samples);
            // 9. Cb scan (69 ms)
            offset = encodeScanlineChannel(pcm, offset, cbRow, cSamples);
        }

        if (listener != null) listener.onProgress(lineCount, lineCount);
        return offset;
    }

    /**
     * Encodes Martin 1 & Martin 2 Color (320×256):
     * Each of the 256 lines:
     * - Sync pulse: 4.8 ms @ 1200 Hz
     * - Sync porch: 0.57 ms @ 1500 Hz
     * - Green scan: yScanMs (320 px)
     * - Separator 1: 0.57 ms @ 1500 Hz
     * - Blue scan: yScanMs (320 px)
     * - Separator 2: 0.57 ms @ 1500 Hz
     * - Red scan: yScanMs (320 px)
     * - Separator 3: 0.57 ms @ 1500 Hz
     */
    private int encodeMartin(int[] pixels, int width, int height, SstvMode mode,
                             short[] pcm, int offset, ProgressListener listener) {
        int lineCount = mode.getHeight();
        int pixelsPerLine = mode.getWidth();
        int chanSamples = mode.getYScanSamples();
        int syncSamples = mode.getSyncSamples();
        int porchSamples = mode.getPorchSamples();
        int sepSamples = mode.getSeparatorSamples();

        int[] gRow = new int[pixelsPerLine];
        int[] bRow = new int[pixelsPerLine];
        int[] rRow = new int[pixelsPerLine];

        for (int y = 0; y < lineCount; y++) {
            if (listener != null) listener.onProgress(y, lineCount);

            int rowStart = y * width;
            for (int x = 0; x < pixelsPerLine; x++) {
                int argb = pixels[rowStart + x];
                rRow[x] = (argb >> 16) & 0xFF;
                gRow[x] = (argb >> 8) & 0xFF;
                bRow[x] = argb & 0xFF;
            }

            // 1. Sync pulse @ 1200 Hz
            offset = writeToneSamples(pcm, offset, 1200.0, syncSamples);
            // 2. Sync porch @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, porchSamples);
            // 3. Green scan
            offset = encodeScanlineChannel(pcm, offset, gRow, chanSamples);
            // 4. Separator 1 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, sepSamples);
            // 5. Blue scan
            offset = encodeScanlineChannel(pcm, offset, bRow, chanSamples);
            // 6. Separator 2 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, sepSamples);
            // 7. Red scan
            offset = encodeScanlineChannel(pcm, offset, rRow, chanSamples);
            // 8. Separator 3 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, sepSamples);
        }

        if (listener != null) listener.onProgress(lineCount, lineCount);
        return offset;
    }

    /**
     * Encodes Scottie 1, Scottie 2, Scottie DX Color (320×256):
     * Preceded by 9.0 ms starting sync pulse @ 1200 Hz before line 0.
     * Each of the 256 lines:
     * - Separator 1: 1.5 ms @ 1500 Hz
     * - Green scan: yScanMs (320 px)
     * - Separator 2: 1.5 ms @ 1500 Hz
     * - Blue scan: yScanMs (320 px)
     * - Interleaved sync pulse: 9.0 ms @ 1200 Hz
     * - Sync porch: 1.5 ms @ 1500 Hz
     * - Red scan: yScanMs (320 px)
     */
    private int encodeScottie(int[] pixels, int width, int height, SstvMode mode,
                              short[] pcm, int offset, ProgressListener listener) {
        int lineCount = mode.getHeight();
        int pixelsPerLine = mode.getWidth();
        int chanSamples = mode.getYScanSamples();
        int syncSamples = mode.getSyncSamples();
        int porchSamples = mode.getPorchSamples();
        int sepSamples = mode.getSeparatorSamples();

        int[] gRow = new int[pixelsPerLine];
        int[] bRow = new int[pixelsPerLine];
        int[] rRow = new int[pixelsPerLine];

        // Preceded by starting sync: 9.0 ms @ 1200 Hz before line 0
        int startingSyncSamples = (int) Math.round(9.0 * 44.1);
        offset = writeToneSamples(pcm, offset, 1200.0, startingSyncSamples);

        for (int y = 0; y < lineCount; y++) {
            if (listener != null) listener.onProgress(y, lineCount);

            int rowStart = y * width;
            for (int x = 0; x < pixelsPerLine; x++) {
                int argb = pixels[rowStart + x];
                rRow[x] = (argb >> 16) & 0xFF;
                gRow[x] = (argb >> 8) & 0xFF;
                bRow[x] = argb & 0xFF;
            }

            // 1. Separator 1 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, sepSamples);
            // 2. Green scan
            offset = encodeScanlineChannel(pcm, offset, gRow, chanSamples);
            // 3. Separator 2 @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, sepSamples);
            // 4. Blue scan
            offset = encodeScanlineChannel(pcm, offset, bRow, chanSamples);
            // 5. Interleaved sync pulse @ 1200 Hz
            offset = writeToneSamples(pcm, offset, 1200.0, syncSamples);
            // 6. Sync porch @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, porchSamples);
            // 7. Red scan
            offset = encodeScanlineChannel(pcm, offset, rRow, chanSamples);
        }

        if (listener != null) listener.onProgress(lineCount, lineCount);
        return offset;
    }

    /**
     * Encodes Wraase SC2-120 Color (120s • 320×256):
     * Each of the 256 lines:
     * - Sync pulse: 5.52 ms @ 1200 Hz
     * - Sync porch: 0.5 ms @ 1500 Hz
     * - Red scan: 138.24 ms (320 px)
     * - Green scan: 138.24 ms (320 px)
     * - Blue scan: 138.24 ms (320 px)
     */
    private int encodeWraase(int[] pixels, int width, int height, SstvMode mode,
                             short[] pcm, int offset, ProgressListener listener) {
        int lineCount = mode.getHeight();
        int pixelsPerLine = mode.getWidth();
        int chanSamples = mode.getYScanSamples();
        int syncSamples = mode.getSyncSamples();
        int porchSamples = mode.getPorchSamples();

        int[] rRow = new int[pixelsPerLine];
        int[] gRow = new int[pixelsPerLine];
        int[] bRow = new int[pixelsPerLine];

        for (int y = 0; y < lineCount; y++) {
            if (listener != null) listener.onProgress(y, lineCount);

            int rowStart = y * width;
            for (int x = 0; x < pixelsPerLine; x++) {
                int argb = pixels[rowStart + x];
                rRow[x] = (argb >> 16) & 0xFF;
                gRow[x] = (argb >> 8) & 0xFF;
                bRow[x] = argb & 0xFF;
            }

            // 1. Sync pulse @ 1200 Hz
            offset = writeToneSamples(pcm, offset, 1200.0, syncSamples);
            // 2. Sync porch @ 1500 Hz
            offset = writeToneSamples(pcm, offset, 1500.0, porchSamples);
            // 3. Red scan
            offset = encodeScanlineChannel(pcm, offset, rRow, chanSamples);
            // 4. Green scan
            offset = encodeScanlineChannel(pcm, offset, gRow, chanSamples);
            // 5. Blue scan
            offset = encodeScanlineChannel(pcm, offset, bRow, chanSamples);
        }

        if (listener != null) listener.onProgress(lineCount, lineCount);
        return offset;
    }

    /**
     * Encodes PD 90 Color (90s • 320×256):
     * 128 cycles transmitting 2 lines each (line 2k and 2k+1):
     * - Sync pulse: 20.0 ms @ 1200 Hz
     * - Sync porch: 2.08 ms @ 1500 Hz
     * - Y0 scan: 91.52 ms (line 2k, 320 px)
     * - Cr scan: 91.52 ms (averaged across 2k and 2k+1, 320 px)
     * - Cb scan: 91.52 ms (averaged across 2k and 2k+1, 320 px)
     * - Y1 scan: 91.52 ms (line 2k+1, 320 px)
     */
    private int encodePd(int[] pixels, int width, int height, SstvMode mode,
                         short[] pcm, int offset, ProgressListener listener) {
        int numCycles = height / 2;
        int pixelsPerLine = mode.getWidth();
        int subScanSamples = mode.getYScanSamples();
        int syncSamples = mode.getSyncSamples();
        int porchSamples = mode.getPorchSamples();

        int[] y0Row = new int[pixelsPerLine];
        int[] y1Row = new int[pixelsPerLine];
        int[] crRow = new int[pixelsPerLine];
        int[] cbRow = new int[pixelsPerLine];

        for (int k = 0; k < numCycles; k++) {
            if (listener != null) listener.onProgress(k * 2, height);

            int row0Start = (2 * k) * width;
            int row1Start = Math.min((2 * k + 1) * width, (height - 1) * width);

            for (int x = 0; x < pixelsPerLine; x++) {
                int argb0 = pixels[row0Start + x];
                int r0 = (argb0 >> 16) & 0xFF;
                int g0 = (argb0 >> 8) & 0xFF;
                int b0 = argb0 & 0xFF;

                int argb1 = pixels[row1Start + x];
                int r1 = (argb1 >> 16) & 0xFF;
                int g1 = (argb1 >> 8) & 0xFF;
                int b1 = argb1 & 0xFF;

                y0Row[x] = clamp((int) Math.round(0.299 * r0 + 0.587 * g0 + 0.114 * b0));
                y1Row[x] = clamp((int) Math.round(0.299 * r1 + 0.587 * g1 + 0.114 * b1));

                double rAvg = (r0 + r1) * 0.5;
                double gAvg = (g0 + g1) * 0.5;
                double bAvg = (b0 + b1) * 0.5;

                crRow[x] = clamp((int) Math.round(128.0 + 0.5 * rAvg - 0.418688 * gAvg - 0.081312 * bAvg));
                cbRow[x] = clamp((int) Math.round(128.0 - 0.168736 * rAvg - 0.331264 * gAvg + 0.5 * bAvg));
            }

            // 1. Sync pulse @ 1200 Hz (20 ms)
            offset = writeToneSamples(pcm, offset, 1200.0, syncSamples);
            // 2. Sync porch @ 1500 Hz (2.08 ms)
            offset = writeToneSamples(pcm, offset, 1500.0, porchSamples);
            // 3. Y0 scan (line 2k)
            offset = encodeScanlineChannel(pcm, offset, y0Row, subScanSamples);
            // 4. Cr scan (averaged)
            offset = encodeScanlineChannel(pcm, offset, crRow, subScanSamples);
            // 5. Cb scan (averaged)
            offset = encodeScanlineChannel(pcm, offset, cbRow, subScanSamples);
            // 6. Y1 scan (line 2k+1)
            offset = encodeScanlineChannel(pcm, offset, y1Row, subScanSamples);
        }

        if (listener != null) listener.onProgress(height, height);
        return offset;
    }

    /**
     * Helper to encode an array of 0..255 pixel values over totalSamples using exact cumulative sample boundaries.
     */
    private int encodeScanlineChannel(short[] pcm, int offset, int[] values, int totalSamples) {
        int len = values.length;
        int prevSample = 0;
        for (int x = 0; x < len; x++) {
            int nextSample = (int) Math.round((double) (x + 1) * totalSamples / len);
            int sampleCount = nextSample - prevSample;
            prevSample = nextSample;

            double freq = 1500.0 + (values[x] / 255.0) * 800.0;
            offset = writeToneSamples(pcm, offset, freq, sampleCount);
        }
        return offset;
    }

    private int writeToneSamples(short[] pcm, int offset, double freq, int sampleCount) {
        double phaseInc = TWO_PI * freq / SAMPLE_RATE;

        for (int i = 0; i < sampleCount; i++) {
            if (offset >= pcm.length) break;
            pcm[offset++] = (short) (Math.sin(phase) * AMPLITUDE);
            phase += phaseInc;
            if (phase >= TWO_PI) {
                phase -= TWO_PI;
            }
        }

        return offset;
    }

    private static int clamp(int val) {
        if (val < 0) return 0;
        if (val > 255) return 255;
        return val;
    }
}

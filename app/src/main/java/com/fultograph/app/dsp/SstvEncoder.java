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
        int totalSamples = headerSamples + (height * mode.getLineSamples()) + 1000;

        short[] pcm = new short[totalSamples];
        int writeIndex = 0;

        // 1. Calibration Header & VIS Code Prefix
        writeIndex = writeCalibrationHeader(pcm, writeIndex, mode.getVisCode());

        // 2. Scanline Encoding
        if (mode == SstvMode.ROBOT_8_BW) {
            writeIndex = encodeRobot8Bw(pixels, width, height, mode, pcm, writeIndex, listener);
        } else {
            writeIndex = encodeColorMode(pixels, width, height, mode, pcm, writeIndex, listener);
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

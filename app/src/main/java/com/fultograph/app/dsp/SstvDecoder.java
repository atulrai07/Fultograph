package com.fultograph.app.dsp;

import android.graphics.Bitmap;

/**
 * High-performance SSTV Audio Decoder.
 * Supports ultra-fast offline decoding (< 300 ms for 6-12s modes, < 1s for Robot 36)
 * and real-time streaming line-by-line decoding with sync locking and slant fine-tuning.
 */
public class SstvDecoder {
    public static final double SAMPLE_RATE = 44100.0;

    public interface LineCallback {
        /**
         * Called when a new scanline has been decoded.
         * @param lineIndex 0-based index of decoded scanline
         * @param totalLines total lines in mode
         * @param argbPixels full image ARGB buffer updated through lineIndex
         * @param mode detected or forced SSTV mode
         */
        void onLineDecoded(int lineIndex, int totalLines, int[] argbPixels, SstvMode mode);
    }

    public static class DecodeResult {
        public final int[] pixels;
        public final int width;
        public final int height;
        public final SstvMode mode;
        public final long decodeTimeMs;
        public final boolean visDetected;
        public final int visCode;

        public DecodeResult(int[] pixels, int width, int height, SstvMode mode,
                            long decodeTimeMs, boolean visDetected, int visCode) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.mode = mode;
            this.decodeTimeMs = decodeTimeMs;
            this.visDetected = visDetected;
            this.visCode = visCode;
        }

        public Bitmap toBitmap() {
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        }
    }

    private final FmDemodulator demodulator = new FmDemodulator();

    /**
     * Decodes a complete short[] PCM buffer into ARGB pixels.
     * Auto-detects VIS header if forcedMode is null.
     */
    public DecodeResult decode(short[] pcm, SstvMode forcedMode, double slantOffsetSamples, LineCallback callback) {
        long startTime = System.currentTimeMillis();

        // 1. Demodulate entire PCM to frequency buffer
        demodulator.reset();
        int pcmLen = pcm.length;
        double[] freqs = new double[pcmLen];
        demodulator.demodulateBuffer(pcm, 0, pcmLen, freqs, 0);

        // 2. Detect VIS calibration header
        VisDetector.VisResult vis = VisDetector.findVisInFrequencies(freqs, 0, freqs.length);
        SstvMode mode = forcedMode;
        boolean visDetected = (vis != null);
        int visCode = visDetected ? vis.visCode : -1;
        int imageStartSample = 0;

        if (visDetected) {
            if (mode == null) {
                mode = vis.mode;
            }
            imageStartSample = vis.imageStartSample;
        }

        // Fallback to default mode if neither VIS nor forced mode was specified
        if (mode == null) {
            mode = SstvMode.ROBOT_36;
        }

        if (!visDetected) {
            imageStartSample = findFirstSyncPulse(freqs, 0, mode);
        }

        // 3. Decode scanlines
        int width = mode.getWidth();
        int height = mode.getHeight();
        int[] pixels = new int[width * height];

        // Fill background with dark gray initially
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF101014;
        }

        decodeScanlines(freqs, imageStartSample, mode, slantOffsetSamples, pixels, callback);

        long decodeTimeMs = System.currentTimeMillis() - startTime;
        return new DecodeResult(pixels, width, height, mode, decodeTimeMs, visDetected, visCode);
    }

    /**
     * Searches for the first valid 1200 Hz sync pulse in frequencies.
     */
    private int findFirstSyncPulse(double[] freqs, int start, SstvMode mode) {
        int syncSamples = mode.getSyncSamples();
        int minSync = (int) (syncSamples * 0.6);

        int count = 0;
        for (int i = start; i < freqs.length; i++) {
            if (freqs[i] <= 1280.0) {
                count++;
                if (count >= minSync) {
                    return Math.max(0, i - count + 1);
                }
            } else {
                count = 0;
            }
        }
        return start;
    }

    /**
     * Core scanline decoding with pixel-window averaging and BT.601 color decoding.
     */
    private void decodeScanlines(double[] freqs, int initialStartSample, SstvMode mode,
                                 double slantOffset, int[] pixels, LineCallback callback) {
        int width = mode.getWidth();
        int height = mode.getHeight();

        int lineSamples = mode.getLineSamples();
        int syncSamples = mode.getSyncSamples();
        int porchSamples = mode.getPorchSamples();
        int yScanTotalSamples = mode.getYScanSamples();
        int sepSamples = mode.getSeparatorSamples();
        int porch2Samples = mode.getPorch2Samples();
        int cScanTotalSamples = mode.getCScanSamples();

        // Temporary buffers for Chroma row pairs: Cr (even lines), Cb (odd lines)
        int[][] pairCr = new int[height / 2][width];
        int[][] pairCb = new int[height / 2][width];
        int[][] pairYEven = new int[height / 2][width];

        for (int y = 0; y < height; y++) {
            // Line timing relative to image start with user slant adjustment
            double currentLineStart = initialStartSample + (y * (double) lineSamples) + (y * slantOffset);
            int lineStartSample = (int) Math.round(currentLineStart);

            if (lineStartSample >= freqs.length) {
                break;
            }

            int yStart = lineStartSample + syncSamples + porchSamples;
            int rowOffset = y * width;
            boolean isEven = (y % 2 == 0);
            int pairIdx = y / 2;

            if (mode == SstvMode.ROBOT_8_BW) {
                // Robot 8 B&W decoding
                for (int x = 0; x < width; x++) {
                    double sStart = yStart + (double) x * yScanTotalSamples / width;
                    double sEnd = yStart + (double) (x + 1) * yScanTotalSamples / width;
                    double f = getAverageFreq(freqs, sStart, sEnd);
                    int val = freqToPixel(f);
                    // Opaque Grayscale ARGB
                    pixels[rowOffset + x] = 0xFF000000 | (val << 16) | (val << 8) | val;
                }
            } else {
                // Color mode decoding (Robot 36, Fast 12, Lightweight)
                // 1. Decode Y (Luminance)
                int[] currentY = new int[width];
                for (int x = 0; x < width; x++) {
                    double sStart = yStart + (double) x * yScanTotalSamples / width;
                    double sEnd = yStart + (double) (x + 1) * yScanTotalSamples / width;
                    double f = getAverageFreq(freqs, sStart, sEnd);
                    currentY[x] = freqToPixel(f);
                }

                // 2. Decode Chroma (Cr on even lines, Cb on odd lines)
                int cStart = yStart + yScanTotalSamples + sepSamples + porch2Samples;
                int[] currentC = isEven ? pairCr[pairIdx] : pairCb[pairIdx];
                for (int x = 0; x < width; x++) {
                    double sStart = cStart + (double) x * cScanTotalSamples / width;
                    double sEnd = cStart + (double) (x + 1) * cScanTotalSamples / width;
                    double f = getAverageFreq(freqs, sStart, sEnd);
                    currentC[x] = freqToPixel(f);
                }

                if (isEven) {
                    // Save even Y for when odd line completes the pair
                    System.arraycopy(currentY, 0, pairYEven[pairIdx], 0, width);

                    // Render interim preview for even line (using neutral Cb = 128)
                    for (int x = 0; x < width; x++) {
                        int yVal = currentY[x];
                        int cr = pairCr[pairIdx][x];
                        int cb = 128;
                        pixels[rowOffset + x] = ycbcrToRgb(yVal, cr, cb);
                    }
                } else {
                    // Odd line: we now have full (Y, Cr, Cb) for BOTH even and odd lines!
                    int prevRowOffset = (y - 1) * width;
                    for (int x = 0; x < width; x++) {
                        int cr = pairCr[pairIdx][x];
                        int cb = pairCb[pairIdx][x];

                        // Update previous (even) line with full color
                        int yEven = pairYEven[pairIdx][x];
                        pixels[prevRowOffset + x] = ycbcrToRgb(yEven, cr, cb);

                        // Render current (odd) line with full color
                        int yOdd = currentY[x];
                        pixels[rowOffset + x] = ycbcrToRgb(yOdd, cr, cb);
                    }
                }
            }

            if (callback != null) {
                callback.onLineDecoded(y, height, pixels, mode);
            }
        }
    }

    /**
     * Averages demodulated frequencies over the pixel window [startPos, endPos)
     * sampling the inner 80% to avoid edge transition smearing.
     */
    private double getAverageFreq(double[] freqs, double startPos, double endPos) {
        double span = endPos - startPos;
        // Sample central 80% of pixel duration
        double s0 = startPos + span * 0.1;
        double s1 = endPos - span * 0.1;

        int i0 = (int) Math.floor(s0);
        int i1 = (int) Math.ceil(s1);

        if (i0 < 0) i0 = 0;
        if (i1 >= freqs.length) i1 = freqs.length - 1;
        if (i1 <= i0) {
            return (i0 < freqs.length) ? freqs[i0] : 1500.0;
        }

        double sum = 0.0;
        int count = 0;
        for (int i = i0; i <= i1; i++) {
            sum += freqs[i];
            count++;
        }

        return (count > 0) ? (sum / count) : 1500.0;
    }

    /**
     * Converts demodulated frequency to 8-bit [0, 255] pixel intensity.
     * 1500 Hz = 0 (Black), 2300 Hz = 255 (White).
     */
    private static int freqToPixel(double freq) {
        double val = (freq - 1500.0) / 800.0 * 255.0;
        if (val < 0.0) return 0;
        if (val > 255.0) return 255;
        return (int) Math.round(val);
    }

    /**
     * Converts Full-range BT.601 YCrCb to 32-bit ARGB.
     * R = clamp(Y + 1.402 * (Cr - 128))
     * G = clamp(Y - 0.344136 * (Cb - 128) - 0.714136 * (Cr - 128))
     * B = clamp(Y + 1.772 * (Cb - 128))
     */
    public static int ycbcrToRgb(int y, int cr, int cb) {
        double dCr = cr - 128.0;
        double dCb = cb - 128.0;

        int r = clamp((int) Math.round(y + 1.402 * dCr));
        int g = clamp((int) Math.round(y - 0.344136 * dCb - 0.714136 * dCr));
        int b = clamp((int) Math.round(y + 1.772 * dCb));

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}

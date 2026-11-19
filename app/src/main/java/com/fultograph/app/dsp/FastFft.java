package com.fultograph.app.dsp;

/**
 * Pre-allocated, zero-allocation Radix-2 Cooley-Tukey Fast Fourier Transform (FFT)
 * with a precomputed Hann window, bit-reversal lookup, and trigonometric tables.
 * Optimized for real-time spectrogram / waterfall audio processing at 44.1 kHz.
 */
public class FastFft {
    public static final int DEFAULT_SIZE = 2048;
    public static final double SAMPLE_RATE = 44100.0;

    private final int n;
    private final int log2N;
    private final float[] window;
    private final int[] bitRev;
    private final float[] cosTable;
    private final float[] sinTable;

    // Working buffers pre-allocated to avoid GC churn
    private final float[] real;
    private final float[] imag;

    public FastFft() {
        this(DEFAULT_SIZE);
    }

    public FastFft(int size) {
        if ((size & (size - 1)) != 0 || size < 4) {
            throw new IllegalArgumentException("FFT size must be a power of 2 >= 4: " + size);
        }
        this.n = size;
        this.log2N = Integer.numberOfTrailingZeros(size);

        this.window = new float[n];
        this.bitRev = new int[n];
        this.real = new float[n];
        this.imag = new float[n];

        // 1. Precompute Hann window
        for (int i = 0; i < n; i++) {
            window[i] = (float) (0.5 * (1.0 - Math.cos((2.0 * Math.PI * i) / (n - 1))));
        }

        // 2. Precompute bit-reversal permutation table
        for (int i = 0; i < n; i++) {
            int reversed = 0;
            int temp = i;
            for (int j = 0; j < log2N; j++) {
                reversed = (reversed << 1) | (temp & 1);
                temp >>= 1;
            }
            bitRev[i] = reversed;
        }

        // 3. Precompute twiddle factors (sin/cos)
        cosTable = new float[n / 2];
        sinTable = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            double angle = -2.0 * Math.PI * i / n;
            cosTable[i] = (float) Math.cos(angle);
            sinTable[i] = (float) Math.sin(angle);
        }
    }

    public int getSize() {
        return n;
    }

    public double getBinWidthHz() {
        return SAMPLE_RATE / n;
    }

    public double getFrequencyForBin(int bin) {
        return bin * SAMPLE_RATE / n;
    }

    public int getBinForFrequency(double freqHz) {
        return (int) Math.round(freqHz * n / SAMPLE_RATE);
    }

    /**
     * Computes the magnitude spectrum of the given 16-bit PCM buffer starting at offset.
     * Output array length must be at least n / 2.
     * Magnitudes are returned in outMagnitudes[0 .. n/2 - 1].
     */
    public synchronized void computeMagnitudes(short[] pcm, int offset, float[] outMagnitudes) {
        int len = Math.min(n, pcm.length - offset);

        // Window and bit-reverse copy into working real/imag arrays
        for (int i = 0; i < len; i++) {
            int targetIdx = bitRev[i];
            real[targetIdx] = (pcm[offset + i] / 32768.0f) * window[i];
            imag[targetIdx] = 0.0f;
        }
        for (int i = len; i < n; i++) {
            int targetIdx = bitRev[i];
            real[targetIdx] = 0.0f;
            imag[targetIdx] = 0.0f;
        }

        // In-place Cooley-Tukey Radix-2 Decimation-in-Time FFT
        for (int lenStep = 2; lenStep <= n; lenStep <<= 1) {
            int halfLen = lenStep >> 1;
            int tableStep = n / lenStep;

            for (int i = 0; i < n; i += lenStep) {
                int k = 0;
                for (int j = 0; j < halfLen; j++) {
                    float cosVal = cosTable[k];
                    float sinVal = sinTable[k];
                    k += tableStep;

                    int idxA = i + j;
                    int idxB = idxA + halfLen;

                    float tr = cosVal * real[idxB] - sinVal * imag[idxB];
                    float ti = sinVal * real[idxB] + cosVal * imag[idxB];

                    real[idxB] = real[idxA] - tr;
                    imag[idxB] = imag[idxA] - ti;
                    real[idxA] += tr;
                    imag[idxA] += ti;
                }
            }
        }

        // Compute magnitude for positive frequencies [0, n/2 - 1]
        int halfN = n / 2;
        for (int i = 0; i < halfN; i++) {
            float r = real[i];
            float im = imag[i];
            outMagnitudes[i] = (float) Math.sqrt(r * r + im * im);
        }
    }

    /**
     * Utility method: Finds the dominant peak frequency (Hz) within [minFreq, maxFreq].
     */
    public double findPeakFrequency(float[] magnitudes, double minFreq, double maxFreq) {
        int startBin = Math.max(0, getBinForFrequency(minFreq));
        int endBin = Math.min(magnitudes.length - 1, getBinForFrequency(maxFreq));

        float maxMag = -1.0f;
        int peakBin = startBin;

        for (int b = startBin; b <= endBin; b++) {
            if (magnitudes[b] > maxMag) {
                maxMag = magnitudes[b];
                peakBin = b;
            }
        }

        return getFrequencyForBin(peakBin);
    }
}

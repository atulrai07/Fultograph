package com.fultograph.app.dsp;

/**
 * High-performance Quadrature (I/Q) FM Demodulator for SSTV signals.
 * Operates at 44,100 Hz sample rate with baseband mixing around 1,900 Hz
 * and a 2nd-order Butterworth IIR low-pass filter (~900 Hz cutoff).
 */
public class FmDemodulator {
    public static final double SAMPLE_RATE = 44100.0;
    public static final double CENTER_FREQ = 1900.0;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double PHASE_INC = TWO_PI * CENTER_FREQ / SAMPLE_RATE;

    // 2nd-order Butterworth Low-Pass Filter (~900 Hz cutoff @ 44.1 kHz)
    // H(s) = 1 / (s^2 + sqrt(2)*s + 1)
    private final double b0, b1, b2;
    private final double a1, a2;

    // Filter states for I channel
    private double i_x1, i_x2;
    private double i_y1, i_y2;

    // Filter states for Q channel
    private double q_x1, q_x2;
    private double q_y1, q_y2;

    // Previous filtered I/Q for instantaneous phase delta calculation
    private double prevFiltI;
    private double prevFiltQ;

    // Local oscillator phase
    private double oscPhase;

    // Moving average filter for frequency smoothing (mic noise suppression)
    private static final int MA_SIZE = 5;
    private final double[] maBuffer = new double[MA_SIZE];
    private int maIndex = 0;
    private double maSum = 0.0;
    private int maCount = 0;

    public FmDemodulator() {
        // Pre-compute 2nd-order Butterworth LPF coefficients for cutoff = 900 Hz, Q = 1/sqrt(2)
        double fc = 900.0;
        double w0 = TWO_PI * fc / SAMPLE_RATE;
        double cosW0 = Math.cos(w0);
        double sinW0 = Math.sin(w0);
        double alpha = sinW0 / (2.0 * 0.7071067811865475);

        double a0 = 1.0 + alpha;
        this.b0 = ((1.0 - cosW0) / 2.0) / a0;
        this.b1 = (1.0 - cosW0) / a0;
        this.b2 = ((1.0 - cosW0) / 2.0) / a0;
        this.a1 = (-2.0 * cosW0) / a0;
        this.a2 = (1.0 - alpha) / a0;

        reset();
    }

    /**
     * Resets internal oscillator, filter, and smoothing state.
     */
    public void reset() {
        oscPhase = 0.0;
        i_x1 = 0; i_x2 = 0; i_y1 = 0; i_y2 = 0;
        q_x1 = 0; q_x2 = 0; q_y1 = 0; q_y2 = 0;
        prevFiltI = 0.0;
        prevFiltQ = 0.0;
        maIndex = 0;
        maSum = 0.0;
        maCount = 0;
        for (int i = 0; i < MA_SIZE; i++) {
            maBuffer[i] = 0.0;
        }
    }

    /**
     * Demodulates a single 16-bit PCM sample and returns instantaneous frequency in Hz.
     * Clamped to [1000.0, 2500.0] Hz.
     */
    public double processSample(short sample) {
        // 1. Normalize input sample [-1.0, 1.0]
        double x = sample / 32768.0;

        // 2. Mix to baseband: I = x * cos(phase), Q = -x * sin(phase)
        double rawI = x * Math.cos(oscPhase);
        double rawQ = -x * Math.sin(oscPhase);

        // Advance oscillator phase
        oscPhase += PHASE_INC;
        if (oscPhase >= TWO_PI) {
            oscPhase -= TWO_PI;
        }

        // 3. 2nd-order Butterworth IIR Low-Pass Filter on I
        double filtI = b0 * rawI + b1 * i_x1 + b2 * i_x2 - a1 * i_y1 - a2 * i_y2;
        i_x2 = i_x1;
        i_x1 = rawI;
        i_y2 = i_y1;
        i_y1 = filtI;

        // 4. 2nd-order Butterworth IIR Low-Pass Filter on Q
        double filtQ = b0 * rawQ + b1 * q_x1 + b2 * q_x2 - a1 * q_y1 - a2 * q_y2;
        q_x2 = q_x1;
        q_x1 = rawQ;
        q_y2 = q_y1;
        q_y1 = filtQ;

        // 5. Instantaneous phase delta:
        // dPhase = atan2(prevI * filtQ - prevQ * filtI, prevI * filtI + prevQ * filtQ)
        double cross = prevFiltI * filtQ - prevFiltQ * filtI;
        double dot = prevFiltI * filtI + prevFiltQ * filtQ;
        double dPhase = Math.atan2(cross, dot);

        prevFiltI = filtI;
        prevFiltQ = filtQ;

        // 6. Convert phase delta to frequency
        double rawFreq = CENTER_FREQ + dPhase * (SAMPLE_RATE / TWO_PI);

        // 7. Light moving average smoothing
        if (maCount < MA_SIZE) {
            maBuffer[maIndex] = rawFreq;
            maSum += rawFreq;
            maCount++;
            maIndex = (maIndex + 1) % MA_SIZE;
            double smoothed = maSum / maCount;
            return clampFreq(smoothed);
        } else {
            maSum -= maBuffer[maIndex];
            maBuffer[maIndex] = rawFreq;
            maSum += rawFreq;
            maIndex = (maIndex + 1) % MA_SIZE;
            double smoothed = maSum / MA_SIZE;
            return clampFreq(smoothed);
        }
    }

    /**
     * Bulk demodulates a short[] PCM buffer into a double[] frequency buffer.
     */
    public void demodulateBuffer(short[] pcm, int offset, int length, double[] outFreqs, int outOffset) {
        for (int i = 0; i < length; i++) {
            outFreqs[outOffset + i] = processSample(pcm[offset + i]);
        }
    }

    private static double clampFreq(double f) {
        if (f < 1000.0) return 1000.0;
        if (f > 2500.0) return 2500.0;
        return f;
    }
}

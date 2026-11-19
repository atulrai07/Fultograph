package com.fultograph.app.dsp;

/**
 * Robust detector for SSTV calibration header and 8-bit VIS (Vertical Interval Signaling) code.
 *
 * SSTV Calibration Sequence:
 * 1. 300 ms @ 1900 Hz (Leader tone 1)
 * 2.  10 ms @ 1200 Hz (Break pulse)
 * 3. 300 ms @ 1900 Hz (Leader tone 2)
 * 4.  30 ms @ 1200 Hz (VIS start bit)
 * 5. 7 data bits (LSB first) + 1 even parity bit: 30 ms each (1100 Hz = 1, 1300 Hz = 0)
 * 6.  30 ms @ 1200 Hz (VIS stop bit)
 */
public class VisDetector {
    public static final double SAMPLE_RATE = 44100.0;
    private static final double SAMPLES_PER_MS = 44.1;

    public static class VisResult {
        public final SstvMode mode;
        public final int visCode;
        public final boolean parityValid;
        public final int imageStartSample; // Sample index where first scanline begins

        public VisResult(SstvMode mode, int visCode, boolean parityValid, int imageStartSample) {
            this.mode = mode;
            this.visCode = visCode;
            this.parityValid = parityValid;
            this.imageStartSample = imageStartSample;
        }

        @Override
        public String toString() {
            return "VisResult{mode=" + (mode != null ? mode.name() : "UNKNOWN") +
                    ", code=" + visCode + ", parityValid=" + parityValid +
                    ", startSample=" + imageStartSample + "}";
        }
    }

    /**
     * Scans demodulated frequencies for a valid VIS calibration sequence.
     * Uses a robust state machine with tolerance for transition transients.
     */
    public static VisResult findVisInFrequencies(double[] freqs, int offset, int length) {
        int end = Math.min(freqs.length, offset + length);
        int minHeaderSamples = (int) (0.5 * SAMPLE_RATE);

        if (end - offset < minHeaderSamples) {
            return null;
        }

        int minLeaderSamples = (int) (100 * SAMPLES_PER_MS); // ~100 ms of 1900 Hz
        int leaderCount = 0;
        int nonLeaderCount = 0;

        for (int i = offset; i < end - (int) (350 * SAMPLES_PER_MS); i++) {
            double f = freqs[i];

            if (f >= 1750.0 && f <= 2050.0) {
                leaderCount++;
                nonLeaderCount = 0;
            } else {
                nonLeaderCount++;

                // If we had a confirmed leader tone (> 100 ms) and frequency dropped towards 1200 Hz
                if (leaderCount >= minLeaderSamples && f <= 1400.0) {
                    int startBitIdx = i;

                    // Verify start bit duration: must be sustained ~1200 Hz for at least ~20 ms
                    int verifyLen = (int) (20 * SAMPLES_PER_MS);
                    if (verifyTone(freqs, startBitIdx, verifyLen, 1100.0, 1350.0)) {
                        // Start bit is 30 ms
                        int startBitSamples = (int) Math.round(30.0 * SAMPLES_PER_MS);
                        int bitsStartIdx = startBitIdx + startBitSamples;

                        VisResult result = decodeVisBits(freqs, bitsStartIdx);
                        if (result != null) {
                            return result;
                        }
                    }

                    // Reset leader counter so leader 2 can be tracked if this was only the 10 ms break
                    leaderCount = 0;
                    nonLeaderCount = 0;
                } else if (nonLeaderCount > (int) (20 * SAMPLES_PER_MS)) {
                    // Not leader and not a valid transition, reset
                    leaderCount = 0;
                }
            }
        }

        return null;
    }

    /**
     * Verifies that the average frequency in [idx, idx + count] is within [minFreq, maxFreq].
     */
    private static boolean verifyTone(double[] freqs, int idx, int count, double minFreq, double maxFreq) {
        if (idx + count > freqs.length) return false;
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += freqs[idx + i];
        }
        double avg = sum / count;
        return (avg >= minFreq && avg <= maxFreq);
    }

    /**
     * Decodes the 8 bits (7 data + 1 parity) starting at bitsStartIdx.
     */
    private static VisResult decodeVisBits(double[] freqs, int bitsStartIdx) {
        int bitSamples = (int) Math.round(30.0 * SAMPLES_PER_MS); // 1323 samples per bit
        int totalBitsSamples = bitSamples * 8;
        int stopBitSamples = (int) Math.round(30.0 * SAMPLES_PER_MS);

        if (bitsStartIdx + totalBitsSamples + stopBitSamples > freqs.length) {
            return null;
        }

        int visCode = 0;
        int oneCount = 0;

        // Decode 7 data bits (LSB first)
        for (int b = 0; b < 7; b++) {
            int bitCenter = bitsStartIdx + b * bitSamples + bitSamples / 4;
            int bitSampleLen = bitSamples / 2; // sample central 15 ms of the 30 ms bit
            double avgFreq = computeAverage(freqs, bitCenter, bitSampleLen);

            // 1100 Hz = 1, 1300 Hz = 0. Threshold = 1200 Hz
            int bitVal = (avgFreq < 1200.0) ? 1 : 0;
            if (bitVal == 1) {
                visCode |= (1 << b);
                oneCount++;
            }
        }

        // Decode parity bit (bit 7)
        int parityCenter = bitsStartIdx + 7 * bitSamples + bitSamples / 4;
        int paritySampleLen = bitSamples / 2;
        double parityAvg = computeAverage(freqs, parityCenter, paritySampleLen);
        int parityBit = (parityAvg < 1200.0) ? 1 : 0;

        // Even parity rule: total 1s including parity bit must be even
        boolean parityValid = ((oneCount + parityBit) % 2 == 0);

        // Check stop bit (30 ms @ 1200 Hz)
        int stopBitStart = bitsStartIdx + totalBitsSamples;
        boolean stopBitValid = verifyTone(freqs, stopBitStart, (int) (18 * SAMPLES_PER_MS), 1100.0, 1350.0);

        SstvMode mode = SstvMode.fromVisCode(visCode, freqs.length);

        // If mode matches a known SSTV mode with valid parity or stop bit
        if (mode != null && (parityValid || stopBitValid)) {
            // First image scanline starts right after the stop bit
            int imageStart = stopBitStart + stopBitSamples;
            return new VisResult(mode, visCode, parityValid, imageStart);
        }

        return null;
    }

    private static double computeAverage(double[] freqs, int start, int count) {
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += freqs[start + i];
        }
        return sum / count;
    }
}

package com.fultograph.app;

import com.fultograph.app.audio.WavFileHandler;
import com.fultograph.app.dsp.FastFft;
import com.fultograph.app.dsp.FmDemodulator;
import com.fultograph.app.dsp.SstvDecoder;
import com.fultograph.app.dsp.SstvEncoder;
import com.fultograph.app.dsp.SstvMode;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Automated Verification Test Suite for SSTV Audio DSP engine.
 * Tests:
 * 1. FastFft frequency accuracy (1200 Hz & 1900 Hz peak detection within ±25 Hz).
 * 2. WavFileHandler round-trip (lossless byte preservation).
 * 3. Robot 8 B&W Loopback (baseline test).
 * 4. Lightweight (6s) Color Loopback.
 * 5. Fast 12 (12s) Color Loopback.
 * 6. Robot 36 (36s) Color Loopback.
 */
public class SstvLoopbackTest {

    private SstvEncoder encoder;
    private SstvDecoder decoder;

    @Before
    public void setUp() {
        encoder = new SstvEncoder();
        decoder = new SstvDecoder();
    }

    /**
     * Test 1: FastFft accuracy test: Feed pure 1200 Hz and 1900 Hz sine waves
     * and assert peak bin frequency is within ±25 Hz.
     */
    @Test
    public void testFastFftAccuracy() {
        FastFft fft = new FastFft(2048);
        short[] pcm1200 = generateSineWave(1200.0, 2048);
        float[] mags1200 = new float[1024];
        fft.computeMagnitudes(pcm1200, 0, mags1200);

        double peak1200 = fft.findPeakFrequency(mags1200, 1000.0, 1400.0);
        System.out.println("FFT 1200 Hz Test: Detected peak at " + peak1200 + " Hz");
        assertTrue("Peak for 1200 Hz must be within ±25 Hz (got " + peak1200 + ")",
                Math.abs(peak1200 - 1200.0) <= 25.0);

        short[] pcm1900 = generateSineWave(1900.0, 2048);
        float[] mags1900 = new float[1024];
        fft.computeMagnitudes(pcm1900, 0, mags1900);

        double peak1900 = fft.findPeakFrequency(mags1900, 1700.0, 2100.0);
        System.out.println("FFT 1900 Hz Test: Detected peak at " + peak1900 + " Hz");
        assertTrue("Peak for 1900 Hz must be within ±25 Hz (got " + peak1900 + ")",
                Math.abs(peak1900 - 1900.0) <= 25.0);
    }

    /**
     * Test 2: WavFileHandler round-trip test: Write short[] PCM to a WAV byte stream,
     * read it back, and verify identical sample length and values.
     */
    @Test
    public void testWavFileHandlerRoundTrip() throws IOException {
        int length = 44100; // 1 second of audio
        short[] original = new short[length];
        for (int i = 0; i < length; i++) {
            original[i] = (short) (Math.sin(2.0 * Math.PI * 1500.0 * i / 44100.0) * 25000.0);
        }

        byte[] wavBytes = WavFileHandler.encodeWav(original, 44100);
        assertNotNull(wavBytes);
        assertEquals(WavFileHandler.HEADER_SIZE + (length * 2), wavBytes.length);

        short[] recovered = WavFileHandler.readWavFromBytes(wavBytes);
        assertNotNull(recovered);
        assertEquals(original.length, recovered.length);

        for (int i = 0; i < length; i++) {
            assertEquals("Sample at index " + i + " mismatch", original[i], recovered[i]);
        }
        System.out.println("WavFileHandler Round-Trip: 44,100 samples verified identical.");
    }

    /**
     * Test 3: Black-and-White Mode First (Baseline): ROBOT_8_BW
     * Verifies VIS detection, sync pulse locking, and grayscale decoding accuracy.
     */
    @Test
    public void testRobot8BwLoopback() {
        SstvMode mode = SstvMode.ROBOT_8_BW;
        int width = mode.getWidth();   // 160
        int height = mode.getHeight(); // 120

        // Create 8-step horizontal grayscale test pattern
        int[] originalPixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = (x * 255) / (width - 1);
                originalPixels[y * width + x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
        }

        // 1. Encode
        long encStart = System.currentTimeMillis();
        short[] pcm = encoder.encodePixels(originalPixels, width, height, mode, null);
        long encTime = System.currentTimeMillis() - encStart;
        System.out.println("Robot 8 B&W: Encoded " + pcm.length + " samples in " + encTime + " ms");
        assertTrue(pcm.length > 44100 * 7);

        // 2. Decode with VIS auto-detection
        SstvDecoder.DecodeResult result = decoder.decode(pcm, null, 0.0, null);

        // 3. Assertions
        assertTrue("VIS header must be detected", result.visDetected);
        assertEquals("VIS code must be 2 for Robot 8 B&W", 2, result.visCode);
        assertEquals("Detected mode must be ROBOT_8_BW", SstvMode.ROBOT_8_BW, result.mode);
        assertEquals(width, result.width);
        assertEquals(height, result.height);

        // Calculate Mean Absolute Error (MAE) on active image area
        double totalError = 0.0;
        int count = 0;
        for (int y = 5; y < height - 5; y++) {
            for (int x = 10; x < width - 10; x++) {
                int origVal = (originalPixels[y * width + x] >> 16) & 0xFF;
                int decVal = (result.pixels[y * width + x] >> 16) & 0xFF;
                totalError += Math.abs(origVal - decVal);
                count++;
            }
        }
        double mae = totalError / count;
        System.out.println("Robot 8 B&W: Decoded in " + result.decodeTimeMs + " ms, Grayscale MAE = " + String.format("%.2f", mae));
        assertTrue("Grayscale MAE should be <= 15.0 (got " + mae + ")", mae <= 15.0);
    }

    /**
     * Test 4: LIGHTWEIGHT Color (6s • 160×120) Loopback
     */
    @Test
    public void testLightweightColorLoopback() {
        runColorLoopbackTest(SstvMode.LIGHTWEIGHT, 300);
    }

    /**
     * Test 5: FAST_12 Color (12s • 320×240) Loopback
     */
    @Test
    public void testFast12ColorLoopback() {
        runColorLoopbackTest(SstvMode.FAST_12, 300);
    }

    /**
     * Test 6: ROBOT_36 Color (36s • 320×240) Loopback
     */
    @Test
    public void testRobot36ColorLoopback() {
        runColorLoopbackTest(SstvMode.ROBOT_36, 1000);
    }

    private void runColorLoopbackTest(SstvMode mode, long maxExpectedDecodeTimeMs) {
        int width = mode.getWidth();
        int height = mode.getHeight();

        // Generate synthetic SMPTE-style 8 color bars
        int[] barColors = {
                0xFFFFFFFF, // White
                0xFFFFFF00, // Yellow
                0xFF00FFFF, // Cyan
                0xFF00FF00, // Green
                0xFFFF00FF, // Magenta
                0xFFFF0000, // Red
                0xFF0000FF, // Blue
                0xFF000000  // Black
        };

        int[] originalPixels = new int[width * height];
        int barWidth = width / barColors.length;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int barIdx = Math.min(barColors.length - 1, x / barWidth);
                originalPixels[y * width + x] = barColors[barIdx];
            }
        }

        // 1. Encode
        long encStart = System.currentTimeMillis();
        short[] pcm = encoder.encodePixels(originalPixels, width, height, mode, null);
        long encTime = System.currentTimeMillis() - encStart;
        System.out.println(mode.name() + ": Encoded " + pcm.length + " samples in " + encTime + " ms");

        // 2. Decode with VIS auto-detection
        SstvDecoder.DecodeResult result = decoder.decode(pcm, null, 0.0, null);

        // 3. Assertions
        assertTrue("VIS header must be detected for " + mode.name(), result.visDetected);
        assertEquals("VIS code mismatch for " + mode.name(), mode.getVisCode(), result.visCode);
        assertEquals("Mode mismatch for " + mode.name(), mode, result.mode);
        assertEquals(width, result.width);
        assertEquals(height, result.height);

        // Calculate RGB Mean Absolute Error
        double totalError = 0.0;
        int count = 0;
        for (int y = 6; y < height - 6; y++) {
            for (int x = 12; x < width - 12; x++) {
                int orig = originalPixels[y * width + x];
                int dec = result.pixels[y * width + x];

                int dr = Math.abs(((orig >> 16) & 0xFF) - ((dec >> 16) & 0xFF));
                int dg = Math.abs(((orig >> 8) & 0xFF) - ((dec >> 8) & 0xFF));
                int db = Math.abs((orig & 0xFF) - (dec & 0xFF));

                totalError += (dr + dg + db) / 3.0;
                count++;
            }
        }
        double mae = totalError / count;
        System.out.println(mode.name() + ": Decoded in " + result.decodeTimeMs + " ms, Color RGB MAE = " + String.format("%.2f", mae));
        assertTrue("RGB MAE should be <= 28.0 (got " + mae + ")", mae <= 28.0);
        assertTrue("Decode time should be <= " + maxExpectedDecodeTimeMs + " ms (got " + result.decodeTimeMs + " ms)",
                result.decodeTimeMs <= maxExpectedDecodeTimeMs);
        System.out.println(mode.name() + " loopback test passed successfully!");
    }

    private short[] generateSineWave(double freqHz, int numSamples) {
        short[] pcm = new short[numSamples];
        double phase = 0;
        double phaseInc = 2.0 * Math.PI * freqHz / 44100.0;
        for (int i = 0; i < numSamples; i++) {
            pcm[i] = (short) (Math.sin(phase) * 25000.0);
            phase += phaseInc;
        }
        return pcm;
    }
}

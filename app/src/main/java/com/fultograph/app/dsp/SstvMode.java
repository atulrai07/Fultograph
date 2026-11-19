package com.fultograph.app.dsp;

/**
 * Enumeration of supported Slow Scan Television (SSTV) modes with exact
 * timing and protocol specifications.
 */
public enum SstvMode {
    ROBOT_8_BW(
            "Robot 8 B&W (8s • 160×120)",
            2,
            160,
            120,
            false,
            10.0,   // Sync pulse ms @ 1200 Hz
            0.0,    // Porch 1 ms
            56.889, // Y Luminance scan ms
            0.0,    // Separator ms
            0.0,    // Porch 2 ms
            0.0     // Chroma scan ms
    ),
    LIGHTWEIGHT(
            "Lightweight Color (6s • 160×120)",
            16,
            160,
            120,
            true,
            4.5,    // Sync ms @ 1200 Hz
            1.5,    // Porch ms @ 1500 Hz
            28.0,   // Y scan ms
            1.5,    // Separator ms (1500 Hz even / 2300 Hz odd)
            0.5,    // Porch 2 ms @ 1900 Hz
            14.0    // Chroma scan ms (Cr even / Cb odd)
    ),
    FAST_12(
            "Fast Color (12s • 320×240)",
            12,
            320,
            240,
            true,
            4.5,    // Sync ms @ 1200 Hz
            1.5,    // Porch ms @ 1500 Hz
            28.0,   // Y scan ms
            1.5,    // Separator ms (1500 Hz even / 2300 Hz odd)
            0.5,    // Porch 2 ms @ 1900 Hz
            14.0    // Chroma scan ms (Cr even / Cb odd)
    ),
    ROBOT_36(
            "Robot 36 Color (36s • 320×240)",
            8,
            320,
            240,
            true,
            9.0,    // Sync ms @ 1200 Hz
            3.0,    // Porch ms @ 1500 Hz
            88.0,   // Y scan ms
            4.5,    // Separator ms (1500 Hz even / 2300 Hz odd)
            1.5,    // Porch 2 ms @ 1900 Hz
            44.0    // Chroma scan ms (Cr even / Cb odd)
    );

    private final String displayName;
    private final int visCode;
    private final int width;
    private final int height;
    private final boolean isColor;
    private final double syncMs;
    private final double porchMs;
    private final double yScanMs;
    private final double separatorMs;
    private final double porch2Ms;
    private final double cScanMs;
    private final double lineMs;

    private final int syncSamples;
    private final int porchSamples;
    private final int yScanSamples;
    private final int separatorSamples;
    private final int porch2Samples;
    private final int cScanSamples;
    private final int lineSamples;

    SstvMode(String displayName, int visCode, int width, int height, boolean isColor,
             double syncMs, double porchMs, double yScanMs,
             double separatorMs, double porch2Ms, double cScanMs) {
        this.displayName = displayName;
        this.visCode = visCode;
        this.width = width;
        this.height = height;
        this.isColor = isColor;
        this.syncMs = syncMs;
        this.porchMs = porchMs;
        this.yScanMs = yScanMs;
        this.separatorMs = separatorMs;
        this.porch2Ms = porch2Ms;
        this.cScanMs = cScanMs;
        this.lineMs = syncMs + porchMs + yScanMs + separatorMs + porch2Ms + cScanMs;

        this.syncSamples = (int) Math.round(syncMs * 44.1);
        this.porchSamples = (int) Math.round(porchMs * 44.1);
        this.yScanSamples = (int) Math.round(yScanMs * 44.1);
        this.separatorSamples = (int) Math.round(separatorMs * 44.1);
        this.porch2Samples = (int) Math.round(porch2Ms * 44.1);
        this.cScanSamples = (int) Math.round(cScanMs * 44.1);
        this.lineSamples = syncSamples + porchSamples + yScanSamples + separatorSamples + porch2Samples + cScanSamples;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getVisCode() {
        return visCode;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isColor() {
        return isColor;
    }

    public double getSyncMs() {
        return syncMs;
    }

    public double getPorchMs() {
        return porchMs;
    }

    public double getYScanMs() {
        return yScanMs;
    }

    public double getSeparatorMs() {
        return separatorMs;
    }

    public double getPorch2Ms() {
        return porch2Ms;
    }

    public double getCScanMs() {
        return cScanMs;
    }

    public double getLineMs() {
        return lineMs;
    }

    public int getSyncSamples() {
        return syncSamples;
    }

    public int getPorchSamples() {
        return porchSamples;
    }

    public int getYScanSamples() {
        return yScanSamples;
    }

    public int getSeparatorSamples() {
        return separatorSamples;
    }

    public int getPorch2Samples() {
        return porch2Samples;
    }

    public int getCScanSamples() {
        return cScanSamples;
    }

    public int getLineSamples() {
        return lineSamples;
    }

    /**
     * Look up mode by VIS code (0-127).
     */
    public static SstvMode fromVisCode(int code) {
        for (SstvMode mode : values()) {
            if (mode.visCode == code) {
                return mode;
            }
        }
        return null;
    }
}

package com.fultograph.app.dsp;

/**
 * Enumeration of supported Slow Scan Television (SSTV) modes with exact
 * timing and protocol specifications.
 * Supports Robot, Martin, Scottie, Wraase, and PD families in full color.
 */
public enum SstvMode {
    ROBOT_8_BW(
            "Robot 8 B&W (8s • 160×120)",
            2,
            160,
            120,
            false,
            ColorFormat.BW_Y,
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
            ColorFormat.YCRCB_LINE_ALT,
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
            ColorFormat.YCRCB_LINE_ALT,
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
            ColorFormat.YCRCB_LINE_ALT,
            9.0,    // Sync ms @ 1200 Hz
            3.0,    // Porch ms @ 1500 Hz
            88.0,   // Y scan ms
            4.5,    // Separator ms (1500 Hz even / 2300 Hz odd)
            1.5,    // Porch 2 ms @ 1900 Hz
            44.0    // Chroma scan ms (Cr even / Cb odd)
    ),
    ROBOT_72(
            "Robot 72 Color (72s • 320×240)",
            12,
            320,
            240,
            true,
            ColorFormat.YCRCB_ROBOT72,
            9.0,    // Sync ms @ 1200 Hz
            3.0,    // Porch ms @ 1500 Hz
            138.0,  // Y scan ms
            4.5,    // Separator 1 ms
            1.5,    // Porch 1 ms
            69.0    // Chroma scan ms (Cr 69ms + Sep2 4.5ms + Porch2 1.5ms + Cb 69ms = 144ms)
    ),
    MARTIN_1(
            "Martin 1 Color (114s • 320×256)",
            44,
            320,
            256,
            true,
            ColorFormat.RGB_MARTIN,
            4.8,    // Sync ms @ 1200 Hz
            0.57,   // Sync porch ms @ 1500 Hz
            146.432,// Channel scan ms (Green, Blue, Red)
            0.57,   // Channel separator ms
            0.0,
            0.0
    ),
    MARTIN_2(
            "Martin 2 Color (58s • 320×256)",
            40,
            320,
            256,
            true,
            ColorFormat.RGB_MARTIN,
            4.8,    // Sync ms @ 1200 Hz
            0.57,   // Sync porch ms @ 1500 Hz
            73.216, // Channel scan ms (Green, Blue, Red)
            0.57,   // Channel separator ms
            0.0,
            0.0
    ),
    SCOTTIE_1(
            "Scottie 1 Color (110s • 320×256)",
            60,
            320,
            256,
            true,
            ColorFormat.RGB_SCOTTIE,
            9.0,    // Interleaved sync pulse ms @ 1200 Hz
            1.5,    // Sync porch ms @ 1500 Hz
            138.24, // Channel scan ms (Green, Blue, Red)
            1.5,    // Channel separator ms
            0.0,
            0.0
    ),
    SCOTTIE_2(
            "Scottie 2 Color (59s • 320×256)",
            56,
            320,
            256,
            true,
            ColorFormat.RGB_SCOTTIE,
            9.0,    // Interleaved sync pulse ms @ 1200 Hz
            1.5,    // Sync porch ms @ 1500 Hz
            71.68,  // Channel scan ms (Green, Blue, Red)
            1.5,    // Channel separator ms
            0.0,
            0.0
    ),
    SCOTTIE_DX(
            "Scottie DX Color (268s • 320×256)",
            76,
            320,
            256,
            true,
            ColorFormat.RGB_SCOTTIE,
            9.0,    // Interleaved sync pulse ms @ 1200 Hz
            1.5,    // Sync porch ms @ 1500 Hz
            345.6,  // Channel scan ms (Green, Blue, Red)
            1.5,    // Channel separator ms
            0.0,
            0.0
    ),
    SC2_120(
            "Wraase SC2-120 Color (120s • 320×256)",
            55,
            320,
            256,
            true,
            ColorFormat.RGB_WRAASE,
            5.52,   // Sync ms @ 1200 Hz
            0.5,    // Sync porch ms @ 1500 Hz
            138.24, // Channel scan ms (Red, Green, Blue)
            0.0,
            0.0,
            0.0
    ),
    PD_90(
            "PD 90 Color (90s • 320×256)",
            99,
            320,
            256,
            true,
            ColorFormat.YCRCB_PD,
            20.0,   // Sync ms @ 1200 Hz
            2.08,   // Sync porch ms @ 1500 Hz
            91.52,  // Sub-scan ms (Y0, Cr, Cb, Y1)
            0.0,
            0.0,
            0.0
    );

    public enum ColorFormat {
        BW_Y,
        YCRCB_LINE_ALT,
        YCRCB_ROBOT72,
        RGB_MARTIN,
        RGB_SCOTTIE,
        RGB_WRAASE,
        YCRCB_PD
    }

    private final String displayName;
    private final int visCode;
    private final int width;
    private final int height;
    private final boolean isColor;
    private final ColorFormat colorFormat;
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
             ColorFormat colorFormat, double syncMs, double porchMs, double yScanMs,
             double separatorMs, double porch2Ms, double cScanMs) {
        this.displayName = displayName;
        this.visCode = visCode;
        this.width = width;
        this.height = height;
        this.isColor = isColor;
        this.colorFormat = colorFormat;
        this.syncMs = syncMs;
        this.porchMs = porchMs;
        this.yScanMs = yScanMs;
        this.separatorMs = separatorMs;
        this.porch2Ms = porch2Ms;
        this.cScanMs = cScanMs;

        this.syncSamples = (int) Math.round(syncMs * 44.1);
        this.porchSamples = (int) Math.round(porchMs * 44.1);
        this.yScanSamples = (int) Math.round(yScanMs * 44.1);
        this.separatorSamples = (int) Math.round(separatorMs * 44.1);
        this.porch2Samples = (int) Math.round(porch2Ms * 44.1);
        this.cScanSamples = (int) Math.round(cScanMs * 44.1);

        if (colorFormat == ColorFormat.RGB_MARTIN) {
            this.lineMs = syncMs + porchMs + (yScanMs * 3.0) + (separatorMs * 3.0);
            this.lineSamples = this.syncSamples + this.porchSamples + (this.yScanSamples * 3) + (this.separatorSamples * 3);
        } else if (colorFormat == ColorFormat.RGB_SCOTTIE) {
            this.lineMs = (separatorMs * 2.0) + (yScanMs * 3.0) + syncMs + porchMs;
            this.lineSamples = (this.separatorSamples * 2) + (this.yScanSamples * 3) + this.syncSamples + this.porchSamples;
        } else if (colorFormat == ColorFormat.YCRCB_ROBOT72) {
            this.lineMs = syncMs + porchMs + yScanMs + (separatorMs * 2.0) + (porch2Ms * 2.0) + (cScanMs * 2.0);
            this.lineSamples = this.syncSamples + this.porchSamples + this.yScanSamples + (this.separatorSamples * 2) + (this.porch2Samples * 2) + (this.cScanSamples * 2);
        } else if (colorFormat == ColorFormat.RGB_WRAASE) {
            this.lineMs = syncMs + porchMs + (yScanMs * 3.0);
            this.lineSamples = this.syncSamples + this.porchSamples + (this.yScanSamples * 3);
        } else if (colorFormat == ColorFormat.YCRCB_PD) {
            this.lineMs = syncMs + porchMs + (yScanMs * 4.0);
            this.lineSamples = this.syncSamples + this.porchSamples + (this.yScanSamples * 4);
        } else {
            this.lineMs = syncMs + porchMs + yScanMs + separatorMs + porch2Ms + cScanMs;
            this.lineSamples = this.syncSamples + this.porchSamples + this.yScanSamples + this.separatorSamples + this.porch2Samples + this.cScanSamples;
        }
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

    public ColorFormat getColorFormat() {
        return colorFormat;
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
        return fromVisCode(code, 0);
    }

    /**
     * Look up mode by VIS code with optional audio length for disambiguation (e.g. VIS 12).
     */
    public static SstvMode fromVisCode(int code, int totalSamples) {
        if (code == 12) {
            // Disambiguate FAST_12 (~12s, 50ms/line) vs ROBOT_72 (~72s, 300ms/line)
            if (totalSamples >= 44100 * 30) {
                return ROBOT_72;
            }
            return FAST_12;
        }
        for (SstvMode mode : values()) {
            if (mode.visCode == code) {
                return mode;
            }
        }
        return null;
    }
}

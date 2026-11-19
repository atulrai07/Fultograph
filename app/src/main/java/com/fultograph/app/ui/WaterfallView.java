package com.fultograph.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.fultograph.app.dsp.FastFft;

/**
 * Compact real-time frequency waterfall (spectrogram) view displaying 1000–2500 Hz.
 * Powered by FastFft with a precomputed 256-color palette (deep navy -> cyan -> yellow -> white)
 * and zero allocations in onDraw().
 */
public class WaterfallView extends View {
    public static final double MIN_FREQ = 1000.0;
    public static final double MAX_FREQ = 2500.0;

    private static final int BITMAP_WIDTH = 300;
    private static final int BITMAP_HEIGHT = 90;

    private final FastFft fft = new FastFft(2048);
    private final float[] fftMagnitudes = new float[1024];

    private final int[] palette = new int[256];
    private final int[] waterfallPixels = new int[BITMAP_WIDTH * BITMAP_HEIGHT];
    private Bitmap waterfallBitmap;

    // Pre-allocated drawing objects for zero GC in onDraw()
    private final Rect srcRect = new Rect(0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);
    private final Rect dstRect = new Rect();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final Paint linePaintSync = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaintBlack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint1900 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaintWhite = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Guide frequencies
    private static final double FREQ_SYNC = 1200.0;
    private static final double FREQ_BLACK = 1500.0;
    private static final double FREQ_1900 = 1900.0;
    private static final double FREQ_WHITE = 2300.0;

    public WaterfallView(Context context) {
        super(context);
        init();
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaterfallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Precompute 256-color palette: Deep Navy -> Cyan -> Yellow -> White
        for (int i = 0; i < 256; i++) {
            float t = i / 255.0f;
            int r, g, b;

            if (t < 0.25f) {
                // Navy to Dark Blue
                float f = t / 0.25f;
                r = (int) (5 + f * 5);
                g = (int) (11 + f * 40);
                b = (int) (30 + f * 123);
            } else if (t < 0.55f) {
                // Dark Blue to Bright Cyan (#00E5FF)
                float f = (t - 0.25f) / 0.30f;
                r = (int) (10 - f * 10);
                g = (int) (51 + f * 178);
                b = (int) (153 + f * 102);
            } else if (t < 0.82f) {
                // Cyan to Warm Gold/Yellow (#FFD60A)
                float f = (t - 0.55f) / 0.27f;
                r = (int) (f * 255);
                g = (int) (229 - f * 15);
                b = (int) (255 - f * 245);
            } else {
                // Yellow to Crisp White
                float f = (t - 0.82f) / 0.18f;
                r = 255;
                g = (int) (214 + f * 41);
                b = (int) (10 + f * 245);
            }

            palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        // Initialize waterfall pixel buffer with deep background
        for (int i = 0; i < waterfallPixels.length; i++) {
            waterfallPixels[i] = palette[0];
        }
        waterfallBitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888);
        waterfallBitmap.setPixels(waterfallPixels, 0, BITMAP_WIDTH, 0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);

        // Setup Guide Line Paints
        float strokeW = 2.0f * getResources().getDisplayMetrics().density;

        linePaintSync.setColor(0xFFFF3B30); // Vibrant Red for 1200 Hz Sync
        linePaintSync.setStrokeWidth(strokeW);

        linePaintBlack.setColor(0xFFFF9500); // Amber for 1500 Hz Black
        linePaintBlack.setStrokeWidth(strokeW);

        linePaint1900.setColor(0xFFFFD60A); // Gold for 1900 Hz Leader
        linePaint1900.setStrokeWidth(strokeW);

        linePaintWhite.setColor(0xFF34C759); // Green for 2300 Hz White
        linePaintWhite.setStrokeWidth(strokeW);

        // Setup Text Paint
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(9.0f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setShadowLayer(3.0f, 1.0f, 1.0f, 0xFF000000);
    }

    /**
     * Feeds incoming 16-bit PCM chunk to FFT and updates the waterfall spectrogram display.
     * Can be called from any background thread safely.
     */
    public void pushPcmChunk(short[] pcm, int offset) {
        if (pcm == null || pcm.length - offset < fft.getSize()) return;

        fft.computeMagnitudes(pcm, offset, fftMagnitudes);

        // Pre-calculate bin indices for 1000 Hz and 2500 Hz
        int minBin = fft.getBinForFrequency(MIN_FREQ);
        int maxBin = fft.getBinForFrequency(MAX_FREQ);
        double binSpan = maxBin - minBin;

        synchronized (waterfallPixels) {
            // Scroll existing rows down by 1 row
            System.arraycopy(
                    waterfallPixels, 0,
                    waterfallPixels, BITMAP_WIDTH,
                    BITMAP_WIDTH * (BITMAP_HEIGHT - 1)
            );

            // Populate top row (row 0) with new spectral slice
            for (int x = 0; x < BITMAP_WIDTH; x++) {
                double binPos = minBin + (x / (double) BITMAP_WIDTH) * binSpan;
                int b0 = (int) Math.floor(binPos);
                int b1 = Math.min(b0 + 1, fftMagnitudes.length - 1);
                double frac = binPos - b0;

                float mag = (float) (fftMagnitudes[b0] * (1.0 - frac) + fftMagnitudes[b1] * frac);

                // Logarithmic / decibel scaling for rich visual contrast
                float db = (float) (20.0 * Math.log10(mag + 1e-6));
                // Normal range ~[-50 dB, 0 dB] mapped to [0, 255]
                int palIdx = (int) ((db + 45.0f) * (255.0f / 45.0f));
                if (palIdx < 0) palIdx = 0;
                if (palIdx > 255) palIdx = 255;

                waterfallPixels[x] = palette[palIdx];
            }

            if (waterfallBitmap != null) {
                waterfallBitmap.setPixels(waterfallPixels, 0, BITMAP_WIDTH, 0, 0, BITMAP_WIDTH, BITMAP_HEIGHT);
            }
        }

        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dstRect.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 1. Draw Spectrogram Bitmap (Zero allocation)
        if (waterfallBitmap != null) {
            canvas.drawBitmap(waterfallBitmap, srcRect, dstRect, bitmapPaint);
        }

        // 2. Draw Frequency Guide Lines & Labels
        double freqRange = MAX_FREQ - MIN_FREQ;

        float xSync = (float) (w * (FREQ_SYNC - MIN_FREQ) / freqRange);
        float xBlack = (float) (w * (FREQ_BLACK - MIN_FREQ) / freqRange);
        float x1900 = (float) (w * (FREQ_1900 - MIN_FREQ) / freqRange);
        float xWhite = (float) (w * (FREQ_WHITE - MIN_FREQ) / freqRange);

        // Vertical Guide Lines
        canvas.drawLine(xSync, 0, xSync, h, linePaintSync);
        canvas.drawLine(xBlack, 0, xBlack, h, linePaintBlack);
        canvas.drawLine(x1900, 0, x1900, h, linePaint1900);
        canvas.drawLine(xWhite, 0, xWhite, h, linePaintWhite);

        // Guide Labels at top
        float textY = textPaint.getTextSize() + 4.0f;
        canvas.drawText("1200 SYNC", xSync + 4.0f, textY, textPaint);
        canvas.drawText("1500 BLK", xBlack + 4.0f, textY, textPaint);
        canvas.drawText("1900", x1900 + 4.0f, textY, textPaint);
        canvas.drawText("2300 WHT", xWhite - textPaint.measureText("2300 WHT") - 4.0f, textY, textPaint);
    }
}

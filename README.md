# Fultograph

A modern, high-performance Slow-Scan Television (SSTV) audio encoder and decoder for Android, engineered entirely in Java.

---

## Overview

Fultograph transmits and receives visual imagery across standard analog audio channels (radio frequency, acoustic speaker-to-microphone, or lossless PCM streams). Designed as an all-in-one, modern upgrade to legacy SSTV tools, it features:

- **Sub-Second Offline Decoding:** Decodes entire multi-second transmissions in milliseconds (e.g. 36s Robot 36 in ~55 ms).
- **Pure Java Real-Time DSP:** Custom Quadrature (I/Q) FM demodulator, 2048-point Radix-2 FFT, and calibration header detector with zero external C++/NDK dependencies.
- **Continuous Phase Synthesis:** Tone generation with continuous phase accumulation to eliminate audio transients and inter-tone clicks.
- **Real-Time Waterfall Display:** Custom zero-allocation 1000–2500 Hz spectrogram view with calibrated frequency guides.
- **Lossless Audio Pipeline:** 44.1 kHz 16-bit Mono RIFF WAV generation, system sharing via FileProvider, and MediaStore storage export.

---

## Supported Modes & Protocol Specifications

| Mode | Type | Resolution | Line Duration | Total Time | VIS Code |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Robot 8 B&W** | Monochrome | 160 × 120 | 66.889 ms | ~8.9 s | 2 |
| **Lightweight** | Color (YCrCb) | 160 × 120 | 50.000 ms | ~6.9 s | 16 |
| **Fast 12** | Color (YCrCb) | 320 × 240 | 50.000 ms | ~12.9 s | 12 |
| **Robot 36** | Color (YCrCb) | 320 × 240 | 150.000 ms | ~36.9 s | 8 |

### Calibration & VIS Header
Every transmission begins with a universal calibration prefix:
1. **Leader Tone:** 300 ms @ 1900 Hz
2. **Break Pulse:** 10 ms @ 1200 Hz
3. **Leader Tone:** 300 ms @ 1900 Hz
4. **VIS Start Bit:** 30 ms @ 1200 Hz
5. **VIS Data Bits:** 7 data bits (LSB first) + 1 even parity bit (30 ms each; 1100 Hz = `1`, 1300 Hz = `0`)
6. **VIS Stop Bit:** 30 ms @ 1200 Hz

### Line Structure
- **Robot 8 B&W:**
  - Sync: 10.0 ms @ 1200 Hz
  - Luminance (Y): 56.889 ms (160 pixels, 1500–2300 Hz)
- **Lightweight (160×120) & Fast 12 (320×240):**
  - Sync: 4.5 ms @ 1200 Hz
  - Porch: 1.5 ms @ 1500 Hz
  - Luminance (Y): 28.0 ms (1500–2300 Hz)
  - Separator: 1.5 ms (1500 Hz even rows, 2300 Hz odd rows)
  - Porch 2: 0.5 ms @ 1900 Hz
  - Chroma: 14.0 ms (Cr on even rows, Cb on odd rows)
- **Robot 36 (320×240):**
  - Sync: 9.0 ms @ 1200 Hz
  - Porch: 3.0 ms @ 1500 Hz
  - Luminance (Y): 88.0 ms (1500–2300 Hz)
  - Separator: 4.5 ms (1500 Hz even rows, 2300 Hz odd rows)
  - Porch 2: 1.5 ms @ 1900 Hz
  - Chroma: 44.0 ms (Cr on even rows, Cb on odd rows)

---

## Signal Processing Architecture

### 1. Continuous Phase Oscillator (Encoder)
Audio is generated at 44,100 Hz Mono signed 16-bit PCM. Frequency mapping follows:
$$f(v) = 1500.0 + \left(\frac{v}{255.0}\right) \times 800.0 \quad \text{Hz}$$
Phase is continuously tracked across sample boundaries:
$$\theta_{n} = (\theta_{n-1} + 2\pi \cdot f / f_s) \pmod{2\pi}$$
$$s[n] = A \cdot \sin(\theta_{n})$$

### 2. Quadrature FM Demodulator (Decoder)
Demodulation operates without FFTs to achieve real-time streaming throughput:
1. **Baseband Mixing:** Mix input with local oscillator at center frequency $f_c = 1900\text{ Hz}$:
   $$I[n] = x[n] \cdot \cos(\omega_c n), \quad Q[n] = -x[n] \cdot \sin(\omega_c n)$$
2. **Low-Pass Filtering:** 2nd-order Butterworth IIR low-pass filter ($f_{cutoff} = 900\text{ Hz}$) applied independently to $I$ and $Q$ to eliminate double-frequency mixing products.
3. **Phase Differentiation:**
   $$\Delta\theta = \text{atan2}(I_{prev} Q_{curr} - Q_{prev} I_{curr}, \, I_{prev} I_{curr} + Q_{prev} Q_{curr})$$
4. **Instantaneous Frequency:**
   $$f = 1900.0 + \Delta\theta \cdot \left(\frac{44100}{2\pi}\right)$$

### 3. Color Space Conversion (Full-Range BT.601)
- **Encoding:**
  $$Y = \text{clamp}(0.299 R + 0.587 G + 0.114 B)$$
  $$Cr = \text{clamp}(128.0 + 0.5 R - 0.418688 G - 0.081312 B)$$
  $$Cb = \text{clamp}(128.0 - 0.168736 R - 0.331264 G + 0.5 B)$$
- **Decoding:**
  $$R = \text{clamp}(Y + 1.402(Cr - 128))$$
  $$G = \text{clamp}(Y - 0.344136(Cb - 128) - 0.714136(Cr - 128))$$
  $$B = \text{clamp}(Y + 1.772(Cb - 128))$$

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/fultograph/app/
│   ├── dsp/
│   │   ├── SstvMode.java            # Mode definitions and sample timing calculations
│   │   ├── SstvEncoder.java         # Continuous-phase PCM audio generator
│   │   ├── SstvDecoder.java         # Offline and streaming scanline decoder
│   │   ├── FmDemodulator.java       # Fast Quadrature I/Q baseband demodulator
│   │   ├── VisDetector.java         # Calibration header & VIS code detector
│   │   └── FastFft.java             # 2048-point radix-2 Cooley-Tukey FFT
│   ├── audio/
│   │   ├── AudioPlayerController.java  # Threaded AudioTrack player (Play/Pause/Seek)
│   │   ├── LiveAudioRecorder.java      # 44.1 kHz AudioRecord microphone capture
│   │   └── WavFileHandler.java         # Lossless 44-byte RIFF WAV reader/writer
│   └── ui/
│       ├── MainActivity.java        # BottomNavigationView navigation host
│       ├── TransmitFragment.java    # Transmitter UI, test pattern, WAV export
│       ├── ReceiveFragment.java     # Receiver UI, live microphone, slant slider
│       └── WaterfallView.java       # Real-time 1000-2500 Hz frequency spectrogram
└── res/
    ├── layout/                      # Material 3 XML layouts
    ├── menu/                        # Navigation menu
    └── values/                      # Dark theme, colors, typography
```

---

## Automated Test Benchmarks

Run the test suite with:
```bash
./gradlew test
```

Verified results on 44.1 kHz 16-bit audio:
- **FastFft Accuracy:** 1200 Hz peak detected at 1205.86 Hz; 1900 Hz peak at 1894.92 Hz (within $\pm25\text{ Hz}$).
- **WAV Round-Trip:** 44,100 samples verified bit-identical.
- **Robot 8 B&W Loopback:** Encoded in **10 ms**, Decoded in **19 ms** (MAE: **4.63**).
- **Lightweight Color Loopback:** Encoded in **7 ms**, Decoded in **15 ms** (MAE: **23.24**).
- **Fast 12 Color Loopback:** Encoded in **9 ms**, Decoded in **23 ms** (MAE: **27.22**).
- **Robot 36 Color Loopback:** Encoded in **13 ms**, Decoded in **55 ms** (MAE: **13.25**).

---

## Building and Installing

### Prerequisites
- JDK 17
- Android SDK 34 (Minimum SDK 24)

### Build Debug APK
```bash
./gradlew assembleDebug
```
The resulting APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

### Install to Connected Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

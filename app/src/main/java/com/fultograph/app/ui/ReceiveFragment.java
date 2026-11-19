package com.fultograph.app.ui;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fultograph.app.audio.LiveAudioRecorder;
import com.fultograph.app.audio.WavFileHandler;
import com.fultograph.app.databinding.FragmentReceiveBinding;
import com.fultograph.app.dsp.FmDemodulator;
import com.fultograph.app.dsp.LiveSstvReceiver;
import com.fultograph.app.dsp.RawSstvStreamDecoder;
import com.fultograph.app.dsp.SstvDecoder;
import com.fultograph.app.dsp.SstvMode;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receive Fragment: Real-time frequency waterfall, live continuous Raw Mode streaming (no time limit),
 * universal real-time live SSTV receiver for all color modes (Martin, Scottie, Robot, SC2-120, PD 90),
 * sub-second offline WAV decoding, slant correction, and gallery export.
 */
public class ReceiveFragment extends Fragment {

    private FragmentReceiveBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SstvDecoder decoder = new SstvDecoder();
    private final RawSstvStreamDecoder rawDecoder = new RawSstvStreamDecoder();
    private final LiveSstvReceiver liveReceiver = new LiveSstvReceiver();
    private final FmDemodulator liveDemodulator = new FmDemodulator();
    private final LiveAudioRecorder recorder = new LiveAudioRecorder();

    // Receiver state
    private boolean isRawMode = false;
    private SstvMode forcedMode = null; // null means Auto-Detect (Continuous Live • All Modes)
    private double currentSlantOffset = 0.0;

    // Buffer for current audio session
    private short[] activePcmBuffer;
    private Bitmap lastDecodedBitmap;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    toggleListening();
                } else {
                    Toast.makeText(requireContext(), "Microphone permission is required to listen to SSTV audio", Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<String> wavPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onWavFilePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentReceiveBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupModeDropdown();
        setupRawModeControls();
        setupAudioRecorder();
        setupActionButtons();
        setupSlantControl();
        setupSaveImage();
    }

    private void setupModeDropdown() {
        List<String> optionsList = new ArrayList<>();
        optionsList.add("Auto-Detect (Continuous Live • All Modes)");
        optionsList.add("Raw Mode (Continuous Real-Time)");
        for (SstvMode m : SstvMode.values()) {
            optionsList.add(m.getDisplayName());
        }
        String[] options = optionsList.toArray(new String[0]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                options
        );
        binding.actvRxMode.setAdapter(adapter);
        binding.actvRxMode.setText(options[0], false);

        binding.actvRxMode.setOnItemClickListener((parent, view, position, id) -> {
            float density = getResources().getDisplayMetrics().density;
            if (position == 0) {
                // Auto-Detect VIS
                isRawMode = false;
                forcedMode = null;
                liveReceiver.forceMode(null);
                binding.layoutRawControls.setVisibility(View.GONE);
                binding.cardDecodedImage.getLayoutParams().height = (int) (320 * density);
                binding.cardDecodedImage.requestLayout();
                binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                binding.scrollDecodedImage.scrollTo(0, 0);
                binding.tvTelemetryMode.setText("Mode: Auto (VIS)");
            } else if (position == 1) {
                // Raw Mode (Continuous Real-Time, No Time Limit, Long Scrollable Area)
                isRawMode = true;
                forcedMode = null;
                binding.layoutRawControls.setVisibility(View.VISIBLE);
                binding.cardDecodedImage.getLayoutParams().height = (int) (440 * density);
                binding.cardDecodedImage.requestLayout();
                binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                binding.tvTelemetryMode.setText("Mode: RAW (" + rawDecoder.getColorMode().name() + " • Real-Time)");
                binding.tvDecodeMetrics.setText("Raw Mode active: Continuous real-time color stream. No time limit.");
            } else {
                // Specific SSTV mode
                isRawMode = false;
                forcedMode = SstvMode.values()[position - 2];
                liveReceiver.forceMode(forcedMode);
                binding.layoutRawControls.setVisibility(View.GONE);
                binding.cardDecodedImage.getLayoutParams().height = (int) (320 * density);
                binding.cardDecodedImage.requestLayout();
                binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                binding.scrollDecodedImage.scrollTo(0, 0);
                binding.tvTelemetryMode.setText("Mode: " + forcedMode.name());
            }

            if (activePcmBuffer != null) {
                decodeOfflineBuffer(activePcmBuffer);
            }
        });
    }

    private void setupRawModeControls() {
        // Disallow parent NestedScrollView interception while user is scrolling the decoded image
        binding.scrollDecodedImage.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        binding.chipRawAutoColor.setOnClickListener(v -> {
            rawDecoder.setColorMode(RawSstvStreamDecoder.RawColorMode.AUTO);
            binding.tvTelemetryMode.setText("Mode: RAW (Auto Color)");
            if (activePcmBuffer != null) decodeOfflineBuffer(activePcmBuffer);
        });

        binding.chipRaw36s.setOnClickListener(v -> {
            rawDecoder.setColorMode(RawSstvStreamDecoder.RawColorMode.ROBOT_36);
            binding.tvTelemetryMode.setText("Mode: RAW (Robot 36 Color • 150ms)");
            if (activePcmBuffer != null) decodeOfflineBuffer(activePcmBuffer);
        });

        binding.chipRawMartin.setOnClickListener(v -> {
            rawDecoder.setColorMode(RawSstvStreamDecoder.RawColorMode.MARTIN);
            binding.tvTelemetryMode.setText("Mode: RAW (Martin Color • RGB)");
            if (activePcmBuffer != null) decodeOfflineBuffer(activePcmBuffer);
        });

        binding.chipRawScottie.setOnClickListener(v -> {
            rawDecoder.setColorMode(RawSstvStreamDecoder.RawColorMode.SCOTTIE);
            binding.tvTelemetryMode.setText("Mode: RAW (Scottie Color • RGB)");
            if (activePcmBuffer != null) decodeOfflineBuffer(activePcmBuffer);
        });

        binding.chipRaw8s.setOnClickListener(v -> {
            rawDecoder.setColorMode(RawSstvStreamDecoder.RawColorMode.ROBOT_8_BW);
            binding.tvTelemetryMode.setText("Mode: RAW (Robot 8 B&W • 66.7ms)");
            if (activePcmBuffer != null) decodeOfflineBuffer(activePcmBuffer);
        });

        binding.btnScrollBottom.setOnClickListener(v -> {
            binding.scrollDecodedImage.post(() -> binding.scrollDecodedImage.fullScroll(View.FOCUS_DOWN));
        });

        binding.btnClearCanvas.setOnClickListener(v -> {
            rawDecoder.clearCanvas();
            binding.ivDecodedImage.setImageBitmap(rawDecoder.getCanvasBitmap());
            binding.scrollDecodedImage.scrollTo(0, 0);
        });
    }

    private void setupAudioRecorder() {
        liveReceiver.setReceiverListener(new LiveSstvReceiver.ReceiverListener() {
            @Override
            public void onModeDetected(SstvMode mode) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    binding.tvTelemetryMode.setText("Mode: " + mode.name() + " (Locked)");
                    binding.tvDecodeMetrics.setText("Locked: " + mode.getDisplayName() + " • Decoding live...");
                    binding.progressDecodeLive.setVisibility(View.VISIBLE);
                    binding.progressDecodeLive.setIndeterminate(false);
                    binding.progressDecodeLive.setProgress(0);
                    float density = getResources().getDisplayMetrics().density;
                    binding.cardDecodedImage.getLayoutParams().height = (int) (320 * density);
                    binding.cardDecodedImage.requestLayout();
                    binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                });
            }

            @Override
            public void onLineRendered(int currentLine, int totalLines, Bitmap currentBitmap) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = currentBitmap;
                    binding.ivDecodedImage.setImageBitmap(currentBitmap);
                    int pct = (int) ((currentLine / (float) totalLines) * 100);
                    binding.progressDecodeLive.setProgress(pct);
                    binding.tvDecodeMetrics.setText(String.format("Decoding %s: Line %d / %d (%d%%)",
                            liveReceiver.getCurrentMode() != null ? liveReceiver.getCurrentMode().name() : "SSTV",
                            currentLine + 1, totalLines, pct));
                });
            }

            @Override
            public void onImageCompleted(SstvMode mode, Bitmap completedBitmap) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = completedBitmap;
                    binding.ivDecodedImage.setImageBitmap(completedBitmap);
                    binding.progressDecodeLive.setProgress(100);
                    binding.tvDecodeMetrics.setText(String.format("Completed %s (%d×%d) • Ready for next image!",
                            mode.name(), mode.getWidth(), mode.getHeight()));
                    Toast.makeText(requireContext(), "Received " + mode.name() + " image!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onStatusUpdate(String status) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> binding.tvDecodeMetrics.setText(status));
            }
        });

        recorder.setAudioRecordListener(new LiveAudioRecorder.AudioRecordListener() {
            @Override
            public void onAudioChunk(short[] chunk, int length, double rms, boolean isClipping, boolean isTooQuiet) {
                // 1. Push to waterfall spectrogram view
                binding.waterfallView.pushPcmChunk(chunk, 0);

                // 2. Instantaneous demodulated frequency for telemetry
                double lastFreq = 0;
                for (int i = 0; i < length; i++) {
                    lastFreq = liveDemodulator.processSample(chunk[i]);
                }
                binding.tvTelemetryFreq.setText(String.format("Freq: %04d Hz", (int) Math.round(lastFreq)));

                // 3. Audio warnings
                if (isClipping) {
                    binding.tvSignalWarning.setVisibility(View.VISIBLE);
                    binding.tvSignalWarning.setText("AUDIO CLIPPING");
                    binding.tvSignalWarning.setTextColor(0xFFFF3B30);
                } else if (isTooQuiet) {
                    binding.tvSignalWarning.setVisibility(View.VISIBLE);
                    binding.tvSignalWarning.setText("LOW SIGNAL");
                    binding.tvSignalWarning.setTextColor(0xFFFF9500);
                } else {
                    binding.tvSignalWarning.setVisibility(View.GONE);
                }

                // 4. Raw Mode: Instantaneous streaming line decode with zero buffer limit
                if (isRawMode) {
                    rawDecoder.processChunk(chunk, 0, length);
                    Bitmap canvas = rawDecoder.getCanvasBitmap();
                    lastDecodedBitmap = canvas;
                    binding.ivDecodedImage.setImageBitmap(canvas);
                    binding.tvDecodeMetrics.setText(String.format("Raw Stream: Frame %d • Line %d (Live)",
                            rawDecoder.getFrameCount() + 1, rawDecoder.getCurrentLine() + 1));
                    binding.scrollDecodedImage.post(() -> binding.scrollDecodedImage.fullScroll(View.FOCUS_DOWN));
                    return;
                }

                // 5. Universal Live SSTV Receiver: Continuous real-time decode with zero time limits
                liveReceiver.processChunk(chunk, 0, length);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                setListeningUi(false);
            }
        });
    }

    private void setupActionButtons() {
        binding.btnListen.setOnClickListener(v -> checkPermissionAndToggleListening());
        binding.btnImportWav.setOnClickListener(v -> {
            stopListening();
            wavPickerLauncher.launch("audio/*");
        });
    }

    private void checkPermissionAndToggleListening() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            toggleListening();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void toggleListening() {
        if (recorder.isRecording()) {
            stopListening();
        } else {
            startListening();
        }
    }

    private void startListening() {
        if (isRawMode) {
            rawDecoder.clearCanvas();
            binding.ivDecodedImage.setImageBitmap(rawDecoder.getCanvasBitmap());
            binding.tvDecodeMetrics.setText("Raw Mode: Listening and decoding live stream continuously...");
        } else {
            if (forcedMode != null) {
                liveReceiver.forceMode(forcedMode);
                binding.tvDecodeMetrics.setText("Listening for " + forcedMode.getDisplayName() + "...");
            } else {
                liveReceiver.reset();
                binding.tvDecodeMetrics.setText("Listening for SSTV tones (Auto-detecting all modes)...");
            }
        }

        liveDemodulator.reset();
        boolean started = recorder.startRecording();
        setListeningUi(started);
        if (started) {
            binding.progressDecodeLive.setVisibility(View.VISIBLE);
            binding.progressDecodeLive.setIndeterminate(true);
        }
    }

    private void stopListening() {
        recorder.stopRecording();
        setListeningUi(false);
        binding.progressDecodeLive.setVisibility(View.GONE);
    }

    private void setListeningUi(boolean listening) {
        binding.btnListen.setText(listening ? "Stop Listening" : "Start Listening");
        binding.btnListen.setIconResource(listening ?
                android.R.drawable.ic_media_pause : android.R.drawable.ic_btn_speak_now);
    }

    private void onWavFilePicked(@Nullable Uri uri) {
        if (uri == null) return;

        binding.tvDecodeMetrics.setText("Reading and decoding audio file...");
        binding.progressDecodeLive.setVisibility(View.VISIBLE);
        binding.progressDecodeLive.setIndeterminate(true);

        executor.execute(() -> {
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                if (is == null) throw new Exception("Could not open file stream");

                short[] pcm = WavFileHandler.readWav(is);
                requireActivity().runOnUiThread(() -> {
                    activePcmBuffer = pcm;
                    decodeOfflineBuffer(pcm);
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    binding.progressDecodeLive.setVisibility(View.GONE);
                    binding.tvDecodeMetrics.setText("Failed to import audio: " + e.getMessage());
                    Toast.makeText(requireContext(), "Import error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void decodeOfflineBuffer(short[] pcm) {
        binding.progressDecodeLive.setVisibility(View.VISIBLE);
        binding.progressDecodeLive.setIndeterminate(false);

        executor.execute(() -> {
            long startTime = System.currentTimeMillis();

            // Check if Raw Mode is active
            if (isRawMode) {
                rawDecoder.clearCanvas();
                rawDecoder.processChunk(pcm, 0, pcm.length);
                Bitmap bmp = rawDecoder.getCanvasBitmap();
                long duration = System.currentTimeMillis() - startTime;

                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = bmp;
                    float density = getResources().getDisplayMetrics().density;
                    binding.cardDecodedImage.getLayoutParams().height = (int) (440 * density);
                    binding.cardDecodedImage.requestLayout();
                    binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                    binding.ivDecodedImage.setImageBitmap(bmp);
                    binding.progressDecodeLive.setVisibility(View.GONE);
                    double audioSec = pcm.length / 44100.0;
                    binding.tvDecodeMetrics.setText(String.format("Raw Mode: Decoded %.1fs in %d ms • %d frames (%d lines)",
                            audioSec, duration, rawDecoder.getFrameCount() + 1, rawDecoder.getCurrentLine() + 1));
                    binding.tvTelemetryMode.setText("Mode: RAW (" + String.format("%.1f", rawDecoder.getLineDurationMs()) + "ms)");
                    binding.scrollDecodedImage.post(() -> binding.scrollDecodedImage.scrollTo(0, 0));
                });
                return;
            }

            // Universal multi-image offline decode (handles single images and multi-image compilations)
            List<SstvDecoder.DecodeResult> results = decoder.decodeAll(pcm, forcedMode, currentSlantOffset, (line, total, pixels, mode) -> {
                int percent = (int) ((line / (float) total) * 100);
                requireActivity().runOnUiThread(() -> binding.progressDecodeLive.setProgress(percent));
            });

            // If no VIS header is detected in the audio (e.g. 1980s transmission), automatically
            // fallback to Raw Mode to decode the full multi-frame signal without failure!
            if (results.isEmpty() || (!results.get(0).visDetected && forcedMode == null && pcm.length > 44100 * 10)) {
                rawDecoder.clearCanvas();
                rawDecoder.processChunk(pcm, 0, pcm.length);
                Bitmap bmp = rawDecoder.getCanvasBitmap();
                long duration = System.currentTimeMillis() - startTime;

                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = bmp;
                    float density = getResources().getDisplayMetrics().density;
                    binding.cardDecodedImage.getLayoutParams().height = (int) (440 * density);
                    binding.cardDecodedImage.requestLayout();
                    binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                    binding.ivDecodedImage.setImageBitmap(bmp);
                    binding.progressDecodeLive.setVisibility(View.GONE);
                    binding.layoutRawControls.setVisibility(View.VISIBLE);
                    double audioSec = pcm.length / 44100.0;
                    binding.tvDecodeMetrics.setText(String.format("Raw Fallback: Decoded %.1fs in %d ms • %d frames (%d lines)",
                            audioSec, duration, rawDecoder.getFrameCount() + 1, rawDecoder.getCurrentLine() + 1));
                    binding.tvTelemetryMode.setText("Mode: RAW (Auto-Fallback)");
                    binding.scrollDecodedImage.post(() -> binding.scrollDecodedImage.scrollTo(0, 0));
                });
                return;
            }

            long duration = System.currentTimeMillis() - startTime;

            if (results.size() == 1) {
                SstvDecoder.DecodeResult res = results.get(0);
                Bitmap bmp = res.toBitmap();
                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = bmp;
                    float density = getResources().getDisplayMetrics().density;
                    binding.cardDecodedImage.getLayoutParams().height = (int) (320 * density);
                    binding.cardDecodedImage.requestLayout();
                    binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    binding.ivDecodedImage.setImageBitmap(bmp);
                    binding.progressDecodeLive.setVisibility(View.GONE);

                    String visInfo = res.visDetected ? ("VIS: " + res.mode.name()) : ("Forced: " + res.mode.name());
                    binding.tvDecodeMetrics.setText("Decoded in " + duration + " ms • " +
                            visInfo + " (" + res.width + "×" + res.height + ")");
                    binding.tvTelemetryMode.setText("Mode: " + res.mode.name());
                });
            } else {
                // Multi-image decode (e.g. YouTube SSTV compilations like h1VmbZuwCJ0 with 7 images!)
                int maxWidth = 0;
                int totalHeight = 0;
                int gap = 16;
                for (SstvDecoder.DecodeResult r : results) {
                    if (r.width > maxWidth) maxWidth = r.width;
                    totalHeight += r.height + gap;
                }
                Bitmap composite = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(composite);
                canvas.drawColor(0xFF101014);

                int yOffset = 0;
                StringBuilder modesSummary = new StringBuilder();
                for (int i = 0; i < results.size(); i++) {
                    SstvDecoder.DecodeResult r = results.get(i);
                    Bitmap img = r.toBitmap();
                    int xOffset = (maxWidth - r.width) / 2;
                    canvas.drawBitmap(img, xOffset, yOffset, null);
                    yOffset += r.height + gap;
                    if (i > 0) modesSummary.append(", ");
                    modesSummary.append(r.mode.name());
                }

                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = composite;
                    float density = getResources().getDisplayMetrics().density;
                    binding.cardDecodedImage.getLayoutParams().height = (int) (440 * density);
                    binding.cardDecodedImage.requestLayout();
                    binding.ivDecodedImage.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                    binding.ivDecodedImage.setImageBitmap(composite);
                    binding.progressDecodeLive.setVisibility(View.GONE);

                    binding.tvDecodeMetrics.setText(String.format("Decoded %d images in %d ms (%s)",
                            results.size(), duration, modesSummary.toString()));
                    binding.tvTelemetryMode.setText("Multi: " + results.size() + " Images");
                    binding.scrollDecodedImage.post(() -> binding.scrollDecodedImage.scrollTo(0, 0));
                });
            }
        });
    }

    private void setupSlantControl() {
        // Slider range [0, 200] mapped to [-10.0, +10.0] samples/line
        binding.seekSlant.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSlantOffset = (progress - 100) / 10.0;
                binding.tvSlantVal.setText(String.format("%+.2f px/line", currentSlantOffset));

                if (fromUser && activePcmBuffer != null && !isRawMode) {
                    decodeOfflineBuffer(activePcmBuffer);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupSaveImage() {
        binding.btnSaveImage.setOnClickListener(v -> {
            if (lastDecodedBitmap == null) {
                Toast.makeText(requireContext(), "No decoded image to save", Toast.LENGTH_SHORT).show();
                return;
            }

            executor.execute(() -> {
                try {
                    String displayName = "SSTV_Decoded_" + System.currentTimeMillis() + ".png";
                    ContentResolver resolver = requireContext().getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SSTV");
                        values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    }

                    Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream os = resolver.openOutputStream(uri)) {
                            if (os != null) {
                                lastDecodedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear();
                            values.put(MediaStore.Images.Media.IS_PENDING, 0);
                            resolver.update(uri, values, null, null);
                        }

                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "Saved image to Pictures/SSTV", Toast.LENGTH_LONG).show()
                        );
                    }
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Failed to save: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            });
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        stopListening();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopListening();
        recorder.release();
        binding = null;
    }
}

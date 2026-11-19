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
import com.fultograph.app.dsp.SstvDecoder;
import com.fultograph.app.dsp.SstvMode;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receive Fragment: Real-time frequency waterfall, live microphone SSTV capture & line-by-line decoding,
 * ultra-fast offline WAV decoding (< 300 ms), slant correction, and gallery export.
 */
public class ReceiveFragment extends Fragment {

    private FragmentReceiveBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SstvDecoder decoder = new SstvDecoder();
    private final FmDemodulator liveDemodulator = new FmDemodulator();
    private final LiveAudioRecorder recorder = new LiveAudioRecorder();

    // Receiver mode: null means Auto-Detect (VIS)
    private SstvMode forcedMode = null;
    private double currentSlantOffset = 0.0;

    // Buffer for current audio session (either live mic or imported WAV)
    private short[] activePcmBuffer;
    private Bitmap lastDecodedBitmap;

    // Streaming buffer for live microphone mode
    private final int MAX_STREAM_SAMPLES = 44100 * 45; // up to ~45 seconds
    private short[] streamPcm = new short[MAX_STREAM_SAMPLES];
    private int streamWriteIndex = 0;
    private long lastLiveDecodeUpdate = 0;

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
        setupAudioRecorder();
        setupActionButtons();
        setupSlantControl();
        setupSaveImage();
    }

    private void setupModeDropdown() {
        String[] options = {
                "Auto-Detect (VIS)",
                SstvMode.ROBOT_8_BW.getDisplayName(),
                SstvMode.LIGHTWEIGHT.getDisplayName(),
                SstvMode.FAST_12.getDisplayName(),
                SstvMode.ROBOT_36.getDisplayName()
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                options
        );
        binding.actvRxMode.setAdapter(adapter);
        binding.actvRxMode.setText(options[0], false);

        binding.actvRxMode.setOnItemClickListener((parent, view, position, id) -> {
            if (position == 0) {
                forcedMode = null;
                binding.tvTelemetryMode.setText("Mode: Auto (VIS)");
            } else {
                forcedMode = SstvMode.values()[position - 1];
                binding.tvTelemetryMode.setText("Mode: " + forcedMode.name());
            }

            // Re-decode if we already have an active PCM buffer
            if (activePcmBuffer != null) {
                decodeOfflineBuffer(activePcmBuffer);
            }
        });
    }

    private void setupAudioRecorder() {
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

                // 4. Append to live stream buffer
                synchronized (streamPcm) {
                    int toCopy = Math.min(length, MAX_STREAM_SAMPLES - streamWriteIndex);
                    System.arraycopy(chunk, 0, streamPcm, streamWriteIndex, toCopy);
                    streamWriteIndex += toCopy;
                }

                // 5. Periodic live scanline decode update (~4 times/sec)
                long now = System.currentTimeMillis();
                if (now - lastLiveDecodeUpdate > 250 && streamWriteIndex > 44100) {
                    lastLiveDecodeUpdate = now;
                    triggerLiveDecodeUpdate();
                }
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
        // Reset live stream buffer
        synchronized (streamPcm) {
            streamWriteIndex = 0;
        }
        liveDemodulator.reset();

        boolean started = recorder.startRecording();
        setListeningUi(started);
        if (started) {
            binding.tvDecodeMetrics.setText("Listening for SSTV tones via microphone...");
            binding.progressDecodeLive.setVisibility(View.VISIBLE);
            binding.progressDecodeLive.setIndeterminate(true);
        }
    }

    private void stopListening() {
        recorder.stopRecording();
        setListeningUi(false);
        binding.progressDecodeLive.setVisibility(View.GONE);

        // Perform final full pass over recorded audio
        synchronized (streamPcm) {
            if (streamWriteIndex > 44100) {
                short[] recorded = new short[streamWriteIndex];
                System.arraycopy(streamPcm, 0, recorded, 0, streamWriteIndex);
                activePcmBuffer = recorded;
                decodeOfflineBuffer(recorded);
            }
        }
    }

    private void setListeningUi(boolean listening) {
        binding.btnListen.setText(listening ? "Stop Listening" : "Start Listening");
        binding.btnListen.setIconResource(listening ?
                android.R.drawable.ic_media_pause : android.R.drawable.ic_btn_speak_now);
    }

    private void triggerLiveDecodeUpdate() {
        executor.execute(() -> {
            short[] snapshot;
            synchronized (streamPcm) {
                snapshot = new short[streamWriteIndex];
                System.arraycopy(streamPcm, 0, snapshot, 0, streamWriteIndex);
            }

            SstvDecoder.DecodeResult res = decoder.decode(snapshot, forcedMode, currentSlantOffset, null);
            if (res.visDetected || res.pixels != null) {
                Bitmap bmp = res.toBitmap();
                requireActivity().runOnUiThread(() -> {
                    lastDecodedBitmap = bmp;
                    binding.ivDecodedImage.setImageBitmap(bmp);
                    binding.tvTelemetryMode.setText("Mode: " + res.mode.name());
                });
            }
        });
    }

    private void onWavFilePicked(@Nullable Uri uri) {
        if (uri == null) return;

        binding.tvDecodeMetrics.setText("Reading and decoding WAV file...");
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
                    binding.tvDecodeMetrics.setText("Failed to import WAV: " + e.getMessage());
                    Toast.makeText(requireContext(), "Import error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void decodeOfflineBuffer(short[] pcm) {
        binding.progressDecodeLive.setVisibility(View.VISIBLE);
        binding.progressDecodeLive.setIndeterminate(false);

        executor.execute(() -> {
            SstvDecoder.DecodeResult res = decoder.decode(pcm, forcedMode, currentSlantOffset, (line, total, pixels, mode) -> {
                int percent = (int) ((line / (float) total) * 100);
                requireActivity().runOnUiThread(() -> binding.progressDecodeLive.setProgress(percent));
            });

            Bitmap bmp = res.toBitmap();
            requireActivity().runOnUiThread(() -> {
                lastDecodedBitmap = bmp;
                binding.ivDecodedImage.setImageBitmap(bmp);
                binding.progressDecodeLive.setVisibility(View.GONE);

                String visInfo = res.visDetected ? ("VIS: " + res.mode.name()) : ("Forced: " + res.mode.name());
                binding.tvDecodeMetrics.setText("Decoded in " + res.decodeTimeMs + " ms • " +
                        visInfo + " (" + res.width + "×" + res.height + ")");
                binding.tvTelemetryMode.setText("Mode: " + res.mode.name());
            });
        });
    }

    private void setupSlantControl() {
        // Slider range [0, 200] mapped to [-10.0, +10.0] samples/line
        binding.seekSlant.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSlantOffset = (progress - 100) / 10.0;
                binding.tvSlantVal.setText(String.format("%+.2f px/line", currentSlantOffset));

                if (fromUser && activePcmBuffer != null) {
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

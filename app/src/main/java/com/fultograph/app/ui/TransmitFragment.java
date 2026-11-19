package com.fultograph.app.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
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
import androidx.fragment.app.Fragment;

import com.fultograph.app.audio.AudioPlayerController;
import com.fultograph.app.audio.WavFileHandler;
import com.fultograph.app.databinding.FragmentTransmitBinding;
import com.fultograph.app.dsp.SstvEncoder;
import com.fultograph.app.dsp.SstvMode;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Transmit Fragment: Image selection, SMPTE pattern generation, instant background encoding,
 * real-time AudioTrack playback, and lossless WAV file sharing & export.
 */
public class TransmitFragment extends Fragment {

    private FragmentTransmitBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SstvEncoder encoder = new SstvEncoder();
    private final AudioPlayerController player = new AudioPlayerController();

    private SstvMode selectedMode = SstvMode.ROBOT_8_BW;
    private Bitmap currentSourceBitmap;
    private short[] currentEncodedPcm;

    private final ActivityResultLauncher<String> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImagePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentTransmitBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupModeSelector();
        setupImageActions();
        setupPlaybackControls();
        setupWavActions();

        // Default to built-in SMPTE color test pattern on initial load
        loadSmpteTestPattern();
    }

    private void setupModeSelector() {
        SstvMode[] modes = SstvMode.values();
        String[] modeNames = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeNames[i] = modes[i].getDisplayName();
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                modeNames
        );
        binding.actvModeSelect.setAdapter(adapter);
        binding.actvModeSelect.setText(selectedMode.getDisplayName(), false);

        binding.actvModeSelect.setOnItemClickListener((parent, view, position, id) -> {
            selectedMode = modes[position];
            updatePreviewImage();
            triggerAutoEncode();
        });
    }

    private void setupImageActions() {
        binding.btnPickImage.setOnClickListener(v -> imagePickerLauncher.launch("image/*"));
        binding.btnTestPattern.setOnClickListener(v -> loadSmpteTestPattern());
    }

    private void onImagePicked(@Nullable Uri uri) {
        if (uri == null) return;
        executor.execute(() -> {
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    Bitmap loaded = android.graphics.BitmapFactory.decodeStream(is);
                    if (loaded != null) {
                        requireActivity().runOnUiThread(() -> {
                            currentSourceBitmap = loaded;
                            updatePreviewImage();
                            triggerAutoEncode();
                        });
                    }
                }
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void loadSmpteTestPattern() {
        int w = selectedMode.getWidth();
        int h = selectedMode.getHeight();

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();

        int[] colors = {
                0xFFFFFFFF, // White
                0xFFFFFF00, // Yellow
                0xFF00FFFF, // Cyan
                0xFF00FF00, // Green
                0xFFFF00FF, // Magenta
                0xFFFF0000, // Red
                0xFF0000FF, // Blue
                0xFF000000  // Black
        };

        float barW = (float) w / colors.length;
        for (int i = 0; i < colors.length; i++) {
            paint.setColor(colors[i]);
            canvas.drawRect(i * barW, 0, (i + 1) * barW, h * 0.75f, paint);
        }

        // Bottom test section (SMPTE ramp)
        int[] bottomColors = {0xFF000077, 0xFFFFFFFF, 0xFFFF00FF, 0xFF222222, 0xFF00FFFF, 0xFF000000};
        float bBarW = (float) w / bottomColors.length;
        for (int i = 0; i < bottomColors.length; i++) {
            paint.setColor(bottomColors[i]);
            canvas.drawRect(i * bBarW, h * 0.75f, (i + 1) * bBarW, h, paint);
        }

        currentSourceBitmap = bmp;
        updatePreviewImage();
        triggerAutoEncode();
    }

    private void updatePreviewImage() {
        if (currentSourceBitmap == null) return;

        int targetW = selectedMode.getWidth();
        int targetH = selectedMode.getHeight();
        Bitmap displayBmp = Bitmap.createScaledBitmap(currentSourceBitmap, targetW, targetH, true);

        // If B&W mode, render grayscale preview
        if (!selectedMode.isColor()) {
            Bitmap bwBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bwBmp);
            Paint p = new Paint();
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(displayBmp, 0, 0, p);
            displayBmp = bwBmp;
        }

        binding.ivTransmitPreview.setImageBitmap(displayBmp);
    }

    private void triggerAutoEncode() {
        if (currentSourceBitmap == null) return;

        player.stop();
        binding.progressEncode.setVisibility(View.VISIBLE);
        binding.tvEncodeStatus.setText("Encoding audio for " + selectedMode.getDisplayName() + "...");

        Bitmap toEncode = currentSourceBitmap;
        SstvMode mode = selectedMode;

        executor.execute(() -> {
            long start = System.currentTimeMillis();
            short[] pcm = encoder.encode(toEncode, mode, (cur, total) -> {
                int percent = (int) ((cur / (float) total) * 100);
                requireActivity().runOnUiThread(() -> binding.progressEncode.setProgress(percent));
            });
            long duration = System.currentTimeMillis() - start;

            requireActivity().runOnUiThread(() -> {
                currentEncodedPcm = pcm;
                player.loadAudio(pcm);

                binding.progressEncode.setVisibility(View.GONE);
                binding.tvEncodeStatus.setText("Encoded in " + duration + " ms • " +
                        String.format("%,d", pcm.length) + " samples (44.1 kHz Mono)");

                updatePlaybackTimer(0, player.getDurationMs());
            });
        });
    }

    private void setupPlaybackControls() {
        player.setPlayerListener(new AudioPlayerController.PlayerListener() {
            @Override
            public void onPlaybackProgress(int currentMs, int totalMs, float fraction) {
                binding.seekPlayback.setProgress((int) (fraction * binding.seekPlayback.getMax()));
                updatePlaybackTimer(currentMs, totalMs);
            }

            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                binding.btnPlayPause.setText(isPlaying ? "Pause" : "Play");
                binding.btnPlayPause.setIconResource(isPlaying ?
                        android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            }

            @Override
            public void onPlaybackCompleted() {
                binding.btnPlayPause.setText("Play");
                binding.btnPlayPause.setIconResource(android.R.drawable.ic_media_play);
                binding.seekPlayback.setProgress(0);
                updatePlaybackTimer(0, player.getDurationMs());
            }
        });

        binding.btnPlayPause.setOnClickListener(v -> {
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.play();
            }
        });

        binding.btnStop.setOnClickListener(v -> player.stop());

        binding.seekPlayback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float fraction = progress / (float) seekBar.getMax();
                    player.seekToFraction(fraction);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updatePlaybackTimer(int currentMs, int totalMs) {
        int curSec = currentMs / 1000;
        int curMin = curSec / 60;
        curSec %= 60;

        int totSec = totalMs / 1000;
        int totMin = totSec / 60;
        totSec %= 60;

        binding.tvPlaybackTime.setText(String.format("%02d:%02d / %02d:%02d", curMin, curSec, totMin, totSec));
    }

    private void setupWavActions() {
        binding.btnShareWav.setOnClickListener(v -> {
            if (currentEncodedPcm == null) {
                Toast.makeText(requireContext(), "No audio encoded yet", Toast.LENGTH_SHORT).show();
                return;
            }

            executor.execute(() -> {
                try {
                    String filename = "SSTV_" + selectedMode.name() + "_" + System.currentTimeMillis() + ".wav";
                    Uri uri = WavFileHandler.saveToCacheForSharing(requireContext(), currentEncodedPcm, filename);

                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("audio/wav");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    requireActivity().runOnUiThread(() ->
                            startActivity(Intent.createChooser(shareIntent, "Share SSTV Audio WAV"))
                    );
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            });
        });

        binding.btnSaveWav.setOnClickListener(v -> {
            if (currentEncodedPcm == null) {
                Toast.makeText(requireContext(), "No audio encoded yet", Toast.LENGTH_SHORT).show();
                return;
            }

            executor.execute(() -> {
                try {
                    String filename = "SSTV_" + selectedMode.name() + "_" + System.currentTimeMillis() + ".wav";
                    Uri uri = WavFileHandler.saveToMediaStore(requireContext(), currentEncodedPcm, filename);

                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Saved WAV to Music/SSTV", Toast.LENGTH_LONG).show()
                    );
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                    );
                }
            });
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        player.pause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        player.release();
        binding = null;
    }
}

package com.fultograph.app.audio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Robust handler for reading and writing standard 44.1 kHz 16-bit Mono RIFF WAV files,
 * with Android FileProvider sharing and MediaStore export support.
 */
public class WavFileHandler {
    public static final int DEFAULT_SAMPLE_RATE = 44100;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int HEADER_SIZE = 44;

    /**
     * Encodes 16-bit PCM short[] array into standard 44-byte RIFF WAV byte array.
     */
    public static byte[] encodeWav(short[] pcm, int offset, int length, int sampleRate) {
        int dataSize = length * 2;
        int totalSize = HEADER_SIZE + dataSize;
        byte[] buffer = new byte[totalSize];
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        bb.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        bb.putInt(36 + dataSize); // ChunkSize
        bb.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');

        // fmt subchunk
        bb.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        bb.putInt(16); // Subchunk1Size (16 for PCM)
        bb.putShort((short) 1); // AudioFormat (1 = PCM)
        bb.putShort((short) CHANNELS);
        bb.putInt(sampleRate);
        int byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8);
        bb.putInt(byteRate);
        short blockAlign = (short) (CHANNELS * (BITS_PER_SAMPLE / 8));
        bb.putShort(blockAlign);
        bb.putShort((short) BITS_PER_SAMPLE);

        // data subchunk
        bb.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        bb.putInt(dataSize);

        // PCM sample data
        for (int i = 0; i < length; i++) {
            bb.putShort(pcm[offset + i]);
        }

        return buffer;
    }

    public static byte[] encodeWav(short[] pcm, int sampleRate) {
        return encodeWav(pcm, 0, pcm.length, sampleRate);
    }

    /**
     * Writes 16-bit PCM short[] to an output stream as a standard WAV file.
     */
    public static void writeWav(OutputStream out, short[] pcm, int offset, int length, int sampleRate) throws IOException {
        byte[] wavBytes = encodeWav(pcm, offset, length, sampleRate);
        out.write(wavBytes);
        out.flush();
    }

    /**
     * Writes 16-bit PCM short[] to a File.
     */
    public static void writeWavFile(File file, short[] pcm, int offset, int length, int sampleRate) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            writeWav(fos, pcm, offset, length, sampleRate);
        }
    }

    /**
     * Reads a WAV file from an InputStream and returns the 16-bit PCM samples as short[].
     * Parses RIFF header and gracefully skips non-data chunks (e.g. LIST, JUNK).
     */
    public static short[] readWav(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(chunk)) != -1) {
            baos.write(chunk, 0, bytesRead);
        }
        byte[] data = baos.toByteArray();
        return readWavFromBytes(data);
    }

    public static short[] readWavFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readWav(fis);
        }
    }

    /**
     * Reads and parses WAV from a raw byte array.
     */
    public static short[] readWavFromBytes(byte[] data) throws IOException {
        if (data.length < 44) {
            throw new IOException("File too small to be a valid WAV (less than 44 bytes)");
        }

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Validate RIFF header
        byte[] tag = new byte[4];
        bb.get(tag);
        if (tag[0] != 'R' || tag[1] != 'I' || tag[2] != 'F' || tag[3] != 'F') {
            throw new IOException("Invalid WAV: Missing 'RIFF' header");
        }

        int fileSize = bb.getInt(); // ChunkSize

        bb.get(tag);
        if (tag[0] != 'W' || tag[1] != 'A' || tag[2] != 'V' || tag[3] != 'E') {
            throw new IOException("Invalid WAV: Missing 'WAVE' format tag");
        }

        int channels = 1;
        int sampleRate = 44100;
        int bitsPerSample = 16;
        int dataOffset = -1;
        int dataLength = 0;

        // Traverse chunks until 'data' chunk is located
        while (bb.remaining() >= 8) {
            bb.get(tag);
            int chunkSize = bb.getInt();

            if (tag[0] == 'f' && tag[1] == 'm' && tag[2] == 't' && tag[3] == ' ') {
                short format = bb.getShort();
                channels = bb.getShort();
                sampleRate = bb.getInt();
                int byteRate = bb.getInt();
                short blockAlign = bb.getShort();
                bitsPerSample = bb.getShort();

                // Skip any extra format bytes (e.g. if chunkSize > 16)
                if (chunkSize > 16) {
                    int extra = chunkSize - 16;
                    if (bb.remaining() >= extra) {
                        bb.position(bb.position() + extra);
                    }
                }
            } else if (tag[0] == 'd' && tag[1] == 'a' && tag[2] == 't' && tag[3] == 'a') {
                dataOffset = bb.position();
                dataLength = Math.min(chunkSize, bb.remaining());
                break;
            } else {
                // Unknown chunk (e.g. LIST, JUNK, INFO), skip it
                if (chunkSize > 0 && bb.remaining() >= chunkSize) {
                    bb.position(bb.position() + chunkSize);
                } else {
                    break;
                }
            }
        }

        if (dataOffset == -1) {
            throw new IOException("WAV data chunk not found");
        }

        int numSamples;
        short[] pcm;

        if (bitsPerSample == 16) {
            numSamples = dataLength / 2;
            pcm = new short[channels == 1 ? numSamples : numSamples / channels];
            bb.position(dataOffset);

            if (channels == 1) {
                for (int i = 0; i < numSamples; i++) {
                    pcm[i] = bb.getShort();
                }
            } else {
                // If stereo, convert to mono by averaging channels
                int monoIndex = 0;
                for (int i = 0; i < numSamples; i += channels) {
                    int sum = 0;
                    for (int ch = 0; ch < channels; ch++) {
                        sum += bb.getShort();
                    }
                    pcm[monoIndex++] = (short) (sum / channels);
                }
            }
        } else if (bitsPerSample == 8) {
            numSamples = dataLength;
            pcm = new short[channels == 1 ? numSamples : numSamples / channels];
            bb.position(dataOffset);
            int monoIndex = 0;
            for (int i = 0; i < numSamples; i += channels) {
                int sum = 0;
                for (int ch = 0; ch < channels; ch++) {
                    int unsignedVal = bb.get() & 0xFF;
                    sum += (unsignedVal - 128) << 8;
                }
                pcm[monoIndex++] = (short) (sum / channels);
            }
        } else {
            throw new IOException("Unsupported WAV bit depth: " + bitsPerSample + " bits (only 16-bit/8-bit supported)");
        }

        return pcm;
    }

    /**
     * Saves audio to app cache directory and returns a content URI via FileProvider.
     */
    public static Uri saveToCacheForSharing(Context context, short[] pcm, String filename) throws IOException {
        File cacheDir = new File(context.getCacheDir(), "sstv_audio");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File wavFile = new File(cacheDir, filename);
        writeWavFile(wavFile, pcm, 0, pcm.length, DEFAULT_SAMPLE_RATE);

        return FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                wavFile
        );
    }

    /**
     * Saves audio WAV into device storage (Music/SSTV or Downloads) using MediaStore.
     */
    public static Uri saveToMediaStore(Context context, short[] pcm, String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SSTV");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        }

        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Failed to create MediaStore entry for " + displayName);
        }

        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) {
                throw new IOException("Failed to open output stream for MediaStore URI");
            }
            writeWav(os, pcm, 0, pcm.length, DEFAULT_SAMPLE_RATE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }

        return uri;
    }
}

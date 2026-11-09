package com.hubgoogle.record.shell;

import android.annotation.SuppressLint;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.*;
import java.util.UUID;

/**
 * Experimental *simultaneous* VOICE_UPLINK + VOICE_DOWNLINK capture for the
 * owner's locally paired ADB shell. No MIC/speaker fallback is permitted.
 *
 * Most Android builds may deny one or both AudioRecord sources. A returned
 * CAPTURING_DUAL only means the two input streams initialized, not that either
 * contains valid speech. Each saved file still requires manual playback proof.
 */
final class DualCallCapture {
    private static final int RATE = 16000;
    private static final long MAX_SEGMENT_BYTES = 16L * 1024 * 1024;
    private final File directory;
    private final Track uplink;
    private final Track downlink;
    private volatile boolean running = false;
    private volatile String lastError = "";
    private long beganNs = 0L;

    DualCallCapture(File directory) {
        this.directory = directory;
        uplink = new Track("CALL_TX", MediaRecorder.AudioSource.VOICE_UPLINK, 1);
        downlink = new Track("CALL_RX", MediaRecorder.AudioSource.VOICE_DOWNLINK, 2);
    }

    private final class Track {
        final String name;
        final int source;
        final int sourceIndex;
        volatile String id;
        AudioRecord recorder;
        Thread writer;
        volatile int level;
        Track(String name, int source, int sourceIndex) {
            this.name = name; this.source = source; this.sourceIndex = sourceIndex;
        }
        // Permission resolution here is based on the explicitly paired shell
        // UID, not ordinary APK runtime permission. start() catches denial.
        @SuppressLint("MissingPermission")
        void init() throws Exception {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
            if (min < 1) throw new IOException("PCM16 mono 16kHz unavailable");
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            recorder = new AudioRecord.Builder()
                .setAudioSource(source)
                .setContext(new ShellAudioContext())
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, 8192)).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IOException(name + " initialization denied");
        }
        void start() throws Exception {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException(name + " startRecording denied");
            rotate(SystemClock.elapsedRealtimeNanos());
            writer = new Thread(() -> capture(this), "record-dual-" + name);
            writer.start();
        }
        void rotate(long startNs) throws IOException {
            String nextId = UUID.randomUUID().toString();
            try (FileWriter metadata = new FileWriter(new File(directory, nextId + ".info"))) {
                metadata.write(Long.toString(startNs) + "\n");
                metadata.write(Integer.toString(sourceIndex) + "\n");
            }
            id = nextId;
        }
        void release() {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
        }
        void stopRecordOnly() {
            if (recorder != null) try { recorder.stop(); } catch (Exception ignored) {}
        }
    }

    synchronized String start() {
        if (running) return "ERR dual recorder already active";
        try {
            uplink.init();
            downlink.init();
            // running before spawning worker threads.
            running = true;
            beganNs = SystemClock.elapsedRealtimeNanos();
            uplink.start();
            downlink.start();
            return "CAPTURING_DUAL " + uplink.id + " " + downlink.id;
        } catch (Exception | LinkageError error) {
            running = false;
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            uplink.stopRecordOnly(); downlink.stopRecordOnly();
            join(uplink); join(downlink);
            uplink.release(); downlink.release();
            return "ERR dual source unsupported: " + lastError;
        }
    }

    private void capture(Track t) {
        byte[] buffer = new byte[8192];
        long bytesWritten = 0L;
        BufferedOutputStream output = null;
        try {
            output = new BufferedOutputStream(
                new FileOutputStream(new File(directory, t.id + ".pcm")));
            while (running) {
                int n = t.recorder.read(buffer, 0, buffer.length);
                if (n > 0) {
                    if (bytesWritten + n > MAX_SEGMENT_BYTES) {
                        output.close();
                        output = null;
                        t.rotate(SystemClock.elapsedRealtimeNanos());
                        output = new BufferedOutputStream(
                            new FileOutputStream(new File(directory, t.id + ".pcm")));
                        bytesWritten = 0L;
                    }
                    output.write(buffer, 0, n);
                    bytesWritten += n;
                    double power = 0.0;
                    for (int i = 0; i + 1 < n; i += 2) {
                        short sample = (short)(((buffer[i + 1] & 255) << 8) | (buffer[i] & 255));
                        power += (double) sample * sample;
                    }
                    double rms = Math.sqrt(power / Math.max(1, n / 2));
                    t.level = rms < 1 ? 0 : Math.max(0, Math.min(100,
                        (int)((20 * Math.log10(rms / 32768.0) + 60) / 0.6)));
                } else if (n < 0) {
                    if (running) {
                        lastError = t.name + " read error " + n;
                        running = false;
                    }
                    break;
                }
            }
        } catch (Exception error) {
            lastError = t.name + " capture failed: " + error.getMessage();
            running = false;
        } finally {
            if (output != null) {
                try { output.close(); } catch (IOException ex) {
                    lastError = t.name + " finalize failed: " + ex.getMessage();
                }
            }
            t.level = 0;
        }
    }

    private void join(Track t) {
        if (t.writer == null) return;
        try { t.writer.join(4000); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    synchronized String stop() {
        running = false;
        uplink.stopRecordOnly();
        downlink.stopRecordOnly();
        join(uplink);
        join(downlink);
        boolean alive = (uplink.writer != null && uplink.writer.isAlive()) ||
            (downlink.writer != null && downlink.writer.isAlive());
        uplink.release();
        downlink.release();
        if (alive) return "ERR dual capture did not terminate cleanly";
        if (uplink.id == null || downlink.id == null)
            return "ERR dual audio sources were never initialized";
        File tx = new File(directory, uplink.id + ".pcm");
        File rx = new File(directory, downlink.id + ".pcm");
        if (tx.length() == 0 || rx.length() == 0) return "ERR dual source empty " + lastError;
        if (!lastError.isEmpty()) return "ERR dual source incomplete " + lastError;
        return "STOPPED_DUAL " + uplink.id + " " + downlink.id;
    }

    boolean isCapturing() { return running; }
    boolean isCurrentId(String id) {
        return (id.equals(uplink.id) || id.equals(downlink.id)) && running;
    }
    String status() {
        return "STATE CAPTURING_DUAL " + (uplink.id == null ? "-" : uplink.id) + " " +
            (downlink.id == null ? "-" : downlink.id) +
            " CALL_TX_RX LEVEL " + ((uplink.level + downlink.level) / 2) +
            " ELAPSED_MS " + (beganNs > 0 ?
                (SystemClock.elapsedRealtimeNanos() - beganNs) / 1_000_000L : 0);
    }
}

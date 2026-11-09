package com.hubgoogle.record.shell;

import android.annotation.SuppressLint;

import android.media.AudioFormat;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Process;
import android.system.Os;
import java.lang.reflect.Field;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * ADB shell-UID recorder, launched locally by the owner's explicitly paired ADB session.
 * OEM permission policy may block VOICE_CALL: failures are reported, never covered by mic fallback.
 * Only binds 127.0.0.1; every request requires a random 256-bit token.
 */
public final class ShellCaptureMain {
    private static final int RATE = 16000;
    private static final int CHANNELS = 1;
    // Under nine minutes at 16 kHz PCM16 mono: a failure loses at most
    // the active segment, and completed segments remain recoverable.
    private static final long SEGMENT_LIMIT_BYTES = 16L * 1024 * 1024;
    private static final File ROOT = new File("/data/local/tmp/record-sessions");
    private final String token;
    private DualCallCapture dual;
    private AudioRecord input;
    private Thread captureThread;
    private volatile boolean capturing;
    private volatile String sessionId;
    private long captureStartedNs = 0L;
    private volatile int meterLevel = 0;
    private int selectedSource = MediaRecorder.AudioSource.VOICE_CALL;
    private String selectedName = "CALL_MIX";
    private File currentFile;
    private volatile String lastError = "";
    private final Object lock = new Object();

    private ShellCaptureMain(String token) { this.token = token; }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid args");
        int port = Integer.parseInt(args[0]);
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Port");
        if (Process.myUid() != Process.SHELL_UID) throw new SecurityException("Owner-authorized shell access required");
        // The shell's default inherited umask may permit other shell users to
        // inspect files. Enforce owner-only access to all newly created audio.
        Os.umask(0077);
        if (!ROOT.isDirectory() && !ROOT.mkdirs()) throw new IOException("Cannot create private shell directory");
        Os.chmod(ROOT.getAbsolutePath(), 0700);
        prepareAndroidRuntime();
        ShellCaptureMain server = new ShellCaptureMain(args[1]);
        try (ServerSocket socket = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                Socket peer = socket.accept();
                peer.setSoTimeout(30000);
                try { server.handle(peer); } catch (Exception e) {
                    System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally { try { peer.close(); } catch (Exception ignored) {} }
                if (server.quit) break;
            }
        } finally {
            if (server.dual != null) server.dual.stop();
            server.stopCapture();
        }
    }
    /**
     * app_process does not have the normal Android Activity main looper.
     * A valid Looper is required before AudioRecord/ActivityThread construction.
     */
    private static void prepareAndroidRuntime() {
        if (Looper.myLooper() == null) Looper.prepare();
        try {
            Field field = Looper.class.getDeclaredField("sMainLooper");
            field.setAccessible(true);
            if (field.get(null) == null) field.set(null, Looper.myLooper());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot prepare Android shell looper", e);
        }
    }
    private volatile boolean quit = false;
    private String line(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (b.size() < 1024) {
            int c = in.read();
            if (c == -1) throw new EOFException();
            if (c == '\n') return b.toString("UTF-8").trim();
            b.write(c);
        }
        throw new IOException("Overlong command");
    }
    private void write(BufferedOutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes(StandardCharsets.UTF_8)); out.flush();
    }
    private static final java.util.regex.Pattern SESSION_ID =
        java.util.regex.Pattern.compile("[0-9a-fA-F-]{36}");

    private File sessionMetadata(String id) { return new File(ROOT, id + ".info"); }

    private long[] readSessionClockAndSource(String id) {
        if (!SESSION_ID.matcher(id).matches()) return new long[]{0L, 0L};
        File metadata = sessionMetadata(id);
        if (!metadata.isFile()) return new long[]{0L, 0L};
        try (BufferedReader input = new BufferedReader(new FileReader(metadata))) {
            long timestamp = Long.parseLong(input.readLine());
            int sourceIndex = Integer.parseInt(input.readLine());
            if (timestamp <= 0 || sourceIndex < 0 || sourceIndex > 2) return new long[]{0L, 0L};
            return new long[]{timestamp, sourceIndex};
        } catch (Exception ignored) { return new long[]{0L, 0L}; }
    }

    private static String hashFile(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private void handle(Socket socket) throws Exception {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String auth = line(in);
        if (!constantTimeEquals(auth, "AUTH " + token)) { write(out, "ERR unauthorized"); return; }
        write(out, "OK");
        String cmd = line(in);
        if (cmd.startsWith("SELECT ")) {
            if (capturing || dual != null) { write(out, "ERR recording active"); return; }
            String source = cmd.substring(7);
            switch (source) {
                case "CALL_MIX": selectedSource = MediaRecorder.AudioSource.VOICE_CALL; break;
                case "CALL_TX": selectedSource = MediaRecorder.AudioSource.VOICE_UPLINK; break;
                case "CALL_RX": selectedSource = MediaRecorder.AudioSource.VOICE_DOWNLINK; break;
                default: write(out, "ERR unknown audio source"); return;
            }
            selectedName = source;
            write(out, "SELECTED " + selectedName);
        } else if (cmd.equals("STATUS") && dual != null) {
            write(out, dual.status());
        } else if (cmd.equals("STATUS")) {
            write(out, "STATE " + (capturing ? "CAPTURING" : "READY") + " " +
                (sessionId == null ? "-" : sessionId) + " " + selectedName +
                " LEVEL " + meterLevel + " ELAPSED_MS " +
                (capturing ? (SystemClock.elapsedRealtimeNanos() - captureStartedNs) / 1000000L : 0L));
        } else if (cmd.equals("START")) {
            write(out, dual == null ? startCapture() : "ERR dual mode active");
        } else if (cmd.equals("START_DUAL")) {
            if (capturing || dual != null) {
                write(out, "ERR another audio capture is active");
            } else {
                DualCallCapture candidate = new DualCallCapture(ROOT);
                String result = candidate.start();
                if (result.startsWith("CAPTURING_DUAL ")) dual = candidate;
                write(out, result);
            }
        } else if (cmd.equals("STOP") || cmd.equals("STOP_DUAL")) {
            if (dual != null) {
                String result = dual.stop();
                dual = null;
                write(out, result);
            } else if (cmd.equals("STOP_DUAL")) {
                write(out, "ERR dual source not recording");
            } else {
                write(out, stopCapture());
            }
        } else if (cmd.startsWith("FETCH ")) {
            String id = cmd.substring(6);
            if (!id.matches("[0-9a-fA-F-]{36}")) { write(out, "ERR invalid id"); return; }
            File f = new File(ROOT, id + ".pcm");
            if (capturing || dual != null || !f.isFile() || !f.getCanonicalFile().getParentFile().equals(ROOT.getCanonicalFile())) {
                write(out, "ERR file unavailable"); return;
            }
            long[] recovered = readSessionClockAndSource(id);
            long began = recovered[0];
            String name = new String[]{"CALL_MIX", "CALL_TX", "CALL_RX"}[(int)recovered[1]];
            write(out, "FILE " + f.length() + " " + RATE + " " + CHANNELS + " " +
                began + " " + name + " " + hashFile(f));
            try (InputStream stream = new BufferedInputStream(new FileInputStream(f))) {
                byte[] bytes = new byte[32768]; int n;
                while ((n = stream.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            out.flush();
        } else if (cmd.startsWith("DELETE ")) {
            String id = cmd.substring(7);
            if (!id.matches("[0-9a-fA-F-]{36}") || capturing || dual != null) { write(out, "ERR invalid state"); return; }
            File f = new File(ROOT, id + ".pcm");
            boolean removed = f.delete() || !f.exists();
            File meta = sessionMetadata(id);
            if (removed) meta.delete();
            write(out, removed ? "DELETED" : "ERR cannot delete");
        } else if (cmd.equals("PENDING")) {
            StringBuilder pending = new StringBuilder("PENDING");
            File[] files = ROOT.listFiles((dir, name) -> name.matches("[0-9a-fA-F-]{36}\\.pcm"));
            if (files != null) {
                for (File f : files) {
                    String id = f.getName().substring(0, f.getName().length() - 4);
                    if (id.equals(sessionId) && capturing) continue;
                    if (dual != null && dual.isCurrentId(id)) continue;
                    if (f.length() > 0 && pending.length() < 800) pending.append(' ').append(id);
                }
            }
            write(out, pending.toString());
        } else if (cmd.equals("SHUTDOWN")) {
            if (dual != null) { dual.stop(); dual = null; }
            stopCapture();
            quit = true; write(out, "BYE");
        } else write(out, "ERR unknown");
    }
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
    // Called only inside the shell UID process after main() checks UID 2000.
    // Android may still deny the protected VOICE_CALL source; we return ERR.
    @SuppressLint("MissingPermission")
    private String startCapture() {
        synchronized (lock) {
            if (capturing) return "CAPTURING " + sessionId;
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min < 0) return "ERR unsupported buffer " + min;
            try {
                AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
                input = new AudioRecord.Builder().setAudioSource(selectedSource)
                    .setContext(new ShellAudioContext()).setAudioFormat(format).setBufferSizeInBytes(Math.max(min * 2, 8192)).build();
                if (input.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord init denied");
                input.startRecording();
                if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Start recording refused");
                sessionId = UUID.randomUUID().toString();
                captureStartedNs = SystemClock.elapsedRealtimeNanos();
                meterLevel = 0;
                currentFile = new File(ROOT, sessionId + ".pcm");
                // Persist source and monotonic start before creating the capture
                // worker; this survives daemon crashes and subsequent recovery.
                try (FileWriter metadata = new FileWriter(sessionMetadata(sessionId))) {
                    metadata.write(Long.toString(captureStartedNs) + "\n");
                    metadata.write(Integer.toString(
                        selectedName.equals("CALL_TX") ? 1 : selectedName.equals("CALL_RX") ? 2 : 0) + "\n");
                }
                capturing = true;
                final AudioRecord recorder = input;
                final File output = currentFile;
                captureThread = new Thread(() -> capture(recorder, output), "record-shell-audio");
                captureThread.start();
                return "CAPTURING " + sessionId;
            } catch (Exception | LinkageError e) {
                lastError = e.getClass().getSimpleName() + " " + e.getMessage();
                if (input != null) { try { input.release(); } catch (Exception ignored) {} input = null; }
                capturing = false;
                return "ERR " + lastError;
            }
        }
    }
    /**
     * Finalizes each PCM segment by closing the output stream before opening
     * another. The caller fetches all PENDING segment UUIDs after STOP.
     */
    private void capture(AudioRecord recorder, File output) {
        byte[] bytes = new byte[8192];
        BufferedOutputStream stream = null;
        long segmentBytes = 0L;
        try {
            stream = new BufferedOutputStream(new FileOutputStream(output));
            while (capturing) {
                int n = recorder.read(bytes, 0, bytes.length);
                if (n > 0) {
                    if (segmentBytes + n > SEGMENT_LIMIT_BYTES) {
                        stream.flush();
                        stream.close();
                        stream = null;
                        String nextId = UUID.randomUUID().toString();
                        long nextStartedNs = SystemClock.elapsedRealtimeNanos();
                        try (FileWriter metadata = new FileWriter(sessionMetadata(nextId))) {
                            metadata.write(Long.toString(nextStartedNs) + "\n");
                            metadata.write(Integer.toString(
                                selectedName.equals("CALL_TX") ? 1 :
                                selectedName.equals("CALL_RX") ? 2 : 0) + "\n");
                        }
                        File nextFile = new File(ROOT, nextId + ".pcm");
                        stream = new BufferedOutputStream(new FileOutputStream(nextFile));
                        captureStartedNs = nextStartedNs;
                        currentFile = nextFile;
                        sessionId = nextId;
                        segmentBytes = 0L;
                    }
                    stream.write(bytes, 0, n);
                    segmentBytes += n;
                    double power = 0;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int sample = (short)(((bytes[i + 1] & 255) << 8) | (bytes[i] & 255));
                        power += (double)sample * sample;
                    }
                    double rms = Math.sqrt(power / Math.max(1, n / 2));
                    meterLevel = rms < 1 ? 0 : Math.max(0, Math.min(100,
                        (int)((20 * Math.log10(rms / 32768.0) + 60) / 0.6)));
                } else if (n < 0) {
                    if (capturing) lastError = "AudioRecord read " + n;
                    break;
                }
            }
        } catch (IOException e) {
            lastError = e.toString();
        } finally {
            if (stream != null) try { stream.close(); } catch (IOException e) {
                lastError = "Failed finalizing audio segment: " + e.getMessage();
            }
            capturing = false;
            meterLevel = 0;
        }
    }
    private String stopCapture() {
        synchronized (lock) {
            if (sessionId == null) return "ERR no session";
            capturing = false;
            if (input != null) {
                try { input.stop(); } catch (Exception ignored) {}
            }
            if (captureThread != null) {
                try { captureThread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (input != null) { input.release(); input = null; }
            if (captureThread != null && captureThread.isAlive()) return "ERR recorder did not stop";
            long bytes = currentFile != null && currentFile.isFile() ? currentFile.length() : 0;
            if (bytes == 0) return "ERR empty capture " + lastError;
            return "STOPPED " + sessionId + " " + bytes;
        }
    }
}


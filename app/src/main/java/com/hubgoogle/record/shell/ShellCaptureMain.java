package com.hubgoogle.record.shell;

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
    private static final File ROOT = new File("/data/local/tmp/record-sessions");
    private final String token;
    private AudioRecord input;
    private Thread captureThread;
    private volatile boolean capturing;
    private String sessionId;
    private long captureStartedNs = 0L;
    private int selectedSource = MediaRecorder.AudioSource.VOICE_CALL;
    private String selectedName = "CALL_MIX";
    private File currentFile;
    private String lastError = "";
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
        } finally { server.stopCapture(); }
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
    private void handle(Socket socket) throws Exception {
        BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String auth = line(in);
        if (!constantTimeEquals(auth, "AUTH " + token)) { write(out, "ERR unauthorized"); return; }
        write(out, "OK");
        String cmd = line(in);
        if (cmd.startsWith("SELECT ")) {
            if (capturing) { write(out, "ERR recording active"); return; }
            String source = cmd.substring(7);
            switch (source) {
                case "CALL_MIX": selectedSource = MediaRecorder.AudioSource.VOICE_CALL; break;
                case "CALL_TX": selectedSource = MediaRecorder.AudioSource.VOICE_UPLINK; break;
                case "CALL_RX": selectedSource = MediaRecorder.AudioSource.VOICE_DOWNLINK; break;
                default: write(out, "ERR unknown audio source"); return;
            }
            selectedName = source;
            write(out, "SELECTED " + selectedName);
        } else if (cmd.equals("STATUS")) {
            write(out, "STATE " + (capturing ? "CAPTURING" : "READY") + " " +
                (sessionId == null ? "-" : sessionId) + " " + selectedName);
        } else if (cmd.equals("START")) {
            write(out, startCapture());
        } else if (cmd.equals("STOP")) {
            write(out, stopCapture());
        } else if (cmd.startsWith("FETCH ")) {
            String id = cmd.substring(6);
            if (!id.matches("[0-9a-fA-F-]{36}")) { write(out, "ERR invalid id"); return; }
            File f = new File(ROOT, id + ".pcm");
            if (capturing || !f.isFile() || !f.getCanonicalFile().getParentFile().equals(ROOT.getCanonicalFile())) {
                write(out, "ERR file unavailable"); return;
            }
            write(out, "FILE " + f.length() + " " + RATE + " " + CHANNELS + " " +
                (id.equals(sessionId) ? captureStartedNs : 0) + " " + selectedName);
            try (InputStream stream = new BufferedInputStream(new FileInputStream(f))) {
                byte[] bytes = new byte[32768]; int n;
                while ((n = stream.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            out.flush();
        } else if (cmd.startsWith("DELETE ")) {
            String id = cmd.substring(7);
            if (!id.matches("[0-9a-fA-F-]{36}") || capturing) { write(out, "ERR invalid state"); return; }
            File f = new File(ROOT, id + ".pcm");
            write(out, f.delete() || !f.exists() ? "DELETED" : "ERR cannot delete");
        } else if (cmd.equals("PENDING")) {
            StringBuilder pending = new StringBuilder("PENDING");
            File[] files = ROOT.listFiles((dir, name) -> name.matches("[0-9a-fA-F-]{36}\\.pcm"));
            if (files != null) {
                for (File f : files) {
                    String id = f.getName().substring(0, f.getName().length() - 4);
                    if (id.equals(sessionId) && capturing) continue;
                    if (f.length() > 0 && pending.length() < 800) pending.append(' ').append(id);
                }
            }
            write(out, pending.toString());
        } else if (cmd.equals("SHUTDOWN")) {
            stopCapture();
            quit = true; write(out, "BYE");
        } else write(out, "ERR unknown");
    }
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }
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
                currentFile = new File(ROOT, sessionId + ".pcm");
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
    private void capture(AudioRecord recorder, File output) {
        byte[] bytes = new byte[8192];
        try (OutputStream file = new BufferedOutputStream(new FileOutputStream(output))) {
            while (capturing) {
                int n = recorder.read(bytes, 0, bytes.length);
                if (n > 0) file.write(bytes, 0, n);
                else if (n < 0) { lastError = "AudioRecord read " + n; break; }
            }
        } catch (IOException e) { lastError = e.toString(); }
        finally { capturing = false; }
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


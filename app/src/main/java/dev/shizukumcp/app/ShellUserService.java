package dev.shizukumcp.app;

import android.content.Context;
import android.os.RemoteException;

import androidx.annotation.Keep;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loaded by Shizuku in a separate process running as the ADB shell user or root.
 * This class stays on the Android boot classpath (no Kotlin) because Shizuku loads the APK
 * with its own class loader, and a missing Kotlin runtime there kills the shell process.
 * The destroy transaction code is the one Shizuku calls when the service is replaced.
 */
public class ShellUserService extends IShellService.Stub {
    private static final int MAX_CHUNK = 256 * 1024;
    private static final int MAX_STDOUT = 200_000;
    private static final int MAX_STDERR = 40_000;
    private final ReentrantLock lock = new ReentrantLock();

    public ShellUserService() {
    }

    @Keep
    public ShellUserService(Context context) {
        this();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String exec(String command, int timeoutMs) {
        if (command == null) return resultJson(-1, "", "command is null", false, false);
        int timeout = Math.min(Math.max(timeoutMs, 1), 120_000);
        try {
            if (!lock.tryLock(timeout + 5_000L, TimeUnit.MILLISECONDS)) {
                return resultJson(-1, "", "another shell command is still running", false, false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultJson(-1, "", "interrupted", false, false);
        }
        try {
            return run(command, timeout);
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            return resultJson(-1, "", message, false, false);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long fileSize(String path) throws RemoteException {
        File file = requireFile(path);
        if (!file.exists() || !file.isFile()) return -1L;
        return file.length();
    }

    @Override
    public byte[] readChunk(String path, long offset, int length) throws RemoteException {
        if (length <= 0) return new byte[0];
        int ask = Math.min(length, MAX_CHUNK);
        File file = requireFile(path);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            if (offset > 0) raf.seek(offset);
            byte[] buf = new byte[ask];
            int n = raf.read(buf);
            if (n < 0) return new byte[0];
            if (n == buf.length) return buf;
            byte[] exact = new byte[n];
            System.arraycopy(buf, 0, exact, 0, n);
            return exact;
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public void writeChunk(String path, long offset, boolean append, byte[] data) throws RemoteException {
        File file = requireFile(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new RemoteException("cannot create directory " + parent);
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            if (!append && offset == 0) raf.setLength(0);
            if (offset > 0) raf.seek(offset);
            if (data != null && data.length > 0) raf.write(data);
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public boolean deleteFile(String path) throws RemoteException {
        File file = requireFile(path);
        return !file.exists() || file.delete();
    }

    private String run(String command, int timeoutMs) throws Exception {
        File shell = new File("/system/bin/sh");
        String bin = shell.canExecute() ? shell.getAbsolutePath() : "sh";
        ProcessBuilder builder = new ProcessBuilder(bin, "-c", command);
        builder.redirectErrorStream(false);
        File dir = new File("/data/local/tmp");
        if (dir.isDirectory()) builder.directory(dir);
        Process process = builder.start();
        closeQuietly(process.getOutputStream());
        PipeDrain stdout = new PipeDrain(process.getInputStream(), MAX_STDOUT);
        PipeDrain stderr = new PipeDrain(process.getErrorStream(), MAX_STDERR);
        Thread outThread = new Thread(stdout, "sh-stdout");
        Thread errThread = new Thread(stderr, "sh-stderr");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            process.waitFor(2, TimeUnit.SECONDS);
        }
        outThread.join(2_000);
        errThread.join(2_000);
        int code = finished ? process.exitValue() : -1;
        return resultJson(
                code,
                decode(stdout.bytes()),
                decode(stderr.bytes()),
                !finished,
                stdout.truncated || stderr.truncated
        );
    }

    private static String resultJson(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {
        try {
            JSONObject json = new JSONObject();
            json.put("exitCode", exitCode);
            json.put("stdout", stdout == null ? "" : stdout);
            json.put("stderr", stderr == null ? "" : stderr);
            json.put("timedOut", timedOut);
            json.put("truncated", truncated);
            return json.toString();
        } catch (Exception e) {
            return "{\"exitCode\":-1,\"stdout\":\"\",\"stderr\":\"json encode failed\",\"timedOut\":false,\"truncated\":false}";
        }
    }

    private static String decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static File requireFile(String path) throws RemoteException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
            throw new RemoteException("invalid path");
        }
        return new File(path);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static final class PipeDrain implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private volatile boolean truncated;

        private PipeDrain(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            int total = 0;
            try {
                while (true) {
                    int n = input.read(buf);
                    if (n < 0) break;
                    if (total < limit) {
                        int take = Math.min(n, limit - total);
                        out.write(buf, 0, take);
                    }
                    total += n;
                    if (total > limit) truncated = true;
                }
            } catch (Exception ignored) {
                // The process was destroyed or the pipe closed.
            }
        }

        private byte[] bytes() {
            return out.toByteArray();
        }
    }
}

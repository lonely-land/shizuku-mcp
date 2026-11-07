package dev.shizukumcp.app;

import android.content.Context;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import dev.shizukumcp.core.shell.ExecCodec;
import dev.shizukumcp.core.shell.ExecResult;
import dev.shizukumcp.core.shell.JdkShellExecutor;

/**
 * Loaded by Shizuku in a separate process running as the ADB shell user or root.
 * The destroy transaction code is the one Shizuku calls when the service is replaced.
 */
public class ShellUserService extends IShellService.Stub {
    private static final int MAX_CHUNK = 256 * 1024;
    private final JdkShellExecutor executor = new JdkShellExecutor();

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
        try {
            if (command == null) {
                return ExecCodec.toJson(new ExecResult(-1, "", "command is null", false, false));
            }
            int timeout = Math.min(Math.max(timeoutMs, 1), 120_000);
            return ExecCodec.toJson(executor.exec(command, timeout));
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            return ExecCodec.toJson(new ExecResult(-1, "", message, false, false));
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
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
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
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
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

    private static File requireFile(String path) throws RemoteException {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
            throw new RemoteException("invalid path");
        }
        return new File(path);
    }
}

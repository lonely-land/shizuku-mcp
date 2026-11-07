package dev.shizukumcp.app;

interface IShellService {
    void destroy() = 16777114;

    String exec(String command, int timeoutMs) = 1;

    long fileSize(String path) = 2;

    byte[] readChunk(String path, long offset, int length) = 3;

    void writeChunk(String path, long offset, boolean append, in byte[] data) = 4;

    boolean deleteFile(String path) = 5;
}

package io.phoenixlink;

import java.io.IOException;

final class NativeCodec implements AutoCloseable {
    static final boolean PACKAGED;
    static { boolean loaded; try { System.loadLibrary("phoenix-pyrowave"); loaded = true; } catch (UnsatisfiedLinkError e) { loaded = false; } PACKAGED = loaded; }
    private long handle;
    NativeCodec(int width, int height, int bitrateMbps, int fps) throws IOException {
        if (!PACKAGED) throw new IOException("Pyrowave native library is missing.");
        int budget = (int)Math.max(4096, Math.min(8 * 1024 * 1024, (long)bitrateMbps * 1000000 / 8 / fps));
        handle = create(width, height, budget);
        if (handle == 0) throw new IOException(createError());
    }
    synchronized byte[] encode(byte[] rgba) throws IOException { requireOpen(); return encodeNative(handle, rgba); }
    synchronized byte[] decode(byte[] encoded, int rgbaBytes) throws IOException { requireOpen(); return decodeNative(handle, encoded, rgbaBytes); }
    private void requireOpen() throws IOException { if (handle == 0) throw new IOException("Pyrowave is closed."); }
    @Override public synchronized void close() { if (handle != 0) { destroy(handle); handle = 0; } }
    private static native long create(int width, int height, int budget);
    private static native String createError();
    private static native byte[] encodeNative(long handle, byte[] rgba) throws IOException;
    private static native byte[] decodeNative(long handle, byte[] encoded, int rgbaBytes) throws IOException;
    private static native void destroy(long handle);
}

package io.phoenixlink;

import org.json.JSONObject;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.concurrent.*;
import javax.net.ssl.*;

final class LanClient implements AutoCloseable {
    static final int MAX_ENCODED = 16 * 1024 * 1024, MAX_RGBA = 32 * 1024 * 1024;
    volatile SSLSocket socket;
    private volatile Socket transport;
    private volatile boolean aborted;
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"PhoenixDeadline"); t.setDaemon(true); return t; });
    private ScheduledFuture<?> timeout;
    private InputStream input; private OutputStream output;
    private NativeCodec codec;
    private final int pixelBytes;
    private long sequence, revision;
    private final boolean compressed;
    long processingUs;
    LanClient(LinkConfig config, int width, int height, LinkConfig.Tuning settings, java.util.function.Consumer<LanClient> register) throws Exception {
        config.validate(); pixelBytes = pixelBytes(width, height); compressed = config.codec.equals("pyrowave");
        register.accept(this);
        try {
            arm();
            if (compressed) codec = new NativeCodec(width, height, config.bitrateMbps, config.fps);
            if (aborted || Thread.currentThread().isInterrupted()) throw new IOException("Session stopped.");
            byte[] pin = hex(config.fingerprint);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[] { new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("Not a client-certificate endpoint"); }
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                    try {
                        if (chain == null || chain.length == 0 || !MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))
                            throw new java.security.cert.CertificateException("Certificate fingerprint mismatch. Pair again with the PC.");
                    } catch (java.security.cert.CertificateException e) { throw e; }
                    catch (Exception e) { throw new java.security.cert.CertificateException("Certificate verification failed", e); }
                }
            } }, null);
            transport = new Socket();
            if (aborted) throw new IOException("Session stopped.");
            transport.connect(new InetSocketAddress(config.host, config.port), 10000);
            if (aborted) throw new IOException("Session stopped.");
            socket = (SSLSocket)tls.getSocketFactory().createSocket(transport, config.host, config.port, true);
            socket.setEnabledProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
            socket.setSoTimeout(10000); socket.setTcpNoDelay(true);
            socket.startHandshake(); // Pin verification must finish before transmitting the bearer token.
            socket.setSoTimeout(130000); input = socket.getInputStream(); output = socket.getOutputStream();
            sendControl(new JSONObject().put("op", "open").put("version", 2).put("token", config.token).put("width", width).put("height", height)
                .put("codec", config.codec).put("bitrate_mbps", config.bitrateMbps).put("fps", config.fps).put("settings", settings.json()));
            JSONObject reply = controlReply();
            if (reply.getInt("version") != 2 || reply.getInt("width") != width || reply.getInt("height") != height ||
                !reply.getString("codec").equals(config.codec) || !reply.getString("format").equals("rgba8_srgb")) throw new IOException("PC returned an incompatible frame contract.");
            revision = reply.getLong("revision");
        } catch (Exception e) { close(); throw e; }
        finally { disarm(); }
    }
    static int pixelBytes(int w, int h) throws IOException {
        long bytes = (long)w*h*4;
        if (w < 1 || h < 1 || w > 16384 || h > 16384 || bytes > MAX_RGBA) throw new IOException("Capture exceeds frame size limit.");
        return (int)bytes;
    }
    static byte[] hex(String s) throws IOException {
        if (!s.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid certificate fingerprint.");
        byte[] result = new byte[32]; for (int i = 0; i < 32; ++i) result[i] = (byte)Integer.parseInt(s.substring(i*2, i*2+2), 16); return result;
    }
    private byte[] read(int size) throws IOException {
        byte[] data = new byte[size]; int offset = 0;
        while (offset < size) { int got = input.read(data, offset, size-offset); if (got < 0) throw new EOFException("PC disconnected."); offset += got; }
        return data;
    }
    private void sendControl(JSONObject j) throws IOException {
        byte[] body = j.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > 4096) throw new IOException("Control message is too large.");
        output.write(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)1).putInt(body.length).array()); output.write(body); output.flush();
    }
    private Packet reply() throws Exception {
        ByteBuffer h = ByteBuffer.wrap(read(5)).order(ByteOrder.LITTLE_ENDIAN); int type = h.get() & 255; long length = Integer.toUnsignedLong(h.getInt());
        if (type == 1 && length > 0 && length <= 4096 || type == 3 && (compressed ? length >= 32 && length <= MAX_ENCODED+24L : length == pixelBytes+24L))
            return new Packet(type, read((int)length));
        throw new IOException("Invalid response size or type.");
    }
    private JSONObject controlReply() throws Exception {
        Packet packet = reply(); if (packet.type != 1) throw new IOException("Expected a control reply.");
        JSONObject j = new JSONObject(new String(packet.data, StandardCharsets.UTF_8));
        if (!j.optBoolean("ok")) {
            String code = j.optString("error");
            if (code.equals("codec_unavailable")) throw new IOException("Pyrowave is missing on the PC. Update the PC app or select Raw.");
            throw new IOException("PC rejected the session or frame. Check pairing, Phoenix DLSS5 components and GPU availability.");
        }
        return j;
    }
    void update(LinkConfig.Tuning settings) throws Exception {
        arm(); try {
            sendControl(new JSONObject().put("op", "update").put("version", 2).put("settings", settings.json()));
            revision = controlReply().getLong("revision");
        } finally { disarm(); }
    }
    void ping() throws Exception { arm(); try { sendControl(new JSONObject().put("op", "ping").put("version", 2)); controlReply(); } finally { disarm(); } }
    byte[] frame(byte[] rgba, boolean reset) throws Exception {
        arm(); try {
        if (rgba.length != pixelBytes) throw new IOException("Capture changed dimensions.");
        byte[] wire = compressed ? codec.encode(rgba) : rgba;
        output.write(ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).put((byte)2).putInt(wire.length+12).putLong(++sequence).putInt(reset ? 1 : 0).array());
        output.write(wire); output.flush(); Packet packet = reply();
        if (packet.type == 1) throw new IOException("PC failed to process the frame.");
        ByteBuffer body = ByteBuffer.wrap(packet.data).order(ByteOrder.LITTLE_ENDIAN);
        if (body.getLong() != sequence || body.getLong() != revision) throw new IOException("Frame sequence or settings revision mismatch.");
        processingUs = body.getLong(); byte[] result = new byte[packet.data.length-24]; body.get(result);
        return compressed ? codec.decode(result, pixelBytes) : result;
        } finally { disarm(); }
    }
    private void arm() { timeout=watchdog.schedule(this::abort,130,TimeUnit.SECONDS); }
    private void disarm() { if(timeout!=null) { timeout.cancel(false); timeout=null; } }
    // Closing TLS itself can transmit close_notify, which crashes if Stop is tapped
    // on Android's UI thread. Close its underlying transport to cancel reads/writes.
    void abort() { aborted = true; Socket active = transport; if (active != null) try { active.close(); } catch (IOException ignored) { } }
    @Override public void close() {
        abort(); watchdog.shutdownNow();
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        if (codec != null) { codec.close(); codec = null; }
    }
    private static final class Packet { final int type; final byte[] data; Packet(int type, byte[] data) { this.type = type; this.data = data; } }
}

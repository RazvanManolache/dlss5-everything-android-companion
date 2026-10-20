package io.phoenixlink;

import org.json.JSONObject;
import java.io.IOException;

final class LinkConfig {
    String host = "", fingerprint = "", token = "", codec = "pyrowave";
    String captureMode = "single";
    int port = 47855, bitrateMbps = 100, fps = 15, maxEdge = 4096;
    boolean touchThrough = true;
    float opacity = .8f;
    void validate() throws IOException {
        if (!host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IOException("Enter the PC's private IPv4 address.");
        String[] parts = host.split("\\."); int[] b = new int[4];
        for (int i = 0; i < 4; ++i) { b[i] = Integer.parseInt(parts[i]); if (b[i] > 255) throw new IOException("Invalid IPv4 address."); }
        if (!(b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] >= 16 && b[1] <= 31 || b[0] == 169 && b[1] == 254 || b[0] == 127))
            throw new IOException("Only LAN and loopback addresses are allowed.");
        if (port < 1024 || port > 65535 || bitrateMbps < 20 || bitrateMbps > 500 || fps < 1 || fps > 60 || maxEdge < 256 || maxEdge > 4096)
            throw new IOException("Check the port, bitrate, frame rate and capture size.");
        if (!Float.isFinite(opacity) || opacity < .1f || opacity > 1f) throw new IOException("Invalid overlay opacity.");
        fingerprint = fingerprint.replace(":", "").replace(" ", "").trim(); token = token.trim();
        if (!fingerprint.matches("[0-9a-fA-F]{64}") || !token.matches("[0-9a-fA-F]{64}")) throw new IOException("Paste the full certificate fingerprint and pairing token from the PC.");
        if (!codec.equals("raw") && !codec.equals("pyrowave")) throw new IOException("Unknown codec.");
        if (!captureMode.equals("single") && !captureMode.equals("follow")) throw new IOException("Unknown capture mode.");
    }
    JSONObject json() {
        JSONObject j = new JSONObject();
        try { j.put("host", host).put("port", port).put("fingerprint", fingerprint).put("token", token).put("codec", codec)
            .put("bitrate", bitrateMbps).put("fps", fps).put("maxEdge", maxEdge).put("touchThrough", touchThrough).put("opacity", opacity).put("captureMode",captureMode); }
        catch (Exception e) { throw new IllegalStateException(e); } return j;
    }
    static LinkConfig from(JSONObject j) {
        LinkConfig c = new LinkConfig(); c.host = j.optString("host", ""); c.port = j.optInt("port", 47855);
        c.fingerprint = j.optString("fingerprint", ""); c.token = j.optString("token", ""); c.codec = j.optString("codec", "pyrowave");
        c.bitrateMbps = j.optInt("bitrate", 100); c.fps = j.optInt("fps", 15); c.maxEdge = j.optInt("maxEdge", 4096);
        c.touchThrough = j.optBoolean("touchThrough", true); c.opacity = (float)j.optDouble("opacity", .8); c.captureMode=j.optString("captureMode","single"); return c;
    }
    static final class Tuning {
        final boolean enabled, autoMask; final int iterations; final float tone, structure, skin; final long revision;
        Tuning(boolean enabled, int iterations, float tone, float structure, float skin, boolean mask, long revision) {
            this.enabled = enabled; this.iterations = iterations; this.tone = tone; this.structure = structure; this.skin = skin; autoMask = mask; this.revision = revision;
        }
        static Tuning defaults() { return new Tuning(true, 1, 1, 1, -1, false, 0); }
        JSONObject json() {
            JSONObject j = new JSONObject();
            try { j.put("enabled", enabled).put("iterations", iterations).put("tone", tone).put("structure", structure).put("skin", skin).put("auto_mask", autoMask); }
            catch (Exception e) { throw new IllegalStateException(e); } return j;
        }
    }
}

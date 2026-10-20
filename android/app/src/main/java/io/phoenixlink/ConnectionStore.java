package io.phoenixlink;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ConnectionStore {
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "phoenix-link-connection";
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generator.generateKey();
        }
        return (SecretKey)store.getKey(alias, null);
    }
    static LinkConfig load(Context context) {
        try {
            String text = context.getSharedPreferences("connection", 0).getString("encrypted", "");
            if (text.isEmpty()) return new LinkConfig();
            byte[] data = Base64.decode(text, Base64.NO_WRAP); if (data.length < 29) return new LinkConfig();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, data, 0, 12));
            return LinkConfig.from(new JSONObject(new String(cipher.doFinal(data, 12, data.length-12), java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { return new LinkConfig(); }
    }
    static void save(Context context, LinkConfig config) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(config.json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] out = new byte[12 + encrypted.length]; System.arraycopy(cipher.getIV(), 0, out, 0, 12); System.arraycopy(encrypted, 0, out, 12, encrypted.length);
        // Setup may finish without an Activity lifecycle to flush an asynchronous write.
        if (!context.getSharedPreferences("connection", 0).edit().putString("encrypted", Base64.encodeToString(out, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("Could not save the paired connection. Try again.");
    }
    static void forget(Context context) { context.getSharedPreferences("connection", 0).edit().clear().apply(); }
}

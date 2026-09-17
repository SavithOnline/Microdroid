package com.example.screenshotmover.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API key storage: AES/GCM encrypted values, key held in the AndroidKeyStore.
 * Protects against offline extraction of the prefs file; keystore data is not backed up,
 * so a restored key simply decrypts to "missing".
 */
public final class ApiKeyStore {
    private ApiKeyStore() {}

    private static final String PREFS = "microdroid_llm";
    private static final String KEY_ALIAS = "microdroid_llm_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String PREFIX = "key_";

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Encrypt and store (empty/null removes the key). Returns false if encryption failed. */
    public static boolean put(Context ctx, String providerId, String key) {
        if (key == null || key.trim().isEmpty()) {
            prefs(ctx).edit().remove(PREFIX + providerId).apply();
            return true;
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] ct = c.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
            String stored = Base64.encodeToString(c.getIV(), Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            prefs(ctx).edit().putString(PREFIX + providerId, stored).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean has(Context ctx, String providerId) {
        return prefs(ctx).contains(PREFIX + providerId);
    }

    /** Decrypted key, or null when absent / undecryptable (e.g. after a backup restore). */
    public static String get(Context ctx, String providerId) {
        String stored = prefs(ctx).getString(PREFIX + providerId, null);
        if (stored == null) return null;
        try {
            String[] parts = stored.split(":", 2);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, secretKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}

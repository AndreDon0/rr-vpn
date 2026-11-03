package com.dream.driftpool;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One authenticated, atomic, private blob; no plaintext subscription or backup copy on disk. */
final class SecretStore {
    private final AtomicFile file;
    SecretStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "subscription.enc")); }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias("drift-pool-subscription"))
            return ((KeyStore.SecretKeyEntry) ks.getEntry("drift-pool-subscription", null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("drift-pool-subscription",
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    synchronized void save(String url, String body) throws Exception {
        saveDocument(new JSONObject().put("url", url).put("body", body));
    }

    synchronized void saveBook(SubscriptionBook book) throws Exception { saveDocument(book.toJson()); }
    synchronized SubscriptionBook loadBook() throws Exception { return SubscriptionBook.fromJson(load()); }

    private void saveDocument(JSONObject document) throws Exception {
        byte[] plain = document.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(plain);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(cipher.getIV());
            out.write(encrypted);
            file.finishWrite(out);
        } catch (Exception e) { if (out != null) file.failWrite(out); throw e; }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    synchronized JSONObject load() throws Exception {
        if (!file.getBaseFile().exists()) return null;
        byte[] encrypted = file.readFully();
        if (encrypted.length < 29) throw new IllegalStateException("Invalid saved subscription");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
        byte[] plain = cipher.doFinal(encrypted, 12, encrypted.length - 12);
        try { return new JSONObject(new String(plain, java.nio.charset.StandardCharsets.UTF_8)); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
}

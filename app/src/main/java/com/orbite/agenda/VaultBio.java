package com.orbite.agenda;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONObject;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Ouverture du coffre par empreinte digitale.
 *
 * La clé du coffre n'est jamais enregistrée en clair : elle est chiffrée par une clé
 * du Keystore Android (matériel sécurisé du téléphone).
 *  - mode 2 (empreinte « forte », la plupart des capteurs) : la clé du Keystore ne
 *    fonctionne qu'après une empreinte reconnue (CryptoObject) ;
 *  - mode 1 (biométrie « simple », ex. certains déverrouillages par visage) : la clé
 *    du Keystore n'est utilisée qu'après une biométrie reconnue.
 * Si une nouvelle empreinte est ajoutée au téléphone, la clé est invalidée : il faut
 * rouvrir avec le code puis réactiver l'empreinte.
 */
final class VaultBio {

    interface Reply { void done(boolean ok, String json); }
    private interface Success { void ok(BiometricPrompt.AuthenticationResult res); }

    private static final String TAG = "ORBITE_SELFTEST";
    private static final String ALIAS_STRONG = "orbite_coffre_empreinte";
    private static final String ALIAS_WEAK = "orbite_coffre_simple";
    private static final int STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG;
    private static final int WEAK = BiometricManager.Authenticators.BIOMETRIC_WEAK;
    private static final String AES = "AES/GCM/NoPadding";

    /** Orbite est-il au premier plan ? (mis à jour par MainActivity) */
    static volatile boolean visible = true;

    private VaultBio() { }

    static int level(FragmentActivity a) {
        BiometricManager m = BiometricManager.from(a);
        if (m.canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS) return 2;
        if (m.canAuthenticate(WEAK) == BiometricManager.BIOMETRIC_SUCCESS) return 1;
        return 0;
    }

    /** {"level":0|1|2,"reason":"ok|none_enrolled|no_hardware|unavailable|update"} */
    static String status(FragmentActivity a) {
        try {
            int lv = level(a);
            String reason = "ok";
            if (lv == 0) {
                switch (BiometricManager.from(a).canAuthenticate(WEAK)) {
                    case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED: reason = "none_enrolled"; break;
                    case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE: reason = "no_hardware"; break;
                    case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED: reason = "update"; break;
                    default: reason = "unavailable";
                }
            }
            JSONObject o = new JSONObject();
            o.put("level", lv);
            o.put("reason", reason);
            return o.toString();
        } catch (Exception e) {
            return "{\"level\":0,\"reason\":\"unavailable\"}";
        }
    }

    /** Active l'empreinte : chiffre la clé du coffre (base64) après une empreinte reconnue. */
    static void enable(FragmentActivity a, String rawB64, Reply r) {
        final byte[] raw;
        try {
            raw = Base64.decode(rawB64, Base64.NO_WRAP);
        } catch (Exception e) {
            r.done(false, err("crypto", e.toString()));
            return;
        }
        int lv = level(a);
        if (lv == 0) { r.done(false, err("none", "")); return; }
        if (lv < 2) { enableWeak(a, raw, r); return; }
        final Cipher c;
        try {
            SecretKey key = createKey(ALIAS_STRONG, true);
            c = Cipher.getInstance(AES);
            c.init(Cipher.ENCRYPT_MODE, key);
        } catch (Exception e) {
            // Clé protégée par empreinte impossible à créer sur ce téléphone : mode simple
            Log.w(TAG, "BIO mode simple (clé forte indisponible) " + e);
            deleteKey(ALIAS_STRONG);
            enableWeak(a, raw, r);
            return;
        }
        prompt(a, "Activer l’empreinte", STRONG, new BiometricPrompt.CryptoObject(c), res -> {
            try {
                BiometricPrompt.CryptoObject co = res.getCryptoObject();
                Cipher cc = co != null ? co.getCipher() : null;
                if (cc == null) throw new IllegalStateException("no cipher");
                byte[] ct = cc.doFinal(raw);
                r.done(true, wrapped(2, cc.getIV(), ct));
            } catch (Exception e) {
                // Empreinte reconnue mais clé protégée inutilisable : mode simple, sans redemander le doigt
                Log.w(TAG, "BIO mode simple (chiffrement fort refusé) " + e);
                deleteKey(ALIAS_STRONG);
                try {
                    r.done(true, weakWrap(raw));
                } catch (Exception e2) {
                    r.done(false, err("crypto", e2.toString()));
                }
            }
        }, r);
    }

    private static void enableWeak(FragmentActivity a, byte[] raw, Reply r) {
        final String payload;
        try {
            payload = weakWrap(raw);
        } catch (Exception e) {
            r.done(false, err("crypto", e.toString()));
            return;
        }
        prompt(a, "Activer l’empreinte", WEAK, null, res -> r.done(true, payload), r);
    }

    private static String weakWrap(byte[] raw) throws Exception {
        SecretKey key = createKey(ALIAS_WEAK, false);
        Cipher c = Cipher.getInstance(AES);
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] ct = c.doFinal(raw);
        return wrapped(1, c.getIV(), ct);
    }

    private static String wrapped(int mode, byte[] iv, byte[] ct) throws Exception {
        JSONObject o = new JSONObject();
        o.put("mode", mode);
        o.put("iv", b64(iv));
        o.put("ct", b64(ct));
        return o.toString();
    }

    /** Ouvre le coffre : rend la clé du coffre (base64) après une empreinte reconnue. */
    static void open(FragmentActivity a, int mode, String ivB64, String ctB64, Reply r) {
        try {
            final byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
            final byte[] ct = Base64.decode(ctB64, Base64.NO_WRAP);
            if (mode == 2) {
                SecretKey key = getKey(ALIAS_STRONG);
                if (key == null) { r.done(false, err("missing", "")); return; }
                Cipher c = Cipher.getInstance(AES);
                try {
                    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                } catch (KeyPermanentlyInvalidatedException e) {
                    deleteKey(ALIAS_STRONG);
                    r.done(false, err("invalidated", ""));
                    return;
                }
                prompt(a, "Ouvrir le coffre", STRONG, new BiometricPrompt.CryptoObject(c), res -> {
                    try {
                        BiometricPrompt.CryptoObject co = res.getCryptoObject();
                        Cipher cc = co != null ? co.getCipher() : null;
                        if (cc == null) { r.done(false, err("crypto", "no cipher")); return; }
                        r.done(true, rawJson(cc.doFinal(ct)));
                    } catch (Exception e) {
                        r.done(false, err("crypto", e.toString()));
                    }
                }, r);
            } else {
                if (getKey(ALIAS_WEAK) == null) { r.done(false, err("missing", "")); return; }
                prompt(a, "Ouvrir le coffre", WEAK, null, res -> {
                    try {
                        SecretKey key = getKey(ALIAS_WEAK);
                        if (key == null) { r.done(false, err("missing", "")); return; }
                        Cipher c = Cipher.getInstance(AES);
                        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                        r.done(true, rawJson(c.doFinal(ct)));
                    } catch (Exception e) {
                        r.done(false, err("crypto", e.toString()));
                    }
                }, r);
            }
        } catch (Exception e) {
            r.done(false, err("crypto", e.toString()));
        }
    }

    static void disable() {
        deleteKey(ALIAS_STRONG);
        deleteKey(ALIAS_WEAK);
    }

    private static void prompt(FragmentActivity a, String title, int auth, BiometricPrompt.CryptoObject crypto, Success s, Reply r) {
        try {
            BiometricPrompt p = new BiometricPrompt(a, ContextCompat.getMainExecutor(a), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult res) {
                    Log.i(TAG, "BIO_OK " + title);
                    s.ok(res);
                }

                @Override
                public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                    String c = codeName(code);
                    if (code == BiometricPrompt.ERROR_CANCELED && !visible) c = "background";
                    Log.i(TAG, "BIO_ERR " + code + " " + c);
                    r.done(false, err(c, msg.toString()));
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle("Posez votre doigt sur le capteur")
                    .setNegativeButtonText("Utiliser mon code")
                    .setAllowedAuthenticators(auth)
                    .setConfirmationRequired(false)
                    .build();
            if (crypto != null) p.authenticate(info, crypto);
            else p.authenticate(info);
            Log.i(TAG, "BIO_PROMPT " + title);
        } catch (Exception e) {
            Log.w(TAG, "BIO_ERR lancement " + e);
            r.done(false, err("other", e.toString()));
        }
    }

    private static String codeName(int c) {
        switch (c) {
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
            case BiometricPrompt.ERROR_USER_CANCELED:
            case BiometricPrompt.ERROR_CANCELED:
                return "cancel";
            case BiometricPrompt.ERROR_LOCKOUT:
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                return "lockout";
            case BiometricPrompt.ERROR_NO_BIOMETRICS:
            case BiometricPrompt.ERROR_HW_NOT_PRESENT:
            case BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL:
                return "none";
            case BiometricPrompt.ERROR_TIMEOUT:
                return "timeout";
            default:
                return "other";
        }
    }

    private static SecretKey createKey(String alias, boolean userAuth) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
        if (userAuth) {
            b.setUserAuthenticationRequired(true);
            b.setInvalidatedByBiometricEnrollment(true);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            }
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private static SecretKey getKey(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return (SecretKey) ks.getKey(alias, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteKey(String alias) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(alias)) ks.deleteEntry(alias);
        } catch (Exception ignored) { }
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String rawJson(byte[] raw) throws Exception {
        JSONObject o = new JSONObject();
        o.put("raw", b64(raw));
        return o.toString();
    }

    private static String err(String code, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", code);
            o.put("msg", msg == null ? "" : msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"other\",\"msg\":\"\"}";
        }
    }
}

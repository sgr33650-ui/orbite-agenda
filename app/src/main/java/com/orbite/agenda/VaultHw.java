package com.orbite.agenda;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.security.Key;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/**
 * Clé de la puce de sécurité du téléphone (Android Keystore), mêlée au code du coffre.
 *
 * La clé ne sort jamais de la puce : le coffre ne peut être ouvert qu'avec le code ET ce
 * téléphone. Une copie des fichiers d'Orbite (sauvegarde, extraction) est donc inutilisable
 * ailleurs, et chaque essai de code doit passer par la puce de ce téléphone.
 */
final class VaultHw {

    private static final String TAG = "ORBITE_SELFTEST";
    private static final String ALIAS = "orbite_coffre_appareil";

    private VaultHw() { }

    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            Key k = ks.getKey(ALIAS, null);
            if (k instanceof SecretKey) return (SecretKey) k;
        }
        if (!create) return null;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build());
        return kg.generateKey();
    }

    /** HMAC-SHA256 calculé dans la puce ; "" si la clé est absente (create = false) ou en cas d'erreur. */
    static String mac(String dataB64, boolean create) {
        try {
            SecretKey k = key(create);
            if (k == null) return "";
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(k);
            return Base64.encodeToString(m.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "HW mac " + e);
            return "";
        }
    }

    /** {"ok":true|false,"hardware":true|false} : hardware = clé gardée dans la puce de sécurité. */
    @SuppressWarnings("deprecation")
    static String status() {
        try {
            SecretKey k = key(true);
            boolean hw = false;
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance(k.getAlgorithm(), "AndroidKeyStore");
                KeyInfo info = (KeyInfo) f.getKeySpec(k, KeyInfo.class);
                hw = info.isInsideSecureHardware();
            } catch (Exception ignored) { }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("hardware", hw);
            return o.toString();
        } catch (Exception e) {
            Log.w(TAG, "HW status " + e);
            return "{\"ok\":false,\"hardware\":false}";
        }
    }
}

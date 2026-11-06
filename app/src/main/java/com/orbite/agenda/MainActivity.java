package com.orbite.agenda;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Executor;

/**
 * Orbite — application Android.
 * L'interface est servie depuis les assets via une origine https locale
 * (stockage IndexedDB et chiffrement WebCrypto disponibles).
 * Le pont "OrbiteNative" donne accès au capteur d'empreinte, au partage
 * Android, aux notifications, à l'ouverture des pièces jointes et à la
 * lecture de l'agenda du téléphone pour l'import.
 */
public class MainActivity extends FragmentActivity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";
    private static final String CHANNEL_ID = "rappels";
    private static final int REQ_FILES = 41;
    private static final String TEST_TAG = "ORBITE_SELFTEST";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private ActivityResultLauncher<String> calendarPermission;
    private boolean selftest;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        calendarPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> js("window.__calPerm&&window.__calPerm(" + Boolean.TRUE.equals(granted) + ")"));
        selftest = getIntent() != null && getIntent().getBooleanExtra("orbite_selftest", false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF070A11);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (selftest) view.evaluateJavascript("window.__orbiteSelftest&&window.__orbiteSelftest()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("ORBITE_JS", m.messageLevel() + " " + m.message() + " (ligne " + m.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(pick, "Joindre un fichier"), REQ_FILES);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "OrbiteNative");

        createChannel();
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        if (selftest) runNativeSelftest();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILES || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new Uri[n];
                for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", value -> {
            if (value == null || !value.contains("1")) MainActivity.super.onBackPressed();
        });
    }

    /** Mode test (lancé uniquement par le test automatique) : vérifie la lecture de l'agenda. */
    private void runNativeSelftest() {
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String r = PhoneCalendar.read(getContentResolver(), now - 400L * 86400000L, now + 800L * 86400000L);
                JSONObject o = new JSONObject(r);
                if (o.optBoolean("ok")) {
                    Log.i(TEST_TAG, "NATIVE ok=true calendars=" + o.getJSONArray("calendars").length()
                            + " events=" + o.getJSONArray("events").length());
                } else {
                    Log.e(TEST_TAG, "NATIVE ok=false error=" + o.optString("error"));
                }
            } catch (Exception e) {
                Log.e(TEST_TAG, "NATIVE FAIL " + e);
            }
        }).start();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Rappels d'événements", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Rappels avant vos rendez-vous");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private static String quote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    /** Pont JavaScript ↔ Android */
    private class Bridge {

        @JavascriptInterface
        public boolean hasBiometric() {
            int r = BiometricManager.from(MainActivity.this)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return r == BiometricManager.BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public void biometric(final String title) {
            runOnUiThread(() -> {
                Executor ex = ContextCompat.getMainExecutor(MainActivity.this);
                BiometricPrompt prompt = new BiometricPrompt(MainActivity.this, ex, new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        js("window.__bioDone&&window.__bioDone(true,'')");
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        js("window.__bioDone&&window.__bioDone(false," + quote(msg.toString()) + ")");
                    }
                });
                BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title == null || title.isEmpty() ? "Coffre Orbite" : title)
                        .setSubtitle("Posez votre doigt sur le capteur")
                        .setNegativeButtonText("Utiliser mon code")
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build();
                prompt.authenticate(info);
            });
        }

        @JavascriptInterface
        public void share(String title, String text) {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, title);
            send.putExtra(Intent.EXTRA_TEXT, text);
            Intent chooser = Intent.createChooser(send, "Partager l'événement");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        }

        @JavascriptInterface
        public void showNotification(String title, String body) {
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Intent open = new Intent(MainActivity.this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(MainActivity.this, 0, open,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Builder b = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            try {
                NotificationManagerCompat.from(MainActivity.this).notify((int) (System.currentTimeMillis() % 100000), b.build());
            } catch (SecurityException ignored) { }
        }

        @JavascriptInterface
        public boolean notificationsAllowed() {
            return NotificationManagerCompat.from(MainActivity.this).areNotificationsEnabled();
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            Intent i = new Intent();
            if (Build.VERSION.SDK_INT >= 26) {
                i.setAction(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                i.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        @JavascriptInterface
        public boolean hasCalendarPermission() {
            return ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestCalendarPermission() {
            runOnUiThread(() -> calendarPermission.launch(Manifest.permission.READ_CALENDAR));
        }

        @JavascriptInterface
        public String readPhoneCalendar(double fromMs, double toMs) {
            if (!hasCalendarPermission()) return "{\"ok\":false,\"error\":\"permission\"}";
            return PhoneCalendar.read(getContentResolver(), (long) fromMs, (long) toMs);
        }

        @JavascriptInterface
        public void openAppSettings() {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (ActivityNotFoundException ignored) { }
        }

        @JavascriptInterface
        public void selftestResult(String result) {
            Log.i(TEST_TAG, "JS " + result);
        }

        @JavascriptInterface
        public void openFile(String dataUrl, String name, String type) {
            try {
                int comma = dataUrl.indexOf(',');
                byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
                File dir = new File(getCacheDir(), "partage");
                if (!dir.exists()) dir.mkdirs();
                String safe = name == null ? "fichier" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
                File f = new File(dir, safe);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, "com.orbite.agenda.files", f);
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, type == null || type.isEmpty() ? "*/*" : type);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(view, "Ouvrir avec").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Aucune application ne peut ouvrir ce fichier", Toast.LENGTH_LONG).show());
            }
        }
    }
}

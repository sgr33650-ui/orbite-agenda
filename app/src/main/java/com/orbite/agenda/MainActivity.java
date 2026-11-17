package com.orbite.agenda;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
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
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private boolean biotest;
    private boolean appstest;
    private volatile boolean secureNow;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String clipSecret;
    private long clipUntil;
    private ContentObserver calendarObserver;
    private volatile boolean foreground;
    private String pendingIcs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        calendarPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (Boolean.TRUE.equals(granted)) watchCalendar();
                    js("window.__calPerm&&window.__calPerm(" + Boolean.TRUE.equals(granted) + ")");
                });
        selftest = getIntent() != null && getIntent().getBooleanExtra("orbite_selftest", false);
        biotest = getIntent() != null && getIntent().getBooleanExtra("orbite_biotest", false);
        appstest = getIntent() != null && getIntent().getBooleanExtra("orbite_appstest", false);

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
                if (selftest) view.evaluateJavascript("window.__selftestMode=true;window.__orbiteSelftest&&window.__orbiteSelftest()", null);
                else if (biotest) view.evaluateJavascript("window.__selftestMode=true;window.__orbiteBioTest&&window.__orbiteBioTest()", null);
                else if (appstest) view.evaluateJavascript("window.__selftestMode=true;window.__orbiteAppsTest&&window.__orbiteAppsTest()", null);
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
        else {
            clearSharedFiles();
            web.loadUrl(START_URL);
        }

        watchCalendar();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        VaultBio.visible = true;
        watchCalendar();
        js("window.__onResume&&window.__onResume()");
    }

    @Override
    protected void onPause() {
        foreground = false;
        VaultBio.visible = false;
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && clipSecret != null && System.currentTimeMillis() >= clipUntil) clearSecretClip();
    }

    /** Efface du presse-papiers un mot de passe copié depuis le coffre, s'il y est encore. */
    private void clearSecretClip() {
        final String secret = clipSecret;
        if (secret == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) { clipSecret = null; return; }
            ClipData cur = cm.getPrimaryClip();
            if (cur == null) return; // lecture impossible hors de l'écran : on réessaiera au retour dans Orbite
            clipSecret = null;
            if (cur.getItemCount() > 0) {
                CharSequence t = cur.getItemAt(0).coerceToText(this);
                if (t != null && secret.contentEquals(t)) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
                    else cm.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            }
        } catch (Exception ignored) { }
    }

    /** Efface les copies temporaires des fichiers ouverts dans une autre application. */
    private void clearSharedFiles() {
        try {
            File[] fs = new File(getCacheDir(), "partage").listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Exception ignored) { }
    }

    /** Réponse de l'empreinte vers l'interface (le numéro de demande évite de confondre deux invites). */
    private void bioReply(int req, boolean ok, String payload) {
        js("window.__bioReply&&window.__bioReply(" + req + "," + ok + "," + JSONObject.quote(payload == null ? "{}" : payload) + ")");
    }

    @Override
    protected void onDestroy() {
        if (calendarObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(calendarObserver);
            } catch (Exception ignored) { }
            calendarObserver = null;
        }
        super.onDestroy();
    }

    /** Prévient l'interface dès que l'agenda du téléphone change (invitation reçue, modification…). */
    private void watchCalendar() {
        if (calendarObserver != null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return;
        ContentObserver o = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                js("window.__onPhoneCalendarChange&&window.__onPhoneCalendarChange()");
            }
        };
        try {
            getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true, o);
            calendarObserver = o;
        } catch (Exception ignored) { }
    }

    /** Invitation (.ics) ouverte depuis une boîte mail ou partagée vers Orbite. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        final String action = i.getAction();
        final Uri data = i.getData();
        final boolean view = Intent.ACTION_VIEW.equals(action) && data != null;
        final boolean send = Intent.ACTION_SEND.equals(action);
        if (!view && !send) return;
        final Uri stream = send ? IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri.class) : null;
        final CharSequence text = send ? i.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        if (!view && stream == null && text == null) return;
        new Thread(() -> {
            String content;
            try {
                if (view) content = readUri(data);
                else if (stream != null) content = readUri(stream);
                else content = text.toString();
            } catch (Exception e) {
                content = "ORBITE_ERREUR:" + e.getClass().getSimpleName();
            }
            if (content == null) return;
            synchronized (MainActivity.this) { pendingIcs = content; }
            js("window.__onIcs&&window.__onIcs()");
        }).start();
    }

    private String readUri(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 5_000_000) break;
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
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

    /** Pont JavaScript ↔ Android */
    private class Bridge {

        /** {"level":0|1|2,"reason":"..."} : 2 = empreinte protégée par le téléphone, 0 = indisponible */
        @JavascriptInterface
        public String bioStatus() {
            return VaultBio.status(MainActivity.this);
        }

        @JavascriptInterface
        public boolean hasBiometric() {
            return VaultBio.level(MainActivity.this) > 0;
        }

        @JavascriptInterface
        public void bioEnable(final String rawB64, final int req) {
            runOnUiThread(() -> {
                if (!foreground) { bioReply(req, false, "{\"error\":\"background\",\"msg\":\"\"}"); return; }
                VaultBio.enable(MainActivity.this, rawB64, (ok, json) -> bioReply(req, ok, json));
            });
        }

        @JavascriptInterface
        public void bioOpen(final int mode, final String iv, final String ct, final int req) {
            runOnUiThread(() -> {
                if (!foreground) { bioReply(req, false, "{\"error\":\"background\",\"msg\":\"\"}"); return; }
                VaultBio.open(MainActivity.this, mode, iv, ct, (ok, json) -> bioReply(req, ok, json));
            });
        }

        @JavascriptInterface
        public void bioDisable() {
            VaultBio.disable();
        }

        /** Applications du téléphone qu'on peut ouvrir : [{"pkg":"…","label":"…"}] (Orbite exclue). */
        @JavascriptInterface
        public String listApps() {
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
                Set<String> seen = new HashSet<>();
                JSONArray arr = new JSONArray();
                for (ResolveInfo ri : found) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    CharSequence label = ri.loadLabel(pm);
                    o.put("label", label == null ? pkg : label.toString());
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                Log.w(TEST_TAG, "listApps " + e);
                return "[]";
            }
        }

        /** Icône d'une application, en image PNG (data URL) de 96 px. */
        @JavascriptInterface
        public String appIcon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                int size = 96;
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(c);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                bmp.recycle();
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        /** Ouvre une application du téléphone ; false si elle n'est plus installée. */
        @JavascriptInterface
        public boolean launchApp(String pkg) {
            try {
                final Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> {
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Impossible d’ouvrir cette application", Toast.LENGTH_LONG).show();
                    }
                });
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openPhoneSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            });
        }

        /** Clé de la puce de sécurité mêlée au code du coffre (voir VaultHw). */
        @JavascriptInterface
        public String hwMac(String dataB64, boolean create) {
            return VaultHw.mac(dataB64, create);
        }

        @JavascriptInterface
        public String hwStatus() {
            return VaultHw.status();
        }

        /** Coffre ouvert : ni capture d'écran, ni enregistrement, aperçu masqué dans les applications récentes. */
        @JavascriptInterface
        public void secureScreen(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                secureNow = on;
            });
        }

        @JavascriptInterface
        public boolean isSecureScreen() {
            return secureNow;
        }

        /** Enregistre la sauvegarde chiffrée du coffre dans Téléchargements ("ok"), ou la propose au partage ("shared"). */
        @JavascriptInterface
        public String saveDownload(String name, String mime, String text) {
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "error";
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os == null) return "error";
                        os.write(data);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(uri, done, null, null);
                    Log.i(TEST_TAG, "SAVE_OK " + name + " " + data.length);
                    return "ok";
                }
                // Android 7 à 9 : on propose d'enregistrer le fichier où l'on veut (Drive, e-mail…)
                File dir = new File(getCacheDir(), "partage");
                if (!dir.exists() && !dir.mkdirs()) return "error";
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, "com.orbite.agenda.files", f);
                runOnUiThread(() -> {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(send, "Enregistrer la sauvegarde du coffre"));
                    } catch (Exception ignored) { }
                });
                return "shared";
            } catch (Exception e) {
                Log.w(TEST_TAG, "saveDownload " + e);
                return "error";
            }
        }

        /** Coffre verrouillé : les copies de fichiers ouvertes dans une autre application sont effacées. */
        @JavascriptInterface
        public void clearShared() {
            clearSharedFiles();
        }

        /** Copie une clé ou un mot de passe du coffre (marqué « sensible », effacé au bout d'une minute). */
        @JavascriptInterface
        public void copySecret(final String text) {
            runOnUiThread(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm == null) return;
                    ClipData clip = ClipData.newPlainText("Orbite", text);
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                    cm.setPrimaryClip(clip);
                    clipSecret = text;
                    clipUntil = System.currentTimeMillis() + 60000;
                    ui.postDelayed(MainActivity.this::clearSecretClip, 60500);
                } catch (Exception ignored) { }
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
        public boolean isForeground() {
            return foreground;
        }

        @JavascriptInterface
        public String takeIcs() {
            synchronized (MainActivity.this) {
                String t = pendingIcs;
                pendingIcs = null;
                return t == null ? "" : t;
            }
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

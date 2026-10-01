package kz.kareta.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebStorage;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.Locale;

public final class MainActivity extends ComponentActivity {
    private static final String BASE_URL = BuildConfig.WEB_ORIGIN;
    private static final String BASE_HOST = Uri.parse(BASE_URL).getHost();
    private static final int NATIVE_API_VERSION = 6;
    private static final int REQ_PERMISSION = 4101;
    private static final int REQ_IMAGE_PICK = 4102;
    private static final int REQ_CAMERA = 4103;
    private static final int REQ_CONTACT = 4104;
    private static final int REQ_WEB_FILE_CHOOSER = 4105;
    private static final int REQ_WEB_PERMISSION = 4106;
    private static final int REQ_WEB_GEOLOCATION = 4107;
    private static final long PAGE_TIMEOUT_MS = 30000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private FrameLayout root;
    private Elm327Manager elm;
    private OfflineQueue offlineQueue;
    private boolean pageLoaded;

    private PendingPermission pendingPermission;
    private JavaScriptReplyProxy pendingNativeReply;
    private String pendingNativeId = "";
    private Uri pendingCameraUri;
    private Uri pendingWebCameraUri;
    private ValueCallback<Uri[]> pendingWebFileCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    private final Runnable pageTimeout = () -> {
        if (!pageLoaded && webView != null) {
            showLoadError("Сервер s.kareta.kz не ответил за 30 секунд.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 18, 22));
        setContentView(root);

        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), root);
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime()
            );
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        elm = new Elm327Manager(this);
        offlineQueue = new OfflineQueue(this);

        configureWebView();
        installNativeBridge();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(BASE_URL);
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // SPA requires JS; Native API is restricted to the trusted HTTPS origin.
    private void configureWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(16, 18, 22));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setGeolocationEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " KARETA-Android/1.4.3");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebChromeClient(new KaretaChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrustedUri(uri)) return false;
                openExternalUri(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url,
                                      android.graphics.Bitmap favicon) {
                pageLoaded = false;
                handler.removeCallbacks(pageTimeout);
                handler.postDelayed(pageTimeout, PAGE_TIMEOUT_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                handler.removeCallbacks(pageTimeout);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    CharSequence description = error == null ? "" : error.getDescription();
                    showLoadError("Не удалось загрузить s.kareta.kz: " + description);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request.isForMainFrame()
                        && errorResponse != null
                        && errorResponse.getStatusCode() >= 500) {
                    showLoadError("s.kareta.kz вернул HTTP " + errorResponse.getStatusCode() + ".");
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                handler.removeCallbacks(pageTimeout);
                if (root != null) root.removeView(view);
                try { view.destroy(); } catch (Throwable ignored) {}
                if (webView == view) webView = null;
                recreate();
                return true;
            }
        });
    }

    @SuppressLint("RequiresFeature") // Guarded by WebViewFeature.isFeatureSupported below.
    private void installNativeBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showLoadError("Системный Android WebView слишком старый для Native API 6.");
            return;
        }

        WebViewCompat.addWebMessageListener(
                webView,
                "KaretaNative",
                Collections.singleton("https://" + BASE_HOST),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || !isTrustedUri(sourceOrigin)) {
                        replyError(replyProxy, "", "ORIGIN_DENIED",
                                "Native API доступен только s.kareta.kz.");
                        return;
                    }
                    handleNativeMessage(message, replyProxy);
                }
        );
    }

    private void handleNativeMessage(WebMessageCompat message,
                                     JavaScriptReplyProxy reply) {
        String raw = message == null ? null : message.getData();
        String id = "";
        try {
            JSONObject request = new JSONObject(raw == null ? "{}" : raw);
            id = request.optString("id", "");
            String command = request.optString("command", "");
            JSONObject payload = request.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            dispatch(command, payload, id, reply);
        } catch (Throwable error) {
            replyError(reply, id, "BAD_REQUEST", message(error));
        }
    }

    private void dispatch(String command, JSONObject payload, String id,
                          JavaScriptReplyProxy reply) throws Exception {
        switch (command) {
            case "ping":
                replyOk(reply, id, json("pong", true, "nativeApiVersion", NATIVE_API_VERSION));
                return;
            case "appInfo":
                replyOk(reply, id, appInfo());
                return;
            case "network":
                replyOk(reply, id, networkInfo());
                return;
            case "requestPermission":
                requestPermission(payload.optString("permission", ""), id, reply);
                return;

            case "pushToken":
                replyOk(reply, id, json("token", "", "configured", false));
                return;
            case "registerPush":
            case "unregisterPush":
                replyOk(reply, id, json("queued", false, "configured", false));
                return;
            case "logout":
                nativeLogout(reply, id);
                return;
            case "pickImage":
                pickImage(reply, id);
                return;
            case "takePhoto":
                takePhoto(reply, id);
                return;
            case "pickContact":
                pickContact(reply, id);
                return;
            case "getLocation":
                getLocation(reply, id);
                return;
            case "scanCode":
                scanCode(payload.optString("mode", "qr"), reply, id);
                return;
            case "actionSheet":
                actionSheet(payload, reply, id);
                return;

            case "elmStatus":
                replyOk(reply, id, elm.status());
                return;
            case "elmDevices":
                replyOk(reply, id, elm.devices());
                return;
            case "elmConnect":
                elm.connect(payload.optString("address", ""), bridgeCallback(reply, id));
                return;
            case "elmReconnectLast":
                elm.reconnectLast(bridgeCallback(reply, id));
                return;
            case "elmDisconnect":
                replyOk(reply, id, elm.disconnect());
                return;
            case "elmInit":
                elm.initialize(bridgeCallback(reply, id));
                return;
            case "elmCommand":
                elm.command(payload.optString("command", ""),
                        payload.optInt("timeoutMs", 2500),
                        bridgeCallback(reply, id));
                return;
            case "elmSnapshot":
                elm.snapshot(bridgeCallback(reply, id));
                return;
            case "elmLiveSnapshot":
                elm.liveSnapshot(bridgeCallback(reply, id));
                return;

            case "offlineState":
                replyOk(reply, id, offlineQueue.state());
                return;
            case "offlineEnqueue":
                replyOk(reply, id, offlineQueue.enqueue(
                        payload.optJSONObject("payload")));
                return;
            case "offlineDrain":
                replyOk(reply, id, offlineQueue.peek());
                return;
            case "offlineAcknowledge":
                replyOk(reply, id, offlineQueue.acknowledge(
                        payload.optJSONArray("items")));
                return;
            case "offlineRestore":
                replyOk(reply, id, offlineQueue.restore(
                        payload.optJSONArray("items")));
                return;
            case "offlineClear":
                replyOk(reply, id, offlineQueue.clear());
                return;

            case "openBluetoothSettings":
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                replyOk(reply, id, json("opened", true));
                return;
            case "openSettings":
                startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                replyOk(reply, id, json("opened", true));
                return;
            case "copy":
                copyText(payload.optString("text", ""));
                replyOk(reply, id, json("copied", true));
                return;
            case "share":
                shareText(payload.optString("text", ""));
                replyOk(reply, id, json("opened", true));
                return;
            case "openPhone":
                openExternalUri(Uri.parse("tel:" + payload.optString("phone", "")));
                replyOk(reply, id, json("opened", true));
                return;
            case "openMap":
                openMap(payload);
                replyOk(reply, id, json("opened", true));
                return;
            case "openExternal":
                openExternalUrl(payload.optString("url", ""));
                replyOk(reply, id, json("opened", true));
                return;
            case "vibrate":
                vibrate(payload.optInt("ms", 40));
                replyOk(reply, id, json("ok", true));
                return;
            case "reload":
                runOnUiThread(() -> webView.reload());
                replyOk(reply, id, json("ok", true));
                return;
            case "openRoute":
                openRoute(payload.optString("path", ""));
                replyOk(reply, id, json("ok", true));
                return;
            default:
                replyError(reply, id, "UNKNOWN_COMMAND",
                        "Команда Native API 6 не поддерживается: " + command);
        }
    }

    private void requestPermission(String permission, String id,
                                   JavaScriptReplyProxy reply) {
        String alias = permission == null ? "" : permission.toLowerCase(Locale.ROOT);
        String[] permissions = permissionsFor(alias);
        if (permissions == null) {
            replyError(reply, id, "UNSUPPORTED_PERMISSION", "Неизвестное разрешение: " + alias);
            return;
        }
        boolean granted = true;
        for (String item : permissions) {
            if (checkSelfPermission(item) != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (permissions.length == 0 || granted) {
            replyOk(reply, id, json("permission", alias, "granted", true, "prompted", false));
            return;
        }
        if (pendingPermission != null) {
            replyError(reply, id, "PERMISSION_BUSY", "Системный запрос разрешения уже открыт.");
            return;
        }
        pendingPermission = new PendingPermission(id, alias, reply);
        requestPermissions(permissions, REQ_PERMISSION);
    }

    private String[] permissionsFor(String alias) {
        switch (alias) {
            case "bluetooth":
                if (Build.VERSION.SDK_INT < 31) return new String[0];
                return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
            case "camera":
                return new String[]{Manifest.permission.CAMERA};
            case "microphone":
                return new String[]{Manifest.permission.RECORD_AUDIO};
            case "location":
                return new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                };
            case "contacts":
                // Contact selection uses ACTION_PICK and does not require broad READ_CONTACTS access.
                return new String[0];
            case "notifications":
                // Push is not configured in Native API 6 yet; do not request a permission we do not use.
                return new String[0];
            case "images":
                return new String[0];
            default:
                return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_WEB_PERMISSION) {
            PermissionRequest request = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (request != null) grantTrustedWebResources(request);
            return;
        }

        if (requestCode == REQ_WEB_GEOLOCATION) {
            GeolocationPermissions.Callback callback = pendingGeoCallback;
            String origin = pendingGeoOrigin;
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
            if (callback != null && origin != null) {
                boolean granted =
                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, granted, false);
            }
            return;
        }

        if (requestCode != REQ_PERMISSION) return;
        PendingPermission pending = pendingPermission;
        pendingPermission = null;
        if (pending == null) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        replyOk(pending.reply, pending.id,
                json("permission", pending.alias, "granted", granted, "prompted", true));
    }

    private void nativeLogout(JavaScriptReplyProxy reply, String id) {
        replyOk(reply, id, json("queued", true));
        CookieManager.getInstance().removeAllCookies(value -> {
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            webView.clearHistory();
            webView.clearCache(false);
            webView.loadUrl(BASE_URL);
        });
    }

    private boolean nativeBusy(JavaScriptReplyProxy reply, String id) {
        if (pendingNativeReply == null) return false;
        replyError(reply, id, "NATIVE_ACTION_BUSY", "Другое системное действие ещё не завершено.");
        return true;
    }

    private void pickImage(JavaScriptReplyProxy reply, String id) {
        if (nativeBusy(reply, id)) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        pendingNativeReply = reply;
        pendingNativeId = id;
        try {
            startActivityForResult(intent, REQ_IMAGE_PICK);
        } catch (ActivityNotFoundException error) {
            clearNativePending();
            replyError(reply, id, "IMAGE_PICKER_UNAVAILABLE", message(error));
        }
    }

    private void takePhoto(JavaScriptReplyProxy reply, String id) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            replyError(reply, id, "CAMERA_PERMISSION_REQUIRED", "Сначала разрешите доступ к камере.");
            return;
        }
        if (nativeBusy(reply, id)) return;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("CAMERA_CACHE_UNAVAILABLE");
            File photo = File.createTempFile("kareta_", ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", photo);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            pendingCameraUri = uri;
            pendingNativeReply = reply;
            pendingNativeId = id;
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Throwable error) {
            clearNativePending();
            replyError(reply, id, "CAMERA_UNAVAILABLE", message(error));
        }
    }

    private void pickContact(JavaScriptReplyProxy reply, String id) {
        if (nativeBusy(reply, id)) return;
        Intent intent = new Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        pendingNativeReply = reply;
        pendingNativeId = id;
        try {
            startActivityForResult(intent, REQ_CONTACT);
        } catch (ActivityNotFoundException error) {
            clearNativePending();
            replyError(reply, id, "CONTACT_PICKER_UNAVAILABLE", message(error));
        }
    }

    private void getLocation(JavaScriptReplyProxy reply, String id) {
        boolean granted =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            replyError(reply, id, "LOCATION_PERMISSION_REQUIRED", "Сначала разрешите геолокацию.");
            return;
        }
        LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            replyError(reply, id, "LOCATION_UNAVAILABLE", "LocationManager недоступен.");
            return;
        }
        try {
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 120000L) {
                replyLocation(reply, id, best);
                return;
            }
            requestSingleLocation(manager, reply, id);
        } catch (Throwable error) {
            replyError(reply, id, "LOCATION_UNAVAILABLE", message(error));
        }
    }

    private void requestSingleLocation(LocationManager manager,
                                       JavaScriptReplyProxy reply, String id) {
        boolean granted =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            replyError(reply, id, "LOCATION_PERMISSION_REQUIRED", "Сначала разрешите геолокацию.");
            return;
        }

        final boolean[] completed = {false};
        final LocationListener[] holder = new LocationListener[1];
        holder[0] = location -> {
            if (completed[0]) return;
            completed[0] = true;
            try { manager.removeUpdates(holder[0]); } catch (Throwable ignored) {}
            replyLocation(reply, id, location);
        };
        try {
            String provider = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            manager.requestSingleUpdate(provider, holder[0], Looper.getMainLooper());
            handler.postDelayed(() -> {
                if (completed[0]) return;
                completed[0] = true;
                try { manager.removeUpdates(holder[0]); } catch (Throwable ignored) {}
                replyError(reply, id, "LOCATION_TIMEOUT", "Не удалось получить координаты.");
            }, 12000L);
        } catch (Throwable error) {
            replyError(reply, id, "LOCATION_UNAVAILABLE", message(error));
        }
    }

    private void replyLocation(JavaScriptReplyProxy reply, String id, Location location) {
        JSONObject out = new JSONObject();
        try {
            out.put("lat", location.getLatitude());
            out.put("lng", location.getLongitude());
            out.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            out.put("time", location.getTime());
        } catch (JSONException ignored) {}
        replyOk(reply, id, out);
    }

    private void scanCode(String mode, JavaScriptReplyProxy reply, String id) {
        try {
            GmsBarcodeScannerOptions.Builder builder =
                    new GmsBarcodeScannerOptions.Builder().enableAutoZoom();
            if ("vin".equalsIgnoreCase(mode)) {
                builder.setBarcodeFormats(
                        Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_QR_CODE
                );
            } else {
                builder.setBarcodeFormats(
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_DATA_MATRIX,
                        Barcode.FORMAT_AZTEC,
                        Barcode.FORMAT_PDF417
                );
            }
            GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, builder.build());
            scanner.startScan()
                    .addOnSuccessListener(barcode -> replyOk(reply, id,
                            json("rawValue", barcode.getRawValue(),
                                    "displayValue", barcode.getDisplayValue(),
                                    "format", barcode.getFormat(),
                                    "valueType", barcode.getValueType(),
                                    "mode", mode)))
                    .addOnCanceledListener(() ->
                            replyError(reply, id, "CANCELLED", "Сканирование отменено."))
                    .addOnFailureListener(error ->
                            replyError(reply, id, "SCAN_FAILED", message(error)));
        } catch (Throwable error) {
            replyError(reply, id, "SCANNER_UNAVAILABLE", message(error));
        }
    }

    private void actionSheet(JSONObject payload, JavaScriptReplyProxy reply, String id) {
        JSONArray actions = payload.optJSONArray("actions");
        if (actions == null || actions.length() == 0 || actions.length() > 10) {
            replyError(reply, id, "INVALID_ACTIONS", "Некорректный список действий.");
            return;
        }
        CharSequence[] labels = new CharSequence[actions.length()];
        String[] ids = new String[actions.length()];
        for (int i = 0; i < actions.length(); i++) {
            JSONObject item = actions.optJSONObject(i);
            if (item == null) {
                replyError(reply, id, "INVALID_ACTIONS", "Некорректный элемент.");
                return;
            }
            labels[i] = item.optString("title", "Действие " + (i + 1));
            ids[i] = item.optString("id", String.valueOf(i));
        }
        final boolean[] answered = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(payload.optString("title", "Действия"))
                .setItems(labels, (d, which) -> {
                    answered[0] = true;
                    replyOk(reply, id, json("action", ids[which], "index", which));
                })
                .create();
        dialog.setOnCancelListener(d -> {
            if (!answered[0]) {
                answered[0] = true;
                replyError(reply, id, "CANCELLED", "Действие отменено.");
            }
        });
        dialog.show();
    }

    private void clearNativePending() {
        pendingNativeReply = null;
        pendingNativeId = "";
        pendingCameraUri = null;
    }

    private Elm327Manager.Callback bridgeCallback(JavaScriptReplyProxy reply,
                                                  String id) {
        return new Elm327Manager.Callback() {
            @Override
            public void ok(JSONObject data) {
                replyOk(reply, id, data);
            }

            @Override
            public void error(String code, String errorMessage) {
                replyError(reply, id, code, errorMessage);
            }
        };
    }

    private JSONObject appInfo() {
        JSONObject out = new JSONObject();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            out.put("platform", "android");
            out.put("versionName", info.versionName == null ? "" : info.versionName);
            out.put("versionCode", Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode);
            out.put("nativeApiVersion", NATIVE_API_VERSION);
            out.put("baseUrl", BASE_URL);
            out.put("androidSdk", Build.VERSION.SDK_INT);
            out.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            out.put("elmTransport", "classic_spp");
            out.put("nativeCapabilities", new JSONArray()
                    .put("elm327").put("camera").put("images")
                    .put("contacts").put("location").put("scanner")
                    .put("actionSheet").put("offlineQueue")
                    .put("webFileChooser").put("webCamera")
                    .put("webMicrophone").put("webGeolocation"));
        } catch (Exception ignored) {}
        return out;
    }

    private JSONObject networkInfo() {
        JSONObject out = new JSONObject();
        boolean online = false;
        String transport = "none";
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null
                    ? null : manager.getNetworkCapabilities(network);
            if (caps != null) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "wifi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "cellular";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ethernet";
                else transport = "other";
            }
            out.put("online", online);
            out.put("transport", transport);
        } catch (Exception ignored) {}
        return out;
    }

    private void openRoute(String path) {
        String value = path == null ? "" : path.trim();
        String url;
        if (value.startsWith("https://" + BASE_HOST)) {
            url = value;
        } else if (value.startsWith("#")) {
            url = BASE_URL + value;
        } else {
            value = value.replaceFirst("^/+", "");
            url = BASE_URL + "#/" + value;
        }
        runOnUiThread(() -> webView.loadUrl(url));
    }

    private void openExternalUrl(String raw) {
        Uri uri = Uri.parse(raw == null ? "" : raw.trim());
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Разрешены только http/https URL.");
        }
        openExternalUri(uri);
    }

    private void openExternalUri(Uri uri) {
        if (uri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {}
    }

    private void openMap(JSONObject payload) {
        String query = payload.optString("query", "");
        double lat = payload.optDouble("lat", Double.NaN);
        double lng = payload.optDouble("lng", Double.NaN);
        Uri uri;
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            uri = Uri.parse(String.format(Locale.US,
                    "geo:%f,%f?q=%f,%f(%s)", lat, lng, lat, lng,
                    Uri.encode(query)));
        } else {
            uri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
        }
        openExternalUri(uri);
    }

    private void copyText(String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("KARETA.KZ", text));
    }

    private void shareText(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "Поделиться"));
    }

    private void vibrate(int durationMs) {
        int duration = Math.max(10, Math.min(500, durationMs));
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(duration);
        }
    }

    private boolean isTrustedUri(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && BASE_HOST.equalsIgnoreCase(uri.getHost());
    }

    private void showLoadError(String message) {
        handler.removeCallbacks(pageTimeout);
        pageLoaded = true;
        if (webView == null) return;
        String safe = escapeHtml(message);
        String html = "<!doctype html><html><head><meta name='viewport' "
                + "content='width=device-width,initial-scale=1'><style>"
                + "body{margin:0;background:#101216;color:#fff;font-family:sans-serif;"
                + "display:grid;place-items:center;min-height:100vh;padding:24px;box-sizing:border-box}"
                + "main{max-width:520px;text-align:center}h1{font-size:24px}p{opacity:.72;line-height:1.5}"
                + "button{border:0;border-radius:14px;background:#ff6a00;color:#fff;"
                + "padding:14px 20px;font-size:16px;font-weight:700}</style></head><body><main>"
                + "<h1>KARETA.KZ</h1><p>" + safe + "</p>"
                + "<button onclick=\"location.href='" + BASE_URL + "'\">Повторить</button>"
                + "</main></body></html>";
        runOnUiThread(() -> {
            webView.stopLoading();
            webView.loadDataWithBaseURL(BASE_URL, html,
                    "text/html", "UTF-8", null);
        });
    }

    private void replyOk(JavaScriptReplyProxy reply, String id, JSONObject data) {
        JSONObject response = new JSONObject();
        try {
            response.put("id", id == null ? "" : id);
            response.put("ok", true);
            response.put("data", data == null ? new JSONObject() : data);
        } catch (JSONException ignored) {}
        postReply(reply, response.toString());
    }

    private void replyError(JavaScriptReplyProxy reply, String id,
                            String code, String errorMessage) {
        JSONObject response = new JSONObject();
        try {
            response.put("id", id == null ? "" : id);
            response.put("ok", false);
            response.put("error", code == null ? "NATIVE_ERROR" : code);
            response.put("message", errorMessage == null ? "" : errorMessage);
        } catch (JSONException ignored) {}
        postReply(reply, response.toString());
    }

    @SuppressLint("RequiresFeature") // Reply proxies only exist after the guarded WEB_MESSAGE_LISTENER registration.
    private void postReply(JavaScriptReplyProxy reply, String text) {
        if (reply == null) return;
        runOnUiThread(() -> {
            try { reply.postMessage(text); } catch (Throwable ignored) {}
        });
    }

    private JSONObject json(Object... pairs) {
        JSONObject out = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            try { out.put(String.valueOf(pairs[i]), pairs[i + 1]); }
            catch (JSONException ignored) {}
        }
        return out;
    }

    private String escapeHtml(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String message(Throwable error) {
        if (error == null) return "Unknown error";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_WEB_FILE_CHOOSER && pendingWebFileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if ((result == null || result.length == 0)
                    && resultCode == RESULT_OK
                    && pendingWebCameraUri != null) {
                result = new Uri[]{pendingWebCameraUri};
            }
            pendingWebFileCallback.onReceiveValue(result);
            pendingWebFileCallback = null;
            pendingWebCameraUri = null;
            return;
        }

        JavaScriptReplyProxy reply = pendingNativeReply;
        String id = pendingNativeId;

        if (requestCode == REQ_IMAGE_PICK && reply != null) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                replyOk(reply, id, json("uri", data.getData().toString(), "source", "picker"));
            } else {
                replyError(reply, id, "CANCELLED", "Выбор изображения отменён.");
            }
            clearNativePending();
            return;
        }

        if (requestCode == REQ_CAMERA && reply != null) {
            Uri photo = pendingCameraUri;
            if (resultCode == RESULT_OK && photo != null) {
                replyOk(reply, id, json("uri", photo.toString(), "source", "camera"));
            } else {
                replyError(reply, id, "CANCELLED", "Съёмка отменена.");
            }
            clearNativePending();
            return;
        }

        if (requestCode == REQ_CONTACT && reply != null) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                String name = "";
                String phone = "";
                try (Cursor cursor = getContentResolver().query(
                        data.getData(),
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                        },
                        null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        name = cursor.getString(0);
                        phone = cursor.getString(1);
                    }
                } catch (Throwable error) {
                    replyError(reply, id, "CONTACT_READ_FAILED", message(error));
                    clearNativePending();
                    return;
                }
                replyOk(reply, id, json("name", name, "phone", phone));
            } else {
                replyError(reply, id, "CANCELLED", "Выбор контакта отменён.");
            }
            clearNativePending();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (elm != null) elm.shutdown();
        if (pendingWebFileCallback != null) {
            try { pendingWebFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
            pendingWebFileCallback = null;
        }
        if (pendingWebPermissionRequest != null) {
            try { pendingWebPermissionRequest.deny(); } catch (Throwable ignored) {}
            pendingWebPermissionRequest = null;
        }
        if (pendingGeoCallback != null && pendingGeoOrigin != null) {
            try { pendingGeoCallback.invoke(pendingGeoOrigin, false, false); } catch (Throwable ignored) {}
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class KaretaChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(
                WebView view,
                ValueCallback<Uri[]> callback,
                FileChooserParams params) {
            if (pendingWebFileCallback != null) {
                try { pendingWebFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
            }
            pendingWebFileCallback = callback;
            pendingWebCameraUri = null;

            try {
                Intent picker = params.createIntent();
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                java.util.ArrayList<Intent> initialIntents = new java.util.ArrayList<>();
                boolean wantsImage = false;
                String[] accept = params.getAcceptTypes();
                if (accept == null || accept.length == 0) {
                    wantsImage = true;
                } else {
                    for (String type : accept) {
                        if (type == null || type.isEmpty() || type.startsWith("image/")) {
                            wantsImage = true;
                            break;
                        }
                    }
                }

                if (wantsImage
                        && checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    File dir = new File(getCacheDir(), "camera");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IllegalStateException("CAMERA_CACHE_UNAVAILABLE");
                    }
                    File photo = File.createTempFile("kareta_web_", ".jpg", dir);
                    Uri uri = FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".files", photo);
                    Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, uri);
                    camera.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if (camera.resolveActivity(getPackageManager()) != null) {
                        pendingWebCameraUri = uri;
                        initialIntents.add(camera);
                    }
                }

                Intent chooser = new Intent(Intent.ACTION_CHOOSER);
                chooser.putExtra(Intent.EXTRA_INTENT, picker);
                chooser.putExtra(Intent.EXTRA_TITLE, "Выберите файл или фото");
                if (!initialIntents.isEmpty()) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                            initialIntents.toArray(new Intent[0]));
                }
                startActivityForResult(chooser, REQ_WEB_FILE_CHOOSER);
                return true;
            } catch (Throwable error) {
                pendingWebFileCallback = null;
                pendingWebCameraUri = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            if (request == null || !isTrustedUri(request.getOrigin())) {
                if (request != null) request.deny();
                return;
            }

            java.util.ArrayList<String> runtime = new java.util.ArrayList<>();
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                        && checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    runtime.add(Manifest.permission.CAMERA);
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    runtime.add(Manifest.permission.RECORD_AUDIO);
                }
            }

            pendingWebPermissionRequest = request;
            if (runtime.isEmpty()) {
                grantTrustedWebResources(request);
                pendingWebPermissionRequest = null;
                return;
            }
            requestPermissions(runtime.toArray(new String[0]), REQ_WEB_PERMISSION);
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin,
                GeolocationPermissions.Callback callback) {
            Uri uri = origin == null ? null : Uri.parse(origin);
            if (!isTrustedUri(uri)) {
                callback.invoke(origin, false, false);
                return;
            }

            boolean granted =
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                callback.invoke(origin, true, false);
                return;
            }

            pendingGeoOrigin = origin;
            pendingGeoCallback = callback;
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_WEB_GEOLOCATION);
        }
    }

    private void grantTrustedWebResources(PermissionRequest request) {
        if (request == null || !isTrustedUri(request.getOrigin())) {
            if (request != null) request.deny();
            return;
        }

        java.util.ArrayList<String> granted = new java.util.ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                granted.add(resource);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                granted.add(resource);
            }
        }

        if (granted.isEmpty()) request.deny();
        else request.grant(granted.toArray(new String[0]));
    }

    private static final class PendingPermission {
        final String id;
        final String alias;
        final JavaScriptReplyProxy reply;

        PendingPermission(String id, String alias, JavaScriptReplyProxy reply) {
            this.id = id;
            this.alias = alias;
            this.reply = reply;
        }
    }
}

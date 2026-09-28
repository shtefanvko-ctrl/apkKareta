package kz.kareta.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String BASE_URL = "https://s.kareta.kz/";
    private static final String BASE_HOST = "s.kareta.kz";
    private static final int NATIVE_API_VERSION = 6;
    private static final int REQ_BLUETOOTH = 4101;
    private static final long PAGE_TIMEOUT_MS = 30000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private FrameLayout root;
    private Elm327Manager elm;
    private OfflineQueue offlineQueue;
    private boolean pageLoaded;

    private PendingPermission pendingBluetooth;

    private final Runnable pageTimeout = () -> {
        if (!pageLoaded && webView != null) {
            showLoadError("Сервер s.kareta.kz не ответил за 30 секунд.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        settings.setUserAgentString(settings.getUserAgentString() + " KARETA-Android/1.4.2");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

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
        });
    }

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
        if (!"bluetooth".equalsIgnoreCase(permission)) {
            replyError(reply, id, "UNSUPPORTED_PERMISSION",
                    "Эта сборка запрашивает через bridge только Bluetooth.");
            return;
        }

        if (Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            replyOk(reply, id, json("permission", "bluetooth", "granted", true));
            return;
        }

        if (pendingBluetooth != null) {
            replyError(reply, id, "PERMISSION_BUSY",
                    "Запрос Bluetooth-разрешения уже открыт.");
            return;
        }

        pendingBluetooth = new PendingPermission(id, reply);
        requestPermissions(
                new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                REQ_BLUETOOTH
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLUETOOTH) return;

        PendingPermission pending = pendingBluetooth;
        pendingBluetooth = null;
        if (pending == null) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        replyOk(pending.reply, pending.id,
                json("permission", "bluetooth", "granted", granted));
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (elm != null) elm.shutdown();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private static final class PendingPermission {
        final String id;
        final JavaScriptReplyProxy reply;

        PendingPermission(String id, JavaScriptReplyProxy reply) {
            this.id = id;
            this.reply = reply;
        }
    }
}

package kz.kareta.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Elm327Manager {
    interface Callback {
        void ok(JSONObject data);
        void error(String code, String message);
    }

    enum State {
        DISCONNECTED, CONNECTING, ADAPTER_CONNECTED, READY, ERROR
    }

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final String PREFS = "kareta_elm327";
    private static final String LAST_ADDRESS = "last_address";
    private static final String LAST_NAME = "last_name";

    private final Context context;
    private final BluetoothAdapter adapter;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService connector = Executors.newCachedThreadPool();
    private final Object socketLock = new Object();

    private volatile State state = State.DISCONNECTED;
    private volatile String lastError = "";
    private volatile String deviceAddress = "";
    private volatile String deviceName = "";
    private volatile String protocol = "";

    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;

    Elm327Manager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        BluetoothManager manager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    JSONObject status() {
        JSONObject out = new JSONObject();
        boolean permission = hasConnectPermission();
        boolean enabled = false;
        try {
            enabled = adapter != null && adapter.isEnabled();
        } catch (SecurityException ignored) {}
        try {
            out.put("transport", "classic_spp");
            out.put("permission", permission);
            out.put("enabled", enabled);
            out.put("state", state.name());
            out.put("connected", isSocketConnected());
            out.put("ready", state == State.READY && isSocketConnected());
            out.put("name", deviceName);
            out.put("address", deviceAddress);
            out.put("protocol", protocol);
            out.put("lastError", lastError);
            out.put("pairedOnly", true);
        } catch (JSONException ignored) {}
        return out;
    }

    @SuppressLint("MissingPermission")
    JSONObject devices() throws Exception {
        requireBluetooth();
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        List<JSONObject> rows = new ArrayList<>();
        if (bonded != null) {
            for (BluetoothDevice device : bonded) {
                JSONObject row = new JSONObject();
                String name = safeName(device);
                String address = device.getAddress();
                row.put("name", name);
                row.put("address", address);
                String probe = name.toLowerCase(Locale.ROOT);
                row.put("likelyElm",
                        probe.contains("elm") || probe.contains("obd") || probe.contains("vlink"));
                rows.add(row);
            }
        }
        Collections.sort(rows, (a, b) ->
                Boolean.compare(b.optBoolean("likelyElm"), a.optBoolean("likelyElm")));
        JSONArray array = new JSONArray();
        for (JSONObject row : rows) array.put(row);
        JSONObject out = new JSONObject();
        out.put("devices", array);
        out.put("pairedOnly", true);
        return out;
    }

    void connect(String address, Callback callback) {
        io.execute(() -> {
            state = State.CONNECTING;
            lastError = "";
            try {
                connectBlocking(address);
                callback.ok(status());
            } catch (Throwable error) {
                fail("ELM_CONNECT_FAILED", message(error));
                callback.error("ELM_CONNECT_FAILED", message(error));
            }
        });
    }

    void reconnectLast(Callback callback) {
        String address = prefs.getString(LAST_ADDRESS, "");
        if (address == null || address.isEmpty()) {
            callback.error("ELM_NO_LAST_DEVICE", "Нет сохранённого ELM327.");
            return;
        }
        connect(address, callback);
    }

    void initialize(Callback callback) {
        io.execute(() -> {
            try {
                requireConnected();
                String reset = commandDirect("ATZ", 5000);
                commandDirect("ATE0", 2500);
                commandDirect("ATL0", 2500);
                commandDirect("ATS0", 2500);
                commandDirect("ATH0", 2500);
                commandDirect("ATSP0", 3500);
                String identity = commandDirect("ATI", 2500);
                String protocolRaw = commandDirect("ATDP", 3000);
                String supported = commandDirect("0100", 5000);

                protocol = cleanText(protocolRaw);
                boolean vehicleConnected = hasModeResponse(supported, "4100");
                if (vehicleConnected) {
                    state = State.READY;
                    lastError = "";
                } else {
                    state = State.ADAPTER_CONNECTED;
                    lastError = "ECU_NO_RESPONSE";
                }

                JSONObject out = status();
                out.put("vehicleConnected", vehicleConnected);
                out.put("adapterIdentity", cleanText(identity));
                out.put("reset", cleanText(reset));
                out.put("supportedPidsRaw", cleanText(supported));
                callback.ok(out);
            } catch (Throwable error) {
                lastError = message(error);
                if (isSocketConnected()) state = State.ADAPTER_CONNECTED;
                else state = State.ERROR;
                callback.error("ELM_INIT_FAILED", message(error));
            }
        });
    }

    void command(String command, int timeoutMs, Callback callback) {
        io.execute(() -> {
            try {
                requireReady();
                String safe = validateCommand(command);
                String raw = commandDirect(safe, clamp(timeoutMs, 800, 8000));
                JSONObject out = new JSONObject();
                out.put("command", safe);
                out.put("raw", cleanText(raw));
                out.put("normalized", normalizeHex(raw));
                callback.ok(out);
            } catch (Throwable error) {
                callback.error("ELM_COMMAND_FAILED", message(error));
            }
        });
    }

    void snapshot(Callback callback) {
        io.execute(() -> {
            try {
                requireReady();
                callback.ok(readSnapshot(true));
            } catch (Throwable error) {
                callback.error("ELM_SNAPSHOT_FAILED", message(error));
            }
        });
    }

    void liveSnapshot(Callback callback) {
        io.execute(() -> {
            try {
                requireReady();
                callback.ok(readSnapshot(false));
            } catch (Throwable error) {
                callback.error("ELM_LIVE_FAILED", message(error));
            }
        });
    }

    JSONObject disconnect() {
        closeSocket(true);
        return status();
    }

    void shutdown() {
        closeSocket(true);
        io.shutdownNow();
        connector.shutdownNow();
    }

    private JSONObject readSnapshot(boolean full) throws Exception {
        String rpmRaw = commandDirect("010C", 2600);
        String speedRaw = commandDirect("010D", 2600);
        String coolantRaw = commandDirect("0105", 2600);
        String voltageRaw = commandDirect("ATRV", 2600);

        JSONObject out = new JSONObject();
        putNumber(out, "rpm", parseRpm(rpmRaw));
        putNumber(out, "speedKph", parseOneBytePid(speedRaw, "410D", 0));
        putNumber(out, "coolantC", parseOneBytePid(coolantRaw, "4105", -40));
        putNumber(out, "voltageV", parseVoltage(voltageRaw));
        out.put("protocol", protocol);
        out.put("adapterName", deviceName);
        out.put("adapterAddress", deviceAddress);

        if (full) {
            String dtcRaw = commandDirect("03", 4500);
            String vinRaw = commandDirect("0902", 5500);
            out.put("dtcRaw", cleanText(dtcRaw));
            out.put("dtcCodes", parseDtc(dtcRaw));
            out.put("vinRaw", cleanText(vinRaw));
            out.put("vin", parseVin(vinRaw));
        }
        return out;
    }

    @SuppressLint("MissingPermission")
    private void connectBlocking(String address) throws Exception {
        requireBluetooth();
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Некорректный Bluetooth MAC.");
        }

        closeSocket(false);
        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothSocket candidate = device.createRfcommSocketToServiceRecord(SPP_UUID);
        Future<?> future = connector.submit(() -> {
            try {
                candidate.connect();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        try {
            future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            try { candidate.close(); } catch (Exception ignored) {}
            throw new Exception("Таймаут подключения ELM327 (" + CONNECT_TIMEOUT_MS + " мс).");
        } catch (ExecutionException error) {
            try { candidate.close(); } catch (Exception ignored) {}
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new Exception(message(cause));
        }

        synchronized (socketLock) {
            socket = candidate;
            input = candidate.getInputStream();
            output = candidate.getOutputStream();
        }
        deviceAddress = address;
        deviceName = safeName(device);
        protocol = "";
        state = State.ADAPTER_CONNECTED;
        lastError = "";
        prefs.edit()
                .putString(LAST_ADDRESS, deviceAddress)
                .putString(LAST_NAME, deviceName)
                .apply();
    }

    private String commandDirect(String command, int timeoutMs) throws Exception {
        BluetoothSocket active;
        InputStream in;
        OutputStream out;
        synchronized (socketLock) {
            active = socket;
            in = input;
            out = output;
        }
        if (active == null || !active.isConnected() || in == null || out == null) {
            throw new Exception("ELM327 не подключён.");
        }

        while (in.available() > 0) {
            byte[] trash = new byte[Math.min(512, in.available())];
            if (in.read(trash) < 0) break;
        }

        out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[512];
        while (System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, available));
                if (read < 0) break;
                response.append(new String(buffer, 0, read, StandardCharsets.US_ASCII));
                if (response.indexOf(">") >= 0) break;
            } else {
                Thread.sleep(20);
            }
        }

        String raw = response.toString().replace(">", "").trim();
        if (raw.isEmpty()) throw new Exception("Нет ответа ELM327: " + command);

        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("UNABLE TO CONNECT")) throw new Exception("ECU недоступен.");
        if (upper.contains("BUS INIT") && upper.contains("ERROR")) throw new Exception("BUS INIT ERROR.");
        if (upper.contains("CAN ERROR")) throw new Exception("CAN ERROR.");
        if (upper.contains("STOPPED")) throw new Exception("ELM327 STOPPED.");
        if (upper.trim().endsWith("?")) throw new Exception("Команда не поддерживается: " + command);
        return raw;
    }

    private String validateCommand(String command) {
        String value = command == null ? "" :
                command.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (value.length() == 0 || value.length() > 32) {
            throw new IllegalArgumentException("Некорректная OBD-команда.");
        }

        switch (value) {
            case "ATI":
            case "ATZ":
            case "ATE0":
            case "ATL0":
            case "ATS0":
            case "ATH0":
            case "ATSP0":
            case "ATDP":
            case "ATRV":
            case "0100":
            case "0105":
            case "010C":
            case "010D":
            case "03":
            case "0902":
                return value;
            default:
                throw new IllegalArgumentException("Команда запрещена Native API 6.");
        }
    }

    private void putNumber(JSONObject out, String key, double value) throws JSONException {
        if (Double.isNaN(value) || Double.isInfinite(value)) out.put(key, JSONObject.NULL);
        else out.put(key, value);
    }

    private JSONArray parseDtc(String raw) {
        List<Integer> bytes = byteTokens(raw);
        JSONArray codes = new JSONArray();
        int start = bytes.indexOf(0x43);
        if (start < 0) return codes;

        for (int i = start + 1; i + 1 < bytes.size(); i += 2) {
            int a = bytes.get(i);
            int b = bytes.get(i + 1);
            if (a == 0 && b == 0) continue;
            char system = "PCBU".charAt((a & 0xC0) >> 6);
            int digit1 = (a & 0x30) >> 4;
            String code = String.format(Locale.US, "%c%d%X%02X",
                    system, digit1, a & 0x0F, b);
            codes.put(code);
        }
        return codes;
    }

    private String parseVin(String raw) {
        List<Integer> bytes = byteTokens(raw);
        int start = -1;
        for (int i = 0; i + 1 < bytes.size(); i++) {
            if (bytes.get(i) == 0x49 && bytes.get(i + 1) == 0x02) {
                start = i + 2;
                break;
            }
        }
        if (start < 0) return "";
        if (start < bytes.size() && bytes.get(start) <= 0x03) start++;

        StringBuilder vin = new StringBuilder();
        for (int i = start; i < bytes.size() && vin.length() < 17; i++) {
            int value = bytes.get(i);
            if ((value >= '0' && value <= '9') ||
                    (value >= 'A' && value <= 'Z')) {
                char c = (char) value;
                if (c != 'I' && c != 'O' && c != 'Q') vin.append(c);
            }
        }
        return vin.toString();
    }

    private double parseRpm(String raw) {
        String hex = normalizeHex(raw);
        int p = hex.indexOf("410C");
        if (p < 0 || p + 8 > hex.length()) return Double.NaN;
        int a = Integer.parseInt(hex.substring(p + 4, p + 6), 16);
        int b = Integer.parseInt(hex.substring(p + 6, p + 8), 16);
        return ((a * 256) + b) / 4.0;
    }

    private double parseOneBytePid(String raw, String marker, int offset) {
        String hex = normalizeHex(raw);
        int p = hex.indexOf(marker);
        if (p < 0 || p + marker.length() + 2 > hex.length()) return Double.NaN;
        int a = Integer.parseInt(
                hex.substring(p + marker.length(), p + marker.length() + 2), 16);
        return a + offset;
    }

    private double parseVoltage(String raw) {
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*V",
                Pattern.CASE_INSENSITIVE).matcher(raw);
        if (!matcher.find()) return Double.NaN;
        try { return Double.parseDouble(matcher.group(1)); }
        catch (Exception ignored) { return Double.NaN; }
    }

    private boolean hasModeResponse(String raw, String marker) {
        return normalizeHex(raw).contains(marker);
    }

    private List<Integer> byteTokens(String raw) {
        List<Integer> out = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)(?<![0-9A-F])[0-9A-F]{2}(?![0-9A-F])")
                .matcher(raw == null ? "" : raw);
        while (matcher.find()) {
            try { out.add(Integer.parseInt(matcher.group(), 16)); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private String normalizeHex(String raw) {
        if (raw == null) return "";
        return raw.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
    }

    private String cleanText(String raw) {
        if (raw == null) return "";
        return raw.replace("\r", "\n")
                .replaceAll("\n{2,}", "\n")
                .trim();
    }

    private void requireBluetooth() throws Exception {
        if (adapter == null) throw new Exception("Bluetooth не поддерживается.");
        if (!hasConnectPermission()) throw new SecurityException("BLUETOOTH_CONNECT_REQUIRED");
        if (!adapter.isEnabled()) throw new Exception("Bluetooth выключен.");
    }

    private void requireConnected() throws Exception {
        requireBluetooth();
        if (!isSocketConnected()) throw new Exception("ELM327 не подключён.");
    }

    private void requireReady() throws Exception {
        requireConnected();
        if (state != State.READY) {
            throw new Exception("ELM327 подключён, но связь с ECU не готова.");
        }
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSocketConnected() {
        synchronized (socketLock) {
            try {
                return socket != null && socket.isConnected();
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "Bluetooth" : name.trim();
        } catch (SecurityException ignored) {
            String saved = prefs.getString(LAST_NAME, "Bluetooth");
            return saved == null ? "Bluetooth" : saved;
        }
    }

    private void fail(String code, String message) {
        closeSocket(false);
        state = State.ERROR;
        lastError = code + ": " + message;
    }

    private void closeSocket(boolean disconnectedState) {
        synchronized (socketLock) {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            input = null;
            output = null;
            socket = null;
        }
        protocol = "";
        if (disconnectedState) {
            state = State.DISCONNECTED;
            lastError = "";
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String message(Throwable error) {
        if (error == null) return "Unknown error";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }
}

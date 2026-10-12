package kz.kareta.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A characteristic-notify BLE serial bridge, not an arbitrary BLE OBD decoder.
 * Supports Nordic UART, HM-10/FFE1 and Vgate-style UART; fails closed for unknown services.
 * Bluetooth operations run on Elm327Manager's I/O executor; callbacks arrive on Android threads.
 */
final class BleElmSerial {
    private static final UUID NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID HM10 = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID HM10_IO = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF0 = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID FFF2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID VGATE = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2");
    private static final UUID VGATE_WRITE = UUID.fromString("e7810a72-73ae-499d-8c15-faa9aef0c3f2");
    private static final UUID VGATE_NOTIFY = UUID.fromString("e7810a73-73ae-499d-8c15-faa9aef0c3f2");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final BluetoothAdapter adapter;
    private final LinkedBlockingQueue<Byte> bytes = new LinkedBlockingQueue<>(16384);
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile CountDownLatch writePending;
    private volatile int writeResult = -1;
    private volatile String error = "BLE_UNAVAILABLE";
    private volatile boolean connected;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic tx;
    private volatile BluetoothGattCharacteristic rx;
    private final InputStream input = new InputStream() {
        @Override public int available() { return bytes.size(); }
        @Override public int read() {
            Byte value = bytes.poll();
            return value == null ? -1 : value & 0xff;
        }
        @Override public int read(byte[] buffer, int offset, int length) {
            if (length == 0) return 0;
            Byte value = bytes.poll();
            if (value == null) return -1;
            buffer[offset] = value;
            int count = 1;
            while (count < length && (value = bytes.poll()) != null) buffer[offset + count++] = value;
            return count;
        }
    };
    private final OutputStream output = new OutputStream() {
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            int written = 0;
            while (written < length) {
                int count = Math.min(20, length - written);
                byte[] chunk = new byte[count];
                System.arraycopy(data, offset + written, chunk, 0, count);
                writeChunk(chunk);
                written += count;
            }
        }
    };

    BleElmSerial(Context context, BluetoothAdapter adapter) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
    }

    @SuppressLint("MissingPermission") // Caller checks BLUETOOTH_CONNECT before invoking.
    void connect(String address) throws Exception {
        BluetoothDevice device = adapter.getRemoteDevice(address);
        gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("BLE_GATT_CONNECT_FAILED");
        if (!ready.await(15, TimeUnit.SECONDS) || !connected) {
            close();
            throw new IOException("BLE_UART_UNAVAILABLE: " + error);
        }
    }

    boolean isConnected() { return connected && gatt != null && tx != null && rx != null; }
    InputStream input() { return input; }
    OutputStream output() { return output; }

    @SuppressLint("MissingPermission") // Closing an existing owned GATT is safe after permission checks.
    void close() {
        connected = false;
        BluetoothGatt active = gatt;
        gatt = null;
        if (active != null) {
            try { active.disconnect(); } catch (Exception ignored) {}
            try { active.close(); } catch (Exception ignored) {}
        }
        CountDownLatch pending = writePending;
        if (pending != null) pending.countDown();
        ready.countDown();
        bytes.clear();
    }

    private boolean canWrite(BluetoothGattCharacteristic c) {
        return c != null && (c.getProperties() &
                (BluetoothGattCharacteristic.PROPERTY_WRITE |
                 BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    private boolean canNotify(BluetoothGattCharacteristic c) {
        return c != null && (c.getProperties() &
                (BluetoothGattCharacteristic.PROPERTY_NOTIFY |
                 BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
    }

    private boolean chooseUart(BluetoothGatt active) {
        List<BluetoothGattService> services = active.getServices();
        for (BluetoothGattService service : services) {
            BluetoothGattCharacteristic write = null, notify = null;
            if (NUS.equals(service.getUuid())) {
                write = service.getCharacteristic(NUS_WRITE);
                notify = service.getCharacteristic(NUS_NOTIFY);
            } else if (HM10.equals(service.getUuid())) {
                write = notify = service.getCharacteristic(HM10_IO);
            } else if (FFF0.equals(service.getUuid())) {
                write = service.getCharacteristic(FFF2);
                notify = service.getCharacteristic(FFF1);
                if (!canWrite(write) || !canNotify(notify)) {
                    BluetoothGattCharacteristic temp = write;
                    write = notify;
                    notify = temp;
                }
            } else if (VGATE.equals(service.getUuid())) {
                write = service.getCharacteristic(VGATE_WRITE);
                notify = service.getCharacteristic(VGATE_NOTIFY);
                if (!canWrite(write) || !canNotify(notify)) {
                    BluetoothGattCharacteristic temp = write;
                    write = notify;
                    notify = temp;
                }
            }
            if (canWrite(write) && canNotify(notify)) {
                tx = write;
                rx = notify;
                return true;
            }
        }
        error = "Неизвестный BLE UART профиль; поддерживаются NUS, FFE0/FFF0, Vgate.";
        return false;
    }

    @SuppressLint("MissingPermission") // GATT originated from a trusted native call.
    private void subscribe(BluetoothGatt active) {
        BluetoothGattCharacteristic notify = rx;
        if (notify == null || !active.setCharacteristicNotification(notify, true)) {
            error = "BLE_NOTIFY_ENABLE_FAILED";
            ready.countDown();
            return;
        }
        BluetoothGattDescriptor descriptor = notify.getDescriptor(CCCD);
        if (descriptor == null) {
            error = "BLE_CCCD_MISSING";
            ready.countDown();
            return;
        }
        byte[] flag = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = active.writeDescriptor(descriptor, flag) == BluetoothStatusCodes.SUCCESS;
        } else {
            descriptor.setValue(flag);
            started = active.writeDescriptor(descriptor);
        }
        if (!started) {
            error = "BLE_CCCD_WRITE_FAILED";
            ready.countDown();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void writeChunk(byte[] data) throws IOException {
        BluetoothGatt active = gatt;
        BluetoothGattCharacteristic characteristic = tx;
        if (!isConnected() || active == null || characteristic == null) {
            throw new IOException("BLE_DISCONNECTED");
        }
        int type = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        characteristic.setWriteType(type);
        CountDownLatch latch = new CountDownLatch(1);
        writePending = latch;
        writeResult = -1;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = active.writeCharacteristic(characteristic, data, type) == BluetoothStatusCodes.SUCCESS;
        } else {
            characteristic.setValue(data);
            started = active.writeCharacteristic(characteristic);
        }
        if (!started) {
            writePending = null;
            throw new IOException("BLE_WRITE_START_FAILED");
        }
        if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writePending = null;
            try { Thread.sleep(35); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        } else {
            try {
                if (!latch.await(2500, TimeUnit.MILLISECONDS) ||
                        writeResult != BluetoothGatt.GATT_SUCCESS) {
                    throw new IOException("BLE_WRITE_TIMEOUT_OR_REJECTED");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                writePending = null;
            }
        }
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                error = "BLE_DISCONNECTED_" + status;
                connected = false;
                ready.countDown();
                CountDownLatch pending = writePending;
                if (pending != null) pending.countDown();
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) {
                    error = "BLE_DISCOVER_FAILED";
                    ready.countDown();
                }
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || !chooseUart(g)) {
                if (status != BluetoothGatt.GATT_SUCCESS) error = "BLE_DISCOVER_" + status;
                ready.countDown();
            } else {
                subscribe(g);
            }
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connected = true;
                error = "";
            } else error = "BLE_CCCD_" + status;
            ready.countDown();
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,
                                                      BluetoothGattCharacteristic c, int status) {
            writeResult = status;
            CountDownLatch pending = writePending;
            if (pending != null) pending.countDown();
        }
        private void append(byte[] data) {
            if (data != null) for (byte value : data) bytes.offer(value);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (rx != null && rx.getUuid().equals(c.getUuid())) append(c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,
                                                      BluetoothGattCharacteristic c, byte[] data) {
            if (rx != null && rx.getUuid().equals(c.getUuid())) append(data);
        }
    };
}

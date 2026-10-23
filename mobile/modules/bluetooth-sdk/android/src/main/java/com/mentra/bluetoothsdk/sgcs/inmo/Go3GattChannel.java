package com.mentra.bluetoothsdk.sgcs.inmo;

import android.annotation.SuppressLint;
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

import com.mentra.bluetoothsdk.Bridge;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * GATT over BR/EDR (not LE) to service 0x2020: the path that coexists with INMO Global.
 * One AA55 frame per write-with-response; each write waits for its callback (backpressure).
 * Blocking; used from one worker thread. {@link #close()} may be called from any thread.
 * Ported from lens-spark@79d33c2 (link/GattChannel.java), with pre-API-33 GATT fallbacks
 * and write statistics removed.
 */
@SuppressLint("MissingPermission")
public final class Go3GattChannel implements Go3Link.Transport {
    private static final String TAG = "InmoGo3";
    private static final UUID SERVICE = uuid16(0x2020), WRITE = uuid16(0x2021), NOTIFY = uuid16(0x2022), CCCD = uuid16(0x2902);
    private static final int CONNECTED = 1, MTU = 2, SERVICES = 3, DESCRIPTOR = 4, WRITTEN = 5, DISCONNECTED = 6;

    private final Context context;
    private final BluetoothDevice device;
    private final BlockingQueue<int[]> events = new ArrayBlockingQueue<>(64);
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private volatile Consumer<byte[]> inbound;
    private volatile Runnable onLost;
    private volatile boolean opened, closed;

    public Go3GattChannel(Context context, BluetoothDevice device) {
        this.context = context.getApplicationContext();
        this.device = device;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                event(CONNECTED, status);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                event(DISCONNECTED, status);
                Runnable lost = onLost;
                if (opened && !closed && lost != null) lost.run();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            event(MTU, status);
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            event(SERVICES, status);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (CCCD.equals(d.getUuid())) event(DESCRIPTOR, status);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (!WRITE.equals(c.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) Bridge.log(TAG + ": gatt write confirm status=" + status);
            event(WRITTEN, status);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            deliver(c, value);
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // Before API 33 only this overload is called.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) deliver(c, c.getValue());
        }
    };

    private void deliver(BluetoothGattCharacteristic c, byte[] value) {
        Consumer<byte[]> sink = inbound;
        if (!closed && sink != null && value != null && NOTIFY.equals(c.getUuid())) sink.accept(value.clone());
    }

    private void event(int kind, int status) {
        events.offer(new int[]{kind, status});
    }

    @Override public void open(Consumer<byte[]> inbound, Runnable onLost) throws IOException {
        this.inbound = inbound;
        this.onLost = onLost;
        BluetoothGatt connection;
        synchronized (this) {
            if (closed) throw new IOException("Cancelled");
            connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_BREDR);
            if (connection == null) throw new IOException("Couldn't open a GATT connection to the glasses.");
            gatt = connection;
        }
        expect(CONNECTED, 15);
        // Android ignores MTU requests on BR/EDR (it reports MTU 23 / status 14); kept best effort as measured.
        if (connection.requestMtu(247)) {
            try {
                expect(MTU, 2);
            } catch (IOException mtuFailure) {
                if (closed) throw mtuFailure;
            }
        }
        if (!connection.discoverServices()) throw new IOException("Couldn't discover the glasses' services.");
        expect(SERVICES, 15);
        BluetoothGattService service = connection.getService(SERVICE);
        if (service == null) throw new IOException("The glasses' GATT service is missing.");
        write = service.getCharacteristic(WRITE);
        BluetoothGattCharacteristic notify = service.getCharacteristic(NOTIFY);
        if (write == null || notify == null) throw new IOException("The glasses' GATT channel is missing.");
        BluetoothGattDescriptor descriptor = notify.getDescriptor(CCCD);
        if (descriptor == null || !connection.setCharacteristicNotification(notify, true))
            throw new IOException("Couldn't subscribe to the glasses.");
        if (!writeDescriptor(connection, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
            throw new IOException("Couldn't subscribe to the glasses.");
        expect(DESCRIPTOR, 10);
        opened = true;
    }

    private void expect(int wanted, int seconds) throws IOException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (true) {
            if (closed) throw new IOException("Cancelled");
            long remaining = end - System.nanoTime();
            int[] event;
            try {
                event = remaining <= 0 ? null : events.poll(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            }
            if (closed) throw new IOException("Cancelled");
            if (event == null) throw new IOException("The glasses stopped responding.");
            if (event[0] == DISCONNECTED) throw new IOException("The glasses disconnected.");
            if (event[0] != wanted) continue;
            if (event[1] != BluetoothGatt.GATT_SUCCESS && wanted != MTU)
                throw new IOException("Bluetooth error " + event[1] + " talking to the glasses.");
            return;
        }
    }

    @Override public OutputStream output() {
        return new OutputStream() {
            @Override public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                synchronized (Go3GattChannel.this) {
                    BluetoothGatt connection = gatt;
                    if (closed || connection == null || write == null) throw new IOException("Connection closed");
                    byte[] frame = Arrays.copyOfRange(bytes, offset, offset + length);
                    try {
                        if (!writeCharacteristic(connection, write, frame))
                            throw new IOException("The glasses rejected a write.");
                        expect(WRITTEN, 5);
                    } finally {
                        Arrays.fill(frame, (byte) 0);
                    }
                }
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static boolean writeCharacteristic(BluetoothGatt connection, BluetoothGattCharacteristic c, byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return connection.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothStatusCodes.SUCCESS;
        }
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(value);
        return connection.writeCharacteristic(c);
    }

    @SuppressWarnings("deprecation")
    private static boolean writeDescriptor(BluetoothGatt connection, BluetoothGattDescriptor d, byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return connection.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS;
        }
        d.setValue(value);
        return connection.writeDescriptor(d);
    }

    @Override public void close() {
        BluetoothGatt connection;
        synchronized (events) {
            if (closed) return;
            closed = true;
            connection = gatt;
        }
        event(DISCONNECTED, 0);
        if (connection != null) {
            try { connection.disconnect(); } catch (RuntimeException ignored) { }
            try { connection.close(); } catch (RuntimeException ignored) { }
        }
    }

    private static UUID uuid16(int value) {
        return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", value));
    }
}

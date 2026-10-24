package com.mentra.bluetoothsdk.sgcs;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;

import com.mentra.bluetoothsdk.Bridge;
import com.mentra.bluetoothsdk.DeviceManager;
import com.mentra.bluetoothsdk.DeviceStore;
import com.mentra.bluetoothsdk.PhotoRequest;
import com.mentra.bluetoothsdk.sgcs.inmo.Go3Canvas;
import com.mentra.bluetoothsdk.sgcs.inmo.Go3GattChannel;
import com.mentra.bluetoothsdk.sgcs.inmo.Go3Link;
import com.mentra.bluetoothsdk.sgcs.inmo.SendRate;
import com.mentra.bluetoothsdk.utils.ConnTypes;
import com.mentra.bluetoothsdk.utils.DeviceTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * INMO GO3 (prototype): display-only support over GATT on classic Bluetooth (BR/EDR), the path that
 * coexists with the INMO Global app. The GO3 must already be paired in Android Bluetooth settings.
 *
 * <p>The GO3 shows images through its Navigation app, so text and bitmaps are rendered to a 640×480
 * PNG ({@link Go3Canvas}) and sent as one frame, paced by an adaptive rate ({@link SendRate}); only the
 * newest pending frame is sent. Never writes or releases a binding (RFCOMM-only, not supported yet).
 * Protocol sequence: lens-spark@3e2158f docs/PROTOCOL.md.
 */
@SuppressLint("MissingPermission")
public class InmoGo3 extends SGCManager {
  private static final String TAG = "InmoGo3";
  static final String NAME_PREFIX = "INMO GO3";
  private static final long CHECK_TO_SESSION_PAUSE_MS = 500;
  private static final long RECONNECT_DELAY_MS = 2000;
  private static final int MAX_RECONNECT_ATTEMPTS = 5;
  /**
   * The first image after connecting can reach the glasses before their Navigation app is ready, leaving only
   * the empty Navigation view (seen on hardware 2026-10-24, and in lens-spark@3e2158f). The newest frame is
   * sent once more this long after every connect.
   */
  private static final long SETTLE_RESEND_MS = 2000;

  private final Context context;
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, TAG));
  /** Newest frame not yet shown; rendered on the worker. */
  private final AtomicReference<Callable<byte[]>> pendingFrame = new AtomicReference<>();
  /** Adaptive send rate; outlives connections so the rate and its marks survive reconnects. */
  private final SendRate rate = new SendRate();

  // Worker-thread state.
  private Go3Link session;
  private BluetoothDevice device;
  private int reconnectAttempts;
  private boolean drainScheduled;
  /** The last frame sent; shown again after a reconnect and by the settle resend. */
  private byte[] lastFrame;
  /** True between connectById() and disconnect(); drives automatic reconnects. */
  private volatile boolean wanted;

  public InmoGo3() {
    context = Bridge.getContext();
    this.type = DeviceTypes.INMO_GO3;
    this.hasMic = false;
    DeviceStore.INSTANCE.apply("glasses", "micEnabled", false);
  }

  // ---------------- Pairing and connection ----------------

  /** Paired devices named "INMO GO3…". The GO3 is paired in Android settings, never from here. */
  private List<BluetoothDevice> pairedGo3s() {
    List<BluetoothDevice> result = new ArrayList<>();
    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
    if (adapter == null || !adapter.isEnabled()) return result;
    try {
      for (BluetoothDevice candidate : adapter.getBondedDevices()) {
        String name = candidate.getName();
        if (name != null && name.startsWith(NAME_PREFIX)) result.add(candidate);
      }
    } catch (SecurityException missingPermission) {
      Bridge.log(TAG + ": Bluetooth permission missing: " + missingPermission.getMessage());
    }
    return result;
  }

  @Override
  public void findCompatibleDevices() {
    List<BluetoothDevice> paired = pairedGo3s();
    if (paired.isEmpty()) {
      Bridge.log(TAG + ": no paired GO3; pair it in Android Bluetooth settings first");
    }
    for (BluetoothDevice candidate : paired) {
      Bridge.sendDiscoveredDevice(type, candidate.getName(), candidate.getAddress(), null, null);
    }
  }

  @Override
  public void stopScan() {}

  @Override
  public void connectById(String id) {
    BluetoothDevice target = null;
    List<BluetoothDevice> paired = pairedGo3s();
    for (BluetoothDevice candidate : paired) {
      if (candidate.getAddress().equalsIgnoreCase(id) || candidate.getName().equals(id)) target = candidate;
    }
    if (target == null && (id == null || id.isEmpty()) && paired.size() == 1) target = paired.get(0);
    if (target == null) {
      Bridge.log(TAG + ": no paired GO3 matches " + id);
      updateConnectionState(ConnTypes.DISCONNECTED);
      return;
    }
    BluetoothDevice chosen = target;
    wanted = true;
    run(() -> {
      device = chosen;
      reconnectAttempts = 0;
      connect(true);
    });
  }

  /** Worker: opens a session. {@code checkBinding} runs the dedicated binding check first. */
  private void connect(boolean checkBinding) {
    closeSession();
    if (!wanted || device == null) return;
    updateConnectionState(ConnTypes.CONNECTING);
    byte[] existing = null;
    Go3Link link = null;
    try {
      if (checkBinding) {
        try (Go3Link check = newLink()) {
          check.open(() -> {});
          existing = check.checkBinding();
        }
        Thread.sleep(CHECK_TO_SESSION_PAUSE_MS);
      }
      link = newLink();
      link.open(this::onLost);
      link.startSession(existing);
      session = link;
      Bridge.log(TAG + ": session started (" + (existing != null ? "login" : "no login") + ")");
      markReady();
      // The first image opens the Navigation view: the pending frame, else the last one shown, else blank.
      byte[] resume = lastFrame;
      pendingFrame.compareAndSet(null, resume != null ? () -> resume : Go3Canvas::blank);
      drain();
      Go3Link started = link;
      worker.schedule(() -> resendAfterSettle(started), SETTLE_RESEND_MS, TimeUnit.MILLISECONDS);
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      Bridge.log(TAG + ": connect failed: " + failure.getMessage());
      if (link != null && link != session) link.close();
      closeSession();
      markDisconnected();
      scheduleReconnect();
    } finally {
      Go3Link.wipe(existing);
    }
  }

  private Go3Link newLink() {
    return new Go3Link(
        new Go3GattChannel(context, device, rate),
        Thread::sleep,
        screen -> Bridge.log(TAG + ": glasses screen " + (screen == 1 ? "on" : "off")));
  }

  /** Called from a Bluetooth binder thread when an open session drops. */
  private void onLost() {
    run(() -> {
      if (session == null) return;
      Bridge.log(TAG + ": connection lost");
      closeSession();
      markDisconnected();
      scheduleReconnect();
    });
  }

  /** Worker: reconnects straight to a no-login session, as lens-spark does (no binding check). */
  private void scheduleReconnect() {
    if (!wanted || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return;
    reconnectAttempts++;
    try {
      worker.schedule(() -> connect(false), RECONNECT_DELAY_MS * reconnectAttempts, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException shutDown) {
      // cleanup() already ran.
    }
  }

  private void closeSession() {
    Go3Link current = session;
    session = null;
    if (current != null) current.close();
  }

  private void markReady() {
    reconnectAttempts = 0;
    DeviceManager.getInstance().setDeviceAddress(device.getAddress());
    DeviceStore.INSTANCE.apply("glasses", "bluetoothName", device.getName());
    DeviceStore.INSTANCE.apply("glasses", "connected", true);
    DeviceStore.INSTANCE.apply("glasses", "fullyBooted", true);
    updateConnectionState(ConnTypes.CONNECTED);
  }

  private void markDisconnected() {
    DeviceStore.INSTANCE.apply("glasses", "connected", false);
    DeviceStore.INSTANCE.apply("glasses", "fullyBooted", false);
    updateConnectionState(ConnTypes.DISCONNECTED);
  }

  private void updateConnectionState(String state) {
    DeviceStore.INSTANCE.apply("glasses", "connectionState", state);
  }

  @Override
  public void disconnect() {
    wanted = false;
    pendingFrame.set(null);
    run(() -> {
      closeSession();
      lastFrame = null;
      markDisconnected();
    });
  }

  @Override
  public void forget() {
    disconnect();
  }

  @Override
  public void cleanup() {
    wanted = false;
    pendingFrame.set(null);
    run(this::closeSession);
    worker.shutdown();
  }

  @Override
  public String getConnectedBluetoothName() {
    BluetoothDevice current = device;
    return current != null && current.getName() != null ? current.getName() : "";
  }

  private void run(Runnable task) {
    try {
      worker.execute(task);
    } catch (RejectedExecutionException shutDown) {
      Bridge.log(TAG + ": ignored work after cleanup");
    }
  }

  // ---------------- Display ----------------

  /** Queues a frame; only the newest queued frame is rendered and sent. */
  private void show(Callable<byte[]> frame) {
    pendingFrame.set(frame);
    run(() -> {
      if (drainScheduled) return;
      drainScheduled = true;
      drain();
    });
  }

  /** Worker: sends pending frames until none is left. Keeps the frame for later when not connected. */
  private void drain() {
    drainScheduled = false;
    Callable<byte[]> frame;
    while (session != null && (frame = pendingFrame.getAndSet(null)) != null) {
      byte[] png;
      try {
        png = frame.call();
      } catch (Exception renderFailure) {
        Bridge.log(TAG + ": frame not rendered: " + renderFailure.getMessage());
        continue;
      }
      if (!send(png)) return;
    }
  }

  /** Worker: the settle resend, unless the session changed or a newer frame is already queued. */
  private void resendAfterSettle(Go3Link started) {
    if (session != started || pendingFrame.get() != null || lastFrame == null) return;
    send(lastFrame);
  }

  /** Worker: shows one frame. On failure, drops the session and schedules a reconnect. */
  private boolean send(byte[] png) {
    try {
      session.showImage(png);
      lastFrame = png;
      return true;
    } catch (IOException failure) {
      Bridge.log(TAG + ": frame not sent: " + failure.getMessage());
      if (session == null) return false;
      closeSession();
      markDisconnected();
      scheduleReconnect();
      return false;
    }
  }

  @Override
  public void clearDisplay() {
    show(Go3Canvas::blank);
  }

  @Override
  public void sendText(String text) {
    show(() -> Go3Canvas.text(text));
  }

  @Override
  public void sendTextWall(String text) {
    show(() -> Go3Canvas.text(text));
  }

  @Override
  public void sendDoubleTextWall(String top, String bottom) {
    show(() -> Go3Canvas.doubleText(top, bottom));
  }

  @Override
  public boolean displayBitmap(
      String base64ImageData, Integer x, Integer y, Integer width, Integer height) {
    if (base64ImageData == null || base64ImageData.isEmpty()) return false;
    show(() -> Go3Canvas.image(base64ImageData));
    return true;
  }

  @Override
  public void showDashboard() {}

  @Override
  public void setDashboardPosition(int height, int depth) {}

  @Override
  public void setBrightness(int level, boolean autoMode) {
    Bridge.log(TAG + ": setBrightness not supported on GO3 yet");
  }

  // ---------------- Unsupported on GO3 ----------------

  @Override
  public void setMicEnabled(boolean enabled) {}

  @Override
  public List<String> sortMicRanking(List<String> list) {
    return list;
  }

  @Override
  public void requestPhoto(PhotoRequest request) {
    Bridge.log(TAG + ": requestPhoto not supported on GO3");
  }

  @Override
  public void startStream(Map<String, Object> message) {}

  @Override
  public void stopStream() {}

  @Override
  public void sendStreamKeepAlive(Map<String, Object> message) {}

  @Override
  public void startVideoRecording(String requestId, boolean save, boolean sound) {}

  @Override
  public void stopVideoRecording(String requestId) {}

  @Override
  public void sendButtonPhotoSettings() {}

  @Override
  public void sendButtonVideoRecordingSettings() {}

  @Override
  public void sendButtonMaxRecordingTime() {}

  @Override
  public void sendCameraFovSetting() {}

  @Override
  public void setHeadUpAngle(int angle) {}

  @Override
  public void getBatteryStatus() {}

  @Override
  public void setSilentMode(boolean enabled) {}

  @Override
  public void exit() {}

  @Override
  public void sendShutdown() {}

  @Override
  public void sendReboot() {}

  @Override
  public void sendRgbLedControl(
      String requestId,
      String packageName,
      String action,
      String color,
      int onDurationMs,
      int offDurationMs,
      int count) {
    Bridge.sendRgbLedControlResponse(requestId, false, "device_not_supported");
  }

  @Override
  public void ping() {}

  @Override
  public void dbg1() {}

  @Override
  public void dbg2() {}

  @Override
  public void requestWifiScan(String scanId) {}

  @Override
  public void sendWifiCredentials(String ssid, String password) {}

  @Override
  public void forgetWifiNetwork(String ssid) {}

  @Override
  public void sendHotspotState(boolean enabled) {}

  @Override
  public void sendUserEmailToGlasses(String email) {}

  @Override
  public void sendIncidentId(String incidentId, String apiBaseUrl) {}

  @Override
  public void queryGalleryStatus() {}

  @Override
  public void sendGalleryMode() {}

  @Override
  public void requestVersionInfo() {}
}

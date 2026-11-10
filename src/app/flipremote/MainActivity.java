package app.flipremote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/** Bluetooth remote for Flipper Zero: mirrors its screen and sends button presses over the BLE serial RPC
 *  (protobuf PB.Main, varint-length-delimited; field numbers from flipperdevices/flipperzero-protobuf). */
public class MainActivity extends Activity {
    static final UUID SERIAL = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000"),
        TX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000"),   // phone -> Flipper
        RX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000"),   // Flipper -> phone
        CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // PB.Main oneof fields
    static final int START_SCREEN_STREAM = 20, SCREEN_FRAME = 22, SEND_INPUT = 23;
    // PB_Gui.InputKey / InputType
    static final int UP = 0, DOWN = 1, RIGHT = 2, LEFT = 3, OK = 4, BACK = 5;
    static final int PRESS = 0, RELEASE = 1, SHORT = 2, LONG = 3, REPEAT = 4;

    static final int BG = 0xFF0E1013, SURFACE = 0xFF1B1E24, RAISED = 0xFF2A2E37, TEXT = 0xFFECEEF2, MUTED = 0xFF8B919C,
        ORANGE = 0xFFFF8200, INK = 0xFF000000;

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    BluetoothAdapter bt;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic tx;
    boolean ready, writing;
    // GATT allows one operation in flight; everything below is touched on the main thread only.
    final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    final ByteArrayOutputStream rx = new ByteArrayOutputStream();
    int cmdId = 1;
    ScanCallback scanCb;
    Screen screen;
    TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView title = text("Flipper Remote", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("Not connected", 14, MUTED);
        status.setPadding(0, dp(4), 0, dp(16));
        root.addView(status);

        screen = new Screen(this);
        LinearLayout frame = new LinearLayout(this);
        frame.setPadding(dp(10), dp(10), dp(10), dp(10));
        frame.setBackground(round(SURFACE, 16));
        frame.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        root.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        // D-pad with Back at bottom right, like the device.
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        pad.setPadding(0, dp(24), 0, dp(24));
        pad.addView(row(null, key("▲", UP), null));
        pad.addView(row(key("◀", LEFT), key("OK", OK), key("▶", RIGHT)));
        pad.addView(row(null, key("▼", DOWN), key("↩", BACK)));
        root.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(button("Connect", v -> connect()), new LinearLayout.LayoutParams(0, dp(48), 1));
        View gap = new View(this);
        bottom.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        bottom.addView(button("Forget", v -> { prefs.edit().remove("addr").apply(); disconnect(); say("Forgot Flipper. Tap Connect to search again"); }),
            new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bottom);
        setContentView(root);

        if (havePerms()) connect(); else say("Tap Connect to find your Flipper");
    }

    @Override protected void onPause() { super.onPause(); release(); }
    @Override protected void onDestroy() { super.onDestroy(); disconnect(); }

    // ---- Bluetooth ----

    String[] perms() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    boolean havePerms() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : perms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (havePerms()) connect(); else say("Bluetooth permission is needed to reach your Flipper");
    }

    void connect() {
        if (bt == null) { say("This phone has no Bluetooth"); return; }
        if (!havePerms()) { requestPermissions(perms(), 1); return; }
        if (!bt.isEnabled()) { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); say("Turn on Bluetooth, then tap Connect"); return; }
        disconnect();
        String addr = prefs.getString("addr", null);
        if (addr != null) { open(bt.getRemoteDevice(addr)); return; }
        for (BluetoothDevice d : bt.getBondedDevices()) if (isFlipper(d.getName())) { open(d); return; }
        scan();
    }

    static boolean isFlipper(String name) { return name != null && name.startsWith("Flipper "); }

    void scan() {
        final BluetoothLeScanner s = bt.getBluetoothLeScanner();
        if (s == null) { say("Bluetooth isn't ready, try again"); return; }
        say("Looking for your Flipper… (Settings › Bluetooth must be ON on the Flipper)");
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                String n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (n == null) n = r.getDevice().getName();
                if (isFlipper(n) && scanCb == this) { stopScan(); open(r.getDevice()); }
            }
        };
        s.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        ui.postDelayed(scanTimeout, 15000);
    }

    final Runnable scanTimeout = () -> { if (scanCb != null) { stopScan(); say("No Flipper found. Turn on Bluetooth on the Flipper and keep it near, then tap Connect"); } };

    void stopScan() {
        ui.removeCallbacks(scanTimeout);
        if (scanCb == null) return;
        BluetoothLeScanner s = bt.getBluetoothLeScanner();
        if (s != null) s.stopScan(scanCb);
        scanCb = null;
    }

    void open(BluetoothDevice d) {
        say("Connecting to " + (d.getName() != null ? d.getName() : "Flipper") + "…");
        gatt = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE) : d.connectGatt(this, false, cb);
    }

    void disconnect() {
        stopScan();
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
        ready = writing = false;
        writes.clear();
        rx.reset();
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int st, final int state) {
            ui.post(() -> {
                if (g != gatt) return;
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    say("Connected, setting up…");
                    if (!g.requestMtu(512)) g.discoverServices();   // bigger MTU = screen frames in fewer chunks
                    return;
                }
                boolean was = ready;
                disconnect();
                if (!was) prefs.edit().remove("addr").apply();      // stale address: search next time
                say(was ? "Disconnected. Tap Connect"
                        : "Couldn't connect. Check Bluetooth is on on the Flipper and the official Flipper app isn't connected to it");
            });
        }
        @Override public void onMtuChanged(final BluetoothGatt g, int mtu, int st) {
            ui.post(() -> { if (g == gatt) g.discoverServices(); });
        }
        @Override public void onServicesDiscovered(final BluetoothGatt g, int st) {
            ui.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(SERIAL);
                BluetoothGattCharacteristic r = s == null ? null : s.getCharacteristic(RX);
                tx = s == null ? null : s.getCharacteristic(TX);
                if (r == null || tx == null) { disconnect(); prefs.edit().remove("addr").apply(); say("That device isn't a Flipper Zero"); return; }
                g.setCharacteristicNotification(r, true);
                BluetoothGattDescriptor d = r.getDescriptor(CCCD);
                d.setValue((r.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);   // the Flipper requires pairing here; Android shows the PIN prompt itself
            });
        }
        @Override public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int st) {
            ui.post(() -> {
                if (g != gatt) return;
                if (st != BluetoothGatt.GATT_SUCCESS) {
                    g.getDevice().createBond();
                    disconnect();
                    say("Pair first: enter the PIN shown on the Flipper, then tap Connect");
                    return;
                }
                ready = true;
                prefs.edit().putString("addr", g.getDevice().getAddress()).apply();
                say("Connected to " + (g.getDevice().getName() != null ? g.getDevice().getName() : "Flipper"));
                send(START_SCREEN_STREAM, new byte[0]);
            });
        }
        @Override public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            ui.post(() -> { if (g == gatt) { writing = false; pump(); } });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {   // Android < 13
            byte[] v = c.getValue();
            if (v != null) received(g, v.clone());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {   // Android 13+
            received(g, v);
        }
    };

    void received(final BluetoothGatt g, final byte[] v) {
        ui.post(() -> { if (g == gatt) receive(v); });
    }

    // ---- RPC ----

    void input(int key, int type) { send(SEND_INPUT, new byte[]{0x08, (byte) key, 0x10, (byte) type}); }

    /** Wraps body as PB.Main{command_id, field=body}, length-prefixed, and queues it. */
    void send(int field, byte[] body) {
        if (!ready) return;
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        varint(m, 1 << 3); varint(m, cmdId++);
        varint(m, field << 3 | 2); varint(m, body.length); m.write(body, 0, body.length);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        varint(f, m.size());
        byte[] mb = m.toByteArray();
        f.write(mb, 0, mb.length);
        writes.add(f.toByteArray());
        pump();
    }

    void pump() {
        if (writing || writes.isEmpty() || gatt == null || tx == null) return;
        byte[] v = writes.poll();
        tx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        tx.setValue(v);
        writing = gatt.writeCharacteristic(tx);
        if (!writing) { writes.addFirst(v); ui.postDelayed(this::pump, 30); }   // stack busy; retry
    }

    void receive(byte[] v) {
        rx.write(v, 0, v.length);
        byte[] d = rx.toByteArray();
        int pos = 0;
        while (true) {
            int[] p = {pos};
            long len = varint(d, p, d.length);
            if (len < 0 || p[0] + len > d.length) break;   // incomplete message, wait for more
            main(d, p[0], (int) (p[0] + len));
            pos = (int) (p[0] + len);
        }
        rx.reset();
        rx.write(d, pos, d.length - pos);
    }

    void main(byte[] d, int from, int to) {
        int[] p = {from};
        while (p[0] < to) {
            long k = varint(d, p, to);
            if (k < 0) return;
            int field = (int) (k >>> 3), wt = (int) (k & 7);
            if (wt == 0) varint(d, p, to);
            else if (wt == 1) p[0] += 8;
            else if (wt == 5) p[0] += 4;
            else if (wt == 2) {
                int l = (int) varint(d, p, to);
                if (field == SCREEN_FRAME) frame(d, p[0], Math.min(p[0] + l, to));
                p[0] += l;
            } else return;
        }
    }

    /** PB_Gui.ScreenFrame{bytes data = 1; ScreenOrientation orientation = 2;} */
    void frame(byte[] d, int from, int to) {
        int[] p = {from};
        int dataAt = -1, dataLen = 0, orient = 0;
        while (p[0] < to) {
            long k = varint(d, p, to);
            if (k < 0) return;
            if ((k & 7) == 2) { int l = (int) varint(d, p, to); if (k >>> 3 == 1) { dataAt = p[0]; dataLen = l; } p[0] += l; }
            else if ((k & 7) == 0) { long v = varint(d, p, to); if (k >>> 3 == 2) orient = (int) v; }
            else return;
        }
        if (dataAt >= 0 && dataLen >= 1024 && dataAt + 1024 <= d.length) screen.set(d, dataAt, orient == 1);
    }

    static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) (v & 0x7F) | 0x80); v >>>= 7; }
        o.write((int) v);
    }

    /** Reads a varint at p[0], advancing it; -1 if the buffer ends first. */
    static long varint(byte[] d, int[] p, int end) {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (p[0] >= end) return -1;
            int b = d[p[0]++] & 0xFF;
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        return -1;
    }

    // ---- Buttons: PRESS, then SHORT on quick release or LONG + REPEATs while held, then RELEASE ----

    int held = -1;
    boolean longSent;
    final Runnable holdTick = new Runnable() {
        @Override public void run() {
            if (held < 0) return;
            if (!longSent) { longSent = true; input(held, LONG); } else input(held, REPEAT);
            ui.postDelayed(this, 150);
        }
    };

    void hold(int key) {
        release();
        held = key;
        longSent = false;
        buzz();
        input(key, PRESS);
        ui.postDelayed(holdTick, 500);
    }

    void release() {
        if (held < 0) return;
        ui.removeCallbacks(holdTick);
        input(held, RELEASE);
        if (!longSent) input(held, SHORT);
        held = -1;
    }

    void buzz() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.vibrate(15);
    }

    // ---- UI ----

    View key(String label, final int key) {
        final TextView b = text(label, label.length() > 1 ? 18 : 24, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(key == OK ? 0xFF3A2410 : RAISED, 40));
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { b.setAlpha(0.6f); hold(key); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { b.setAlpha(1f); release(); }
            return true;
        });
        return b;
    }

    View row(View a, View b, View c) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        for (View v : new View[]{a, b, c}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(80));
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            r.addView(v != null ? v : new View(this), lp);
        }
        return r;
    }

    TextView button(String label, View.OnClickListener l) {
        TextView b = text(label, 16, TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(RAISED, 12));
        b.setOnClickListener(l);
        return b;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    void say(String s) { status.setText(s); }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** The Flipper's 128x64 LCD. Frames are u8g2 pages: byte (y/8)*128+x, bit y%8 (LSB on top). */
    static class Screen extends View {
        final Bitmap bmp = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
        final int[] px = new int[128 * 64];
        final Paint paint = new Paint();
        final Rect dst = new Rect();

        Screen(Context c) {
            super(c);
            paint.setFilterBitmap(false);   // crisp pixels
            Arrays.fill(px, ORANGE);
            bmp.setPixels(px, 0, 128, 0, 0, 128, 64);
        }

        void set(byte[] d, int at, boolean flip) {
            for (int y = 0; y < 64; y++)
                for (int x = 0; x < 128; x++) {
                    boolean on = ((d[at + (y >> 3) * 128 + x] >> (y & 7)) & 1) != 0;
                    px[flip ? (63 - y) * 128 + (127 - x) : y * 128 + x] = on ? INK : ORANGE;
                }
            bmp.setPixels(px, 0, 128, 0, 0, 128, 64);
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, width / 2);
        }

        @Override protected void onDraw(Canvas c) {
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(bmp, null, dst, paint);
        }
    }
}

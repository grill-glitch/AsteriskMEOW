package org.asterisk.meow.poc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.asterisk.meow.Libmeow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MeowVpnService extends VpnService {
    private static final String TAG = "AsteriskMEOW";
    private static final String CHANNEL = "asterisk-meow";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "org.asterisk.meow.poc.START";
    public static final String ACTION_STOP = "org.asterisk.meow.poc.STOP";

    /** Last engine message, shown by the activity. */
    public static volatile String lastStatus = "not started";

    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn("stopped by user");
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        try {
            startVpn();
        } catch (Throwable t) {
            Log.e(TAG, "VPN start failed", t);
            stopVpn("start failed: " + t.getMessage());
        }
        return START_STICKY;
    }

    private void startVpn() throws Exception {
        File config = new File(getFilesDir(), "config.yaml");
        if (!config.exists()) {
            copyAsset("config.yaml", config);
        }

        Builder builder = new Builder()
                .setSession("AsteriskMEOW")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addAddress("fdfe:dcba:9876::1", 64)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("172.19.0.2")
                .setBlocking(false);
        // Keep the engine's own traffic (its DNS upstreams are protected
        // sockets anyway) out of the tunnel.
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "addDisallowedApplication: " + e);
        }

        tun = builder.establish();
        if (tun == null) {
            throw new IllegalStateException("VpnService.Builder.establish() returned null");
        }

        Libmeow.nativeInit();
        Libmeow.nativeSetProtector(this);
        Libmeow.nativeSetOwnerResolver(this);
        int fd = tun.detachFd();
        tun = null;

        int rc = Libmeow.nativeStart(config.getAbsolutePath(), fd, 1500);
        if (rc != 0) {
            throw new IllegalStateException("libmeow start: " + Libmeow.nativeLastError());
        }
        lastStatus = "TUN up · " + Libmeow.nativeVersion();
        Log.i(TAG, lastStatus);
    }

    private void stopVpn(String status) {
        lastStatus = status;
        Log.i(TAG, status);
        try {
            Libmeow.nativeStop();
            Libmeow.nativeClearProtector();
        } catch (Throwable t) {
            Log.w(TAG, "native stop: " + t);
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void copyAsset(String name, File out) throws Exception {
        try (InputStream in = getAssets().open(name);
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "AsteriskMEOW", NotificationManager.IMPORTANCE_LOW));
            }
        }
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("AsteriskMEOW")
                .setContentText("meow-rs VPN running")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onRevoke() {
        stopVpn("revoked by system");
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        try {
            Libmeow.nativeStop();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}

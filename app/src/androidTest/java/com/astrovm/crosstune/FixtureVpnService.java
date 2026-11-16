package com.astrovm.crosstune;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/** A real unmetered VPN for the dev app alone. Fixture HTTP requests are intercepted, so no traffic is forwarded. */
public final class FixtureVpnService extends VpnService {
    private ParcelFileDescriptor tunnel;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new IllegalStateException("This fixture is emulator-only");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tunnel == null) {
            try {
                tunnel = new Builder().setSession("Offline network fixture")
                        .addAddress("10.33.0.1", 32).addRoute("0.0.0.0", 0)
                        .addAllowedApplication("com.astrovm.crosstune.dev").setMetered(false).establish();
            } catch (PackageManager.NameNotFoundException error) {
                throw new IllegalStateException("The dev app is required", error);
            }
            Objects.requireNonNull(tunnel, "Accept the fixture VPN consent first");
        }
        NotificationManager manager = Objects.requireNonNull(getSystemService(NotificationManager.class));
        manager.createNotificationChannel(new NotificationChannel("network-fixture", "Offline network fixture", NotificationManager.IMPORTANCE_LOW));
        Notification notice = new Notification.Builder(this, "network-fixture").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Offline network fixture").build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else startForeground(101, notice);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (tunnel != null) tunnel.close();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            tunnel = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            super.onDestroy();
        }
    }
}

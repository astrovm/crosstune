package com.astrovm.crosstune;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** The test APK has its own UID, separate from the measured app. */
public final class FixtureVpnControl extends BroadcastReceiver {
    public static final String START = "com.astrovm.crosstune.fixture.VPN_START";
    public static final String STOP = "com.astrovm.crosstune.fixture.VPN_STOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new IllegalStateException("This fixture is emulator-only");
        }
        Intent service = new Intent(context, FixtureVpnService.class);
        if (START.equals(intent.getAction())) context.startForegroundService(service);
        else if (STOP.equals(intent.getAction())) context.stopService(service);
    }
}

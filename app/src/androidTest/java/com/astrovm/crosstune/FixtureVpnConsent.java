package com.astrovm.crosstune;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;

/** Consent runs in the test APK, before the measured app flow starts. */
public final class FixtureVpnConsent extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new IllegalStateException("This fixture is emulator-only");
        }
        Intent permission = VpnService.prepare(this);
        if (permission == null) finish();
        else startActivityForResult(permission, 1);
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        finish();
    }
}

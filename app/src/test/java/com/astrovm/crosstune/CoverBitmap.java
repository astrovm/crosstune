package com.astrovm.crosstune;

import android.graphics.Bitmap;
import android.os.Looper;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNativeBitmap;

/** Counts real JPEG encodes, including which ones block the screen thread. */
@Implements(value = Bitmap.class, minSdk = 26, isInAndroidSdk = false, callNativeMethodsByDefault = true)
public class CoverBitmap extends ShadowNativeBitmap {
    static final AtomicInteger encodes = new AtomicInteger();
    static final AtomicInteger mainEncodes = new AtomicInteger();
    static volatile Runnable before = () -> {};

    @Implementation
    protected static boolean nativeCompress(long bitmap, int format, int quality, OutputStream out, byte[] storage) {
        before.run();
        encodes.incrementAndGet();
        if (Looper.myLooper() == Looper.getMainLooper()) mainEncodes.incrementAndGet();
        return ShadowNativeBitmap.nativeCompress(bitmap, format, quality, out, storage);
    }
}

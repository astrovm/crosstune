package com.astrovm.crosstune;

import android.graphics.Bitmap;
import android.os.Looper;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowNativeBitmap;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import static org.robolectric.util.reflector.Reflector.reflector;

/** Counts real JPEG encodes, including which ones block the screen thread. */
@Implements(value = Bitmap.class, minSdk = 26, isInAndroidSdk = false, callNativeMethodsByDefault = true)
public class CoverBitmap extends ShadowNativeBitmap {
    static final AtomicInteger encodes = new AtomicInteger();
    static final AtomicInteger mainEncodes = new AtomicInteger();
    static volatile Runnable before = () -> {};
    static volatile String result = "not called";
    @RealObject private Bitmap bitmap;

    @Implementation
    protected boolean compress(Bitmap.CompressFormat format, int quality, OutputStream out) {
        before.run();
        encodes.incrementAndGet();
        if (Looper.myLooper() == Looper.getMainLooper()) mainEncodes.incrementAndGet();
        try {
            boolean ok = reflector(Original.class, bitmap).compress(format, quality, out);
            result = "compressed=" + ok;
            return ok;
        } catch (RuntimeException | Error failure) {
            result = failure.toString();
            throw failure;
        }
    }

    @ForType(Bitmap.class)
    interface Original {
        @Direct boolean compress(Bitmap.CompressFormat format, int quality, OutputStream out);
    }
}

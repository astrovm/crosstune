package com.astrovm.crosstune;

import android.content.res.Resources;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowResources;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import static org.robolectric.util.reflector.Reflector.reflector;

/** The resource-name API of an invented music app with a filled favorite icon. */
@Implements(Resources.class)
public class FixtureResourceNames extends ShadowResources {
    public static final String MUSIC_PACKAGE = "com.example.fixture.music";
    @RealObject private Resources resources;

    @Implementation
    protected String getResourceEntryName(int id) {
        if (id == R.drawable.ic_thumb_up) return "fixture_favorite_filled";
        return reflector(Original.class, resources).getResourceEntryName(id);
    }

    @ForType(Resources.class)
    interface Original {
        @Direct String getResourceEntryName(int id);
    }

    /** Records the requested owner, then supplies the invented app's fixture resources. */
    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    public static class Packages extends ShadowApplicationPackageManager {
        public static final List<String> requestedPackages = new ArrayList<>();

        @Implementation
        protected Resources getResourcesForApplication(String packageName) throws PackageManager.NameNotFoundException {
            requestedPackages.add(packageName);
            String resourcePackage = MUSIC_PACKAGE.equals(packageName)
                ? RuntimeEnvironment.getApplication().getPackageName() : packageName;
            return super.getResourcesForApplication(resourcePackage);
        }
    }
}

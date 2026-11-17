package com.astrovm.crosstune;

import android.content.res.Resources;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowResources;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;
import static org.robolectric.util.reflector.Reflector.reflector;

/** The resource-name API of an invented music app with a filled favorite icon. */
@Implements(Resources.class)
public class FixtureResourceNames extends ShadowResources {
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
}

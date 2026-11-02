package com.astrovm.crosstune

import android.app.AppOpsManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppOpsManager

/**
 * App ops as a phone has them, where Robolectric allows every one: capturing what other apps play
 * isn't allowed until Shizuku or root allows it, which needs a phone. Every test has it, from
 * robolectric.properties, so none asks Android for a projection it can't give.
 */
@Implements(AppOpsManager::class)
class PhoneAppOps : ShadowAppOpsManager() {
    @Implementation
    override fun checkOpNoThrow(op: String, uid: Int, packageName: String): Int =
        if (op == OneTapSetup.PROJECT_MEDIA_OP) AppOpsManager.MODE_DEFAULT else super.checkOpNoThrow(op, uid, packageName)
}

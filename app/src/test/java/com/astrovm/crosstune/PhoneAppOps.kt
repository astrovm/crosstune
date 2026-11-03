package com.astrovm.crosstune

import android.app.AppOpsManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowAppOpsManager

/**
 * App ops as a phone has them, where Robolectric allows every one: capturing what other apps play
 * isn't allowed until Shizuku or root allows it, which needs a phone. Every test has it, from
 * robolectric.properties, so none asks Android for a projection it can't give.
 */
@Implements(AppOpsManager::class)
class PhoneAppOps : ShadowAppOpsManager() {
    // By the op's number, which every check by name comes down to, as the checks by name are deprecated.
    @Implementation
    override fun checkOpNoThrow(op: Int, uid: Int, packageName: String?): Int =
        if (op == PROJECT_MEDIA) projectMedia else super.checkOpNoThrow(op, uid, packageName)

    companion object {
        /** The op's number, which Android keeps to itself. */
        private val PROJECT_MEDIA = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java).invoke(null, OneTapSetup.PROJECT_MEDIA_OP) as Int

        /** What Shizuku or root set it to; a test that sets it gets it back as a phone has it, after. */
        var projectMedia = AppOpsManager.MODE_DEFAULT

        /** Robolectric runs only this shadow's own resetter, so it runs Robolectric's too. */
        @Resetter
        @JvmStatic
        fun resetAll() {
            projectMedia = AppOpsManager.MODE_DEFAULT
            ShadowAppOpsManager.reset()
        }
    }
}

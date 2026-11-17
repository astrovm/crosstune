package com.astrovm.crosstune

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import org.robolectric.shadows.ShadowActivity
import org.robolectric.util.ReflectionHelpers

/** Answers the permission [asked] with [result], as Android would, through the activity's results. */
internal fun ComponentActivity.answer(asked: ShadowActivity.PermissionsRequest, result: Int) {
    val answer = Intent()
        .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, asked.requestedPermissions)
        .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, IntArray(asked.requestedPermissions.size) { result })
    // Android clears this when the answer comes back, and a second ask waits for it; going through the
    // registry skips that step, so without this the next ask is turned away on the spot.
    ReflectionHelpers.setField(Activity::class.java, this, "mHasCurrentPermissionsRequest", false)
    activityResultRegistry.dispatchResult(asked.requestCode, Activity.RESULT_OK, answer)
}

package com.astrovm.crosstune

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import org.robolectric.shadows.ShadowActivity

/** Answers the permission [asked] with [result], as Android would, through the activity's results. */
internal fun ComponentActivity.answer(asked: ShadowActivity.PermissionsRequest, result: Int) {
    val answer = Intent()
        .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, asked.requestedPermissions)
        .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, IntArray(asked.requestedPermissions.size) { result })
    activityResultRegistry.dispatchResult(asked.requestCode, Activity.RESULT_OK, answer)
}

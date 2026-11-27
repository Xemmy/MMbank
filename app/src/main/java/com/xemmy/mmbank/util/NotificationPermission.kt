package com.xemmy.mmbank.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether a notification can be posted right now. Reminder workers check this
 * BEFORE recording a reminder as "sent": the helpers silently skip posting
 * without the permission, and a recorded-but-never-shown reminder would never
 * fire after the user grants it later.
 */
object NotificationPermission {
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

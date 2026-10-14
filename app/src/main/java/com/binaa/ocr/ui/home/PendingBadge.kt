package com.binaa.ocr.ui.home

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.binaa.ocr.MainActivity
import com.binaa.ocr.R

/**
 * الرقم على أيقونة التطبيق = عدد الدفعات «للإدخال». في Android لا يظهر الرقم إلا مع إشعار، فهذا إشعار صامت واحد
 * (بلا صوت ولا اهتزاز) يُحدَّث مع كل تغيير، ويُزال حين تفرغ القائمة. محلي بالكامل.
 */
object PendingBadge {

    private const val CHANNEL = "pending"
    private const val ID = 1

    fun update(context: Context, pending: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (pending <= 0) { nm.cancel(ID); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.badge_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(true); setSound(null, null); enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.resources.getQuantityString(R.plurals.badge_text, pending, pending)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setNumber(pending)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ID, n) }
    }
}

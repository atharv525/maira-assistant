package com.maira.assistant

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** Optional: lets Maira read the notifications currently in the shade ("notification padho"). */
class MairaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() { super.onListenerConnected(); instance = this }
    override fun onListenerDisconnected() { instance = null; super.onListenerDisconnected() }
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun current(): List<NotifItem> {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return emptyList()
        val pm = packageManager
        return list.filter { sbn ->
            sbn.packageName != packageName && !sbn.isOngoing &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.mapNotNull { sbn ->
            val ex = sbn.notification.extras ?: return@mapNotNull null
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(". ")
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: lines
                ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()).orEmpty()
            if (title.isBlank() && text.isBlank()) return@mapNotNull null
            val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
            NotifItem(app, title, text, sbn.postTime)
        }
    }

    companion object {
        @Volatile var instance: MairaNotificationListener? = null
        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}

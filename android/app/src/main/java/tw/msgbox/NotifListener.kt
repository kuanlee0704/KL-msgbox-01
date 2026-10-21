package tw.msgbox

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 通知監聽服務: 使用者授權「通知存取」後, 新通知交給 MsgCapture 收錄、通知被清除則標為已讀。 */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            MsgCapture.posted(applicationContext, sbn)
        } catch (e: Exception) {
            AppLog.w("MSGDB", "收錄通知失敗: $e")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            MsgCapture.removed(applicationContext, sbn)
        } catch (_: Exception) {
        }
    }
}

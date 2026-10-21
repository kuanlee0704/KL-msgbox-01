package tw.msgbox

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * 通知 → 手機訊息資料庫。由 NotifListener 直接呼叫, 不需要連著控制器、也不需要橋接服務在執行,
 * 只要「通知存取」已授權就會收錄。過濾規則和轉發到控制器的相同 (常駐/進度/媒體/群組摘要/本 App 都不收)。
 */
object MsgCapture {
    private val last = HashMap<String, String>()
    private val skipCats = arrayOf(
        Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_STOPWATCH
    )

    /** 常見的通訊 / 社群 App (其餘靠通知本身的「訊息」分類判斷) */
    val SOCIAL = setOf(
        "jp.naver.line.android", "com.facebook.orca", "com.facebook.mlite", "com.facebook.katana", "com.instagram.android",
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.discord",
        "com.Slack", "com.microsoft.teams", "com.tencent.mm", "com.kakao.talk", "com.viber.voip", "com.skype.raider",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.snapchat.android",
        "com.twitter.android", "com.zhiliaoapp.musically", "com.google.android.apps.dynamite"
    )

    /** 去掉標題尾巴的「(3 則訊息)」「(2 new messages)」之類的計數, 讓同一個人的對話歸在一起 */
    private val countTail = Regex("\\s*[\\(（]\\s*\\d+\\s*[^\\)）]*[\\)）]\\s*$")

    private fun cleanConv(s: String): String {
        val r = countTail.replace(s, "").trim()
        return if (r.isEmpty()) s.trim() else r
    }

    private fun key(sbn: StatusBarNotification) = sbn.packageName + ":" + sbn.id + ":" + (sbn.tag ?: "")

    private fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    @Synchronized
    fun posted(ctx: Context, sbn: StatusBarNotification) {
        if (!Prefs.msgDb(ctx)) return
        val pkg = sbn.packageName
        if (pkg == ctx.packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category != null && n.category in skipCats) return
        if (!Prefs.msgDbAll(ctx) && !Prefs.msgAllow(ctx).contains(pkg)) return
        val ex = n.extras ?: return
        if ((ex.getString(Notification.EXTRA_TEMPLATE) ?: "").contains("MediaStyle")) return
        val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT) ?: "")
            .toString().trim()
        if (title.isEmpty() && text.isEmpty()) return
        val k = key(sbn)
        val sig = title + "\u0000" + text
        if (last[k] == sig) return                       // 同一則通知被重複更新, 不重複收錄
        last[k] = sig
        if (last.size > 300) last.clear()
        val convTitle = (ex.getCharSequence("android.conversationTitle") ?: "").toString().trim()
        val template = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val social = n.category == Notification.CATEGORY_MESSAGE || template.contains("MessagingStyle") || pkg in SOCIAL
        val conv = cleanConv(if (convTitle.isNotEmpty()) convTitle else title).take(80)
        MsgDb.insert(ctx, pkg, label(ctx, pkg).take(24), title.take(120), text.take(2000), k, conv, social)
        Widgets.updateAll(ctx)
    }

    @Synchronized
    fun removed(ctx: Context, sbn: StatusBarNotification) {
        if (!Prefs.msgDb(ctx)) return
        val k = key(sbn)
        if (last.remove(k) == null) return
        MsgDb.markReadByKey(ctx, k)
        Widgets.updateAll(ctx)
    }
}

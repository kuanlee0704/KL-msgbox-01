package tw.msgbox

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 桌面小工具 (2x2): 顯示最新幾則訊息與未讀數, 點開訊息資料庫。
 * 新訊息、已讀、刪除時由 Widgets.updateAll() 更新。
 */
object Widgets {
    private var lastKey = ""

    fun updateAll(ctx: Context, force: Boolean = false) {
        try {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, MsgWidget::class.java))
            if (ids.isEmpty()) return
            val unread = MsgDb.unread(app)
            val top = MsgDb.query(app, "", null, false, 4)
            val key = unread.toString() + "|" + top.joinToString("|") { it.id.toString() + (if (it.read) "r" else "u") }
            if (!force && key == lastKey) return
            lastKey = key
            mgr.updateAppWidget(ids, build(app, unread, top))
        } catch (e: Exception) {
            AppLog.w("WIDGET", "更新小工具失敗: $e")
        }
    }

    private fun openMsgs(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun build(ctx: Context, unread: Int, top: List<Msg>): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_msgs)
        rv.setTextViewText(R.id.m_head, if (unread > 0) "手機訊息  ·  $unread 則未讀" else "手機訊息")
        val ids = intArrayOf(R.id.m_r1, R.id.m_r2, R.id.m_r3, R.id.m_r4)
        for ((n, id) in ids.withIndex()) {
            val m = top.getOrNull(n)
            if (m == null) {
                rv.setTextViewText(id, if (n == 0) "還沒有訊息 (需開啟通知存取並選擇 App)" else "")
            } else {
                val line = (if (m.read) "" else "● ") + m.app + "  " + (if (m.title.isNotEmpty()) m.title + ": " else "") + m.body.replace('\n', ' ')
                rv.setTextViewText(id, line)
            }
        }
        rv.setOnClickPendingIntent(R.id.m_root, openMsgs(ctx))
        return rv
    }
}

class MsgWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Widgets.updateAll(context, true)
    }
}

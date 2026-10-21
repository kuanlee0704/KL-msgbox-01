package tw.msgbox

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 手機訊息資料庫 (三層): App 清單 → 該 App 的對話 (社群/通訊 App 依聯絡人或群組分開) → 對話內的訊息串。
 * 訊息串依時間由舊到新排列, 不同日期用日期標題分隔, 同一天相隔超過 30 分鐘用「間隔」分隔線,
 * 開啟時會在第一則新訊息前標示「新訊息」。不用啟動連線、也不用打開原本的 App。
 * 預設永遠保留訊息, 只有你按刪除才會刪。
 */
class MessagesActivity : Activity() {
    private val C_BG = 0xFFF2F4F7.toInt()
    private val C_CARD = 0xFFFFFFFF.toInt()
    private val C_PRIMARY = 0xFF1565C0.toInt()
    private val C_TEXT = 0xFF212121.toInt()
    private val C_SUB = 0xFF616161.toInt()
    private val C_BAD = 0xFFC62828.toInt()
    private val C_LINE = 0xFFB0BEC5.toInt()
    private val GAP_MS = 30 * 60 * 1000L

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnBack: Button
    private lateinit var btnRead: Button
    private lateinit var btnDel: Button
    private lateinit var btnSet: Button
    private lateinit var btnMode: Button
    private lateinit var sv: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var hintCard: LinearLayout

    private var curPkg: String? = null
    private var curConv: String? = null
    private var curApp = ""
    private var limit = 150
    private var seenVer = -1
    private var newFrom = Long.MAX_VALUE          // 開啟對話時第一則未讀訊息的編號 (用來畫「新訊息」分隔)
    private var toBottom = false
    private val expanded = HashSet<Long>()

    private val poll = object : Runnable {
        override fun run() {
            if (seenVer != MsgDb.version) refresh()
            h.postDelayed(this, 1500)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int = 14): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun tv(parent: ViewGroup, t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        parent.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun smallBtn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 13f
        b.setOnClickListener { onClick() }
        return b
    }

    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(-1, -2)
        p.setMargins(0, dp(top), 0, dp(bottom))
        return p
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.setPadding(dp(14), dp(26), dp(14), 0)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        btnBack = smallBtn("←") { goBack() }
        head.addView(btnBack)
        tvTitle = TextView(this)
        tvTitle.textSize = 20f
        tvTitle.setTextColor(C_TEXT)
        tvTitle.setTypeface(null, Typeface.BOLD)
        tvTitle.maxLines = 1
        tvTitle.ellipsize = TextUtils.TruncateAt.END
        head.addView(tvTitle, LinearLayout.LayoutParams(0, -2, 1f))
        btnRead = smallBtn("已讀") { markAllRead() }
        head.addView(btnRead)
        btnSet = smallBtn("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }
        head.addView(btnSet)
        btnDel = smallBtn("刪除") { confirmDelete() }
        head.addView(btnDel)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        tvHint = tv(root, "", 12f, C_SUB)

        hintCard = LinearLayout(this)
        hintCard.orientation = LinearLayout.VERTICAL
        hintCard.background = bg(0xFFFFF3E0.toInt())
        hintCard.setPadding(dp(14), dp(10), dp(14), dp(10))
        root.addView(hintCard, lp(8, 0))

        etSearch = EditText(this)
        etSearch.hint = "搜尋 App / 對話 / 內容"
        etSearch.setSingleLine(true)
        etSearch.textSize = 14f
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                limit = 150
                refresh()
            }
        })
        root.addView(etSearch, LinearLayout.LayoutParams(-1, -2))

        btnMode = smallBtn("") { cycleMode() }
        root.addView(btnMode, LinearLayout.LayoutParams(-2, -2))

        sv = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(6), 0, dp(24))
        sv.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // 從小工具/通知帶特定 App 進來
        curPkg = intent?.getStringExtra("pkg")
    }

    override fun onResume() {
        super.onResume()
        refresh()
        h.postDelayed(poll, 1500)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(poll)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (etSearch.text.isNotEmpty()) {
            etSearch.setText("")
            return
        }
        if (curConv != null || curPkg != null) goBack() else super.onBackPressed()
    }

    private fun goBack() {
        if (curConv != null) {
            curConv = null
        } else {
            curPkg = null
        }
        limit = 150
        toBottom = false
        refresh()
    }

    // ---------------- 狀態與提示 ----------------
    private fun notifAccessOn(): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    } catch (_: Exception) {
        true
    }

    private fun updateHint() {
        hintCard.removeAllViews()
        var shown = false
        if (!notifAccessOn()) {
            shown = true
            tv(hintCard, "尚未開啟「通知存取」, 無法收錄訊息。", 13f, C_TEXT)
            hintCard.addView(smallBtn("前往開啟") {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                }
            }, LinearLayout.LayoutParams(-2, -2))
        } else if (!Prefs.msgDb(this)) {
            shown = true
            tv(hintCard, "訊息收錄已關閉 (可到右上角 ⚙ 設定開啟)。", 13f, C_TEXT)
        } else if (!Prefs.msgDbAll(this) && Prefs.msgAllow(this).isEmpty()) {
            shown = true
            tv(hintCard, "還沒選擇要收錄哪些 App。也可以到 ⚙ 設定開啟「收錄所有 App」。", 13f, C_TEXT)
            hintCard.addView(smallBtn("選擇 App") {
                AppPicker.multi(this, "收錄這些 App 的通知", Prefs.msgAllow(this)) {
                    Prefs.setMsgAllow(this, it)
                    refresh()
                }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        hintCard.visibility = if (shown && curPkg == null) View.VISIBLE else View.GONE
    }

    // ---------------- 時間格式 ----------------
    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance()
        x.timeInMillis = a
        val y = Calendar.getInstance()
        y.timeInMillis = b
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    private fun fmtTime(ts: Long): String {
        val f = if (sameDay(ts, System.currentTimeMillis())) SimpleDateFormat("HH:mm", Locale.getDefault())
        else SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        return f.format(Date(ts))
    }

    private fun dayLabel(ts: Long): String {
        val now = System.currentTimeMillis()
        if (sameDay(ts, now)) return "今天"
        if (sameDay(ts, now - 24L * 3600L * 1000L)) return "昨天"
        return SimpleDateFormat("yyyy/MM/dd EEEE", Locale.getDefault()).format(Date(ts))
    }

    private fun gapText(ms: Long): String {
        val m = ms / 60000
        return if (m < 60) "$m 分鐘後" else if (m < 24 * 60) "${m / 60} 小時${if (m % 60 > 0) " ${m % 60} 分" else ""}後" else "${m / (24 * 60)} 天後"
    }

    // ---------------- 主要重繪 ----------------
    private fun refresh() {
        seenVer = MsgDb.version
        updateHint()
        val q = etSearch.text.toString().trim()
        val total = MsgDb.total(this)
        val unread = MsgDb.unread(this)
        val pkg = curPkg
        val conv = curConv
        list.removeAllViews()
        btnMode.visibility = View.GONE
        btnBack.visibility = if (pkg != null || conv != null) View.VISIBLE else View.GONE
        btnSet.visibility = if (pkg == null && conv == null) View.VISIBLE else View.GONE
        if (pkg != null && curApp.isEmpty()) curApp = MsgDb.apps(this).firstOrNull { it.pkg == pkg }?.app ?: pkg
        tvTitle.text = when {
            conv != null -> conv
            pkg != null -> curApp
            else -> "手機訊息" + if (unread > 0) "  ($unread)" else ""
        }
        tvHint.text = "共 $total 則 · 只存在這支手機 · 預設永遠保留, 只有你刪才會刪"
        btnRead.text = "全部已讀"
        btnDel.text = if (conv != null) "刪除對話" else if (pkg != null) "刪除此 App" else "清空"

        if (q.isNotEmpty()) {
            renderSearch(q, pkg)
        } else if (conv != null && pkg != null) {
            renderThread(pkg, conv)
        } else if (pkg != null) {
            renderApp(pkg)
        } else {
            renderApps()
        }
    }

    private fun empty(t: String) {
        val v = tv(list, t, 14f, C_SUB)
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(40), 0, 0)
    }

    private fun box(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = bg(C_CARD)
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        return c
    }

    private fun badge(n: Int): String = if (n > 0) "  ● $n" else ""

    // 第 1 層: App 清單
    private fun renderApps() {
        val apps = MsgDb.apps(this)
        if (apps.isEmpty()) {
            empty("還沒有收錄任何訊息")
            return
        }
        for (a in apps) {
            val c = box()
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            val name = TextView(this)
            name.text = a.app + badge(a.unread)
            name.textSize = 16f
            name.setTextColor(if (a.unread > 0) C_BAD else C_TEXT)
            name.setTypeface(null, Typeface.BOLD)
            top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val t = TextView(this)
            t.text = fmtTime(a.lastTs)
            t.textSize = 12f
            t.setTextColor(C_SUB)
            top.addView(t)
            c.addView(top, LinearLayout.LayoutParams(-1, -2))
            tv(c, a.total.toString() + " 則" + if (isGrouped(a.pkg, a.social)) " · 依對話分組" else "", 12f, C_SUB)
            c.setOnClickListener {
                curPkg = a.pkg
                curApp = a.app
                curConv = null
                limit = 150
                refresh()
            }
            c.setOnLongClickListener {
                openApp(a.pkg)
                true
            }
            list.addView(c, lp(0, 8))
        }
    }

    private fun isGrouped(pkg: String, social: Boolean): Boolean {
        val m = Prefs.convMode(this, pkg)
        return m == 1 || (m == 0 && (social || pkg in MsgCapture.SOCIAL))
    }

    private fun cycleMode() {
        val pkg = curPkg ?: return
        Prefs.setConvMode(this, pkg, (Prefs.convMode(this, pkg) + 1) % 3)
        refresh()
    }

    // 第 2 層: 某個 App (社群 → 對話清單; 其他 → 依時間的訊息)
    private fun renderApp(pkg: String) {
        val social = MsgDb.apps(this).firstOrNull { it.pkg == pkg }?.social ?: false
        val mode = Prefs.convMode(this, pkg)
        btnMode.visibility = View.VISIBLE
        btnMode.text = "依對話分組: " + (if (mode == 0) "自動" else if (mode == 1) "開" else "關") + " (點一下切換)"
        if (isGrouped(pkg, social)) {
            val convs = MsgDb.convs(this, pkg)
            if (convs.isEmpty()) {
                empty("沒有訊息")
                return
            }
            for (cv in convs) {
                val last = MsgDb.query(this, "", pkg, false, 1, cv.conv).firstOrNull()
                val c = box()
                val top = LinearLayout(this)
                top.orientation = LinearLayout.HORIZONTAL
                val name = TextView(this)
                name.text = cv.conv + badge(cv.unread)
                name.textSize = 16f
                name.setTextColor(if (cv.unread > 0) C_BAD else C_TEXT)
                name.setTypeface(null, Typeface.BOLD)
                name.maxLines = 1
                name.ellipsize = TextUtils.TruncateAt.END
                top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                val t = TextView(this)
                t.text = fmtTime(cv.lastTs)
                t.textSize = 12f
                t.setTextColor(C_SUB)
                top.addView(t)
                c.addView(top, LinearLayout.LayoutParams(-1, -2))
                if (last != null) {
                    val p = tv(c, last.body, 13f, C_SUB)
                    p.maxLines = 1
                    p.ellipsize = TextUtils.TruncateAt.END
                }
                tv(c, cv.total.toString() + " 則", 11f, C_SUB)
                c.setOnClickListener { openThread(pkg, cv.conv) }
                c.setOnLongClickListener {
                    AlertDialog.Builder(this).setMessage("確定刪除與「" + cv.conv + "」的所有訊息?").setNegativeButton("取消", null)
                        .setPositiveButton("刪除") { _, _ ->
                            MsgDb.deleteConv(this, pkg, cv.conv)
                            Widgets.updateAll(this)
                            refresh()
                        }.show()
                    true
                }
                list.addView(c, lp(0, 8))
            }
        } else {
            val rows = MsgDb.query(this, "", pkg, false, limit + 1)
            if (rows.isEmpty()) {
                empty("沒有訊息")
                return
            }
            for (m in rows.take(limit)) list.addView(flatCard(m, false), lp(0, 8))
            moreButton(rows.size > limit)
        }
    }

    private fun moreButton(show: Boolean) {
        if (!show) return
        list.addView(smallBtn("載入更多") {
            limit += 150
            refresh()
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openThread(pkg: String, conv: String) {
        curPkg = pkg
        curConv = conv
        if (curApp.isEmpty()) curApp = MsgDb.apps(this).firstOrNull { it.pkg == pkg }?.app ?: pkg
        limit = 150
        expanded.clear()
        // 記下第一則未讀, 再把整個對話標為已讀; 畫面上用「新訊息」分隔線標出新舊
        val firstNew = MsgDb.query(this, "", pkg, true, 5000, conv, true).firstOrNull()
        newFrom = firstNew?.id ?: Long.MAX_VALUE
        MsgDb.markConvRead(this, pkg, conv)
        Widgets.updateAll(this)
        toBottom = true
        etSearch.setText("")
        refresh()
    }

    // 第 3 層: 對話內的訊息串 (舊 → 新, 底部是最新)
    private fun renderThread(pkg: String, conv: String) {
        val rows = MsgDb.query(this, "", pkg, false, limit + 1, conv, true)
        if (rows.isEmpty()) {
            empty("沒有訊息")
            return
        }
        val shown = if (rows.size > limit) rows.drop(1) else rows
        if (rows.size > limit) {
            list.addView(smallBtn("載入更早的訊息") {
                limit += 150
                toBottom = false
                refresh()
            }, LinearLayout.LayoutParams(-1, -2))
        }
        var prev: Msg? = null
        var newDrawn = false
        for (m in shown) {
            val p = prev
            if (p == null || !sameDay(p.ts, m.ts)) {
                dayHeader(dayLabel(m.ts))
            } else if (m.ts - p.ts >= GAP_MS) {
                gapLine("間隔 " + gapText(m.ts - p.ts))
            }
            if (!newDrawn && m.id >= newFrom) {
                newDrawn = true
                newLine()
            }
            list.addView(bubble(m, conv), lp(0, 6))
            prev = m
        }
        if (toBottom) {
            toBottom = false
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dayHeader(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 12f
        v.setTextColor(Color.WHITE)
        v.background = bg(0xFF78909C.toInt(), 12)
        v.setPadding(dp(14), dp(4), dp(14), dp(4))
        v.gravity = Gravity.CENTER
        val p = LinearLayout.LayoutParams(-2, -2)
        p.gravity = Gravity.CENTER_HORIZONTAL
        p.setMargins(0, dp(14), 0, dp(8))
        list.addView(v, p)
    }

    private fun gapLine(t: String) = divider(t, C_SUB)

    private fun newLine() = divider("以下為新訊息", C_BAD)

    private fun divider(t: String, color: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        fun line() {
            val l = View(this)
            l.setBackgroundColor(color)
            val p = LinearLayout.LayoutParams(0, dp(1), 1f)
            row.addView(l, p)
        }
        line()
        val v = TextView(this)
        v.text = t
        v.textSize = 11f
        v.setTextColor(color)
        v.setPadding(dp(8), 0, dp(8), 0)
        row.addView(v, LinearLayout.LayoutParams(-2, -2))
        line()
        list.addView(row, lp(8, 8))
    }

    private fun bubble(m: Msg, conv: String): View {
        val c = box()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val who = TextView(this)
        who.text = if (m.title.isNotEmpty() && m.title != conv) m.title else ""
        who.textSize = 12f
        who.setTextColor(C_PRIMARY)
        who.setTypeface(null, Typeface.BOLD)
        top.addView(who, LinearLayout.LayoutParams(0, -2, 1f))
        val t = TextView(this)
        t.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts))
        t.textSize = 11f
        t.setTextColor(C_SUB)
        top.addView(t)
        c.addView(top, LinearLayout.LayoutParams(-1, -2))
        tv(c, m.body.ifEmpty { m.title }, 15f, C_TEXT)
        c.setOnLongClickListener {
            messageMenu(m)
            true
        }
        return c
    }

    // 沒有分組的 App / 搜尋結果: 一般卡片
    private fun flatCard(m: Msg, showApp: Boolean): View {
        val c = box()
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val app = TextView(this)
        val label = if (showApp) m.app + (if (m.title.isNotEmpty()) " · " + m.title.take(20) else "") else ""
        app.text = (if (m.read) "" else "● ") + label
        app.textSize = 12f
        app.setTextColor(if (m.read) C_SUB else C_BAD)
        app.setTypeface(null, Typeface.BOLD)
        top.addView(app, LinearLayout.LayoutParams(0, -2, 1f))
        val time = TextView(this)
        time.text = fmtTime(m.ts)
        time.textSize = 12f
        time.setTextColor(C_SUB)
        top.addView(time)
        c.addView(top, LinearLayout.LayoutParams(-1, -2))
        if (!showApp && m.title.isNotEmpty()) tv(c, m.title, 15f, C_TEXT, !m.read)
        val body = tv(c, m.body, 14f, C_TEXT)
        if (!expanded.contains(m.id)) {
            body.maxLines = 3
            body.ellipsize = TextUtils.TruncateAt.END
        }
        c.setOnClickListener {
            if (showApp) {
                openThread(m.pkg, convOf(m))
                return@setOnClickListener
            }
            if (expanded.contains(m.id)) expanded.remove(m.id) else expanded.add(m.id)
            if (!m.read) {
                MsgDb.markRead(this, m.id)
                Widgets.updateAll(this)
            }
            refresh()
        }
        c.setOnLongClickListener {
            messageMenu(m)
            true
        }
        return c
    }

    private fun convOf(m: Msg): String = if (m.conv.isNotEmpty()) m.conv else m.title

    // 搜尋結果: 跨對話的訊息, 點一則會跳到該對話
    private fun renderSearch(q: String, pkg: String?) {
        val rows = MsgDb.query(this, q, pkg, false, limit + 1)
        if (rows.isEmpty()) {
            empty("沒有符合的訊息")
            return
        }
        for (m in rows.take(limit)) list.addView(flatCard(m, true), lp(0, 8))
        moreButton(rows.size > limit)
    }

    private fun messageMenu(m: Msg) {
        val items = arrayOf("複製內容", "開啟「" + m.app + "」", "刪除這則")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("訊息", (if (m.title.isNotEmpty()) m.title + "\n" else "") + m.body))
                    Toast.makeText(this, "已複製", Toast.LENGTH_SHORT).show()
                }
                1 -> openApp(m.pkg)
                else -> {
                    AlertDialog.Builder(this).setMessage("確定刪除這則訊息?").setNegativeButton("取消", null)
                        .setPositiveButton("刪除") { _, _ ->
                            MsgDb.delete(this, m.id)
                            Widgets.updateAll(this)
                            refresh()
                        }.show()
                }
            }
        }.show()
    }

    private fun openApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) startActivity(launch) else Toast.makeText(this, "找不到這個 App", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 已讀 / 刪除 (依目前所在層級) ----------------
    private fun markAllRead() {
        val pkg = curPkg
        val conv = curConv
        if (pkg != null && conv != null) MsgDb.markConvRead(this, pkg, conv) else MsgDb.markAllRead(this, pkg)
        Widgets.updateAll(this)
        refresh()
    }

    private fun confirmDelete() {
        val pkg = curPkg
        val conv = curConv
        val msg = when {
            conv != null -> "確定刪除與「$conv」的所有訊息?"
            pkg != null -> "確定刪除「$curApp」的所有訊息?"
            else -> "確定刪除所有已收錄的訊息?"
        }
        AlertDialog.Builder(this).setMessage(msg).setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ ->
                if (pkg != null && conv != null) {
                    MsgDb.deleteConv(this, pkg, conv)
                    curConv = null
                } else {
                    MsgDb.clear(this, pkg)
                    curPkg = null
                }
                expanded.clear()
                Widgets.updateAll(this)
                refresh()
            }.show()
    }
}

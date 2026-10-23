package tw.msgbox

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 手機訊息資料庫 (三層): App 清單 → 該 App 的對話 (社群/通訊 App 依聯絡人或群組分開) → 對話內的訊息串。
 * 首頁最上方是「釘選」的對話; 釘選的 App 排在最前。長按對話/訊息可釘選或「忽略此來源」,
 * 被忽略的來源不會顯示也不再收錄, 可在該 App 的「此 App 設定 → 已忽略的來源」找回。
 *
 * 版面: 標題列 = 返回 + 完整標題 (最多兩行) + ⋮ 選單, 所有操作收進選單, 標題不會再被按鈕擋住。
 * 效能: 所有資料庫讀寫都在背景執行緒, 讀完才回主執行緒畫畫面; 資料變動由 MsgDb 通知, 不再定時輪詢。
 */
class MessagesActivity : Activity() {
    private val C_PRIMARY = Ui.PRIMARY
    private val C_TEXT = Ui.TEXT
    private val C_SUB = Ui.SUB
    private val C_BAD = Ui.BAD
    private val GAP_MS = 30 * 60 * 1000L

    private val h = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var tvTitle: TextView
    private lateinit var tvSub: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnGear: TextView
    private lateinit var btnMenu: TextView
    private lateinit var searchBar: LinearLayout
    private lateinit var btnClear: TextView
    private lateinit var appBar: LinearLayout
    private lateinit var tvAppSet: TextView
    private lateinit var sv: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var hintCard: LinearLayout

    private var curPkg: String? = null
    private var curConv: String? = null
    private var curApp = ""
    private var limit = 150
    @Volatile private var newFrom = Long.MAX_VALUE      // 開啟對話時第一則未讀訊息的編號 (用來畫「新訊息」分隔)
    private var openingThread = false                   // 下一次載入是「剛打開對話」: 要記下第一則未讀並標為已讀
    private var toBottom = false
    private var gen = 0                                  // 載入世代: 太舊的結果丟掉
    private var lastIgnoredN = 0
    private var lastAppPinned = false
    private var lastConvPinned = false
    private var shownKey = ""                            // 目前畫面顯示的是哪一頁 (換頁才捲回頂端)
    private var shownThread = false
    private val expanded = HashSet<Long>()

    /** 背景載入的結果 (畫面只依這份資料重繪, 不再碰資料庫) */
    private class Snap(
        val q: String,
        val pkg: String?,
        val conv: String?,
        val total: Int,
        val unread: Int,
        val grouped: Boolean = false,
        val apps: List<AppCount> = emptyList(),
        val pinned: List<PinnedConv> = emptyList(),
        val convs: List<ConvCount> = emptyList(),
        val rows: List<Msg> = emptyList(),
        val ignoredN: Int = 0,
        val appPinned: Boolean = false,
        val convPinned: Boolean = false
    )

    private val dbListener: () -> Unit = {
        h.removeCallbacks(refreshRun)
        h.postDelayed(refreshRun, 200)                   // 連續變動合併成一次重繪
    }
    private val refreshRun = Runnable { refresh() }
    private val searchRun = Runnable {
        limit = 150
        refresh()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun lp(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(-1, -2)
        p.setMargins(0, dp(top), 0, dp(bottom))
        return p
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

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** 資料庫寫入/讀取一律丟到背景 (單一執行緒, 保持先後順序) */
    private fun io(f: () -> Unit) {
        exec.execute {
            try {
                f()
            } catch (e: Exception) {
                AppLog.e("MSGDB", "資料庫操作失敗: $e")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        Ui.styleWindow(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.setPadding(dp(12), dp(4), dp(12), 0)
        root.isFocusableInTouchMode = true                // 開啟時不要讓搜尋框搶走焦點、彈出鍵盤

        // ---- 標題列: [返回] 標題 (最多兩行, 完整顯示) / 副標題 [⚙] [⋮] ----
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(56)
        btnBack = Ui.iconBtn(this, "‹", "返回", 32f) { goBack() }
        head.addView(btnBack)
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.setPadding(dp(4), dp(4), dp(4), dp(4))
        tvTitle = TextView(this)
        tvTitle.textSize = 20f
        tvTitle.setTextColor(C_TEXT)
        tvTitle.setTypeface(null, Typeface.BOLD)
        tvTitle.maxLines = 2
        tvTitle.ellipsize = TextUtils.TruncateAt.END
        titleCol.addView(tvTitle, LinearLayout.LayoutParams(-1, -2))
        tvSub = TextView(this)
        tvSub.textSize = 12f
        tvSub.setTextColor(C_SUB)
        tvSub.maxLines = 1
        tvSub.ellipsize = TextUtils.TruncateAt.END
        titleCol.addView(tvSub, LinearLayout.LayoutParams(-1, -2))
        head.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        btnGear = Ui.iconBtn(this, "⚙", "設定", 22f) { startActivity(Intent(this, SettingsActivity::class.java)) }
        head.addView(btnGear)
        btnMenu = Ui.iconBtn(this, "⋮", "更多", 24f) { showMenu() }
        head.addView(btnMenu)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // ---- 權限/設定提示 ----
        hintCard = LinearLayout(this)
        hintCard.orientation = LinearLayout.VERTICAL
        hintCard.background = Ui.round(this, Ui.WARN_BG, 14)
        hintCard.setPadding(dp(14), dp(10), dp(8), dp(4))
        root.addView(hintCard, lp(4, 4))

        // ---- 搜尋列 ----
        searchBar = LinearLayout(this)
        searchBar.orientation = LinearLayout.HORIZONTAL
        searchBar.gravity = Gravity.CENTER_VERTICAL
        searchBar.background = Ui.round(this, Ui.CARD, 24)
        searchBar.setPadding(dp(14), 0, dp(4), 0)
        val mag = TextView(this)
        mag.text = "🔍"
        mag.textSize = 14f
        searchBar.addView(mag)
        etSearch = EditText(this)
        etSearch.hint = "搜尋 App / 對話 / 內容"
        etSearch.setSingleLine(true)
        etSearch.textSize = 15f
        etSearch.background = null
        etSearch.setPadding(dp(8), dp(10), dp(8), dp(10))
        etSearch.imeOptions = EditorInfo.IME_ACTION_SEARCH
        etSearch.setOnEditorActionListener { v, _, _ ->
            hideKeyboard(v)
            true
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                h.removeCallbacks(searchRun)
                h.postDelayed(searchRun, 250)            // 打字停下來才搜尋
            }
        })
        searchBar.addView(etSearch, LinearLayout.LayoutParams(0, -2, 1f))
        btnClear = Ui.iconBtn(this, "✕", "清除搜尋", 16f) { etSearch.setText("") }
        btnClear.visibility = View.GONE
        searchBar.addView(btnClear, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(searchBar, lp(2, 6))

        // ---- App 內的設定列 ----
        appBar = LinearLayout(this)
        appBar.orientation = LinearLayout.HORIZONTAL
        tvAppSet = Ui.textBtn(this, "") { appSettings() }
        appBar.addView(tvAppSet)
        root.addView(appBar, LinearLayout.LayoutParams(-1, -2))

        sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(2), 0, dp(24))
        sv.addView(list, ViewGroup.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // 從小工具/通知帶特定 App 進來
        curPkg = intent?.getStringExtra("pkg")
        io { MsgDb.warmUp(applicationContext) }
    }

    private fun hideKeyboard(v: View) {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        } catch (_: Exception) {
        }
        v.clearFocus()
    }

    override fun onResume() {
        super.onResume()
        MsgDb.addListener(dbListener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        MsgDb.removeListener(dbListener)
        h.removeCallbacks(refreshRun)
        h.removeCallbacks(searchRun)
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
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

    // ---------------- ⋮ 選單 (依所在層級列出可用操作) ----------------
    private fun showMenu() {
        val pkg = curPkg
        val conv = curConv
        val m = PopupMenu(this, btnMenu)
        val menu = m.menu
        if (pkg != null && conv != null) {
            menu.add(0, 1, 0, if (lastConvPinned) "取消釘選" else "釘選這個對話")
            menu.add(0, 2, 1, "忽略此來源")
            menu.add(0, 3, 2, "開啟「$curApp」")
            menu.add(0, 4, 3, "全部標為已讀")
            menu.add(0, 8, 4, "清除畫面擷取的訊息 (可重新擷取)")
            menu.add(0, 5, 5, "刪除這個對話")
        } else if (pkg != null) {
            menu.add(0, 10, 0, "此 App 設定")
            menu.add(0, 3, 1, "開啟「$curApp」")
            menu.add(0, 4, 2, "全部標為已讀")
            menu.add(0, 6, 3, "刪除此 App 的訊息")
        } else {
            menu.add(0, 4, 0, "全部標為已讀")
            menu.add(0, 11, 1, "設定")
            menu.add(0, 7, 2, "清空所有訊息")
        }
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (pkg != null && conv != null) io { MsgDb.setPinned(this, pkg, conv, !lastConvPinned) }
                2 -> if (pkg != null && conv != null) confirmIgnore(pkg, curApp, conv)
                3 -> if (pkg != null) openApp(pkg)
                4 -> markAllRead()
                5, 6, 7 -> confirmDelete()
                8 -> if (pkg != null && conv != null) confirmClearCaptured(pkg, conv)
                10 -> appSettings()
                11 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        m.show()
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
            hintCard.addView(Ui.textBtn(this, "前往開啟") {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                }
            })
        } else if (!Prefs.msgDb(this)) {
            shown = true
            tv(hintCard, "訊息收錄已關閉。", 13f, C_TEXT)
            hintCard.addView(Ui.textBtn(this, "前往設定") { startActivity(Intent(this, SettingsActivity::class.java)) })
        } else if (!Prefs.msgDbAll(this) && Prefs.msgAllow(this).isEmpty()) {
            shown = true
            tv(hintCard, "還沒選擇要收錄哪些 App。", 13f, C_TEXT)
            hintCard.addView(Ui.textBtn(this, "選擇 App") {
                AppPicker.multi(this, "收錄這些 App 的通知", Prefs.msgAllow(this)) {
                    Prefs.setMsgAllow(this, it)
                    refresh()
                }
            })
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

    /** 清單右側的時間: 今天 HH:mm / 昨天 / 今年 M/d / 其他 yyyy/M/d */
    private fun fmtShort(ts: Long): String {
        if (ts <= 0) return ""
        val now = System.currentTimeMillis()
        if (sameDay(ts, now)) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        if (sameDay(ts, now - 24L * 3600L * 1000L)) return "昨天"
        val a = Calendar.getInstance()
        a.timeInMillis = ts
        val b = Calendar.getInstance()
        return if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) SimpleDateFormat("M/d", Locale.getDefault()).format(Date(ts))
        else SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(Date(ts))
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

    // ---------------- 載入 (背景) 與重繪 (主執行緒) ----------------
    private fun refresh() {
        val my = ++gen
        val q = etSearch.text.toString().trim()
        val pkg = curPkg
        val conv = curConv
        val lim = limit
        val opening = openingThread
        openingThread = false
        updateHint()
        io {
            val snap = load(q, pkg, conv, lim, opening)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && my == gen) render(snap)
            }
        }
    }

    private fun isGrouped(pkg: String, social: Boolean): Boolean {
        val m = Prefs.convMode(this, pkg)
        return m == 1 || (m == 0 && (social || pkg in MsgCapture.SOCIAL))
    }

    /** 在背景執行緒執行: 只讀資料庫, 不碰畫面 */
    private fun load(q: String, pkg: String?, conv: String?, lim: Int, opening: Boolean): Snap {
        val total = MsgDb.total(this)
        val unread = MsgDb.unread(this)
        if (q.isNotEmpty()) return Snap(q, pkg, conv, total, unread, rows = MsgDb.query(this, q, pkg, false, lim + 1))
        if (pkg != null && conv != null) {
            if (opening) {                                // 記下第一則未讀, 再把整個對話標為已讀
                newFrom = MsgDb.query(this, "", pkg, true, 5000, conv, true).firstOrNull()?.id ?: Long.MAX_VALUE
                MsgDb.markConvRead(this, pkg, conv)
                Widgets.updateAll(this)
            }
            return Snap(
                q, pkg, conv, total, unread,
                rows = MsgDb.query(this, "", pkg, false, lim + 1, conv, true),
                convPinned = MsgDb.isPinned(this, pkg, conv)
            )
        }
        if (pkg != null) {
            val info = MsgDb.apps(this).firstOrNull { it.pkg == pkg }
            val grouped = isGrouped(pkg, info?.social ?: false)
            return Snap(
                q, pkg, conv, total, unread, grouped = grouped,
                apps = listOfNotNull(info),
                convs = if (grouped) MsgDb.convs(this, pkg) else emptyList(),
                rows = if (grouped) emptyList() else MsgDb.query(this, "", pkg, false, lim + 1),
                ignoredN = info?.ignored ?: MsgDb.ignoredList(this, pkg).size,
                appPinned = info?.pinned ?: MsgDb.isPinned(this, pkg, "")
            )
        }
        return Snap(q, pkg, conv, total, unread, apps = MsgDb.apps(this), pinned = MsgDb.pinnedConvs(this))
    }

    private fun render(s: Snap) {
        val pkg = s.pkg
        val conv = s.conv
        lastIgnoredN = s.ignoredN
        lastAppPinned = s.appPinned
        lastConvPinned = s.convPinned

        // 記住捲動狀態: 換頁 → 回頂端; 同一頁內容更新 → 維持位置; 在對話底部時 → 繼續跟著最新訊息
        val key = "$pkg|$conv|${s.q.isNotEmpty()}"
        val samePage = key == shownKey
        val savedY = sv.scrollY
        val wasAtBottom = shownThread && samePage && (sv.scrollY + sv.height >= list.height - dp(80))
        shownKey = key
        shownThread = conv != null && pkg != null && s.q.isEmpty()

        list.removeAllViews()
        val nested = pkg != null || conv != null
        btnBack.visibility = if (nested) View.VISIBLE else View.GONE
        btnGear.visibility = if (nested) View.GONE else View.VISIBLE
        searchBar.visibility = if (conv != null && s.q.isEmpty()) View.GONE else View.VISIBLE
        appBar.visibility = View.GONE
        if (pkg != null && curApp.isEmpty()) curApp = s.apps.firstOrNull { it.pkg == pkg }?.app ?: pkg
        tvTitle.text = when {
            s.q.isNotEmpty() -> "搜尋結果"
            conv != null -> conv
            pkg != null -> curApp
            else -> "手機訊息"
        }
        tvSub.text = when {
            s.q.isNotEmpty() -> "「${s.q}」"
            conv != null -> curApp + if (s.convPinned) " · 📌 已釘選" else ""
            pkg != null -> if (s.appPinned) "📌 已釘選" else "點進對話查看"
            else -> if (s.unread > 0) "共 ${s.total} 則 · ${s.unread} 則未讀" else "共 ${s.total} 則 · 長按可釘選或忽略"
        }
        tvSub.visibility = if (tvSub.text.isEmpty()) View.GONE else View.VISIBLE

        if (s.q.isNotEmpty()) {
            renderSearch(s)
        } else if (conv != null && pkg != null) {
            renderThread(s)
        } else if (pkg != null) {
            renderApp(s)
        } else {
            renderApps(s)
        }

        val goBottom = toBottom && shownThread
        toBottom = false
        sv.post {
            if (goBottom || wasAtBottom) sv.fullScroll(View.FOCUS_DOWN)
            else if (samePage) sv.scrollTo(0, savedY)
            else sv.scrollTo(0, 0)
        }
    }

    /** 沒有內容時的說明畫面 (圖示 + 標題 + 說明 + 可選的按鈕) */
    private fun emptyState(icon: String, title: String, hint: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24), dp(56), dp(24), 0)
        val i = TextView(this)
        i.text = icon
        i.textSize = 40f
        i.gravity = Gravity.CENTER
        col.addView(i, LinearLayout.LayoutParams(-2, -2))
        val t = tv(col, title, 16f, C_TEXT, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(8), 0, dp(4))
        val d = tv(col, hint, 13f, C_SUB)
        d.gravity = Gravity.CENTER
        if (action != null && onAction != null) {
            val b = Ui.textBtn(this, action) { onAction() }
            val p = LinearLayout.LayoutParams(-2, -2)
            p.topMargin = dp(8)
            col.addView(b, p)
        }
        list.addView(col, LinearLayout.LayoutParams(-1, -2))
    }

    private fun box(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = Ui.round(this, Ui.CARD, 14)
        c.elevation = dp(1).toFloat()
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        return c
    }

    private fun sectionTitle(t: String) {
        val v = tv(list, t, 12f, C_SUB, true)
        v.setPadding(dp(6), dp(8), 0, dp(4))
    }

    /** 清單的一列: [頭像] 名稱 + 預覽 | 時間 + 未讀徽章 */
    private fun row(
        lead: View, title: String, preview: String, ts: Long, unread: Int, pinned: Boolean,
        onClick: () -> Unit, onLong: () -> Unit
    ): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.background = Ui.round(this, Ui.CARD, 14)
        c.elevation = dp(1).toFloat()
        c.foreground = Ui.ripple(this, false)
        c.setPadding(dp(12), dp(10), dp(12), dp(10))
        c.addView(lead, LinearLayout.LayoutParams(dp(44), dp(44)))

        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = (if (pinned) "📌 " else "") + title
        name.textSize = 16f
        name.setTextColor(C_TEXT)
        name.setTypeface(null, Typeface.BOLD)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        mid.addView(name, LinearLayout.LayoutParams(-1, -2))
        if (preview.isNotEmpty()) {
            val p = TextView(this)
            p.text = preview.replace('\n', ' ')
            p.textSize = 13f
            p.setTextColor(if (unread > 0) C_TEXT else C_SUB)
            if (unread > 0) p.setTypeface(null, Typeface.BOLD)
            p.maxLines = 1
            p.ellipsize = TextUtils.TruncateAt.END
            mid.addView(p, LinearLayout.LayoutParams(-1, -2))
        }
        val mp = LinearLayout.LayoutParams(0, -2, 1f)
        mp.leftMargin = dp(12)
        c.addView(mid, mp)

        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.gravity = Gravity.END
        val t = TextView(this)
        t.text = fmtShort(ts)
        t.textSize = 11f
        t.setTextColor(if (unread > 0) C_PRIMARY else C_SUB)
        right.addView(t, LinearLayout.LayoutParams(-2, -2))
        if (unread > 0) {
            val bp = LinearLayout.LayoutParams(-2, -2)
            bp.topMargin = dp(4)
            right.addView(Ui.badge(this, unread), bp)
        }
        val rp = LinearLayout.LayoutParams(-2, -2)
        rp.leftMargin = dp(8)
        c.addView(right, rp)

        c.setOnClickListener { onClick() }
        c.setOnLongClickListener {
            onLong()
            true
        }
        c.layoutParams = lp(0, 6)
        return c
    }

    // 第 1 層: 釘選的對話 + App 清單 (釘選的 App 在前)
    private fun renderApps(s: Snap) {
        if (s.apps.isEmpty() && s.pinned.isEmpty()) {
            emptyState(
                "💬", "還沒有收錄任何訊息",
                "收到的通知會自動出現在這裡。請確認已開啟「通知存取」, 並在設定選擇要收錄的 App。",
                "開啟設定"
            ) { startActivity(Intent(this, SettingsActivity::class.java)) }
            return
        }
        if (s.pinned.isNotEmpty()) {
            sectionTitle("📌 釘選")
            for (p in s.pinned) {
                list.addView(
                    row(
                        Ui.avatar(this, p.conv), p.conv, p.app + " · " + p.last, p.lastTs, p.unread, false,
                        { curApp = p.app; openThread(p.pkg, p.conv) },
                        { convMenu(p.pkg, p.app, p.conv, false) }
                    )
                )
            }
            sectionTitle("全部 App")
        }
        for (a in s.apps) {
            val info = a.total.toString() + " 則" + (if (a.total > 0 && isGrouped(a.pkg, a.social)) " · 依對話分組" else "") +
                (if (a.ignored > 0) " · 已忽略 ${a.ignored} 個來源" else "")
            list.addView(
                row(
                    Ui.appIcon(this, a.pkg, a.app), a.app, info, a.lastTs, a.unread, a.pinned,
                    {
                        curPkg = a.pkg
                        curApp = a.app
                        curConv = null
                        limit = 150
                        refresh()
                    },
                    { appMenu(a) }
                )
            )
        }
    }

    // 第 2 層: 某個 App (社群 → 對話清單; 其他 → 依時間的訊息)
    private fun renderApp(s: Snap) {
        val pkg = s.pkg ?: return
        appBar.visibility = View.VISIBLE
        tvAppSet.text = "⚙ 此 App 設定" + if (s.ignoredN > 0) " · 已忽略 ${s.ignoredN} 個來源" else ""
        if (s.grouped) {
            if (s.convs.isEmpty()) {
                emptyState(
                    "🗂", "沒有可顯示的對話",
                    if (s.ignoredN > 0) "來源都被忽略了, 可到「此 App 設定 → 已忽略的來源」找回。" else "這個 App 還沒有收錄到訊息。"
                )
                return
            }
            for (cv in s.convs) {
                list.addView(
                    row(
                        Ui.avatar(this, cv.conv), cv.conv, cv.last, cv.lastTs, cv.unread, cv.pinned,
                        { openThread(pkg, cv.conv) },
                        { convMenu(pkg, curApp, cv.conv, false) }
                    )
                )
            }
        } else {
            if (s.rows.isEmpty()) {
                emptyState(
                    "🗂", "沒有可顯示的訊息",
                    if (s.ignoredN > 0) "來源都被忽略了, 可到「此 App 設定 → 已忽略的來源」找回。" else "這個 App 還沒有收錄到訊息。"
                )
                return
            }
            for (m in s.rows.take(limit)) list.addView(flatCard(m, false), lp(0, 6))
            moreButton(s.rows.size > limit)
        }
    }

    private fun moreButton(show: Boolean) {
        if (!show) return
        val b = Ui.textBtn(this, "載入更多") {
            limit += 150
            refresh()
        }
        b.gravity = Gravity.CENTER
        list.addView(b, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openThread(pkg: String, conv: String) {
        curPkg = pkg
        curConv = conv
        if (curApp.isEmpty()) curApp = pkg
        limit = 150
        expanded.clear()
        openingThread = true
        toBottom = true
        etSearch.setText("")
        h.removeCallbacks(searchRun)
        hideKeyboard(etSearch)
        refresh()
    }

    // 第 3 層: 對話內的訊息串 (舊 → 新, 底部是最新)
    private fun renderThread(s: Snap) {
        val rows = s.rows
        if (rows.isEmpty()) {
            emptyState("💬", "沒有訊息", "這個對話目前沒有可顯示的訊息。")
            return
        }
        val conv = s.conv ?: return
        val shown = if (rows.size > limit) rows.drop(1) else rows
        if (rows.size > limit) {
            val b = Ui.textBtn(this, "載入更早的訊息") {
                limit += 150
                toBottom = false
                refresh()
            }
            b.gravity = Gravity.CENTER
            list.addView(b, LinearLayout.LayoutParams(-1, -2))
        }
        var prev: Msg? = null
        var newDrawn = false
        val nf = newFrom
        for (m in shown) {
            val p = prev
            if (p == null || !sameDay(p.ts, m.ts)) {
                dayHeader(dayLabel(m.ts))
            } else if (m.ts - p.ts >= GAP_MS) {
                divider("間隔 " + gapText(m.ts - p.ts), C_SUB)
            }
            if (!newDrawn && m.id >= nf) {
                newDrawn = true
                divider("以下為新訊息", C_PRIMARY)
            }
            list.addView(bubble(m, conv))
            prev = m
        }
    }

    private fun dayHeader(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 12f
        v.setTextColor(Color.WHITE)
        v.background = Ui.round(this, 0xFF78909C.toInt(), 12)
        v.setPadding(dp(14), dp(4), dp(14), dp(4))
        v.gravity = Gravity.CENTER
        val p = LinearLayout.LayoutParams(-2, -2)
        p.gravity = Gravity.CENTER_HORIZONTAL
        p.setMargins(0, dp(14), 0, dp(8))
        list.addView(v, p)
    }

    private fun divider(t: String, color: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        fun line() {
            val l = View(this)
            l.setBackgroundColor(color)
            row.addView(l, LinearLayout.LayoutParams(0, dp(1), 1f))
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

    /** 聊天氣泡: 對方靠左 (白底), 我靠右 (淺藍底); 寬度依內容, 最寬約七成螢幕 */
    private fun bubble(m: Msg, conv: String): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = Ui.round(this, if (m.mine) Ui.MINE else Ui.CARD, 16)
        c.elevation = dp(1).toFloat()
        c.setPadding(dp(12), dp(8), dp(12), dp(6))
        val who = if (m.mine || m.title.isEmpty() || m.title == conv || m.title.endsWith(": $conv")) "" else m.title
        if (who.isNotEmpty()) {
            val w = TextView(this)
            w.text = who
            w.textSize = 12f
            w.setTextColor(C_PRIMARY)
            w.setTypeface(null, Typeface.BOLD)
            c.addView(w, LinearLayout.LayoutParams(-2, -2))
        }
        val body = TextView(this)
        body.text = m.body.ifEmpty { m.title }
        body.textSize = 15f
        body.setTextColor(C_TEXT)
        body.setTextIsSelectable(false)
        body.maxWidth = (resources.displayMetrics.widthPixels * 0.68).toInt()
        c.addView(body, LinearLayout.LayoutParams(-2, -2))
        val t = TextView(this)
        t.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts))
        t.textSize = 11f
        t.setTextColor(C_SUB)
        val tp = LinearLayout.LayoutParams(-2, -2)
        tp.gravity = Gravity.END
        c.addView(t, tp)
        c.setOnLongClickListener {
            messageMenu(m)
            true
        }
        val p = LinearLayout.LayoutParams(-2, -2)
        p.gravity = if (m.mine) Gravity.END else Gravity.START
        p.setMargins(if (m.mine) dp(48) else 0, 0, if (m.mine) 0 else dp(48), dp(6))
        c.layoutParams = p
        return c
    }

    // 沒有分組的 App / 搜尋結果: 一般卡片
    private fun flatCard(m: Msg, showApp: Boolean): View {
        val c = box()
        c.foreground = Ui.ripple(this, false)
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val app = TextView(this)
        val label = if (showApp) m.app + (if (m.conv.isNotEmpty()) " · " + m.conv.take(20) else "") else ""
        app.text = (if (m.read) "" else "● ") + (if (m.mine) "我 → " else "") + label
        app.textSize = 12f
        app.setTextColor(if (m.read) C_SUB else C_PRIMARY)
        app.setTypeface(null, Typeface.BOLD)
        app.maxLines = 1
        app.ellipsize = TextUtils.TruncateAt.END
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
                curApp = m.app
                openThread(m.pkg, m.conv)
                return@setOnClickListener
            }
            if (expanded.contains(m.id)) expanded.remove(m.id) else expanded.add(m.id)
            if (!m.read) {
                io {
                    MsgDb.markRead(this, m.id)
                    Widgets.updateAll(this)
                }
            } else {
                refresh()
            }
        }
        c.setOnLongClickListener {
            messageMenu(m)
            true
        }
        return c
    }

    // 搜尋結果: 跨對話的訊息, 點一則會跳到該對話
    private fun renderSearch(s: Snap) {
        if (s.rows.isEmpty()) {
            emptyState("🔍", "找不到符合的訊息", "換個關鍵字試試, 可搜尋 App 名稱、對話名稱或內容。")
            return
        }
        for (m in s.rows.take(limit)) list.addView(flatCard(m, true), lp(0, 6))
        moreButton(s.rows.size > limit)
    }

    // ---------------- 長按選單: 釘選 / 忽略 / 刪除 ----------------
    private fun appMenu(a: AppCount) {
        val items = arrayOf(if (a.pinned) "取消釘選" else "釘選這個 App", "開啟「" + a.app + "」")
        AlertDialog.Builder(this).setTitle(a.app).setItems(items) { _, which ->
            if (which == 0) io { MsgDb.setPinned(this, a.pkg, "", !a.pinned) } else openApp(a.pkg)
        }.show()
    }

    private fun convMenu(pkg: String, app: String, conv: String, inThread: Boolean) {
        val pinned = if (inThread) lastConvPinned else MsgDb.isPinned(this, pkg, conv)
        val items = arrayOf(
            if (pinned) "取消釘選" else "釘選這個對話",
            "忽略此來源 (不顯示、不再收錄)",
            "開啟「$app」",
            "刪除這個對話"
        )
        AlertDialog.Builder(this).setTitle(conv).setItems(items) { _, which ->
            when (which) {
                0 -> io { MsgDb.setPinned(this, pkg, conv, !pinned) }
                1 -> confirmIgnore(pkg, app, conv)
                2 -> openApp(pkg)
                else -> AlertDialog.Builder(this).setMessage("確定刪除與「$conv」的所有訊息?").setNegativeButton("取消", null)
                    .setPositiveButton("刪除") { _, _ ->
                        if (curConv == conv) curConv = null
                        io {
                            MsgDb.deleteConv(this, pkg, conv)
                            Widgets.updateAll(this)
                        }
                    }.show()
            }
        }.show()
    }

    /** 只刪「從聊天畫面擷取」的訊息 (我送出的、開著聊天時收到的), 通知收錄的訊息保留 */
    private fun confirmClearCaptured(pkg: String, conv: String) {
        AlertDialog.Builder(this)
            .setMessage("清除「$conv」裡從聊天畫面擷取的訊息?\n\n通知收錄的訊息會保留。擷取設定調整後, 之後新的內容會重新擷取。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                io {
                    MsgDb.deleteScreenCaptured(this, pkg, conv)
                    Widgets.updateAll(this)
                }
                toast("已清除")
            }.show()
    }

    private fun confirmIgnore(pkg: String, app: String, conv: String) {
        AlertDialog.Builder(this)
            .setMessage("忽略「$conv」?\n\n這個來源的訊息會從清單、搜尋、未讀數和小工具中消失, 之後的新訊息也不再收錄。已存的舊訊息會保留, 可到「$app」的「此 App 設定 → 已忽略的來源」找回。")
            .setNegativeButton("取消", null)
            .setPositiveButton("忽略") { _, _ ->
                if (curConv == conv) curConv = null
                io {
                    MsgDb.ignore(this, pkg, app, conv)
                    Widgets.updateAll(this)
                }
                toast("已忽略「$conv」")
            }.show()
    }

    private fun appSettings() {
        val pkg = curPkg ?: return
        val mode = Prefs.convMode(this, pkg)
        val items = arrayOf(
            "依對話分組: " + (if (mode == 0) "自動" else if (mode == 1) "開" else "關") + " (點一下切換)",
            "已忽略的來源 ($lastIgnoredN)",
            if (lastAppPinned) "取消釘選此 App" else "釘選此 App"
        )
        AlertDialog.Builder(this).setTitle(curApp + " · 設定").setItems(items) { _, which ->
            when (which) {
                0 -> {
                    Prefs.setConvMode(this, pkg, (mode + 1) % 3)
                    refresh()
                }
                1 -> showIgnored(pkg)
                else -> io { MsgDb.setPinned(this, pkg, "", !lastAppPinned) }
            }
        }.setNegativeButton("關閉", null).show()
    }

    /** 已忽略的來源清單: 點一個就找回, 或一次全部找回 */
    private fun showIgnored(pkg: String) {
        io {
            val l = MsgDb.ignoredList(this, pkg)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (l.isEmpty()) {
                    toast("沒有已忽略的來源")
                    return@runOnUiThread
                }
                val names = l.map { it.conv + "  (" + it.count + " 則)" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("點一下找回來源")
                    .setItems(names) { _, i ->
                        val src = l[i]
                        io {
                            MsgDb.unignore(this, pkg, src.conv)
                            Widgets.updateAll(this)
                        }
                        toast("已找回「" + src.conv + "」")
                        if (l.size > 1) showIgnoredSoon(pkg)
                    }
                    .setNeutralButton("全部找回") { _, _ ->
                        io {
                            MsgDb.unignoreAll(this, pkg)
                            Widgets.updateAll(this)
                        }
                        toast("已找回全部來源")
                    }
                    .setNegativeButton("關閉", null).show()
            }
        }
    }

    private fun showIgnoredSoon(pkg: String) = h.postDelayed({ if (!isFinishing && !isDestroyed) showIgnored(pkg) }, 150)

    private fun messageMenu(m: Msg) {
        val items = arrayOf("複製內容", "開啟「" + m.app + "」", "忽略來源「" + m.conv.take(16) + "」", "刪除這則")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("訊息", (if (m.title.isNotEmpty()) m.title + "\n" else "") + m.body))
                    toast("已複製")
                }
                1 -> openApp(m.pkg)
                2 -> confirmIgnore(m.pkg, m.app, m.conv)
                else -> AlertDialog.Builder(this).setMessage("確定刪除這則訊息?").setNegativeButton("取消", null)
                    .setPositiveButton("刪除") { _, _ ->
                        io {
                            MsgDb.delete(this, m.id)
                            Widgets.updateAll(this)
                        }
                    }.show()
            }
        }.show()
    }

    private fun openApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) startActivity(launch) else toast("找不到這個 App")
    }

    // ---------------- 已讀 / 刪除 (依目前所在層級) ----------------
    private fun markAllRead() {
        val pkg = curPkg
        val conv = curConv
        io {
            if (pkg != null && conv != null) MsgDb.markConvRead(this, pkg, conv) else MsgDb.markAllRead(this, pkg)
            Widgets.updateAll(this)
        }
        toast("已全部標為已讀")
    }

    private fun confirmDelete() {
        val pkg = curPkg
        val conv = curConv
        val msg = when {
            conv != null -> "確定刪除與「$conv」的所有訊息?"
            pkg != null -> "確定刪除「$curApp」的所有訊息? (釘選與已忽略的來源設定會保留)"
            else -> "確定刪除所有已收錄的訊息? (釘選與已忽略的來源設定會保留)"
        }
        AlertDialog.Builder(this).setMessage(msg).setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ ->
                if (pkg != null && conv != null) curConv = null else curPkg = null
                expanded.clear()
                io {
                    if (pkg != null && conv != null) MsgDb.deleteConv(this, pkg, conv) else MsgDb.clear(this, pkg)
                    Widgets.updateAll(this)
                }
            }.show()
    }
}

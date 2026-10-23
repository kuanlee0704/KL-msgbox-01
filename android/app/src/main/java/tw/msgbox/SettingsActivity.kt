package tw.msgbox

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** 設定: 通知存取狀態、收錄開關、要收錄的 App、自動刪除舊訊息天數。 */
class SettingsActivity : Activity() {
    private val C_BG = 0xFFF2F4F7.toInt()
    private val C_PRIMARY = 0xFF1565C0.toInt()
    private val C_TEXT = 0xFF212121.toInt()
    private val C_SUB = 0xFF616161.toInt()
    private val C_BAD = 0xFFC62828.toInt()
    private val C_OK = 0xFF2E7D32.toInt()

    private lateinit var tvAccess: TextView
    private lateinit var tvSel: TextView
    private lateinit var tvChat: TextView
    private lateinit var tvChatSel: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(0xFFFFFFFF.toInt())
        g.cornerRadius = dp(14).toFloat()
        c.background = g
        c.elevation = dp(1).toFloat()
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        val p = LinearLayout.LayoutParams(-1, -2)
        p.setMargins(0, 0, 0, dp(10))
        parent.addView(c, p)
        return c
    }

    private fun text(parent: ViewGroup, t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        parent.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun button(parent: ViewGroup, t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(-1, -2))
        return b
    }

    private fun switch(parent: ViewGroup, t: String, on: Boolean, change: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = t
        s.isChecked = on
        s.setOnCheckedChangeListener { _, c -> change(c) }
        parent.addView(s, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        Ui.styleWindow(this)
        val sv = ScrollView(this)
        sv.setBackgroundColor(C_BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(4), dp(12), dp(24))
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))

        // 標題列: 返回 + 標題 (和主畫面一致)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = android.view.Gravity.CENTER_VERTICAL
        head.addView(Ui.iconBtn(this, "‹", "返回", 32f) { finish() })
        val ttl = TextView(this)
        ttl.text = "設定"
        ttl.textSize = 20f
        ttl.setTextColor(C_TEXT)
        ttl.setTypeface(null, Typeface.BOLD)
        ttl.setPadding(dp(4), 0, 0, 0)
        head.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(head, LinearLayout.LayoutParams(-1, dp(56)))

        val a = card(col)
        text(a, "通知存取", 16f, C_TEXT, true)
        tvAccess = text(a, "", 14f, C_TEXT)
        button(a, "前往系統設定") {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
            }
        }

        val c = card(col)
        text(c, "收錄", 16f, C_TEXT, true)
        text(c, "把收到的通知存在這支手機上, 不必打開原本的 App 就能先看內容 (可搜尋、依 App 與對話分組、只看未讀)。資料只存在這支手機, 不會上傳。", 12f, C_SUB)
        switch(c, "收錄通知到訊息資料庫", Prefs.msgDb(this)) { Prefs.setMsgDb(this, it) }
        switch(c, "收錄所有 App (不限下面勾選的)", Prefs.msgDbAll(this)) { Prefs.setMsgDbAll(this, it) }
        tvSel = text(c, "", 13f, C_PRIMARY)
        button(c, "選擇要收錄的 App") {
            AppPicker.multi(this, "收錄這些 App 的通知", Prefs.msgAllow(this)) {
                Prefs.setMsgAllow(this, it)
                tvSel.text = selText()
            }
        }

        val ch = card(col)
        text(ch, "聊天畫面擷取 (實驗性)", 16f, C_TEXT, true)
        text(
            ch,
            "通知只看得到對方的訊息。開啟後, 用「無障礙服務」讀你勾選的聊天 App 畫面, 記錄你自己送出的訊息 (靠右的氣泡); 你正開著聊天時對方傳來的訊息因為不會跳通知, 也能從畫面讀到 (靠左的氣泡), 對話畫面就會有來有往。" +
                "只讀你勾選的 App、不讀輸入框與密碼欄、沒開著聊天時對方的訊息仍靠通知收錄、資料只存在這支手機。\n" +
                "要先到系統「無障礙」開啟本 App 的服務; Android 13 以上若選項是灰的, 請到「設定 → 應用程式 → 本 App → ⋮ → 允許受限制的設定」。",
            12f, C_SUB
        )
        tvChat = text(ch, "", 13f, C_TEXT)
        switch(ch, "啟用聊天畫面擷取", Prefs.chatOn(this)) {
            Prefs.setChatOn(this, it)
            ChatAccess.applyConfig()
        }
        switch(ch, "同時記錄對方在畫面上的訊息 (開著聊天時不會跳通知)", Prefs.chatBoth(this)) { Prefs.setChatBoth(this, it) }
        val etSelf = EditText(this)
        etSelf.hint = "我的名字 (例如 Kilo, 可多個用逗號分隔)"
        etSelf.setText(Prefs.chatSelf(this))
        etSelf.setSingleLine(true)
        etSelf.textSize = 14f
        etSelf.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.setChatSelf(this@SettingsActivity, s?.toString() ?: "")
            }
        })
        ch.addView(etSelf, LinearLayout.LayoutParams(-1, -2))
        text(ch, "Messenger 這類 App 會把畫面文字寫成「寄件人, 內容, double tap…」, 填你在 App 裡顯示的名字 (如 Kilo), 才能分清哪些是你送出的; 空白 = 自動判斷 (1 對 1 聊天通常準, 群組建議填寫)。", 12f, C_SUB)
        tvChatSel = text(ch, "", 13f, C_PRIMARY)
        button(ch, "選擇要擷取的 App") {
            AppPicker.multi(this, "擷取這些 App 的聊天畫面", Prefs.chatApps(this)) {
                Prefs.setChatApps(this, it)
                ChatAccess.applyConfig()
                tvChatSel.text = chatSelText()
            }
        }
        button(ch, "前往系統無障礙設定") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
        }
        button(ch, "除錯: 輸出下一個畫面的文字位置") {
            ChatAccess.dumpNext = true
            Toast.makeText(this, "請切到聊天畫面停留 1 秒, 再回來按「查看記錄」", Toast.LENGTH_LONG).show()
        }
        button(ch, "查看記錄") { showLog() }

        val k = card(col)
        text(k, "保留", 16f, C_TEXT, true)
        val keepOpts = intArrayOf(0, 30, 90, 180, 365)
        fun keepText(d: Int) = "自動刪除舊訊息: " + (if (d <= 0) "永不 (只有你手動刪才會刪)" else "超過 $d 天的")
        val btnKeep = button(k, keepText(Prefs.msgKeepDays(this))) { }
        btnKeep.setOnClickListener {
            val cur = keepOpts.indexOf(Prefs.msgKeepDays(this)).let { if (it < 0) 0 else it }
            val next = keepOpts[(cur + 1) % keepOpts.size]
            Prefs.setMsgKeepDays(this, next)
            btnKeep.text = keepText(next)
        }
        text(k, "手機上把通知滑掉或已讀時, 資料庫裡只會標為已讀、不會刪除。", 12f, C_SUB)

        text(col, "所有訊息只存在這支手機, 不會上傳。預設永遠保留, 只有你手動刪除或設定了自動刪除天數才會刪。", 12f, C_SUB).setPadding(dp(6), dp(4), dp(6), 0)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        val on = try {
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        } catch (_: Exception) {
            true
        }
        tvAccess.text = if (on) "已開啟" else "尚未開啟, 無法收錄訊息"
        tvAccess.setTextColor(if (on) C_OK else C_BAD)
        tvSel.text = selText()
        tvChatSel.text = chatSelText()
        val acc = accessOn()
        tvChat.text = (if (acc) "無障礙服務: 已開啟" else "無障礙服務: 尚未開啟") +
            (if (ChatAccess.lastInfo.isNotEmpty()) "\n" + ChatAccess.lastInfo else "")
        tvChat.setTextColor(if (acc) C_OK else C_BAD)
    }

    private fun accessOn(): Boolean {
        val en = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, ChatAccess::class.java)
        return en.contains(cn.flattenToString()) || en.contains(cn.flattenToShortString())
    }

    private fun chatSelText(): String {
        val n = Prefs.chatApps(this).size
        return if (n == 0) "尚未選擇任何 App" else "已選 $n 個 App"
    }

    private fun showLog() {
        val lines = AppLog.text(false).split("\n").filter { it.contains("/CHAT") }.takeLast(150)
        val tv = TextView(this)
        tv.text = if (lines.isEmpty()) "(還沒有記錄)" else lines.joinToString("\n")
        tv.textSize = 11f
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        val sc = ScrollView(this)
        sc.addView(tv)
        android.app.AlertDialog.Builder(this).setTitle("聊天畫面擷取記錄").setView(sc)
            .setPositiveButton("關閉", null)
            .setNeutralButton("複製") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("記錄", tv.text))
                Toast.makeText(this, "已複製", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun selText(): String {
        val n = Prefs.msgAllow(this).size
        return if (n == 0) "尚未選擇任何 App" else "已選 $n 個 App"
    }
}

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(0xFFFFFFFF.toInt())
        g.cornerRadius = dp(14).toFloat()
        c.background = g
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
        val sv = ScrollView(this)
        sv.setBackgroundColor(C_BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(26), dp(14), dp(24))
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))

        text(col, "設定", 22f, C_TEXT, true).setPadding(0, 0, 0, dp(12))

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
    }

    private fun selText(): String {
        val n = Prefs.msgAllow(this).size
        return if (n == 0) "尚未選擇任何 App" else "已選 $n 個 App"
    }
}

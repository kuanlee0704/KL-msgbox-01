package tw.msgbox

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 選 App 的對話框 (單選 / 多選), 上方有搜尋欄 (可輸入 App 名稱或套件名稱), 不用一直往下滑。 */
object AppPicker {

    fun multi(act: Activity, title: String, current: Set<String>, save: (Set<String>) -> Unit) {
        show(act, title, current, true, { save(it) }, null)
    }

    fun single(act: Activity, title: String, onPick: (String) -> Unit) {
        show(act, title, emptySet(), false, null, onPick)
    }

    private fun show(
        act: Activity, title: String, current: Set<String>, multi: Boolean,
        onSave: ((Set<String>) -> Unit)?, onPick: ((String) -> Unit)?
    ) {
        val all = launcherApps(act)
        if (all.isEmpty()) {
            android.widget.Toast.makeText(act, "找不到可開啟的 App", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sel = HashSet(current)
        // 多選時, 已勾選的排在最前面
        val apps = if (multi) all.sortedWith(compareBy({ it.second !in sel }, { it.first.lowercase() })) else all
        var shown: List<Pair<String, String>> = apps

        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(4), dp(12), 0)
        val search = EditText(act)
        search.hint = "搜尋 App 名稱…"
        search.setSingleLine(true)
        root.addView(search, LinearLayout.LayoutParams(-1, -2))
        val count = TextView(act)
        count.textSize = 12f
        count.setTextColor(0xFF616161.toInt())
        if (multi) root.addView(count, LinearLayout.LayoutParams(-1, -2))
        val list = ListView(act)
        root.addView(list, LinearLayout.LayoutParams(-1, dp(380)))

        fun updateCount() {
            count.text = "已選 ${sel.size} 個" + if (shown.size != apps.size) "  (符合 ${shown.size} 個)" else ""
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val tv = (cv as? TextView) ?: TextView(act).also {
                    it.textSize = 16f
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.setPadding(dp(8), dp(12), dp(8), dp(12))
                }
                val a = shown[p]
                val on = a.second in sel
                tv.text = if (multi) (if (on) "☑  " else "☐  ") + a.first else a.first
                tv.setTextColor(if (multi && on) 0xFF1565C0.toInt() else Color.BLACK)
                return tv
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            val pkg = shown[i].second
            if (multi) {
                if (!sel.add(pkg)) sel.remove(pkg)
                updateCount()
                adapter.notifyDataSetChanged()
            } else {
                dlg?.dismiss()
                onPick?.invoke(pkg)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown = if (q.isEmpty()) apps else apps.filter {
                    it.first.lowercase().contains(q) || it.second.lowercase().contains(q)
                }
                updateCount()
                adapter.notifyDataSetChanged()
                list.setSelection(0)
            }
        })
        updateCount()

        val b = AlertDialog.Builder(act).setTitle(title).setView(root).setNegativeButton("取消", null)
        if (multi) {
            b.setPositiveButton("儲存") { _, _ ->
                // 不在桌面清單裡的項目 (例如已解除安裝) 原樣保留
                for (p in current) if (all.none { it.second == p }) sel.add(p)
                onSave?.invoke(sel)
                android.widget.Toast.makeText(act, "已儲存 (${sel.size} 個)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dlg = b.create()
        dlg?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg?.show()
    }

    private var dlg: AlertDialog? = null

    /** 手機上所有可從桌面開啟的 App: (名稱, 套件名稱), 依名稱排序 */
    fun launcherApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }
}

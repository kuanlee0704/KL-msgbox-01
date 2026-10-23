package tw.msgbox

import android.content.Context
import android.content.SharedPreferences

/** 所有設定集中在這裡 (SharedPreferences "msgbox")。 */
object Prefs {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("msgbox", Context.MODE_PRIVATE)

    /** 是否收錄通知到資料庫 */
    fun msgDb(c: Context): Boolean = sp(c).getBoolean("msg_db", true)
    fun setMsgDb(c: Context, v: Boolean) { sp(c).edit().putBoolean("msg_db", v).apply() }

    /** 是否收錄所有 App (不只勾選的) */
    fun msgDbAll(c: Context): Boolean = sp(c).getBoolean("msg_db_all", false)
    fun setMsgDbAll(c: Context, v: Boolean) { sp(c).edit().putBoolean("msg_db_all", v).apply() }

    /** 自動刪除幾天前的舊訊息; 0 = 永不 (預設, 只有你手動刪才會刪) */
    fun msgKeepDays(c: Context): Int = sp(c).getInt("msg_keep_days", 0)
    fun setMsgKeepDays(c: Context, v: Int) { sp(c).edit().putInt("msg_keep_days", v).apply() }

    /** 「依對話分組」: 0 = 自動 (社群/通訊 App 會分組), 1 = 一律分組, 2 = 不分組 (照時間排) */
    fun convMode(c: Context, pkg: String): Int = sp(c).getInt("conv_mode_$pkg", 0)
    fun setConvMode(c: Context, pkg: String, v: Int) { sp(c).edit().putInt("conv_mode_$pkg", v).apply() }

    /** 要收錄的 App 白名單 (空 = 都不收, 除非開了「收錄所有 App」) */
    fun msgAllow(c: Context): Set<String> = getSet(c, "msg_allow")
    fun setMsgAllow(c: Context, v: Collection<String>) = putSet(c, "msg_allow", v)

    /** 聊天畫面擷取 (無障礙服務, 實驗性): 預設關閉; 只讀有勾選的 App */
    fun chatOn(c: Context): Boolean = sp(c).getBoolean("chat_on", false)
    fun setChatOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("chat_on", v).apply() }
    /** 聊天畫面擷取: 同時記錄對方在畫面上的訊息 (開著聊天時不會有通知, 只能從畫面讀); 預設開啟 */
    fun chatBoth(c: Context): Boolean = sp(c).getBoolean("chat_both", true)
    fun setChatBoth(c: Context, v: Boolean) { sp(c).edit().putBoolean("chat_both", v).apply() }
    /** 我在聊天 App 裡顯示的名字 (逗號分隔, 如 Kilo), 用來辨識畫面上哪些是我送出的; 空 = 自動判斷 */
    fun chatSelf(c: Context): String = sp(c).getString("chat_self", "") ?: ""
    fun setChatSelf(c: Context, v: String) { sp(c).edit().putString("chat_self", v.trim()).apply() }
    fun chatApps(c: Context): Set<String> = getSet(c, "chat_apps")
    fun setChatApps(c: Context, v: Collection<String>) = putSet(c, "chat_apps", v)

    private fun getSet(c: Context, k: String): Set<String> = try {
        val a = org.json.JSONArray(sp(c).getString(k, "[]"))
        HashSet<String>().also { s -> for (i in 0 until a.length()) s.add(a.optString(i)) }
    } catch (_: Exception) {
        emptySet()
    }

    private fun putSet(c: Context, k: String, v: Collection<String>) {
        sp(c).edit().putString(k, org.json.JSONArray(v.toList()).toString()).apply()
    }
}

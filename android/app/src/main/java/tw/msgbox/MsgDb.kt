package tw.msgbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 資料庫裡的一則訊息 */
class Msg(
    val id: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val ts: Long,
    val read: Boolean,
    val conv: String
)

/** 某個 App 在資料庫裡的統計 */
class AppCount(val pkg: String, val app: String, val total: Int, val unread: Int, val social: Boolean, val lastTs: Long)

/** 某個 App 內的一個對話 (聯絡人或群組) */
class ConvCount(val conv: String, val total: Int, val unread: Int, val lastTs: Long)

/**
 * 手機訊息資料庫: 把手機收到的通知 (只收有勾選的 App, 或「收錄全部」) 存在手機上,
 * 不必連著控制器、也不必開啟原本的 App 就能在這裡先看內容。資料只留在這支手機, 不會上傳。
 * **預設永遠保留**: 只有你自己在畫面上按刪除/清空才會刪;手機上通知被滑掉或已讀只會把該則「標為已讀」, 不會刪。
 * 想自動清掉舊訊息, 可在設定分頁選保留天數 (Prefs.msgKeepDays, 0 = 永不)。
 */
object MsgDb {
    /** 資料每變動一次就 +1, 畫面看到變動就重新整理 */
    @Volatile var version = 0

    private class Helper(c: Context) : SQLiteOpenHelper(c.applicationContext, "msgs.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, " +
                    "title TEXT, body TEXT, ts INTEGER, read INTEGER DEFAULT 0, nkey TEXT, conv TEXT, social INTEGER DEFAULT 0)"
            )
            db.execSQL("CREATE INDEX idx_msgs_ts ON msgs(ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {                         // v2: 新增「對話」與「是否社群/通訊 App」欄位, 舊資料保留
                db.execSQL("ALTER TABLE msgs ADD COLUMN conv TEXT")
                db.execSQL("ALTER TABLE msgs ADD COLUMN social INTEGER DEFAULT 0")
            }
        }
    }

    private var helper: Helper? = null
    private var inserts = 0

    @Synchronized
    private fun db(c: Context): SQLiteDatabase {
        var h = helper
        if (h == null) {
            h = Helper(c)
            helper = h
        }
        return h.writableDatabase
    }

    fun insert(c: Context, pkg: String, app: String, title: String, body: String, nkey: String, conv: String, social: Boolean) {
        val v = ContentValues()
        v.put("pkg", pkg)
        v.put("app", app)
        v.put("title", title)
        v.put("body", body)
        v.put("ts", System.currentTimeMillis())
        v.put("read", 0)
        v.put("nkey", nkey)
        v.put("conv", conv)
        v.put("social", if (social) 1 else 0)
        val d = db(c)
        d.insert("msgs", null, v)
        inserts++
        if (inserts % 50 == 1) prune(c, d)
        version++
    }

    /** 只有使用者在設定裡選了「保留天數」才會自動刪舊訊息 (預設 0 = 永不刪) */
    private fun prune(c: Context, d: SQLiteDatabase) {
        val days = Prefs.msgKeepDays(c)
        if (days <= 0) return
        try {
            val old = System.currentTimeMillis() - days * 24L * 3600L * 1000L
            d.delete("msgs", "ts < ?", arrayOf(old.toString()))
        } catch (e: Exception) {
            AppLog.w("MSGDB", "清理舊訊息失敗: $e")
        }
    }

    /** 查詢: q=搜尋字 (App 名稱/標題/內容), pkg=只看某個 App, unreadOnly=只看未讀 */
    fun query(c: Context, q: String, pkg: String?, unreadOnly: Boolean, limit: Int, conv: String? = null, ascending: Boolean = false): List<Msg> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (pkg != null) {
            where.add("pkg = ?")
            args.add(pkg)
        }
        if (conv != null) {
            where.add("$CONV = ?")
            args.add(conv)
        }
        if (unreadOnly) where.add("read = 0")
        if (q.isNotEmpty()) {
            where.add("(title LIKE ? OR body LIKE ? OR app LIKE ?)")
            val like = "%" + q + "%"
            args.add(like)
            args.add(like)
            args.add(like)
        }
        val out = ArrayList<Msg>()
        val cur = db(c).query(
            "msgs", null, if (where.isEmpty()) null else where.joinToString(" AND "),
            if (args.isEmpty()) null else args.toTypedArray(), null, null, "ts DESC", limit.toString()
        )
        cur.use {
            while (it.moveToNext()) {
                out.add(
                    Msg(
                        it.getLong(it.getColumnIndexOrThrow("id")),
                        it.getString(it.getColumnIndexOrThrow("pkg")) ?: "",
                        it.getString(it.getColumnIndexOrThrow("app")) ?: "",
                        it.getString(it.getColumnIndexOrThrow("title")) ?: "",
                        it.getString(it.getColumnIndexOrThrow("body")) ?: "",
                        it.getLong(it.getColumnIndexOrThrow("ts")),
                        it.getInt(it.getColumnIndexOrThrow("read")) != 0,
                        it.getString(it.getColumnIndexOrThrow("conv")) ?: ""
                    )
                )
            }
        }
        if (ascending) out.reverse()
        return out
    }

    /** 對話欄位: 沒有 conv 的舊資料用標題代替 */
    private const val CONV = "COALESCE(NULLIF(conv, ''), title)"

    fun apps(c: Context): List<AppCount> {
        val out = ArrayList<AppCount>()
        val cur = db(c).rawQuery(
            "SELECT pkg, app, COUNT(*), SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END), MAX(social), MAX(ts) FROM msgs GROUP BY pkg ORDER BY MAX(ts) DESC",
            null
        )
        cur.use {
            while (it.moveToNext()) out.add(AppCount(it.getString(0) ?: "", it.getString(1) ?: "", it.getInt(2), it.getInt(3), it.getInt(4) != 0, it.getLong(5)))
        }
        return out
    }

    /** 某個 App 的所有對話 (最近有訊息的排前面) */
    fun convs(c: Context, pkg: String): List<ConvCount> {
        val out = ArrayList<ConvCount>()
        val cur = db(c).rawQuery(
            "SELECT $CONV AS cv, COUNT(*), SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END), MAX(ts) FROM msgs WHERE pkg = ? GROUP BY cv ORDER BY MAX(ts) DESC",
            arrayOf(pkg)
        )
        cur.use {
            while (it.moveToNext()) out.add(ConvCount(it.getString(0) ?: "", it.getInt(1), it.getInt(2), it.getLong(3)))
        }
        return out
    }

    fun markConvRead(c: Context, pkg: String, conv: String) {
        val v = ContentValues()
        v.put("read", 1)
        db(c).update("msgs", v, "pkg = ? AND $CONV = ? AND read = 0", arrayOf(pkg, conv))
        version++
    }

    fun deleteConv(c: Context, pkg: String, conv: String) {
        db(c).delete("msgs", "pkg = ? AND $CONV = ?", arrayOf(pkg, conv))
        version++
    }

    fun unread(c: Context): Int {
        val cur = db(c).rawQuery("SELECT COUNT(*) FROM msgs WHERE read = 0", null)
        cur.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun total(c: Context): Int {
        val cur = db(c).rawQuery("SELECT COUNT(*) FROM msgs", null)
        cur.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun markRead(c: Context, id: Long) {
        val v = ContentValues()
        v.put("read", 1)
        db(c).update("msgs", v, "id = ? AND read = 0", arrayOf(id.toString()))
        version++
    }

    /** 手機上的通知被滑掉/已讀 → 把該通知最後一則標為已讀 */
    fun markReadByKey(c: Context, nkey: String) {
        db(c).execSQL("UPDATE msgs SET read = 1 WHERE id = (SELECT MAX(id) FROM msgs WHERE nkey = ?)", arrayOf<Any>(nkey))
        version++
    }

    fun markAllRead(c: Context, pkg: String?) {
        val v = ContentValues()
        v.put("read", 1)
        if (pkg == null) db(c).update("msgs", v, "read = 0", null)
        else db(c).update("msgs", v, "pkg = ? AND read = 0", arrayOf(pkg))
        version++
    }

    fun delete(c: Context, id: Long) {
        db(c).delete("msgs", "id = ?", arrayOf(id.toString()))
        version++
    }

    fun clear(c: Context, pkg: String?) {
        if (pkg == null) db(c).delete("msgs", null, null)
        else db(c).delete("msgs", "pkg = ?", arrayOf(pkg))
        version++
    }
}

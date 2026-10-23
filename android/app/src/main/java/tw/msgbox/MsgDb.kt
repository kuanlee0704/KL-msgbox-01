package tw.msgbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.CopyOnWriteArrayList

/** 資料庫裡的一則訊息 (conv = 對話/來源名稱, 已正規化, 不會是空字串) */
class Msg(
    val id: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val body: String,
    val ts: Long,
    val read: Boolean,
    val conv: String,
    val mine: Boolean = false          // true = 我自己送出的訊息 (由聊天畫面擷取)
)

/** 某個 App 在資料庫裡的統計 (已忽略來源的訊息不計入) */
class AppCount(
    val pkg: String, val app: String, val total: Int, val unread: Int, val social: Boolean,
    val lastTs: Long, val pinned: Boolean, val ignored: Int
)

/** 某個 App 內的一個對話 (聯絡人或群組); last = 最新一則的內容 */
class ConvCount(val conv: String, val total: Int, val unread: Int, val lastTs: Long, val last: String, val pinned: Boolean)

/** 被釘選的對話 (顯示在首頁最上方) */
class PinnedConv(val pkg: String, val app: String, val conv: String, val total: Int, val unread: Int, val lastTs: Long, val last: String)

/** 被忽略的來源 */
class IgnoredSrc(val conv: String, val ts: Long, val count: Int)

/**
 * 手機訊息資料庫: 把手機收到的通知存在手機上, 不必連著任何裝置、也不必開啟原本的 App 就能先看內容。
 * 資料只留在這支手機, 不會上傳。**預設永遠保留**: 只有你自己按刪除/清空才會刪;
 * 通知被滑掉或已讀只會標為已讀。想自動清掉舊訊息, 可在設定選保留天數 (Prefs.msgKeepDays, 0 = 永不)。
 *
 * 效能設計:
 *  - 對話名稱 (conv) 寫入時就正規化成固定欄位並建索引, 查詢不必每列做 COALESCE 運算。
 *  - 啟用 WAL: 背景寫入 (收錄通知) 不會卡住畫面讀取。
 *  - 對話清單用一次查詢帶出「最新一則內容」, 不再每個對話各查一次。
 *  - 資料變動時通知監聽者 (addListener), 畫面不必定時輪詢。
 *  - 釘選 / 忽略清單常駐記憶體, 判斷不用碰資料庫。
 *
 * 忽略來源: 被忽略的 (App, 來源) 不會顯示在任何地方 (清單、搜尋、未讀數、小工具), 之後新訊息也不再收錄;
 * 已存的舊訊息保留, 在該 App 的「此 App 設定 → 已忽略的來源」找回後會重新出現。
 */
object MsgDb {
    /** 資料每變動一次就 +1 */
    @Volatile var version = 0

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 資料變動時呼叫 (可能在任何執行緒; 收到後請自行切回主執行緒並 debounce) */
    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        version++
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }

    private class Helper(c: Context) : SQLiteOpenHelper(c.applicationContext, "msgs.db", null, 4) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, app TEXT, " +
                    "title TEXT, body TEXT, ts INTEGER, read INTEGER DEFAULT 0, nkey TEXT, conv TEXT NOT NULL, social INTEGER DEFAULT 0, mine INTEGER DEFAULT 0)"
            )
            db.execSQL("CREATE INDEX idx_msgs_pkg_conv ON msgs(pkg, conv)")
            db.execSQL("CREATE INDEX idx_msgs_ts ON msgs(ts)")
            db.execSQL("CREATE INDEX idx_msgs_unread ON msgs(pkg) WHERE read = 0")
            db.execSQL("CREATE INDEX idx_msgs_nkey ON msgs(nkey)")
            // conv = '' 代表整個 App
            db.execSQL("CREATE TABLE pins (pkg TEXT NOT NULL, conv TEXT NOT NULL, ts INTEGER, PRIMARY KEY (pkg, conv))")
            db.execSQL("CREATE TABLE ignored (pkg TEXT NOT NULL, conv TEXT NOT NULL, app TEXT, ts INTEGER, PRIMARY KEY (pkg, conv))")
        }

        /** v4: 清理舊版畫面擷取的雜訊 (nkey 為空 = 畫面擷取): 去掉「double tap…」說明與寄件人前綴, 刪除「Replying to…」標籤 */
        private fun cleanScreenRows(db: SQLiteDatabase) {
            val rows = ArrayList<Pair<Long, String>>()
            db.rawQuery("SELECT id, body FROM msgs WHERE nkey = ''", null).use {
                while (it.moveToNext()) rows.add(Pair(it.getLong(0), it.getString(1) ?: ""))
            }
            for ((id, body) in rows) {
                if (ChatText.isReplyLabel(body)) {
                    db.delete("msgs", "id = ?", arrayOf(id.toString()))
                    continue
                }
                val p = ChatText.parse(body)
                if (p.body.isEmpty()) {
                    db.delete("msgs", "id = ?", arrayOf(id.toString()))
                } else if (p.body != body) {
                    val v = ContentValues()
                    v.put("body", p.body)
                    db.update("msgs", v, "id = ?", arrayOf(id.toString()))
                }
            }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion in 3..3) cleanScreenRows(db)
            if (oldVersion < 3) db.execSQL("ALTER TABLE msgs ADD COLUMN mine INTEGER DEFAULT 0")
            if (oldVersion < 2) {
                // v2: 對話標題是「自己的帳號」、標題是「帳號: 對象」的舊資料 (如 Instagram), 改用對象當對話, 才分得出不同人
                db.execSQL(
                    "UPDATE msgs SET conv = TRIM(SUBSTR(title, LENGTH(conv) + 3)) " +
                        "WHERE conv <> '' AND LENGTH(title) > LENGTH(conv) + 2 AND SUBSTR(title, 1, LENGTH(conv) + 2) = conv || ': ' " +
                        "AND TRIM(SUBSTR(title, LENGTH(conv) + 3)) <> ''"
                )
            }
        }
    }

    private var helper: Helper? = null
    private var inserts = 0
    private val pinSet = HashSet<String>()
    private val ignoredSet = HashSet<String>()

    private fun k(pkg: String, conv: String) = pkg + "\u0000" + conv

    @Synchronized
    private fun db(c: Context): SQLiteDatabase {
        var h = helper
        if (h == null) {
            h = Helper(c)
            val d = h.writableDatabase
            helper = h
            d.rawQuery("SELECT pkg, conv FROM pins", null).use { while (it.moveToNext()) pinSet.add(k(it.getString(0), it.getString(1))) }
            d.rawQuery("SELECT pkg, conv FROM ignored", null).use { while (it.moveToNext()) ignoredSet.add(k(it.getString(0), it.getString(1))) }
            return d
        }
        return h.writableDatabase
    }

    /** 事先開好資料庫 (請在背景執行緒呼叫) */
    fun warmUp(c: Context) { db(c) }

    /** 對話名稱正規化: 優先用對話名稱, 沒有就用標題, 再沒有就用佔位字 */
    fun normConv(conv: String, title: String): String = conv.ifEmpty { title }.ifEmpty { "(無標題)" }

    /** 過濾掉已忽略來源的 SQL 條件; 沒有任何忽略時回傳 "1" (不用多做子查詢) */
    @Synchronized
    private fun hide(c: Context): String {
        db(c)
        return if (ignoredSet.isEmpty()) "1"
        else "NOT EXISTS (SELECT 1 FROM ignored i WHERE i.pkg = msgs.pkg AND i.conv = msgs.conv)"
    }

    // ---------------- 寫入 ----------------
    fun insert(c: Context, pkg: String, app: String, title: String, body: String, nkey: String, conv: String, social: Boolean) {
        val v = ContentValues()
        v.put("pkg", pkg)
        v.put("app", app)
        v.put("title", title)
        v.put("body", body)
        v.put("ts", System.currentTimeMillis())
        v.put("read", 0)
        v.put("nkey", nkey)
        v.put("conv", normConv(conv, title))
        v.put("social", if (social) 1 else 0)
        val d = db(c)
        d.insert("msgs", null, v)
        inserts++
        if (inserts % 50 == 1) prune(c, d)
        changed()
    }

    /** 記錄從聊天畫面讀到的訊息 (mine = 我送出的; 否則是對方的): 直接標為已讀, 不影響未讀數 */
    fun insertScreen(c: Context, pkg: String, app: String, conv: String, body: String, mine: Boolean) {
        val v = ContentValues()
        v.put("pkg", pkg)
        v.put("app", app)
        v.put("title", "")
        v.put("body", body.take(2000))
        v.put("ts", System.currentTimeMillis())
        v.put("read", 1)
        v.put("nkey", "")
        v.put("conv", normConv(conv, ""))
        v.put("social", 1)
        v.put("mine", if (mine) 1 else 0)
        db(c).insert("msgs", null, v)
        changed()
    }

    /** 最近 withinMs 毫秒內, 這個對話是否已有一樣內容的對方訊息 (避免和通知收錄的重複) */
    fun recentDuplicate(c: Context, pkg: String, conv: String, body: String, withinMs: Long): Boolean {
        db(c).rawQuery(
            "SELECT 1 FROM msgs WHERE pkg = ? AND conv = ? AND mine = 0 AND body = ? AND ts > ? LIMIT 1",
            arrayOf(pkg, conv, body.take(2000), (System.currentTimeMillis() - withinMs).toString())
        ).use { return it.moveToFirst() }
    }

    /** 這個對話裡出現過的寄件人名稱 (通知標題), 用來把畫面上「名字標籤」和真正的訊息分開 */
    fun knownNames(c: Context, pkg: String, conv: String): Set<String> {
        val out = HashSet<String>()
        out.add(conv)
        db(c).rawQuery("SELECT DISTINCT title FROM msgs WHERE pkg = ? AND conv = ? AND title <> '' LIMIT 60", arrayOf(pkg, conv)).use {
            while (it.moveToNext()) {
                val t = it.getString(0) ?: continue
                out.add(t)
                val i = t.lastIndexOf(": ")
                if (i >= 0 && i + 2 < t.length) out.add(t.substring(i + 2))
            }
        }
        return out
    }

    /** 刪除某個對話裡「畫面擷取」的訊息 (通知收錄的保留), 之後可重新擷取 */
    fun deleteScreenCaptured(c: Context, pkg: String, conv: String) {
        db(c).delete("msgs", "pkg = ? AND conv = ? AND nkey = ''", arrayOf(pkg, conv))
        changed()
    }

    /** 這個 App 是否已有這個名稱的對話 (用來比對聊天畫面上方的名稱) */
    fun convExists(c: Context, pkg: String, conv: String): Boolean {
        db(c).rawQuery("SELECT 1 FROM msgs WHERE pkg = ? AND conv = ? LIMIT 1", arrayOf(pkg, conv)).use { return it.moveToFirst() }
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

    fun markConvRead(c: Context, pkg: String, conv: String) {
        val v = ContentValues()
        v.put("read", 1)
        db(c).update("msgs", v, "pkg = ? AND conv = ? AND read = 0", arrayOf(pkg, conv))
        changed()
    }

    fun deleteConv(c: Context, pkg: String, conv: String) {
        db(c).delete("msgs", "pkg = ? AND conv = ?", arrayOf(pkg, conv))
        changed()
    }

    fun markRead(c: Context, id: Long) {
        val v = ContentValues()
        v.put("read", 1)
        db(c).update("msgs", v, "id = ? AND read = 0", arrayOf(id.toString()))
        changed()
    }

    /** 手機上的通知被滑掉/已讀 → 把該通知最後一則標為已讀 */
    fun markReadByKey(c: Context, nkey: String) {
        db(c).execSQL("UPDATE msgs SET read = 1 WHERE id = (SELECT MAX(id) FROM msgs WHERE nkey = ?)", arrayOf<Any>(nkey))
        changed()
    }

    fun markAllRead(c: Context, pkg: String?) {
        val v = ContentValues()
        v.put("read", 1)
        if (pkg == null) db(c).update("msgs", v, "read = 0", null)
        else db(c).update("msgs", v, "pkg = ? AND read = 0", arrayOf(pkg))
        changed()
    }

    fun delete(c: Context, id: Long) {
        db(c).delete("msgs", "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun clear(c: Context, pkg: String?) {
        if (pkg == null) db(c).delete("msgs", null, null)
        else db(c).delete("msgs", "pkg = ?", arrayOf(pkg))
        changed()
    }

    // ---------------- 查詢 ----------------
    /** 查詢: q=搜尋字 (App 名稱/標題/內容), pkg=只看某個 App, unreadOnly=只看未讀, conv=只看某個對話 */
    fun query(c: Context, q: String, pkg: String?, unreadOnly: Boolean, limit: Int, conv: String? = null, ascending: Boolean = false): List<Msg> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        where.add(hide(c))
        if (pkg != null) {
            where.add("pkg = ?")
            args.add(pkg)
        }
        if (conv != null) {
            where.add("conv = ?")
            args.add(conv)
        }
        if (unreadOnly) where.add("read = 0")
        if (q.isNotEmpty()) {
            where.add("(title LIKE ? OR body LIKE ? OR app LIKE ? OR conv LIKE ?)")
            val like = "%" + q + "%"
            repeat(4) { args.add(like) }
        }
        val out = ArrayList<Msg>()
        db(c).query(
            "msgs", null, where.joinToString(" AND "),
            if (args.isEmpty()) null else args.toTypedArray(), null, null, "ts DESC, id DESC", limit.toString()
        ).use {
            val iId = it.getColumnIndexOrThrow("id")
            val iPkg = it.getColumnIndexOrThrow("pkg")
            val iApp = it.getColumnIndexOrThrow("app")
            val iTitle = it.getColumnIndexOrThrow("title")
            val iBody = it.getColumnIndexOrThrow("body")
            val iTs = it.getColumnIndexOrThrow("ts")
            val iRead = it.getColumnIndexOrThrow("read")
            val iConv = it.getColumnIndexOrThrow("conv")
            val iMine = it.getColumnIndexOrThrow("mine")
            while (it.moveToNext()) {
                out.add(
                    Msg(
                        it.getLong(iId), it.getString(iPkg) ?: "", it.getString(iApp) ?: "", it.getString(iTitle) ?: "",
                        it.getString(iBody) ?: "", it.getLong(iTs), it.getInt(iRead) != 0, it.getString(iConv) ?: "", it.getInt(iMine) != 0
                    )
                )
            }
        }
        if (ascending) out.reverse()
        return out
    }

    /** App 清單: 釘選的排前面, 其次最近有訊息的; 只剩「已忽略來源」的 App 也會列出 (才找得回來) */
    fun apps(c: Context): List<AppCount> {
        val d = db(c)
        val ign = HashMap<String, Pair<String, Int>>()
        d.rawQuery("SELECT pkg, MAX(app), COUNT(*) FROM ignored GROUP BY pkg", null).use {
            while (it.moveToNext()) ign[it.getString(0) ?: ""] = Pair(it.getString(1) ?: "", it.getInt(2))
        }
        val out = ArrayList<AppCount>()
        val seen = HashSet<String>()
        d.rawQuery(
            "SELECT pkg, app, COUNT(*), SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END), MAX(social), MAX(ts) FROM msgs WHERE ${hide(c)} GROUP BY pkg",
            null
        ).use {
            while (it.moveToNext()) {
                val pkg = it.getString(0) ?: ""
                seen.add(pkg)
                out.add(
                    AppCount(
                        pkg, it.getString(1) ?: "", it.getInt(2), it.getInt(3), it.getInt(4) != 0, it.getLong(5),
                        isPinned(c, pkg, ""), ign[pkg]?.second ?: 0
                    )
                )
            }
        }
        for ((pkg, v) in ign) if (pkg !in seen) out.add(AppCount(pkg, v.first.ifEmpty { pkg }, 0, 0, false, 0L, isPinned(c, pkg, ""), v.second))
        return out.sortedWith(compareByDescending<AppCount> { it.pinned }.thenByDescending { it.lastTs })
    }

    /** 某個 App 的所有對話 (釘選的在前, 其次最近有訊息的); 一次查詢帶出各對話最新一則內容 */
    fun convs(c: Context, pkg: String): List<ConvCount> {
        val out = ArrayList<ConvCount>()
        db(c).rawQuery(
            "SELECT conv, COUNT(*), SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END), MAX(ts), " +
                "(SELECT (CASE WHEN m2.mine = 1 THEN '我: ' ELSE '' END) || COALESCE(NULLIF(m2.body, ''), m2.title) FROM msgs m2 WHERE m2.pkg = msgs.pkg AND m2.conv = msgs.conv ORDER BY m2.id DESC LIMIT 1) " +
                "FROM msgs WHERE pkg = ? AND ${hide(c)} GROUP BY conv",
            arrayOf(pkg)
        ).use {
            while (it.moveToNext()) {
                val cv = it.getString(0) ?: ""
                out.add(ConvCount(cv, it.getInt(1), it.getInt(2), it.getLong(3), it.getString(4) ?: "", isPinned(c, pkg, cv)))
            }
        }
        return out.sortedWith(compareByDescending<ConvCount> { it.pinned }.thenByDescending { it.lastTs })
    }

    fun unread(c: Context): Int {
        db(c).rawQuery("SELECT COUNT(*) FROM msgs WHERE read = 0 AND ${hide(c)}", null).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun total(c: Context): Int {
        db(c).rawQuery("SELECT COUNT(*) FROM msgs WHERE ${hide(c)}", null).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ---------------- 釘選 ----------------
    /** conv 傳空字串 = 釘選整個 App */
    @Synchronized
    fun isPinned(c: Context, pkg: String, conv: String): Boolean {
        db(c)
        return k(pkg, conv) in pinSet
    }

    fun setPinned(c: Context, pkg: String, conv: String, on: Boolean) {
        val d = db(c)
        if (on) {
            val v = ContentValues()
            v.put("pkg", pkg)
            v.put("conv", conv)
            v.put("ts", System.currentTimeMillis())
            d.insertWithOnConflict("pins", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            synchronized(this) { pinSet.add(k(pkg, conv)) }
        } else {
            d.delete("pins", "pkg = ? AND conv = ?", arrayOf(pkg, conv))
            synchronized(this) { pinSet.remove(k(pkg, conv)) }
        }
        changed()
    }

    /** 被釘選的對話 (首頁最上方), 最近釘選的在前; 已被忽略或已沒有訊息的略過 */
    fun pinnedConvs(c: Context): List<PinnedConv> {
        val d = db(c)
        val pins = ArrayList<Pair<String, String>>()
        d.rawQuery("SELECT pkg, conv FROM pins WHERE conv <> '' ORDER BY ts DESC", null).use {
            while (it.moveToNext()) pins.add(Pair(it.getString(0) ?: "", it.getString(1) ?: ""))
        }
        val out = ArrayList<PinnedConv>()
        for ((pkg, cv) in pins) {
            if (isIgnored(c, pkg, cv)) continue
            var app = pkg
            var total = 0
            var unread = 0
            var lastTs = 0L
            d.rawQuery(
                "SELECT MAX(app), COUNT(*), SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END), MAX(ts) FROM msgs WHERE pkg = ? AND conv = ?",
                arrayOf(pkg, cv)
            ).use {
                if (it.moveToFirst()) {
                    app = it.getString(0) ?: pkg
                    total = it.getInt(1)
                    unread = it.getInt(2)
                    lastTs = it.getLong(3)
                }
            }
            if (total == 0) continue
            var last = ""
            d.rawQuery(
                "SELECT (CASE WHEN mine = 1 THEN '我: ' ELSE '' END) || COALESCE(NULLIF(body, ''), title) FROM msgs WHERE pkg = ? AND conv = ? ORDER BY id DESC LIMIT 1",
                arrayOf(pkg, cv)
            ).use { if (it.moveToFirst()) last = it.getString(0) ?: "" }
            out.add(PinnedConv(pkg, app, cv, total, unread, lastTs, last))
        }
        return out
    }

    // ---------------- 忽略來源 ----------------
    @Synchronized
    fun isIgnored(c: Context, pkg: String, conv: String): Boolean {
        db(c)
        return k(pkg, conv) in ignoredSet
    }

    /** 忽略某個 App 的某個來源 (對話/聯絡人): 不再顯示、不再收錄; 舊訊息保留, 找回後重新出現 */
    fun ignore(c: Context, pkg: String, app: String, conv: String) {
        val d = db(c)
        val v = ContentValues()
        v.put("pkg", pkg)
        v.put("conv", conv)
        v.put("app", app)
        v.put("ts", System.currentTimeMillis())
        d.insertWithOnConflict("ignored", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        d.delete("pins", "pkg = ? AND conv = ?", arrayOf(pkg, conv))
        synchronized(this) {
            ignoredSet.add(k(pkg, conv))
            pinSet.remove(k(pkg, conv))
        }
        changed()
    }

    fun unignore(c: Context, pkg: String, conv: String) {
        db(c).delete("ignored", "pkg = ? AND conv = ?", arrayOf(pkg, conv))
        synchronized(this) { ignoredSet.remove(k(pkg, conv)) }
        changed()
    }

    fun unignoreAll(c: Context, pkg: String) {
        db(c).delete("ignored", "pkg = ?", arrayOf(pkg))
        synchronized(this) { ignoredSet.removeAll { it.startsWith(pkg + "\u0000") } }
        changed()
    }

    /** 某個 App 已忽略的來源 (最近忽略的在前), count = 還留著的舊訊息數 */
    fun ignoredList(c: Context, pkg: String): List<IgnoredSrc> {
        val out = ArrayList<IgnoredSrc>()
        db(c).rawQuery(
            "SELECT i.conv, i.ts, (SELECT COUNT(*) FROM msgs WHERE msgs.pkg = i.pkg AND msgs.conv = i.conv) " +
                "FROM ignored i WHERE i.pkg = ? ORDER BY i.ts DESC",
            arrayOf(pkg)
        ).use { while (it.moveToNext()) out.add(IgnoredSrc(it.getString(0) ?: "", it.getLong(1), it.getInt(2))) }
        return out
    }
}

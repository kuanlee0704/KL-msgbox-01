package tw.msgbox

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * 聊天畫面擷取 (實驗性): 通知只看得到「對方」且「沒開著那個聊天」時的訊息。
 * 你自己送出的、以及你正開著聊天時對方傳來的 (App 不會跳通知), 只能從畫面讀:
 * 用無障礙服務讀聊天畫面, 把「我」和「對方」的氣泡分開記錄。
 *
 * 隱私與範圍:
 *  - 預設關閉; 只有在設定裡開啟、且只對你勾選的 App 才會收到事件 (系統層就過濾, 其他 App 完全不經手)。
 *  - 不讀輸入框、不讀密碼欄; 全部只存在這支手機, 沒有任何網路功能。
 *
 * 怎麼辨識一則訊息 (畫面要有輸入框才算聊天畫面; 只看標題列以下、輸入框以上):
 *  1. 有些 App (Messenger) 把訊息寫成「寄件人, 內容, double tap to see…」: 拆出寄件人與內容,
 *     寄件人是「我的名字」(設定) 就是我, 否則是對方; 說明文字不會存進資料庫。
 *  2. 其他 App 用位置判斷: 右邊緣 >= 86% 螢幕寬 → 我; 靠左半邊 → 對方 (可在設定關閉)。
 *  3. 排除「已讀/Seen/時間」「You replied to …」等標籤、回覆標籤後面那則被引用的原文、
 *     這個對話裡出現過的寄件人名稱標籤; 同一則訊息被畫面重複描述 (文字節點 + 說明節點) 時只算一則。
 * 哪些是新的: 把每次看到的 (我/對方 + 內容) 依序「拼接」成這個對話的已知清單, 只有接在清單尾端的新內容才記錄,
 * 所以往上捲看舊訊息、再捲回來不會重複記錄; 進入對話當下看到的歷史只當基準, 不記錄 (避免時間順序錯亂)。
 * 對方的訊息若通知已經收錄過 (最近 5 分鐘內相同內容) 就不重複記錄。
 */
class ChatAccess : AccessibilityService() {
    companion object {
        @Volatile var inst: ChatAccess? = null
        /** 設定頁的「除錯」按鈕: 下一次掃描把畫面上每個節點的位置與判定結果寫進記錄 */
        @Volatile var dumpNext = false
        @Volatile var lastInfo = ""

        /** 設定改變後呼叫: 重新套用「要監看哪些 App」 */
        fun applyConfig() { inst?.apply() }

        private val noise = Regex(
            "^(seen|delivered|sent|sending|failed|read|edited|active now|active today|typing|已讀|已送出|已送達|傳送中|傳送失敗|已編輯|已收回|今天|昨天|today|yesterday).{0,24}$",
            RegexOption.IGNORE_CASE
        )
        private val timeLike = Regex("^[\\d:/.\\-\\s年月日上下午AMPamp今昨天TodayesterdayMonTueWedThuFriSatSun]{1,16}$")

        fun isNoise(t: String): Boolean =
            t.length <= 28 && (noise.matches(t) || (timeLike.matches(t) && t.any { it.isDigit() })) || ChatText.isReplyLabel(t)

        private val defaultSelf = setOf("you", "你", "我", "自己")
    }

    /** desc = true: 這個節點沒有文字、只有描述 (contentDescription) */
    private class Item(
        val text: String, val l: Int, val t: Int, val r: Int, val b: Int, val desc: Boolean,
        val cls: String, val id: String
    )

    /** 掃描出的候選訊息: sender != null 表示來自「寄件人, 內容, double tap…」格式, 要等知道對話後才能判斷是誰; geo 是位置判斷 (1 我 / 2 對方) */
    private class Cand(val sender: String?, val body: String, val geo: Int, val t: Int, val b: Int)

    private data class Line(val mine: Boolean, val text: String)

    @Volatile private var allowed: Set<String> = emptySet()
    private val h = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val scan = Runnable { scanNow() }

    // 以下只在 exec 執行緒使用
    private var sessKey: String? = null
    private val known = ArrayList<Line>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.init(this)
        inst = this
        apply()
        AppLog.i("CHAT", "無障礙服務已連線")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        inst = null
        exec.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun apply() {
        allowed = if (Prefs.chatOn(this)) Prefs.chatApps(this) else emptySet()
        try {
            val info = serviceInfo
            if (info != null) {
                info.packageNames = if (allowed.isEmpty()) arrayOf("tw.msgbox.none") else allowed.toTypedArray()
                serviceInfo = info
            }
        } catch (e: Exception) {
            AppLog.w("CHAT", "套用設定失敗: $e")
        }
        exec.execute { sessKey = null; known.clear() }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg !in allowed) return
        h.removeCallbacks(scan)
        h.postDelayed(scan, 700)                          // 畫面穩定後才讀 (等訊息載入、動畫結束)
    }

    @Suppress("DEPRECATION")
    private fun scanNow() {
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            if (pkg !in allowed) return
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val hgt = dm.heightPixels
            val items = ArrayList<Item>()
            var inputTop = Int.MAX_VALUE
            var count = 0
            val r = Rect()

            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (++count > 2500 || depth > 45) return
                if (!n.isVisibleToUser || n.isPassword) return
                n.getBoundsInScreen(r)
                if (n.isEditable) {                       // 輸入框: 只記位置, 不讀內容
                    if (r.top < inputTop) inputTop = r.top
                    return
                }
                val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    items.add(Item(t, r.left, r.top, r.right, r.bottom, false, cls, id))
                } else {
                    val d = n.contentDescription?.toString()?.trim()
                    if (!d.isNullOrEmpty()) items.add(Item(d, r.left, r.top, r.right, r.bottom, true, cls, id))
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    walk(c, depth + 1)
                    c.recycle()
                }
            }
            walk(root, 0)
            val both = Prefs.chatBoth(this)

            // 能當訊息的節點: 有文字的, 或描述裡帶 "double tap…" 的 (整列訊息的說明)
            val sorted = items.filter { !it.desc || ChatText.hasTail(it.text) }.sortedBy { it.t }
            // 回覆標籤 (You replied to Roxy) 後面的第一則非狀態文字是「被引用的原文」, 不是訊息
            val quote = HashSet<Item>()
            for (i in sorted.indices) {
                if (!sorted[i].desc && ChatText.isReplyLabel(sorted[i].text)) {
                    var j = i + 1
                    while (j < sorted.size && isNoise(sorted[j].text)) j++
                    if (j < sorted.size) quote.add(sorted[j])
                }
            }

            if (dumpNext) {
                dumpNext = false
                AppLog.i("CHAT", "== 畫面結構 $pkg 螢幕 ${w}x$hgt 輸入框上緣=${if (inputTop == Int.MAX_VALUE) "無" else inputTop.toString()} 節點=$count 擷取對方=$both")
                for (item in items.sortedBy { x -> x.t }) {
                    val p = ChatText.parse(item.text)
                    val tag = when {
                        item.desc && !p.hadTail -> "[描述]"
                        item in quote -> "[引用]"
                        isNoise(item.text) -> "[標籤]"
                        p.sender != null -> "[列:${p.sender.take(8)}]"
                        geoSide(item, w, both) == 1 -> "[我]"
                        geoSide(item, w, both) == 2 -> "[對方]"
                        else -> "[略過]"
                    }
                    AppLog.i("CHAT", "$tag ${item.cls}#${item.id} (${item.l},${item.t},${item.r},${item.b}) " + item.text.take(120).replace('\n', ' '))
                }
            }

            if (inputTop == Int.MAX_VALUE) {
                lastInfo = "最近掃描 ${label(pkg)}: 沒有輸入框, 不是聊天畫面"
                return
            }
            val header = items.filter { !it.desc && it.b <= hgt * 0.17 && !isNoise(it.text) }
                .sortedWith(compareBy({ it.t }, { it.l })).map { it.text }.distinct()

            val cands = ArrayList<Cand>()
            for (x in sorted) {
                if (x in quote || x.t < hgt * 0.14 || x.b > inputTop) continue
                val p = ChatText.parse(x.text)
                if (p.body.isEmpty() || isNoise(p.body) || ChatText.isReplyLabel(p.body)) continue
                val geo = if (p.sender == null) geoSide(x, w, both) else 0
                if (p.sender == null && geo == 0) continue
                // 同一則訊息被重複描述 (文字節點 + 說明節點, 位置重疊、內容相同): 只算一則
                if (cands.any { c -> c.body == p.body && c.t < x.b && x.t < c.b }) continue
                cands.add(Cand(p.sender, p.body, geo, x.t, x.b))
            }
            exec.execute {
                try {
                    process(pkg, header, cands, both)
                } catch (e: Exception) {
                    AppLog.e("CHAT", "處理畫面失敗: $e")
                }
            }
        } finally {
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** 位置判斷: 1 = 靠右 (我), 2 = 靠左 (對方), 0 = 都不是 */
    private fun geoSide(x: Item, w: Int, both: Boolean): Int {
        if (x.r >= w * 0.86) return if ((x.r - x.l) < w * 0.9) 1 else 0
        return if (both && x.l <= w * 0.5) 2 else 0
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** 寄件人名稱是不是我: 先看設定的「我的名字」, 沒設定就用 You/你, 再不然「跟對話名稱無關的就是我」 */
    private fun isSelf(sender: String, conv: String, names: Set<String>): Boolean {
        val s = sender.trim().lowercase()
        if (s in defaultSelf) return true
        val custom = Prefs.chatSelf(this).split(',', '，', '、', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (custom.isNotEmpty()) return custom.any { s == it || s.contains(it) }
        val cl = conv.lowercase()
        if (s == cl || cl.contains(s) || s.contains(cl)) return false
        if (names.any { it.lowercase() == s }) return false
        return true
    }

    private fun process(pkg: String, header: List<String>, cands: List<Cand>, both: Boolean) {
        var conv: String? = null
        for (cand in header) if (MsgDb.convExists(this, pkg, cand)) {
            conv = cand
            break
        }
        if (conv == null) conv = header.firstOrNull()?.take(80)
        if (conv == null) {
            lastInfo = "最近掃描 ${label(pkg)}: 找不到對話名稱"
            return
        }
        if (MsgDb.isIgnored(this, pkg, conv)) return
        // 去掉「名字標籤」(群組聊天氣泡上方的小字寄件人名稱) 和對話標題本身
        val names = MsgDb.knownNames(this, pkg, conv)
        val lines = ArrayList<Line>()
        for (c in cands) {
            val mine = if (c.sender != null) isSelf(c.sender, conv, names) else c.geo == 1
            if (!mine && (!both || c.body in names)) continue
            lines.add(Line(mine, c.body))
        }
        val nMine = lines.count { it.mine }
        val nTheirs = lines.size - nMine

        val key = pkg + "\u0000" + conv
        if (key != sessKey) {                              // 剛進入這個對話: 把看到的當作基準, 不記錄
            sessKey = key
            known.clear()
            known.addAll(lines)
            lastInfo = "最近掃描 ${label(pkg)} · $conv: 基準 (我 $nMine / 對方 $nTheirs)"
            AppLog.i("CHAT", lastInfo)
            return
        }
        val added = merge(lines)
        var saved = 0
        for (ln in added) {
            if (!ln.mine && MsgDb.recentDuplicate(this, pkg, conv, ln.text, 5 * 60 * 1000L)) continue   // 通知已收錄過
            MsgDb.insertScreen(this, pkg, label(pkg).take(24), conv, ln.text, ln.mine)
            saved++
        }
        if (known.size > 300) known.subList(0, known.size - 300).clear()
        lastInfo = "最近掃描 ${label(pkg)} · $conv: 畫面上 我 $nMine / 對方 $nTheirs, 新增 $saved 則"
        if (saved > 0) AppLog.i("CHAT", "$lastInfo: " + added.joinToString(" / ") { (if (it.mine) "我:" else "對:") + it.text.take(12) })
    }

    /** 把目前畫面看到的訊息 (由上到下) 拼進已知清單, 回傳「新出現在尾端的」那些 */
    private fun merge(cur: List<Line>): List<Line> {
        if (cur.isEmpty()) return emptyList()
        if (known.isEmpty()) {                             // 沒有基準可比對: 不冒險記錄
            known.addAll(cur)
            return emptyList()
        }
        if (indexOfSub(known, cur) >= 0) return emptyList()   // 都是已知的 (捲動)
        val max = minOf(known.size, cur.size)
        for (k in max downTo 1) {                          // 接在尾端: 新訊息
            if (known.subList(known.size - k, known.size) == cur.subList(0, k)) {
                val add = ArrayList(cur.subList(k, cur.size))
                known.addAll(add)
                return add
            }
        }
        for (k in max downTo 1) {                          // 接在前端: 往上捲看到更舊的
            if (cur.subList(cur.size - k, cur.size) == known.subList(0, k)) {
                known.addAll(0, cur.subList(0, cur.size - k))
                return emptyList()
            }
        }
        // 邊界對不上 (例如最後幾則的文字在傳送中途變了): 改用「已知清單最後幾則」在畫面上找錨點, 錨點之後的就是新的
        for (i in known.size - 1 downTo maxOf(0, known.size - 5)) {
            val j = cur.lastIndexOf(known[i])
            if (j >= 0) {
                val add = ArrayList(cur.subList(j + 1, cur.size))
                known.addAll(add)
                return add
            }
        }
        known.clear()                                      // 完全對不上 (跳到別處): 重設基準, 不記錄
        known.addAll(cur)
        return emptyList()
    }

    private fun indexOfSub(big: List<Line>, small: List<Line>): Int {
        if (small.size > big.size) return -1
        for (i in 0..big.size - small.size) if (big.subList(i, i + small.size) == small) return i
        return -1
    }
}

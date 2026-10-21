package tw.msgbox

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全 App 共用的記錄 (連線、指令、錯誤都寫這裡)。
 * 同時存在記憶體 (最近 400 行) 與檔案 (重開 App 還看得到)。「記錄」分頁顯示的就是這份。
 * 用法: AppLog.i("BLE", "已連上") / AppLog.w(...) / AppLog.e(...)
 */
object AppLog {
    private const val MAX = 400
    private val buf = ArrayList<String>()
    private var file: File? = null

    @Volatile var version: Int = 0
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "applog.txt")
        file = f
        try {
            if (f.exists()) {
                val ls = f.readLines()
                val tail = if (ls.size > 200) ls.subList(ls.size - 200, ls.size) else ls
                buf.addAll(tail)
                if (ls.size > 1000) f.writeText(tail.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
        version++
    }

    @Synchronized
    fun add(level: String, tag: String, msg: String) {
        val t = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date())
        val line = "$t $level/$tag $msg"
        buf.add(line)
        while (buf.size > MAX) buf.removeAt(0)
        try {
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
        version++
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    /** onlyProblems = true 時只留警告(W)與錯誤(E) */
    @Synchronized
    fun text(onlyProblems: Boolean): String {
        val src = if (onlyProblems) buf.filter { it.length > 15 && (it[15] == 'W' || it[15] == 'E') } else buf
        return src.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        buf.clear()
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
        version++
    }
}

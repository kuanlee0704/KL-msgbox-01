package tw.msgbox

/**
 * 聊天 App (例如 Messenger) 會把一則訊息的無障礙文字寫成:
 *   「寄件人, 訊息內容, double tap to see sent/receive date and time, double tap and hold to react on message」
 * 這裡把它拆成 (寄件人, 內容), 並辨識「You replied to Roxy」這類回覆標籤 (它們不是訊息, 其後一則是被引用的原文)。
 */
object ChatText {
    class Parsed(val sender: String?, val body: String, val hadTail: Boolean)

    /** 說明尾巴: 以逗號開頭的 "double tap …" (或中文的連按兩下…), 一直到結尾 */
    private val tail = Regex("(?is)[,，]\\s*(double[ -]?tap|連按兩下|輕觸兩下|點兩下|按兩下).*$")

    private val replyLabels = listOf(
        Regex("(?i)^(you )?replied to .{1,60}$"),
        Regex("(?i)^replying to .{1,60}$"),
        Regex("(?i)^.{1,40} replied to (you|.{1,40})$"),
        Regex("^你回覆(了|給)?.{1,40}$"),
        Regex("^.{1,40}回覆了你$")
    )

    fun hasTail(raw: String): Boolean = tail.containsMatchIn(raw)

    fun parse(raw: String): Parsed {
        val t = raw.trim()
        val m = tail.find(t) ?: return Parsed(null, t, false)
        val core = t.substring(0, m.range.first).trim()
        val i = core.indexOf(", ")
        if (i in 1..40) return Parsed(core.substring(0, i).trim(), core.substring(i + 2).trim(), true)
        return Parsed(null, core, true)
    }

    fun isReplyLabel(t: String): Boolean = t.length <= 70 && replyLabels.any { it.matches(t.trim()) }
}

package tw.msgbox

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 共用的顏色與小元件 (全部用程式碼建立, 不用 XML)。 */
object Ui {
    val BG = 0xFFF2F4F7.toInt()
    val CARD = 0xFFFFFFFF.toInt()
    val PRIMARY = 0xFF1565C0.toInt()
    val TEXT = 0xFF212121.toInt()
    val SUB = 0xFF616161.toInt()
    val BAD = 0xFFC62828.toInt()
    val OK = 0xFF2E7D32.toInt()
    val MINE = 0xFFD6E8FA.toInt()
    val WARN_BG = 0xFFFFF3E0.toInt()

    private val palette = intArrayOf(
        0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(),
        0xFF3949AB.toInt(), 0xFF00897B.toInt(), 0xFFD84315.toInt(), 0xFF6D4C41.toInt()
    )

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun round(c: Context, color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(c, radiusDp).toFloat()
        return g
    }

    fun oval(color: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        return g
    }

    /** 系統的點擊水波紋 (借用主題裡的 selectableItemBackground) */
    fun ripple(c: Context, borderless: Boolean): Drawable? {
        val a = c.obtainStyledAttributes(
            intArrayOf(if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground)
        )
        val d = a.getDrawable(0)
        a.recycle()
        return d
    }

    /** 狀態列/導覽列跟頁面背景同色 (淺色), 圖示用深色 */
    @Suppress("DEPRECATION")
    fun styleWindow(a: Activity) {
        a.window.statusBarColor = BG
        a.window.navigationBarColor = BG
        a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    /** 標題列上的圖示按鈕 (48dp 觸控範圍, 無邊框水波紋) */
    fun iconBtn(c: Context, glyph: String, desc: String, size: Float = 24f, onClick: () -> Unit): TextView {
        val b = TextView(c)
        b.text = glyph
        b.textSize = size
        b.setTextColor(TEXT)
        b.gravity = Gravity.CENTER
        b.contentDescription = desc
        b.background = ripple(c, true)
        b.layoutParams = LinearLayout.LayoutParams(dp(c, 44), dp(c, 48))
        b.setOnClickListener { onClick() }
        return b
    }

    /** 文字按鈕 (主色粗體, 有水波紋), 取代厚重的預設按鈕 */
    fun textBtn(c: Context, label: String, onClick: () -> Unit): TextView {
        val b = TextView(c)
        b.text = label
        b.textSize = 14f
        b.setTextColor(PRIMARY)
        b.setTypeface(null, Typeface.BOLD)
        b.gravity = Gravity.CENTER_VERTICAL
        b.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        b.background = ripple(c, false)
        b.setOnClickListener { onClick() }
        return b
    }

    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "?"
        return String(Character.toChars(t.codePointAt(0))).uppercase()
    }

    /** 圓形頭像: 取名字第一個字, 顏色依名字固定 */
    fun avatar(c: Context, name: String): TextView {
        val v = TextView(c)
        v.text = initial(name)
        v.textSize = 18f
        v.setTextColor(Color.WHITE)
        v.setTypeface(null, Typeface.BOLD)
        v.gravity = Gravity.CENTER
        v.background = oval(palette[(name.hashCode() and 0x7fffffff) % palette.size])
        return v
    }

    private val icons = HashMap<String, Drawable?>()

    /** App 圖示; 取不到就退回文字頭像 */
    fun appIcon(c: Context, pkg: String, name: String): View {
        val d = synchronized(icons) {
            if (!icons.containsKey(pkg)) {
                icons[pkg] = try {
                    c.packageManager.getApplicationIcon(pkg)
                } catch (_: Exception) {
                    null
                }
            }
            icons[pkg]
        }
        if (d == null) return avatar(c, name)
        val iv = ImageView(c)
        iv.setImageDrawable(d.constantState?.newDrawable() ?: d)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        return iv
    }

    /** 未讀數徽章 (主色圓角膠囊) */
    fun badge(c: Context, n: Int): TextView {
        val b = TextView(c)
        b.text = if (n > 99) "99+" else n.toString()
        b.textSize = 11f
        b.setTextColor(Color.WHITE)
        b.setTypeface(null, Typeface.BOLD)
        b.gravity = Gravity.CENTER
        b.minWidth = dp(c, 20)
        b.setPadding(dp(c, 6), dp(c, 1), dp(c, 6), dp(c, 1))
        b.background = round(c, PRIMARY, 10)
        return b
    }
}

package tw.claudegate.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 很小的畫面工具：這個 App 刻意不依賴 AndroidX，
 * 畫面都用框架元件在程式裡組，這裡集中顏色與常用元件。
 */
object Ui {
    const val BG = 0xFF1F1E1D.toInt()
    const val CARD = 0xFF2B2A28.toInt()
    const val TEXT = 0xFFF3F0E8.toInt()
    const val MUTED = 0xFFB5B0A5.toInt()
    const val ACCENT = 0xFFD97757.toInt()
    const val OK = 0xFF7FB77E.toInt()
    const val WARN = 0xFFE0A458.toInt()
    const val BUBBLE_ME = 0xFF3D4A5C.toInt()
    const val BUBBLE_CLAUDE = 0xFF3A332D.toInt()

    fun Context.dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = with(ctx) { dp(radiusDp).toFloat() }
        }

    fun column(ctx: Context, padDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = with(ctx) { dp(padDp) }
        setPadding(p, p, p, p)
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = TEXT, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun section(ctx: Context, title: String) = text(ctx, title, 16f, ACCENT, bold = true).apply {
        layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply {
            topMargin = with(ctx) { dp(22) }
            bottomMargin = with(ctx) { dp(4) }
        }
    }

    fun note(ctx: Context, s: String) = text(ctx, s, 12.5f, MUTED)

    fun edit(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setTextColor(TEXT)
            setHintTextColor(0xFF77736B.toInt())
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

    fun button(ctx: Context, label: String, primary: Boolean = true, onClick: (View) -> Unit) =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) 0xFF1F1E1D.toInt() else TEXT)
            background = rounded(ctx, if (primary) ACCENT else CARD, 10)
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply {
                topMargin = with(ctx) { dp(6) }
            }
        }

    fun match() = ViewGroup.LayoutParams.MATCH_PARENT
    fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 橫排時平均分寬 */
    fun weighted(v: View, startMarginPx: Int = 0): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f).apply { marginStart = startMarginPx }
    }
}

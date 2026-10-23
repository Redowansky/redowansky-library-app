package app.pepper.library

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = Color.BLACK
    val PANEL = Color.rgb(13, 17, 23)
    val GREEN = Color.rgb(57, 211, 83)
    val TEXT = Color.rgb(230, 237, 243)
    val DIM = Color.rgb(139, 148, 158)
    val CARD = Color.rgb(22, 27, 34)

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun text(
        context: Context, s: String, sizeSp: Float, color: Int,
        bold: Boolean = false, gravity: Int = Gravity.START
    ): TextView = TextView(context).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        this.gravity = gravity
    }

    fun greenButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(GREEN)
                cornerRadius = context.dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }

    fun ghostButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(TEXT)
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = context.dp(8).toFloat()
                setStroke(context.dp(1), Color.rgb(48, 54, 61))
            }
            setOnClickListener { onClick() }
        }

    fun vspace(context: Context, hDp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, context.dp(hDp)
        )
    }

    /** Rounded solid drawable — the workhorse of the modern UI. */
    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(context.dp(1), strokeColor)
        }

    /** Small pill badge that sits on poster cards (type, year, rating…). */
    fun badge(context: Context, label: String, bg: Int, fg: Int): TextView =
        TextView(context).apply {
            text = label
            textSize = 9f
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(3))
            background = rounded(context, bg, 6)
        }

    /** Semi-transparent dark pill used on top of artwork. */
    fun scrimBadge(context: Context, label: String, fg: Int): TextView =
        badge(context, label, android.graphics.Color.argb(190, 0, 0, 0), fg)

    /** Bottom-to-top black fade placed over the lower part of artwork. */
    fun fade(context: Context): View = View(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(android.graphics.Color.argb(200, 0, 0, 0), android.graphics.Color.TRANSPARENT)
        )
    }
}

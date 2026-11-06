package com.avonotes.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.avonotes.R
import kotlin.math.min

/**
 * A card-style menu: a header describing what it acts on (preview or icon, title, details), then
 * rows with an icon, a label and an optional description. Destructive rows are drawn in red and
 * belong at the end, after a divider.
 */
class ActionSheet(private val context: Context) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8f), 0, dp(8f))
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** The thing being acted on: a [preview] image if there is one, otherwise [iconRes]. */
    fun header(title: String, subtitle: String?, preview: Bitmap? = null, iconRes: Int = 0): ActionSheet {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(14f))
        }
        if (preview != null || iconRes != 0) {
            val image = ImageView(context).apply {
                val r = 10 * d
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, r)
                }
                clipToOutline = true
                if (preview != null) {
                    setImageBitmap(preview)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(context.getColor(R.color.paper_preview))
                } else {
                    setImageResource(iconRes)
                    imageTintList = context.getColorStateList(R.color.accent)
                    background = GradientDrawable().apply { setColor(context.getColor(R.color.tool_selected_bg)); cornerRadius = r }
                    setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val w = if (preview != null) 88f else 56f
            row.addView(image, LinearLayout.LayoutParams(dp(w), dp(if (preview != null) 66f else 56f)).apply { marginEnd = dp(14f) })
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = title; textSize = 18f; setTextColor(context.getColor(R.color.icon))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrEmpty()) texts.addView(TextView(context).apply {
            text = subtitle; textSize = 13f; setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(2f), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, full())
        divider()
        return this
    }

    fun item(iconRes: Int, label: String, description: String? = null, destructive: Boolean = false, onClick: () -> Unit): ActionSheet {
        val color = context.getColor(if (destructive) R.color.danger else R.color.icon)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
            background = context.getDrawable(R.drawable.tool_bg)
            isClickable = true
            setOnClickListener { onClick(); dialog.dismiss() }
        }
        row.addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(18f) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(color) })
        if (description != null) texts.addView(TextView(context).apply {
            text = description; textSize = 13f; setTextColor(context.getColor(R.color.text_secondary))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row, full())
        return this
    }

    fun item(iconRes: Int, labelRes: Int, destructive: Boolean = false, onClick: () -> Unit) =
        item(iconRes, context.getString(labelRes), null, destructive, onClick)

    fun divider(): ActionSheet {
        box.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.toolbar_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1)).apply {
                topMargin = dp(4f); bottomMargin = dp(4f)
            })
        return this
    }

    /** Called when the sheet closes, whether an item was picked or not. */
    fun onDismiss(block: () -> Unit): ActionSheet {
        dialog.setOnDismissListener { block() }
        return this
    }

    fun show() {
        val card = ScrollView(context).apply {
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.popup_bg)); cornerRadius = 18 * d
            }
            clipToOutline = true
            addView(box)
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = min(dp(420f), context.resources.displayMetrics.widthPixels - dp(32f))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}

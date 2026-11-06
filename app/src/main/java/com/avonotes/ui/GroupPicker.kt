package com.avonotes.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.avonotes.R
import kotlin.math.min

/**
 * Picks the group a note goes into. Built for many groups: a search box, a scrolling list of
 * limited height, and rows that tell groups apart (latest note's preview and title, note count,
 * last change), with the current group checked. "No group" comes first, "New group…" last.
 */
class GroupPicker(
    private val context: Context,
    private val noteTitle: String,
    entries: List<Entry>,
    private val currentId: String?,
    private val onChoice: (Choice) -> Unit,
) {
    class Entry(val id: String, val name: String, val detail: String, val preview: Bitmap?, val lastChange: Long)

    sealed class Choice {
        object None : Choice()
        object New : Choice()
        class Group(val id: String) : Choice()
    }

    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

    // Current group first, then most recently changed, then by name.
    private val sorted = entries.sortedWith(
        compareByDescending<Entry> { it.id == currentId }.thenByDescending { it.lastChange }.thenBy { it.name.lowercase() },
    )
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun show() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18f), 0, dp(8f))
        }
        box.addView(TextView(context).apply {
            setText(R.string.move_to_group); textSize = 18f; setTextColor(context.getColor(R.color.icon))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(20f), 0, dp(20f), 0)
        }, full())
        box.addView(TextView(context).apply {
            text = noteTitle; textSize = 13f; setTextColor(context.getColor(R.color.text_secondary))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(20f), dp(2f), dp(20f), dp(10f))
        }, full())

        if (sorted.size > 5) {
            val search = EditText(context).apply {
                setHint(R.string.search_groups)
                textSize = 15f
                isSingleLine = true
                background = context.getDrawable(R.drawable.search_bg)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
                compoundDrawablePadding = dp(10f)
                compoundDrawableTintList = ColorStateList.valueOf(context.getColor(R.color.text_secondary))
                setTextColor(context.getColor(R.color.icon))
                setHintTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(14f), 0, dp(14f), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) = fill(s?.toString().orEmpty())
                })
            }
            box.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f)).apply {
                marginStart = dp(16f); marginEnd = dp(16f); bottomMargin = dp(8f)
            })
        }

        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = true; addView(list) }
        box.addView(scroll, full())
        fill("")

        box.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.toolbar_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1)).apply {
                topMargin = dp(4f); bottomMargin = dp(4f)
            })
        box.addView(row(R.drawable.ic_add, null, context.getString(R.string.new_group_ellipsis), null, false) {
            pick(Choice.New)
        }, full())

        val card = LinearLayout(context).apply {
            background = GradientDrawable().apply { setColor(context.getColor(R.color.popup_bg)); cornerRadius = 18 * d }
            clipToOutline = true
            addView(box, full())
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(min(dp(460f), context.resources.displayMetrics.widthPixels - dp(32f)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
    }

    private fun pick(c: Choice) { dialog.dismiss(); onChoice(c) }

    /** Rebuilds the list for [query]. */
    private fun fill(query: String) {
        list.removeAllViews()
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            list.addView(row(R.drawable.ic_folder, null, context.getString(R.string.no_group),
                context.getString(R.string.group_picker_none_detail), currentId == null, dimIcon = true) { pick(Choice.None) }, full())
        }
        val shown = if (q.isEmpty()) sorted else sorted.filter { it.name.lowercase().contains(q) }
        for (e in shown) {
            list.addView(row(R.drawable.ic_folder, e.preview, e.name, e.detail, e.id == currentId) { pick(Choice.Group(e.id)) }, full())
        }
        if (shown.isEmpty() && q.isNotEmpty()) {
            list.addView(TextView(context).apply {
                setText(R.string.no_groups_match); textSize = 14f; setTextColor(context.getColor(R.color.text_secondary))
                setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            }, full())
        }
    }

    private fun row(
        iconRes: Int, preview: Bitmap?, title: String, detail: String?, selected: Boolean,
        dimIcon: Boolean = false, onClick: () -> Unit,
    ): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60f)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            background = context.getDrawable(R.drawable.tool_bg)
            isSelected = selected
            setOnClickListener { onClick() }
        }
        val radius = 8 * d
        val image = ImageView(context).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (preview != null) {
                setImageBitmap(preview); scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(context.getColor(R.color.paper_preview))
            } else {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(context.getColor(if (dimIcon) R.color.text_secondary else R.color.accent))
                background = GradientDrawable().apply { setColor(context.getColor(R.color.tool_selected_bg)); cornerRadius = radius }
                setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
            }
        }
        r.addView(image, LinearLayout.LayoutParams(dp(56f), dp(42f)).apply { marginEnd = dp(14f) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = title; textSize = 16f; setTextColor(context.getColor(R.color.icon))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (detail != null) texts.addView(TextView(context).apply {
            text = detail; textSize = 12.5f; setTextColor(context.getColor(R.color.text_secondary))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) r.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.accent))
            contentDescription = context.getString(R.string.current_group)
        }, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginStart = dp(10f) })
        return r
    }
}

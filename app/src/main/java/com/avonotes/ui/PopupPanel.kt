package com.avonotes.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.avonotes.R

/**
 * A rounded popup card built from a few standard rows: section headers, segmented choices,
 * switches, sliders and plain actions. Colors are resolved when the panel is built, so it
 * always matches the current light/dark theme.
 */
class PopupPanel(private val context: Context, widthDp: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(8f), dp(16f), dp(12f))
    }
    private val scroll = ScrollView(context).apply {
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.popup_bg)); cornerRadius = 14 * d
            setStroke(d.toInt().coerceAtLeast(1), context.getColor(R.color.popup_border))
        }
        clipToOutline = true
        isVerticalScrollBarEnabled = false
        addView(box)
    }
    val popup = PopupWindow(
        scroll,
        if (widthDp > 0) dp(widthDp.toFloat()) else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true,
    ).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = 8 * d
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun header(textRes: Int) {
        box.addView(TextView(context).apply {
            setText(textRes)
            textSize = 12f
            isAllCaps = true
            letterSpacing = 0.06f
            setTextColor(context.getColor(R.color.accent))
            setPadding(0, dp(12f), 0, dp(6f))
        }, full())
    }

    /** Small secondary text; returned so callers can update it. */
    fun hint(textRes: Int): TextView {
        val v = TextView(context).apply {
            setText(textRes); textSize = 12.5f; setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, dp(2f), 0, dp(2f))
        }
        box.addView(v, full())
        return v
    }

    fun divider() {
        box.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.toolbar_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1)).apply {
                topMargin = dp(8f)
            })
    }

    /** A row of equally wide chips; exactly one is selected. */
    fun choice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        labels.forEachIndexed { i, label ->
            val chip = TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(context.getColor(R.color.icon))
                background = context.getDrawable(R.drawable.chip_bg)
                isSelected = i == selected
                setPadding(dp(8f), 0, dp(8f), 0)
                setOnClickListener {
                    for ((k, c) in chips.withIndex()) c.isSelected = k == i
                    onSelect(i)
                }
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(0, dp(36f), 1f).apply {
                if (i > 0) marginStart = dp(6f)
            })
        }
        box.addView(row, full().apply { bottomMargin = dp(4f) })
    }

    fun switch(titleRes: Int, subtitleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setText(titleRes); textSize = 15f; setTextColor(context.getColor(R.color.icon))
            })
            addView(TextView(context).apply {
                setText(subtitleRes); textSize = 12.5f; setTextColor(context.getColor(R.color.text_secondary))
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(0, dp(4f), 0, dp(4f))
            background = context.getDrawable(R.drawable.tool_bg)
            setOnClickListener { sw.toggle() }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
        box.addView(row, full())
    }

    /** A plain tappable row; closes the panel first. */
    fun action(titleRes: Int, onClick: () -> Unit) {
        box.addView(TextView(context).apply {
            setText(titleRes)
            textSize = 15f
            setTextColor(context.getColor(R.color.icon))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44f)
            background = context.getDrawable(R.drawable.tool_bg)
            setOnClickListener { popup.dismiss(); onClick() }
        }, full())
    }

    /** Several actions side by side, as outlined chips; each closes the panel. */
    fun actions(vararg items: Pair<Int, () -> Unit>) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (res, onClick) ->
            row.addView(TextView(context).apply {
                setText(res)
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(context.getColor(R.color.icon))
                background = context.getDrawable(R.drawable.chip_bg)
                setOnClickListener { popup.dismiss(); onClick() }
            }, LinearLayout.LayoutParams(0, dp(36f), 1f).apply { if (i > 0) marginStart = dp(6f) })
        }
        box.addView(row, full().apply { bottomMargin = dp(4f) })
    }

    /**
     * A labelled slider over [range]; [format] renders the current value next to the label.
     * An optional [preview] (e.g. a [SizePreviewView]) sits to its left; [onChange] updates it.
     */
    fun slider(
        labelRes: Int, range: ClosedFloatingPointRange<Float>, value: Float,
        format: (Float) -> String, preview: View? = null, previewDp: Float = 64f, onChange: (Float) -> Unit,
    ) {
        val target: LinearLayout
        if (preview != null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, 0)
            }
            row.addView(preview, LinearLayout.LayoutParams(dp(previewDp), dp(previewDp)).apply { marginEnd = dp(10f) })
            target = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            row.addView(target, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row, full())
        } else target = box
        val valueView = TextView(context).apply {
            textSize = 13f; setTextColor(context.getColor(R.color.text_secondary)); text = format(value)
        }
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f), 0, 0)
            addView(TextView(context).apply {
                setText(labelRes); textSize = 13f; setTextColor(context.getColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        }
        target.addView(labelRow, full())
        val span = range.endInclusive - range.start
        target.addView(SeekBar(context).apply {
            max = 100
            progress = (((value - range.start) / span) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = range.start + span * p / 100f
                    valueView.text = format(v)
                    onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, full())
    }

    fun showBelow(anchor: View, gravity: Int = Gravity.START) {
        popup.showAsDropDown(anchor, 0, dp(4f), gravity)
    }
}

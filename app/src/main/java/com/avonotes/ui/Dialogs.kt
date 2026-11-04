package com.avonotes.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import com.avonotes.R

/** Theme choice (System / Light / Dark), applied app-wide by the system on Android 12+. */
object ThemePref {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    val supported get() = android.os.Build.VERSION.SDK_INT >= 31

    fun get(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", SYSTEM)

    fun set(context: Context, value: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("theme", value).apply()
        if (!supported) return
        val ui = context.getSystemService(android.app.UiModeManager::class.java)
        // Persisted by the system; running activities get a uiMode configuration change.
        ui.setApplicationNightMode(
            when (value) {
                LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
                DARK -> android.app.UiModeManager.MODE_NIGHT_YES
                else -> android.app.UiModeManager.MODE_NIGHT_AUTO
            }
        )
    }

    fun showDialog(context: Context) {
        val items = arrayOf(
            context.getString(R.string.theme_system),
            context.getString(R.string.theme_light),
            context.getString(R.string.theme_dark),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(items, get(context)) { d, which -> d.dismiss(); set(context, which) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/**
 * Asks for a note name. [onName] gets the trimmed text (falls back to [initial] if left blank).
 */
fun showNameDialog(context: Context, titleRes: Int, initial: String, positiveRes: Int = R.string.ok, onName: (String) -> Unit) {
    val input = EditText(context).apply {
        setText(initial)
        setSelectAllOnFocus(true)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE
        hint = context.getString(R.string.untitled)
    }
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    val frame = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
    val dialog = AlertDialog.Builder(context)
        .setTitle(titleRes)
        .setView(frame)
        .setPositiveButton(positiveRes) { _, _ -> onName(input.text.toString().trim().ifEmpty { initial }) }
        .setNegativeButton(R.string.cancel, null)
        .create()
    input.setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_DONE) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true
        } else false
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
    input.requestFocus()
}

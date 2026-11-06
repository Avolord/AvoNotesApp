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
 * Picks the PDF layout. Notes without imported PDF pages have only one sensible layout, so
 * [onMode] is called right away for them.
 */
fun choosePdfExportMode(context: Context, hasPdfPages: Boolean, onMode: (com.avonotes.io.PdfExporter.Mode) -> Unit) {
    if (!hasPdfPages) { onMode(com.avonotes.io.PdfExporter.Mode.CANVAS); return }
    val items = arrayOf(
        context.getString(R.string.pdf_mode_canvas),
        context.getString(R.string.pdf_mode_pages),
    )
    AlertDialog.Builder(context)
        .setTitle(R.string.export_pdf_title)
        .setItems(items) { _, which ->
            onMode(if (which == 1) com.avonotes.io.PdfExporter.Mode.PAGES else com.avonotes.io.PdfExporter.Mode.CANVAS)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** A file name for [title] without characters file systems reject. */
fun safeFileName(title: String, fallback: String) = title.ifEmpty { fallback }.replace(Regex("[\\\\/:*?\"<>|]"), "_")

/**
 * Asks for a note name. [onName] gets the trimmed text (falls back to [initial] if left blank).
 */
fun showNameDialog(
    context: Context, titleRes: Int, initial: String, positiveRes: Int = R.string.ok,
    /** Returns an error message for a name that can't be used (the OK button is disabled), or null. */
    validate: ((String) -> String?)? = null,
    onName: (String) -> Unit,
) {
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
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (ok.isEnabled) ok.performClick()
            true
        } else false
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
    input.requestFocus()
    if (validate != null) {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun check() {
            val name = input.text.toString().trim().ifEmpty { initial }
            val error = validate(name)
            input.error = error
            ok.isEnabled = error == null
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = check()
        })
        check()
    }
}

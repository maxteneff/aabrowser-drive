/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.car

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * On-screen keyboard drawn inside the car surface. The car host never shows a system IME for
 * surface content, so text entry has to be handled by the app itself.
 */
class CarKeyboardView(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onHide()
    }

    private enum class Layout(val label: String, val rows: List<String>) {
        LATIN("EN", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        CYRILLIC("РУ", listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё")),
        SYMBOLS("123", listOf("1234567890", "@#$%&-+()/", "*\"':;!?_="))
    }

    private var layout = Layout.LATIN
    private var letters = Layout.LATIN
    private var shifted = false

    private val density = resources.displayMetrics.density
    private val keyHeight = (46 * density).toInt()
    private val keyMargin = (2 * density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(24, 24, 28))
        val pad = (4 * density).toInt()
        setPadding(pad, pad, pad, pad)
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        val rows = layout.rows
        addView(row { rows[0].forEach { addKey(charLabel(it)) } })
        addView(row { rows[1].forEach { addKey(charLabel(it)) } })
        addView(row {
            if (layout != Layout.SYMBOLS) {
                addKey("↑", weight = 1.5f, special = true, active = shifted) {
                    shifted = !shifted
                    rebuild()
                }
            }
            rows[2].forEach { addKey(charLabel(it)) }
            addKey("←", weight = 1.5f, special = true) { listener.onBackspace() }
        })
        addView(row {
            val symbolsToggle = if (layout == Layout.SYMBOLS) "ABC" else Layout.SYMBOLS.label
            addKey(symbolsToggle, weight = 1.5f, special = true) {
                layout = if (layout == Layout.SYMBOLS) letters else Layout.SYMBOLS
                rebuild()
            }
            val other = if (letters == Layout.LATIN) Layout.CYRILLIC else Layout.LATIN
            addKey(other.label, weight = 1.5f, special = true) {
                letters = other
                layout = other
                shifted = false
                rebuild()
            }
            addKey(",")
            addKey(" ", weight = 4f)
            addKey(".")
            addKey("Go", weight = 1.5f, special = true) { listener.onEnter() }
            addKey("×", weight = 1.5f, special = true) { listener.onHide() }
        })
    }

    private fun charLabel(char: Char): String =
        if (shifted && layout != Layout.SYMBOLS) char.uppercaseChar().toString() else char.toString()

    private fun row(content: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        content()
    }

    private fun LinearLayout.addKey(
        label: String,
        weight: Float = 1f,
        special: Boolean = false,
        active: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        val key = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(
                    when {
                        active -> Color.rgb(60, 110, 200)
                        special -> Color.rgb(44, 44, 52)
                        else -> Color.rgb(64, 64, 74)
                    }
                )
            }
            setOnClickListener {
                if (onClick != null) {
                    onClick()
                } else {
                    listener.onText(label)
                    if (shifted) {
                        shifted = false
                        rebuild()
                    }
                }
            }
        }
        addView(key, LayoutParams(0, keyHeight, weight).apply {
            setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
        })
    }
}

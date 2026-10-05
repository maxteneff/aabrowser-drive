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

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import org.json.JSONObject

/** Browser UI rendered on the car's virtual display: page, toolbar, bookmarks and keyboard. */
class CarBrowserPresentation(
    outerContext: Context,
    display: Display,
    private val controller: CarBrowserController
) : Presentation(outerContext, display), CarKeyboardView.Listener {

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var toolbar: LinearLayout
    private lateinit var urlView: TextView
    private lateinit var progressView: View
    private lateinit var keyboard: CarKeyboardView
    private lateinit var bookmarksPanel: ScrollView
    private lateinit var bookmarksList: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private val density get() = context.resources.displayMetrics.density

    private var urlEditing = false
    private var urlBuffer = ""
    private var urlReplaceOnType = false
    private val checkEditableRunnable = Runnable { syncKeyboardWithPageFocus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never let the phone's own IME pop up for fields on the car screen.
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        setContentView(buildContent())
        onUrlChanged(controller.currentUrl)
        onProgressChanged(controller.progress)
        onChromeVisibilityChanged(controller.chromeVisible)
        controller.customView?.let(::showCustomView)
    }

    // region layout

    private fun buildContent(): View {
        root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        webContainer = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        webView = controller.obtainWebView(context)
        webContainer.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        progressView = View(context).apply { setBackgroundColor(Color.rgb(80, 150, 255)) }
        webContainer.addView(progressView, FrameLayout.LayoutParams(0, dp(3), Gravity.TOP or Gravity.START))

        bookmarksList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bookmarksPanel = ScrollView(context).apply {
            setBackgroundColor(Color.rgb(18, 18, 22))
            visibility = View.GONE
            addView(bookmarksList, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        webContainer.addView(bookmarksPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        column.addView(webContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        keyboard = CarKeyboardView(context, this).apply { visibility = View.GONE }
        column.addView(keyboard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        toolbar = buildToolbar()
        column.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, dp(TOOLBAR_HEIGHT_DP)))

        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        fullscreenContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    private fun buildToolbar(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(30, 30, 36))

        addView(iconButton(R.drawable.arrow_back_24px) { controller.goBack() })
        addView(iconButton(R.drawable.arrow_forward_24px) { controller.goForward() })
        addView(iconButton(R.drawable.refresh_24px) { controller.reload() })
        addView(iconButton(R.drawable.home_24px) {
            closeOverlays()
            controller.goHome()
        })

        urlView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { if (urlEditing) stopUrlEditing() else startUrlEditing() }
        }
        addView(urlView, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
            setMargins(dp(4), dp(8), dp(4), dp(8))
        })

        addView(iconButton(R.drawable.kid_star_24px) { toggleBookmarks() })
        addView(textButton("ABC") { toggleKeyboard() })
    }

    private fun iconButton(@DrawableRes icon: Int, onClick: () -> Unit): View = ImageView(context).apply {
        // The app's vector icons reference AppCompat theme attributes, which the bare
        // presentation theme does not define.
        val themed = ContextThemeWrapper(context, R.style.Theme_AABrowser)
        val drawable = runCatching { AppCompatResources.getDrawable(themed, icon) }.getOrNull()
        setImageDrawable(drawable?.mutate()?.apply { setTint(Color.WHITE) })
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(TOOLBAR_HEIGHT_DP), MATCH_PARENT)
        setOnClickListener { onClick() }
    }

    private fun textButton(label: String, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        layoutParams = LinearLayout.LayoutParams(dp(TOOLBAR_HEIGHT_DP), MATCH_PARENT)
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    fun applyVisibleArea(area: Rect) {
        if (!::root.isInitialized) return
        val size = Rect()
        @Suppress("DEPRECATION")
        display.getRectSize(size)
        if (area.isEmpty || !size.contains(area)) {
            root.setPadding(0, 0, 0, 0)
            return
        }
        root.setPadding(area.left, area.top, size.right - area.right, size.bottom - area.bottom)
    }

    // endregion

    // region controller callbacks

    fun onUrlChanged(url: String) {
        if (urlEditing || !::webView.isInitialized) return
        renderUrl()
        // A new page rarely keeps a field focused; drop the keyboard if it no longer applies.
        if (keyboard.visibility == View.VISIBLE) {
            webView.removeCallbacks(checkEditableRunnable)
            webView.postDelayed(checkEditableRunnable, EDITABLE_CHECK_DELAY_MS)
        }
    }

    fun onProgressChanged(progress: Int) {
        if (!::progressView.isInitialized) return
        val loading = progress in 1..99
        progressView.visibility = if (loading) View.VISIBLE else View.GONE
        progressView.layoutParams = progressView.layoutParams.apply {
            width = webContainer.width * progress / 100
        }
    }

    fun onChromeVisibilityChanged(visible: Boolean) {
        if (!::toolbar.isInitialized) return
        toolbar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) closeOverlays()
    }

    fun showCustomView(view: View) {
        if (!::fullscreenContainer.isInitialized) return
        closeOverlays()
        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
    }

    fun hideCustomView() {
        if (!::fullscreenContainer.isInitialized) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
    }

    /** Area that scroll gestures are replayed into, in window coordinates. */
    fun scrollBounds(): Rect? {
        if (!::root.isInitialized) return null
        val target: View = when {
            fullscreenContainer.visibility == View.VISIBLE -> fullscreenContainer
            else -> webContainer
        }
        if (target.width == 0 || target.height == 0) return null
        val location = IntArray(2)
        target.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + target.width, location[1] + target.height)
    }

    fun isWebScrollTarget(): Boolean =
        ::root.isInitialized && bookmarksPanel.visibility != View.VISIBLE

    fun fling(velocityX: Int, velocityY: Int) {
        if (!::root.isInitialized) return
        if (bookmarksPanel.visibility == View.VISIBLE) {
            bookmarksPanel.fling(velocityY)
        } else {
            webView.flingScroll(velocityX, velocityY)
        }
    }

    fun onClickDispatched(x: Float, y: Float) {
        if (!::root.isInitialized || urlEditing || bookmarksPanel.visibility == View.VISIBLE) return
        val bounds = scrollBounds() ?: return
        if (!bounds.contains(x.toInt(), y.toInt())) return
        webView.requestFocus()
        webView.removeCallbacks(checkEditableRunnable)
        webView.postDelayed(checkEditableRunnable, EDITABLE_CHECK_DELAY_MS)
    }

    // endregion

    // region keyboard

    private fun syncKeyboardWithPageFocus() {
        if (urlEditing) return
        webView.evaluateJavascript("$JS_ACTIVE_ELEMENT return editable(deep(document));})()") { result ->
            if (urlEditing) return@evaluateJavascript
            val editable = result == "true"
            if (editable && keyboard.visibility != View.VISIBLE) {
                keyboard.visibility = View.VISIBLE
                webView.postDelayed({
                    webView.evaluateJavascript(
                        "$JS_ACTIVE_ELEMENT var e=deep(document);" +
                            "if(e&&e.scrollIntoView)e.scrollIntoView({block:'center'});})()",
                        null
                    )
                }, EDITABLE_CHECK_DELAY_MS)
            } else if (!editable) {
                keyboard.visibility = View.GONE
            }
        }
    }

    private fun toggleKeyboard() {
        if (keyboard.visibility == View.VISIBLE) {
            onHide()
        } else {
            bookmarksPanel.visibility = View.GONE
            keyboard.visibility = View.VISIBLE
        }
    }

    private fun startUrlEditing() {
        bookmarksPanel.visibility = View.GONE
        urlEditing = true
        urlBuffer = controller.currentUrl
        urlReplaceOnType = true
        keyboard.visibility = View.VISIBLE
        renderUrl()
    }

    private fun stopUrlEditing() {
        urlEditing = false
        keyboard.visibility = View.GONE
        renderUrl()
    }

    private fun renderUrl() {
        if (!::urlView.isInitialized) return
        urlView.text = if (urlEditing) "$urlBuffer|" else controller.currentUrl
        val background = when {
            urlEditing && urlReplaceOnType -> Color.rgb(50, 90, 170)
            urlEditing -> Color.rgb(70, 70, 82)
            else -> Color.rgb(48, 48, 56)
        }
        urlView.setBackgroundColor(background)
        urlView.ellipsize = if (urlEditing) TextUtils.TruncateAt.START else TextUtils.TruncateAt.END
    }

    override fun onText(text: String) {
        if (urlEditing) {
            urlBuffer = if (urlReplaceOnType) text else urlBuffer + text
            urlReplaceOnType = false
            renderUrl()
            return
        }
        webView.evaluateJavascript("$JS_INSERT_TEXT(${JSONObject.quote(text)})", null)
    }

    override fun onBackspace() {
        if (urlEditing) {
            urlBuffer = if (urlReplaceOnType) "" else urlBuffer.dropLast(1)
            urlReplaceOnType = false
            renderUrl()
            return
        }
        sendKey(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
        if (urlEditing) {
            val input = urlBuffer
            stopUrlEditing()
            controller.navigate(input)
            return
        }
        sendKey(KeyEvent.KEYCODE_ENTER)
    }

    override fun onHide() {
        if (urlEditing) stopUrlEditing() else keyboard.visibility = View.GONE
    }

    private fun sendKey(keyCode: Int) {
        webView.requestFocus()
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // endregion

    // region bookmarks

    private fun toggleBookmarks() {
        if (bookmarksPanel.visibility == View.VISIBLE) {
            bookmarksPanel.visibility = View.GONE
            return
        }
        if (urlEditing) stopUrlEditing()
        keyboard.visibility = View.GONE
        populateBookmarks()
        bookmarksPanel.scrollTo(0, 0)
        bookmarksPanel.visibility = View.VISIBLE
    }

    private fun populateBookmarks() {
        bookmarksList.removeAllViews()
        val appContext = context.applicationContext
        val current = controller.currentUrl
        val entries = BrowserPreferences.getBookmarkEntries(appContext)

        if (BrowserPreferences.isHttpOrHttps(current)) {
            val saved = entries.any { it.url == current }
            val label = context.getString(
                if (saved) R.string.car_bookmark_remove else R.string.car_bookmark_add
            )
            bookmarksList.addView(bookmarkRow(label, null, accent = true) {
                if (saved) {
                    BrowserPreferences.removeBookmark(appContext, current)
                } else {
                    BrowserPreferences.addBookmark(appContext, current, webView.title.orEmpty())
                }
                populateBookmarks()
            })
        }

        if (entries.isEmpty()) {
            bookmarksList.addView(bookmarkRow(context.getString(R.string.car_bookmarks_empty), null) {})
        }
        entries.forEach { entry ->
            val title = entry.title.ifBlank { entry.url }
            val subtitle = entry.url.takeIf { it != title }
            bookmarksList.addView(bookmarkRow(title, subtitle) {
                bookmarksPanel.visibility = View.GONE
                controller.navigate(entry.url)
            })
        }
    }

    private fun bookmarkRow(
        title: String,
        subtitle: String?,
        accent: Boolean = false,
        onClick: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        addView(TextView(context).apply {
            text = title
            setTextColor(if (accent) Color.rgb(120, 170, 255) else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle != null) {
            addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.rgb(150, 150, 160))
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        setOnClickListener { onClick() }
    }

    private fun closeOverlays() {
        if (urlEditing) stopUrlEditing()
        keyboard.visibility = View.GONE
        bookmarksPanel.visibility = View.GONE
    }

    // endregion

    private companion object {
        const val TOOLBAR_HEIGHT_DP = 56
        const val EDITABLE_CHECK_DELAY_MS = 300L

        /** Opens an IIFE and defines helpers; callers append a body and the closing `})()`. */
        const val JS_ACTIVE_ELEMENT = "(function(){" +
            "function deep(d){var e=d.activeElement;for(;;){" +
            "if(e&&e.shadowRoot&&e.shadowRoot.activeElement){e=e.shadowRoot.activeElement;}" +
            "else if(e&&e.tagName==='IFRAME'){try{var n=e.contentDocument.activeElement;" +
            "if(!n)break;e=n;}catch(x){break;}}else break;}return e;}" +
            "function editable(e){if(!e)return false;var t=e.tagName;" +
            "if(t==='TEXTAREA')return !e.readOnly;" +
            "if(t==='INPUT'){var k=(e.type||'text').toLowerCase();" +
            "return !e.readOnly&&['text','search','url','email','password','number','tel']" +
            ".indexOf(k)>=0;}return !!e.isContentEditable;}"

        /** Function expression taking the text to insert at the caret of the focused field. */
        const val JS_INSERT_TEXT = "(function(t){" +
            "function deep(d){var e=d.activeElement;for(;;){" +
            "if(e&&e.shadowRoot&&e.shadowRoot.activeElement){e=e.shadowRoot.activeElement;}" +
            "else if(e&&e.tagName==='IFRAME'){try{var n=e.contentDocument.activeElement;" +
            "if(!n)break;e=n;}catch(x){break;}}else break;}return e;}" +
            "var e=deep(document);if(!e)return false;" +
            "try{if(e.ownerDocument.execCommand('insertText',false,t))return true;}catch(x){}" +
            "if(!('value' in e))return false;" +
            "var v=e.value,s=e.selectionStart,n=e.selectionEnd;" +
            "if(s==null||n==null){s=n=v.length;}" +
            "var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "Object.getOwnPropertyDescriptor(p,'value').set.call(e,v.slice(0,s)+t+v.slice(n));" +
            "try{e.setSelectionRange(s+t.length,s+t.length);}catch(x){}" +
            "e.dispatchEvent(new InputEvent('input',{bubbles:true,data:t,inputType:'insertText'}));" +
            "return true;})"
    }
}

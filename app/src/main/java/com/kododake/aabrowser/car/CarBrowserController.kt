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
import android.content.MutableContextWrapper
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.web.BrowserCallbacks
import com.kododake.aabrowser.web.UserAgentManager
import com.kododake.aabrowser.web.configureWebView
import com.kododake.aabrowser.web.releaseCompletely

/**
 * Hosts the browser on a car surface. The car host only hands us a [Surface] plus high-level
 * gestures (click / scroll / fling / scale), so the UI lives in a [CarBrowserPresentation] shown
 * on a private virtual display that renders into that surface, and gestures are replayed into it
 * as synthetic touch events.
 */
class CarBrowserController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarBrowserPresentation? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var visibleArea: Rect? = null

    private var webViewContext: MutableContextWrapper? = null
    private var webView: WebView? = null
    private var webViewDensityDpi = 0

    var currentUrl: String = ""
        private set
    var progress: Int = 100
        private set
    var chromeVisible: Boolean = true
        private set
    var customView: View? = null
        private set
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var pendingClickUp: Runnable? = null
    private var dragging = false
    private var dragDownTime = 0L
    private var dragX = 0f
    private var dragY = 0f
    private val endDragRunnable = Runnable { endDrag(cancel = false) }

    private val callbacks = BrowserCallbacks(
        onUrlChange = { url ->
            currentUrl = url
            if (BrowserPreferences.isHttpOrHttps(url)) BrowserPreferences.persistUrl(appContext, url)
            presentation?.onUrlChanged(url)
        },
        onProgressChange = { value ->
            progress = value
            presentation?.onProgressChanged(value)
        },
        // There is no way to ask on the car screen, so plain http is simply allowed.
        onCleartextNavigationRequested = { _, allowOnce, _, _ -> allowOnce() },
        onEnterFullscreen = { view, callback ->
            exitFullscreen()
            customView = view
            customViewCallback = callback
            presentation?.showCustomView(view)
        },
        onExitFullscreen = {
            customView = null
            customViewCallback = null
            presentation?.hideCustomView()
        }
    )

    // region surface

    fun attachSurface(surface: Surface, surfaceWidth: Int, surfaceHeight: Int, densityDpi: Int) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val existing = virtualDisplay
        val sameGeometry = surfaceWidth == width && surfaceHeight == height && densityDpi == dpi
        if (existing != null && sameGeometry && presentation?.isShowing == true) {
            existing.surface = surface
            return
        }
        releaseDisplay()
        width = surfaceWidth
        height = surfaceHeight
        dpi = densityDpi
        try {
            val display = displayManager.createVirtualDisplay(
                DISPLAY_NAME, surfaceWidth, surfaceHeight, densityDpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            virtualDisplay = display
            val created = CarBrowserPresentation(appContext, display.display, this)
            created.show()
            presentation = created
            visibleArea?.let(created::applyVisibleArea)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to start car presentation", e)
            releaseDisplay()
        }
    }

    fun detachSurface() {
        endDrag(cancel = true)
        virtualDisplay?.surface = null
    }

    fun setVisibleArea(area: Rect) {
        visibleArea = Rect(area)
        presentation?.applyVisibleArea(area)
    }

    fun destroy() {
        releaseDisplay()
        webView?.releaseCompletely()
        webView = null
        webViewContext = null
    }

    private fun releaseDisplay() {
        endDrag(cancel = true)
        flushPendingClick()
        exitFullscreen()
        (webView?.parent as? ViewGroup)?.removeView(webView)
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // endregion

    // region browser

    fun obtainWebView(context: Context): WebView {
        val existing = webView
        if (existing != null) {
            webViewContext?.baseContext = context
            (existing.parent as? ViewGroup)?.removeView(existing)
            val density = context.resources.displayMetrics.densityDpi
            if (density != webViewDensityDpi) {
                // The page scale is derived from the display density, so redo it for the new one.
                webViewDensityDpi = density
                UserAgentManager.applyBrowserIdentity(
                    existing,
                    BrowserPreferences.getUserAgentProfile(appContext),
                    BrowserPreferences.shouldUseDesktopMode(appContext)
                )
                existing.reload()
            }
            return existing
        }
        val wrapper = MutableContextWrapper(context)
        val created = WebView(wrapper)
        configureWebView(
            created,
            callbacks,
            BrowserPreferences.shouldUseDesktopMode(appContext),
            BrowserPreferences.getUserAgentProfile(appContext),
            BrowserPreferences.isDrmL3EnforcerEnabled(appContext)
        )
        webViewContext = wrapper
        webView = created
        webViewDensityDpi = context.resources.displayMetrics.densityDpi
        currentUrl = BrowserPreferences.resolveInitialUrl(appContext)
        created.loadUrl(currentUrl)
        return created
    }

    fun navigate(rawInput: String) {
        val url = BrowserPreferences.formatNavigableUrl(rawInput)
        if (url.isBlank()) return
        currentUrl = url
        presentation?.onUrlChanged(url)
        webView?.loadUrl(url)
    }

    fun goHome() = navigate(BrowserPreferences.getHomePageUrl(appContext) ?: BrowserPreferences.defaultUrl())

    fun goBack() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        webView?.reload()
    }

    /** Action-strip button: leaves fullscreen video if any, otherwise shows/hides the toolbar. */
    fun toggleChrome() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        chromeVisible = !chromeVisible
        presentation?.onChromeVisibilityChanged(chromeVisible)
    }

    private fun exitFullscreen() {
        val callback = customViewCallback ?: return
        customView = null
        customViewCallback = null
        presentation?.hideCustomView()
        runCatching { callback.onCustomViewHidden() }
    }

    // endregion

    // region gestures

    fun click(x: Float, y: Float) {
        val target = presentation ?: return
        endDrag(cancel = false)
        flushPendingClick()
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(MotionEvent.ACTION_DOWN, x, y, downTime)
        val up = Runnable {
            pendingClickUp = null
            dispatchTouch(MotionEvent.ACTION_UP, x, y, downTime)
            target.onClickDispatched(x, y)
        }
        pendingClickUp = up
        handler.postDelayed(up, CLICK_DURATION_MS)
    }

    /** Distances follow GestureDetector: positive when the finger moves left / up. */
    fun scroll(distanceX: Float, distanceY: Float) {
        val target = presentation ?: return
        flushPendingClick()
        val bounds = target.scrollBounds() ?: return
        if (dragging && !bounds.contains((dragX - distanceX).toInt(), (dragY - distanceY).toInt())) {
            endDrag(cancel = false)
        }
        if (!dragging) {
            dragX = bounds.exactCenterX()
            dragY = bounds.exactCenterY()
            dragDownTime = SystemClock.uptimeMillis()
            dispatchTouch(MotionEvent.ACTION_DOWN, dragX, dragY, dragDownTime)
            dragging = true
        }
        dragX -= distanceX
        dragY -= distanceY
        dispatchTouch(MotionEvent.ACTION_MOVE, dragX, dragY, dragDownTime)
        handler.removeCallbacks(endDragRunnable)
        handler.postDelayed(endDragRunnable, DRAG_IDLE_TIMEOUT_MS)
    }

    fun fling(velocityX: Float, velocityY: Float) {
        // Cancel instead of lifting so the view does not add a fling of its own on top.
        endDrag(cancel = true)
        presentation?.fling(-velocityX.toInt(), -velocityY.toInt())
    }

    fun scale(scaleFactor: Float) {
        if (scaleFactor.isNaN() || presentation?.isWebScrollTarget() != true) return
        endDrag(cancel = true)
        runCatching { webView?.zoomBy(scaleFactor.coerceIn(0.5f, 2f)) }
    }

    private fun endDrag(cancel: Boolean) {
        handler.removeCallbacks(endDragRunnable)
        if (!dragging) return
        dragging = false
        val action = if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        dispatchTouch(action, dragX, dragY, dragDownTime)
    }

    private fun flushPendingClick() {
        val up = pendingClickUp ?: return
        handler.removeCallbacks(up)
        up.run()
    }

    private fun dispatchTouch(action: Int, x: Float, y: Float, downTime: Long) {
        val decor = presentation?.window?.decorView ?: return
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            decor.dispatchTouchEvent(event)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Touch dispatch failed", e)
        } finally {
            event.recycle()
        }
    }

    // endregion

    private companion object {
        const val TAG = "CarBrowser"
        const val DISPLAY_NAME = "AABrowserCar"
        const val CLICK_DURATION_MS = 40L
        const val DRAG_IDLE_TIMEOUT_MS = 200L
    }
}

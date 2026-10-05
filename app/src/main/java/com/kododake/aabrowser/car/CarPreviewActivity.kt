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

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets

/**
 * Shows the car UI on the phone, fed with the same reduced gesture set the car host delivers.
 * Lets the driving-mode browser be tried without a head unit:
 * `adb shell am start -n <applicationId>/com.kododake.aabrowser.car.CarPreviewActivity`
 */
class CarPreviewActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var controller: CarBrowserController
    private lateinit var gestures: GestureDetector
    private lateinit var scaleGestures: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CarBrowserController(this)
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                controller.click(e.x, e.y)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                controller.scroll(distanceX, distanceY)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                controller.fling(velocityX, velocityY)
                return true
            }

            override fun onLongPress(e: MotionEvent) = controller.toggleChrome()
        })
        scaleGestures = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                controller.scale(detector.scaleFactor)
                return true
            }
        })
        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { _, event ->
            scaleGestures.onTouchEvent(event)
            if (!scaleGestures.isInProgress) gestures.onTouchEvent(event)
            true
        }
        // Stands in for the host reporting which part of the surface is not covered by its own UI.
        surfaceView.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.post {
                controller.setVisibleArea(
                    Rect(bars.left, bars.top, view.width - bars.right, view.height - bars.bottom)
                )
            }
            insets
        }
        setContentView(surfaceView)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        controller.attachSurface(holder.surface, width, height, resources.displayMetrics.densityDpi)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = controller.detachSurface()

    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }
}

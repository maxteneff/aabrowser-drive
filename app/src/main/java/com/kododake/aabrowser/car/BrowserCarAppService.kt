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

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kododake.aabrowser.R

/**
 * Registers the browser as a navigation-category Car App Library app. Navigation apps are the
 * only ones that get a drawing surface which stays interactive while the vehicle is moving.
 */
class BrowserCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = BrowserSession()
}

private class BrowserSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val controller = CarBrowserController(carContext)
        carContext.getCarService(AppManager::class.java)
            .setSurfaceCallback(BrowserSurfaceCallback(controller))
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = controller.destroy()
        })
        return BrowserScreen(carContext, controller)
    }
}

private class BrowserSurfaceCallback(private val controller: CarBrowserController) : SurfaceCallback {

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        controller.attachSurface(surface, surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = controller.detachSurface()

    override fun onVisibleAreaChanged(visibleArea: Rect) = controller.setVisibleArea(visibleArea)

    override fun onClick(x: Float, y: Float) = controller.click(x, y)

    override fun onScroll(distanceX: Float, distanceY: Float) = controller.scroll(distanceX, distanceY)

    override fun onFling(velocityX: Float, velocityY: Float) = controller.fling(velocityX, velocityY)

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = controller.scale(scaleFactor)
}

private class BrowserScreen(
    carContext: CarContext,
    private val controller: CarBrowserController
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val toggleChrome = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_toggle_chrome)).build())
            .setOnClickListener(controller::toggleChrome)
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(toggleChrome).build())
            .build()
    }
}

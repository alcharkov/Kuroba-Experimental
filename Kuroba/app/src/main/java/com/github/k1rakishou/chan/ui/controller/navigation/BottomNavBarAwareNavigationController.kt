package com.github.k1rakishou.chan.ui.controller.navigation

import android.content.Context
import android.view.View
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.ui.NavigationControllerContainerLayout
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.manager.GlobalWindowInsetsManager
import com.github.k1rakishou.chan.core.manager.WindowInsetsListener
import com.github.k1rakishou.chan.ui.toolbar.Toolbar
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getDimen
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.inflate
import com.github.k1rakishou.common.updatePaddings
import com.github.k1rakishou.core_themes.ThemeEngine
import javax.inject.Inject

class BottomNavBarAwareNavigationController(
  context: Context,
  private val listener: CloseBottomNavBarAwareNavigationControllerListener
) :
  ToolbarNavigationController(context),
  WindowInsetsListener,
  Toolbar.ToolbarHeightUpdatesCallback {

  @Inject
  lateinit var themeEngine: ThemeEngine
  @Inject
  lateinit var globalWindowInsetsManager: GlobalWindowInsetsManager

  private var bottomNavBarHeight: Int = 0

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onCreate() {
    super.onCreate()

    view = inflate(context, R.layout.controller_navigation_bottom_nav_bar_aware)
    container = view.findViewById<View>(R.id.bottom_bar_aware_controller_container) as NavigationControllerContainerLayout

    bottomNavBarHeight = getDimen(R.dimen.bottom_nav_view_height)

    val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
    setToolbar(toolbar)

    requireToolbar().setCallback(this)
    requireToolbar().hideArrowMenu()
    requireToolbar().addToolbarHeightUpdatesCallback(this)

    // Wait a little bit so that GlobalWindowInsetsManager have time to get initialized so we can
    // use the insets
    view.post {
      onInsetsChanged()
      globalWindowInsetsManager.addInsetsUpdatesListener(this)
    }
  }

  override fun onDestroy() {
    super.onDestroy()

    requireToolbar().removeCallback()
    globalWindowInsetsManager.removeInsetsUpdatesListener(this)
    requireToolbar().removeToolbarHeightUpdatesCallback(this)
  }

  override fun onToolbarHeightKnown(heightChanged: Boolean) {
    if (!heightChanged) {
      return
    }

    onInsetsChanged()
  }

  override fun onInsetsChanged() {
    container.updatePaddings(
      top = requireToolbar().toolbarHeight,
      bottom = bottomNavBarHeight + globalWindowInsetsManager.bottom()
    )
  }

  override fun onMenuOrBackClicked(isArrow: Boolean) {
    listener.onCloseController()
  }

  interface CloseBottomNavBarAwareNavigationControllerListener {
    fun onCloseController()
  }
}
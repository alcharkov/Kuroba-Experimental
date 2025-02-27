/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.ui.controller

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.Controller
import com.github.k1rakishou.chan.core.base.SerializedCoroutineExecutor
import com.github.k1rakishou.chan.core.helper.DialogFactory
import com.github.k1rakishou.chan.core.manager.ApplicationVisibility
import com.github.k1rakishou.chan.core.manager.ApplicationVisibilityListener
import com.github.k1rakishou.chan.core.manager.ApplicationVisibilityManager
import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.manager.ChanThreadManager
import com.github.k1rakishou.chan.core.manager.ChanThreadViewableInfoManager
import com.github.k1rakishou.chan.core.manager.GlobalWindowInsetsManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.manager.ThreadFollowHistoryManager
import com.github.k1rakishou.chan.core.usecase.FilterOutHiddenImagesUseCase
import com.github.k1rakishou.chan.features.drawer.MainControllerCallbacks
import com.github.k1rakishou.chan.features.media_viewer.MediaViewerActivity
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaViewerScrollerHelper
import com.github.k1rakishou.chan.ui.controller.ThreadSlideController.SlideChangeListener
import com.github.k1rakishou.chan.ui.controller.navigation.ToolbarNavigationController
import com.github.k1rakishou.chan.ui.helper.OpenExternalThreadHelper
import com.github.k1rakishou.chan.ui.helper.RefreshUIMessage
import com.github.k1rakishou.chan.ui.helper.ShowPostsInExternalThreadHelper
import com.github.k1rakishou.chan.ui.layout.ThreadLayout
import com.github.k1rakishou.chan.ui.layout.ThreadLayout.ThreadLayoutCallback
import com.github.k1rakishou.chan.ui.toolbar.Toolbar
import com.github.k1rakishou.chan.ui.view.floating_menu.FloatingListMenuItem
import com.github.k1rakishou.chan.ui.widget.KurobaSwipeRefreshLayout
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.dp
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.inflate
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.isDevBuild
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.core_themes.ThemeEngine
import com.github.k1rakishou.model.data.descriptor.ArchiveDescriptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.filter.ChanFilterMutable
import com.github.k1rakishou.model.data.filter.FilterType
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ChanPostImage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import javax.inject.Inject

abstract class ThreadController(
  context: Context,
  var drawerCallbacks: MainControllerCallbacks?
) : Controller(context),
  ThreadLayoutCallback,
  OnRefreshListener,
  SlideChangeListener,
  ApplicationVisibilityListener,
  ThemeEngine.ThemeChangesListener,
  Toolbar.ToolbarHeightUpdatesCallback,
  AlbumViewController.ThreadControllerCallbacks {

  @Inject
  lateinit var siteManager: SiteManager
  @Inject
  lateinit var themeEngine: ThemeEngine
  @Inject
  lateinit var applicationVisibilityManager: ApplicationVisibilityManager
  @Inject
  lateinit var filterOutHiddenImagesUseCase: FilterOutHiddenImagesUseCase
  @Inject
  lateinit var chanThreadManager: ChanThreadManager
  @Inject
  lateinit var dialogFactory: DialogFactory
  @Inject
  lateinit var chanThreadViewableInfoManager: ChanThreadViewableInfoManager
  @Inject
  lateinit var threadFollowHistoryManager: ThreadFollowHistoryManager
  @Inject
  lateinit var archivesManager: ArchivesManager
  @Inject
  lateinit var globalWindowInsetsManager: GlobalWindowInsetsManager
  @Inject
  lateinit var mediaViewerScrollerHelper: MediaViewerScrollerHelper

  protected lateinit var threadLayout: ThreadLayout
  protected lateinit var showPostsInExternalThreadHelper: ShowPostsInExternalThreadHelper
  protected lateinit var openExternalThreadHelper: OpenExternalThreadHelper

  private lateinit var swipeRefreshLayout: KurobaSwipeRefreshLayout
  private lateinit var serializedCoroutineExecutor: SerializedCoroutineExecutor

  val chanDescriptor: ChanDescriptor?
    get() = threadLayout.presenter.currentChanDescriptor

  override val toolbar: Toolbar?
    get() = (navigationController as? ToolbarNavigationController)?.toolbar

  abstract val threadControllerType: ThreadSlideController.ThreadControllerType

  override fun onCreate() {
    super.onCreate()

    EventBus.getDefault().register(this)

    threadLayout = inflate(context, R.layout.layout_thread, null) as ThreadLayout
    threadLayout.create(this, threadControllerType)
    threadLayout.setDrawerCallbacks(drawerCallbacks)

    swipeRefreshLayout = KurobaSwipeRefreshLayout(context)

    swipeRefreshLayout.setOnChildScrollUpCallback { parent, child ->
      return@setOnChildScrollUpCallback threadLayout.canChildScrollUp()
    }

    swipeRefreshLayout.id = R.id.swipe_refresh_layout
    swipeRefreshLayout.addView(threadLayout)
    swipeRefreshLayout.setOnRefreshListener(this)

    view = swipeRefreshLayout

    serializedCoroutineExecutor = SerializedCoroutineExecutor(mainScope)
    applicationVisibilityManager.addListener(this)

    toolbar?.addToolbarHeightUpdatesCallback(this)

    showPostsInExternalThreadHelper = ShowPostsInExternalThreadHelper(
      context = context,
      scope = mainScope,
      postPopupHelper = threadLayout.popupHelper,
      chanThreadManager = chanThreadManager,
      presentControllerFunc = { controller -> presentController(controller) },
      showAvailableArchivesListFunc = { postDescriptor ->
        showAvailableArchivesList(postDescriptor = postDescriptor, preview = true)
      },
      showToastFunc = { message -> showToast(message) }
    )

    openExternalThreadHelper = OpenExternalThreadHelper(
      postPopupHelper = threadLayout.popupHelper,
      chanThreadViewableInfoManager = chanThreadViewableInfoManager,
      threadFollowHistoryManager = threadFollowHistoryManager
    )

    mainScope.launch {
      mediaViewerScrollerHelper.mediaViewerScrollEventsFlow
        .collect { chanPostImage -> threadLayout.presenter.scrollToImage(chanPostImage, true) }
    }

    onThemeChanged()
    themeEngine.addListener(this)
  }

  override fun onShow() {
    super.onShow()

    threadLayout.gainedFocus(threadControllerType)
  }

  override fun onHide() {
    super.onHide()

    threadLayout.lostFocus(threadControllerType)
  }

  override fun onDestroy() {
    super.onDestroy()

    toolbar?.removeToolbarHeightUpdatesCallback(this)
    drawerCallbacks = null
    threadLayout.destroy()
    applicationVisibilityManager.removeListener(this)
    themeEngine.removeListener(this)

    EventBus.getDefault().unregister(this)
  }

  override fun onThemeChanged() {
    swipeRefreshLayout.setBackgroundColor(themeEngine.chanTheme.backColor)
  }

  override fun onToolbarHeightKnown(heightChanged: Boolean) {
    val toolbarHeight = toolbar?.toolbarHeight
      ?: return

    swipeRefreshLayout.setProgressViewOffset(
      false,
      toolbarHeight - dp(40f),
      toolbarHeight + dp(64 - 40.toFloat())
    )
  }

  fun passMotionEventIntoDrawer(event: MotionEvent): Boolean {
    return drawerCallbacks?.passMotionEventIntoDrawer(event) ?: false
  }

  fun passMotionEventIntoSlidingPaneLayout(event: MotionEvent): Boolean {
    val threadSlideController = (this.parentController as? ThreadSlideController)
      ?: return false

    return threadSlideController.passMotionEventIntoSlidingPaneLayout(event)
  }

  fun showLoading() {
    threadLayout.showLoading()
  }

  open suspend fun showSitesNotSetup() {
    threadLayout.presenter.showNoContent()
  }

  fun selectPost(postDescriptor: PostDescriptor?) {
    threadLayout.presenter.selectPost(postDescriptor)
  }

  override fun onBack(): Boolean {
    return threadLayout.onBack()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    return threadLayout.sendKeyEvent(event) || super.dispatchKeyEvent(event)
  }

  override fun onApplicationVisibilityChanged(applicationVisibility: ApplicationVisibility) {
    threadLayout.presenter.onForegroundChanged(applicationVisibility.isInForeground())
  }

  @Subscribe
  fun onEvent(message: RefreshUIMessage?) {
    Logger.d(TAG, "onEvent reason=${message?.reason ?: "<reason is empty>"}")
    threadLayout.presenter.normalLoad()
  }

  override fun onRefresh() {
    threadLayout.refreshFromSwipe()
  }

  override fun openReportController(post: ChanPost) {
    val site = siteManager.bySiteDescriptor(post.boardDescriptor.siteDescriptor)
    if (site != null) {
      val toolbarHeight = toolbar?.toolbarHeight
        ?: return

      val reportController = ReportController(context, post, site, toolbarHeight)
      navigationController!!.pushController(reportController)
    }
  }

  fun selectPostImage(postImage: ChanPostImage) {
    threadLayout.presenter.selectPostImage(postImage)
  }

  override fun showImages(
    chanDescriptor: ChanDescriptor,
    initialImageUrl: String?,
    transitionThumbnailUrl: String
  ) {
    when (chanDescriptor) {
      is ChanDescriptor.CatalogDescriptor -> {
        MediaViewerActivity.catalogAlbum(
          context = context,
          catalogDescriptor = chanDescriptor,
          initialImageUrl = initialImageUrl,
          transitionThumbnailUrl = transitionThumbnailUrl,
          lastTouchCoordinates = globalWindowInsetsManager.lastTouchCoordinates()
        )
      }
      is ChanDescriptor.ThreadDescriptor -> {
        MediaViewerActivity.threadAlbum(
          context = context,
          threadDescriptor = chanDescriptor,
          initialImageUrl = initialImageUrl,
          transitionThumbnailUrl = transitionThumbnailUrl,
          lastTouchCoordinates = globalWindowInsetsManager.lastTouchCoordinates()
        )
      }
    }
  }

  override fun showAlbum(images: List<ChanPostImage>, index: Int) {
    if (threadLayout.presenter.currentChanDescriptor == null) {
      return
    }

    val input = FilterOutHiddenImagesUseCase.Input(
      images = images,
      index = index,
      isOpeningAlbum = true,
      postDescriptorSelector = { chanPostImage -> chanPostImage.ownerPostDescriptor }
    )

    val output = filterOutHiddenImagesUseCase.filter(input)
    val filteredImages = output.images
    val newIndex = output.index

    if (filteredImages.isEmpty()) {
      showToast("No images left to show after filtering out images of hidden/removed posts");
      return
    }

    val albumViewController = AlbumViewController(context)
    albumViewController.setImages(chanDescriptor, filteredImages, newIndex, navigation.title)

    if (doubleNavigationController != null) {
      doubleNavigationController!!.pushController(albumViewController)
    } else {
      navigationController!!.pushController(albumViewController)
    }
  }

  override fun onShowPosts() {
    // no-op
  }

  override fun onShowError() {
    // no-op
  }

  override fun unpresentController(predicate: (Controller) -> Boolean) {
    getControllerOrNull { controller ->
      if (predicate(controller)) {
        controller.stopPresenting()
        return@getControllerOrNull true
      }

      return@getControllerOrNull false
    }
  }

  override fun isAlreadyPresentingController(predicate: (Controller) -> Boolean): Boolean {
    return super.isAlreadyPresenting(predicate)
  }

  override fun hideSwipeRefreshLayout() {
    if (!::swipeRefreshLayout.isInitialized) {
      return
    }

    swipeRefreshLayout.isRefreshing = false
  }

  override fun openFilterForType(type: FilterType, filterText: String?) {
    val filter = ChanFilterMutable()
    filter.type = type.flag
    filter.pattern = '/'.toString() + (filterText ?: "") + '/'
    openFiltersController(filter)
  }

  override fun openFiltersController(chanFilterMutable: ChanFilterMutable) {
    val filtersController = openFiltersController()
    filtersController.showFilterDialog(chanFilterMutable)
  }

  private fun openFiltersController(): FiltersController {
    val filtersController = FiltersController(context)

    if (doubleNavigationController != null) {
      doubleNavigationController!!.openControllerWrappedIntoBottomNavAwareController(filtersController)
    } else {
      requireStartActivity().openControllerWrappedIntoBottomNavAwareController(filtersController)
    }

    requireStartActivity().setSettingsMenuItemSelected()
    return filtersController
  }

  override fun onLostFocus(wasFocused: ThreadSlideController.ThreadControllerType) {
    if (isDevBuild()) {
      check(wasFocused == threadControllerType) {
        "ThreadControllerTypes do not match! wasFocused=$wasFocused, current=$threadControllerType"
      }
    }

    threadLayout.lostFocus(wasFocused)
    controllerNavigationManager.onControllerSwipedFrom(this)
  }

  override fun onGainedFocus(nowFocused: ThreadSlideController.ThreadControllerType) {
    if (isDevBuild()) {
      check(nowFocused == threadControllerType) {
        "ThreadControllerTypes do not match! nowFocused=$nowFocused, current=$threadControllerType"
      }
    }

    threadLayout.gainedFocus(nowFocused)
    controllerNavigationManager.onControllerSwipedTo(this)
  }

  override fun threadBackPressed(): Boolean {
    return false
  }

  override fun threadBackLongPressed() {
    // no-op
  }

  override fun showAvailableArchivesList(postDescriptor: PostDescriptor, preview: Boolean) {
    Logger.d(TAG, "showAvailableArchives($postDescriptor)")

    val descriptor = postDescriptor.descriptor as? ChanDescriptor.ThreadDescriptor
      ?: return

    val supportedArchiveDescriptors = archivesManager.getSupportedArchiveDescriptors(descriptor)
      .filter { archiveDescriptor ->
        return@filter siteManager.bySiteDescriptor(archiveDescriptor.siteDescriptor)?.enabled()
          ?: false
      }

    if (supportedArchiveDescriptors.isEmpty()) {
      Logger.d(TAG, "showAvailableArchives($descriptor) supportedThreadDescriptors is empty")

      val message = AppModuleAndroidUtils.getString(
        R.string.thread_presenter_no_archives_found_to_open_thread,
        descriptor.toString()
      )
      showToast(message, Toast.LENGTH_LONG)
      return
    }

    val items = mutableListOf<FloatingListMenuItem>()

    supportedArchiveDescriptors.forEach { archiveDescriptor ->
      items += FloatingListMenuItem(
        archiveDescriptor,
        archiveDescriptor.name
      )
    }

    if (items.isEmpty()) {
      Logger.d(TAG, "showAvailableArchives($descriptor) items is empty")
      return
    }

    val floatingListMenuController = FloatingListMenuController(
      context,
      globalWindowInsetsManager.lastTouchCoordinatesAsConstraintLayoutBias(),
      items,
      itemClickListener = { clickedItem ->
        mainScope.launch {
          val archiveDescriptor = (clickedItem.key as? ArchiveDescriptor)
            ?: return@launch

          val externalArchivePostDescriptor = PostDescriptor.create(
            archiveDescriptor.domain,
            postDescriptor.descriptor.boardCode(),
            postDescriptor.getThreadNo(),
            postDescriptor.postNo
          )

          if (preview) {
            showPostsInExternalThread(
              postDescriptor = externalArchivePostDescriptor,
              isPreviewingCatalogThread = false
            )
          } else {
            openExternalThread(
              postDescriptor = externalArchivePostDescriptor,
              showOpenThreadDialog = false
            )
          }
        }
      }
    )

    presentController(floatingListMenuController)
  }

  companion object {
    private const val TAG = "ThreadController"
  }
}
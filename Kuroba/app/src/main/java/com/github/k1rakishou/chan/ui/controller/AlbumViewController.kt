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
import android.graphics.Color
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.Controller
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.helper.ThumbnailLongtapOptionsHelper
import com.github.k1rakishou.chan.core.manager.GlobalWindowInsetsManager
import com.github.k1rakishou.chan.core.manager.WindowInsetsListener
import com.github.k1rakishou.chan.core.navigation.RequiresNoBottomNavBar
import com.github.k1rakishou.chan.features.media_viewer.MediaViewerActivity
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaViewerGoToImagePostHelper
import com.github.k1rakishou.chan.features.media_viewer.helper.MediaViewerScrollerHelper
import com.github.k1rakishou.chan.features.settings.screens.AppearanceSettingsScreen.Companion.clampColumnsCount
import com.github.k1rakishou.chan.ui.cell.AlbumViewCell
import com.github.k1rakishou.chan.ui.cell.post_thumbnail.PostImageThumbnailView
import com.github.k1rakishou.chan.ui.controller.navigation.DoubleNavigationController
import com.github.k1rakishou.chan.ui.controller.navigation.SplitNavigationController
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableGridRecyclerView
import com.github.k1rakishou.chan.ui.toolbar.Toolbar.ToolbarHeightUpdatesCallback
import com.github.k1rakishou.chan.ui.toolbar.ToolbarMenuItem
import com.github.k1rakishou.chan.ui.toolbar.ToolbarMenuSubItem
import com.github.k1rakishou.chan.ui.view.FastScroller
import com.github.k1rakishou.chan.ui.view.FastScrollerHelper
import com.github.k1rakishou.chan.ui.view.FixedLinearLayoutManager
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.common.updatePaddings
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.filter.ChanFilterMutable
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.persist_state.PersistableChanState.albumLayoutGridMode
import com.github.k1rakishou.persist_state.PersistableChanState.showAlbumViewsImageDetails
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

class AlbumViewController(
  context: Context,
) : Controller(context), RequiresNoBottomNavBar, WindowInsetsListener, ToolbarHeightUpdatesCallback {
  private lateinit var recyclerView: ColorizableGridRecyclerView

  private var postImages: List<ChanPostImage>? = null
  private var targetIndex = -1
  private var chanDescriptor: ChanDescriptor? = null

  private var fastScroller: FastScroller? = null
  private var albumAdapter: AlbumAdapter? = null

  private val spanCountAndSpanWidth: SpanInfo
    get() {
      var albumSpanCount = ChanSettings.albumSpanCount.get()
      var albumSpanWith = DEFAULT_SPAN_WIDTH
      val displayWidth = AndroidUtils.getDisplaySize(context).x

      if (albumSpanCount == 0) {
        albumSpanCount = displayWidth / DEFAULT_SPAN_WIDTH
      } else {
        albumSpanWith = displayWidth / albumSpanCount
      }

      albumSpanCount = clampColumnsCount(albumSpanCount)
      return SpanInfo(albumSpanCount, albumSpanWith)
    }

  @Inject
  lateinit var globalWindowInsetsManager: GlobalWindowInsetsManager
  @Inject
  lateinit var thumbnailLongtapOptionsHelper: ThumbnailLongtapOptionsHelper
  @Inject
  lateinit var mediaViewerScrollerHelper: MediaViewerScrollerHelper
  @Inject
  lateinit var mediaViewerGoToImagePostHelper: MediaViewerGoToImagePostHelper

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onCreate() {
    super.onCreate()

    // View setup
    view = AppModuleAndroidUtils.inflate(context, R.layout.controller_album_view)
    recyclerView = view.findViewById(R.id.recycler_view)
    recyclerView.setHasFixedSize(true)
    albumAdapter = AlbumAdapter()
    recyclerView.adapter = albumAdapter
    updateRecyclerView(false)

    // Navigation
    val downloadDrawable = ContextCompat.getDrawable(context, R.drawable.ic_file_download_white_24dp)!!
    downloadDrawable.setTint(Color.WHITE)

    val gridDrawable = if (albumLayoutGridMode.get()) {
      ContextCompat.getDrawable(context, R.drawable.ic_baseline_view_quilt_24)!!
    } else {
      ContextCompat.getDrawable(context, R.drawable.ic_baseline_view_comfy_24)!!
    }

    gridDrawable.setTint(Color.WHITE)

    navigation
      .buildMenu(context)
      .withItem(ACTION_TOGGLE_LAYOUT_MODE, gridDrawable) { item -> toggleLayoutModeClicked(item) }
      .withItem(ACTION_DOWNLOAD, downloadDrawable) { item -> downloadAlbumClicked(item) }
      .withOverflow(navigationController)
      .withCheckableSubItem(
        ACTION_TOGGLE_IMAGE_DETAILS,
        R.string.action_album_show_image_details,
        true,
        showAlbumViewsImageDetails.get()
      ) { subItem: ToolbarMenuSubItem -> onToggleAlbumViewsImageInfoToggled(subItem) }
      .build()
      .build()

    fastScroller = FastScrollerHelper.create(
      FastScroller.FastScrollerControllerType.Album,
      recyclerView,
      null
    )

    mainScope.launch {
      mediaViewerScrollerHelper.mediaViewerScrollEventsFlow
        .collect { chanPostImage ->
          val index = postImages?.indexOf(chanPostImage)
            ?: return@collect

          scrollToInternal(index)
        }
    }

    mainScope.launch {
      mediaViewerGoToImagePostHelper.mediaViewerGoToPostEventsFlow
        .collect { chanPostImage -> goToPost(chanPostImage) }
    }

    requireNavController().requireToolbar().addToolbarHeightUpdatesCallback(this)
    globalWindowInsetsManager.addInsetsUpdatesListener(this)
    onInsetsChanged()
  }

  private fun scrollToInternal(scrollPosition: Int) {
    val layoutManager = recyclerView.layoutManager

    if (layoutManager is GridLayoutManager) {
      layoutManager.scrollToPositionWithOffset(scrollPosition, 0)
      return
    }

    if (layoutManager is StaggeredGridLayoutManager) {
      layoutManager.scrollToPositionWithOffset(scrollPosition, 0)
      return
    }

    if (layoutManager is FixedLinearLayoutManager) {
      layoutManager.scrollToPositionWithOffset(scrollPosition, 0)
      return
    }

    recyclerView.scrollToPosition(scrollPosition)
  }

  private fun updateRecyclerView(reloading: Boolean) {
    val spanInfo = spanCountAndSpanWidth
    val staggeredGridLayoutManager = StaggeredGridLayoutManager(
      spanInfo.spanCount,
      StaggeredGridLayoutManager.VERTICAL
    )

    recyclerView.layoutManager = staggeredGridLayoutManager
    recyclerView.setSpanWidth(spanInfo.spanWidth)
    recyclerView.itemAnimator = null
    recyclerView.scrollToPosition(targetIndex)

    if (reloading) {
      albumAdapter?.refresh()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    requireNavController().requireToolbar().removeToolbarHeightUpdatesCallback(this)
    globalWindowInsetsManager.removeInsetsUpdatesListener(this)

    fastScroller?.onCleanup()
    fastScroller = null

    recyclerView.swapAdapter(null, true)
  }

  override fun onToolbarHeightKnown(heightChanged: Boolean) {
    if (!heightChanged) {
      return
    }
    onInsetsChanged()
  }

  override fun onInsetsChanged() {
    var bottomPadding = globalWindowInsetsManager.bottom()
    if (ChanSettings.getCurrentLayoutMode() == ChanSettings.LayoutMode.SPLIT) {
      bottomPadding = 0
    }

    recyclerView.updatePaddings(
      null,
      FastScrollerHelper.FAST_SCROLLER_WIDTH,
      requireNavController().requireToolbar().toolbarHeight,
      bottomPadding
    )
  }

  fun setImages(
    chanDescriptor: ChanDescriptor?,
    postImages: List<ChanPostImage>,
    index: Int,
    title: String?
  ) {
    this.chanDescriptor = chanDescriptor
    this.postImages = postImages

    navigation.title = title
    navigation.subtitle = AppModuleAndroidUtils.getQuantityString(R.plurals.image, postImages.size, postImages.size)
    targetIndex = index
  }

  private fun onToggleAlbumViewsImageInfoToggled(subItem: ToolbarMenuSubItem) {
    showAlbumViewsImageDetails.toggle()
    albumAdapter?.refresh()
  }

  private fun downloadAlbumClicked(item: ToolbarMenuItem) {
    val albumDownloadController = AlbumDownloadController(context)
    albumDownloadController.setPostImages(postImages)
    requireNavController().pushController(albumDownloadController)
  }

  private fun toggleLayoutModeClicked(item: ToolbarMenuItem) {
    albumLayoutGridMode.toggle()
    updateRecyclerView(true)
    val menuItem = navigation.findItem(ACTION_TOGGLE_LAYOUT_MODE)

    val gridDrawable = if (albumLayoutGridMode.get()) {
      ContextCompat.getDrawable(context, R.drawable.ic_baseline_view_quilt_24)!!
    } else {
      ContextCompat.getDrawable(context, R.drawable.ic_baseline_view_comfy_24)!!
    }

    gridDrawable.setTint(Color.WHITE)
    menuItem.setImage(gridDrawable)
  }

  private fun goToPost(postImage: ChanPostImage) {
    var threadController: ThreadController? = null
    if (previousSiblingController is ThreadController) {
      //phone mode
      threadController = previousSiblingController as ThreadController?
    } else if (previousSiblingController is DoubleNavigationController) {
      //slide mode
      val doubleNav = previousSiblingController as DoubleNavigationController
      if (doubleNav.getRightController() is ThreadController) {
        threadController = doubleNav.getRightController() as ThreadController?
      }
    } else if (previousSiblingController == null) {
      //split nav has no "sibling" to look at, so we go WAY back to find the view thread controller
      val splitNav = parentController!!.parentController!!.presentedByController as SplitNavigationController?
      threadController = splitNav!!.rightController.childControllers[0] as ThreadController
      threadController.selectPostImage(postImage)
      //clear the popup here because split nav is weirdly laid out in the stack
      splitNav.popController()
    }

    if (threadController != null) {
      threadController.selectPostImage(postImage)
      navigationController!!.popController(false)
    }
  }

  private fun openImage(postImage: ChanPostImage) {
    val images = postImages
      ?: return
    val index = postImages?.indexOf(postImage)
      ?: return
    val descriptor = chanDescriptor
      ?: return

    when (descriptor) {
      is ChanDescriptor.CatalogDescriptor -> {
        MediaViewerActivity.catalogAlbum(
          context = context,
          catalogDescriptor = descriptor,
          initialImageUrl = images[index].imageUrl?.toString(),
          transitionThumbnailUrl = images[index].getThumbnailUrl()!!.toString(),
          lastTouchCoordinates = globalWindowInsetsManager.lastTouchCoordinates()
        )
      }
      is ChanDescriptor.ThreadDescriptor -> {
        MediaViewerActivity.threadAlbum(
          context = context,
          threadDescriptor = descriptor,
          initialImageUrl = images[index].imageUrl?.toString(),
          transitionThumbnailUrl = images[index].getThumbnailUrl()!!.toString(),
          lastTouchCoordinates = globalWindowInsetsManager.lastTouchCoordinates()
        )
      }
    }
  }

  private fun showImageLongClickOptions(postImage: ChanPostImage) {
    thumbnailLongtapOptionsHelper.onThumbnailLongTapped(
      context = context,
      isCurrentlyInAlbum = true,
      postImage = postImage,
      presentControllerFunc = { controller -> presentController(controller) },
      showFiltersControllerFunc = { }
    )
  }

  private inner class AlbumAdapter : RecyclerView.Adapter<AlbumItemCellHolder>() {
    private val albumCellType = 1

    init {
      setHasStableIds(true)
    }

    override fun getItemViewType(position: Int): Int {
      return albumCellType
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumItemCellHolder {
      val view = AppModuleAndroidUtils.inflate(parent.context, R.layout.cell_album_view, parent, false)
      return AlbumItemCellHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumItemCellHolder, position: Int) {
      val postImage = postImages?.get(position)
      if (postImage == null) {
        return
      }

      val canUseHighResCells = ColorizableGridRecyclerView.canUseHighResCells(recyclerView.currentSpanCount)
      val isStaggeredGridMode = !albumLayoutGridMode.get()
      holder.cell.bindPostImage(
        postImage,
        canUseHighResCells,
        isStaggeredGridMode,
        showAlbumViewsImageDetails.get()
      )
    }

    override fun onViewRecycled(holder: AlbumItemCellHolder) {
      holder.cell.unbindPostImage()
    }

    override fun getItemCount(): Int {
      return postImages!!.size
    }

    override fun getItemId(position: Int): Long {
      return position.toLong()
    }

    fun refresh() {
      notifyDataSetChanged()
    }
  }

  private inner class AlbumItemCellHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener, OnLongClickListener {
    private val ALBUM_VIEW_CELL_THUMBNAIL_CLICK_TOKEN = "ALBUM_VIEW_CELL_THUMBNAIL_CLICK"
    private val ALBUM_VIEW_CELL_THUMBNAIL_LONG_CLICK_TOKEN = "ALBUM_VIEW_CELL_THUMBNAIL_LONG_CLICK"

    val cell = itemView as AlbumViewCell
    val thumbnailView = cell.thumbnailView as PostImageThumbnailView

    init {
      thumbnailView.setOnImageClickListener(ALBUM_VIEW_CELL_THUMBNAIL_CLICK_TOKEN, this)
      thumbnailView.setOnImageLongClickListener(ALBUM_VIEW_CELL_THUMBNAIL_LONG_CLICK_TOKEN, this)
    }

    override fun onClick(v: View) {
      val postImage = postImages?.get(adapterPosition)
        ?: return

      openImage(postImage)
    }

    override fun onLongClick(v: View): Boolean {
      val postImage = postImages?.get(adapterPosition)
        ?: return false

      showImageLongClickOptions(postImage)
      return true
    }

  }

  private class SpanInfo(val spanCount: Int, val spanWidth: Int)

  interface ThreadControllerCallbacks {
    fun openFiltersController(chanFilterMutable: ChanFilterMutable)
  }

  companion object {
    private val DEFAULT_SPAN_WIDTH = AppModuleAndroidUtils.dp(120f)
    private const val ACTION_DOWNLOAD = 0
    private const val ACTION_TOGGLE_LAYOUT_MODE = 1
    private const val ACTION_TOGGLE_IMAGE_DETAILS = 2
  }
}
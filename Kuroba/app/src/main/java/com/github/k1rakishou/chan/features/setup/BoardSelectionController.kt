package com.github.k1rakishou.chan.features.setup

import android.content.Context
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.epoxy.EpoxyController
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.features.setup.data.BoardSelectionControllerState
import com.github.k1rakishou.chan.features.setup.epoxy.selection.epoxyBoardSelectionGridView
import com.github.k1rakishou.chan.features.setup.epoxy.selection.epoxyBoardSelectionListView
import com.github.k1rakishou.chan.features.setup.epoxy.selection.epoxySiteSelectionView
import com.github.k1rakishou.chan.ui.controller.BaseFloatingController
import com.github.k1rakishou.chan.ui.controller.FloatingListMenuController
import com.github.k1rakishou.chan.ui.epoxy.epoxyErrorView
import com.github.k1rakishou.chan.ui.epoxy.epoxyTextView
import com.github.k1rakishou.chan.ui.layout.SearchLayout
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableBarButton
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableEpoxyRecyclerView
import com.github.k1rakishou.chan.ui.view.floating_menu.CheckableFloatingListMenuItem
import com.github.k1rakishou.chan.ui.view.floating_menu.FloatingListMenuItem
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.core_themes.ThemeEngine
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.persist_state.PersistableChanState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds

class BoardSelectionController(
  context: Context,
  private val callback: UserSelectionListener
) : BaseFloatingController(context), BoardSelectionView, ThemeEngine.ThemeChangesListener {

  @Inject
  lateinit var themeEngine: ThemeEngine
  @Inject
  lateinit var siteManager: SiteManager
  @Inject
  lateinit var boardManager: BoardManager
  @Inject
  lateinit var archivesManager: ArchivesManager

  private val presenter by lazy {
    BoardSelectionPresenter(
      siteManager = siteManager,
      boardManager = boardManager,
      archivesManager = archivesManager,
    )
  }

  private val controller = BoardsSelectionEpoxyController()

  private lateinit var epoxyRecyclerView: ColorizableEpoxyRecyclerView
  private lateinit var searchView: SearchLayout
  private lateinit var outsideArea: FrameLayout
  private lateinit var openSitesButton: ColorizableBarButton
  private lateinit var openSettingsButton: AppCompatImageView

  override fun getLayoutId(): Int = R.layout.controller_board_selection

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  @OptIn(ExperimentalTime::class)
  override fun onCreate() {
    super.onCreate()

    epoxyRecyclerView = view.findViewById(R.id.epoxy_recycler_view)
    epoxyRecyclerView.setController(controller)

    outsideArea = view.findViewById(R.id.outside_area)
    searchView = view.findViewById(R.id.search_view)
    searchView.setAutoRequestFocus(false)
    openSitesButton = view.findViewById(R.id.open_all_sites_settings)
    openSettingsButton = view.findViewById(R.id.open_settings_button)

    openSitesButton.setOnClickListener {
      callback.onOpenSitesSettingsClicked()
      pop()
    }

    outsideArea.setOnClickListener {
      pop()
    }

    openSettingsButton.setOnClickListener { showOptions() }

    mainScope.launch {
      startListeningForSearchQueries()
        .debounce(350.milliseconds)
        .collect { query -> presenter.onSearchQueryChanged(query) }
    }

    compositeDisposable.add(
      presenter.listenForStateChanges()
        .subscribe { state -> onStateChanged(state) }
    )

    themeEngine.addListener(this)
    presenter.onCreate(this)

    updateRecyclerLayoutMode()
    onThemeChanged()
  }

  private fun showOptions() {
    val drawerOptions = mutableListOf<FloatingListMenuItem>()

    drawerOptions += CheckableFloatingListMenuItem(
      key = ACTION_TOGGLE_LAYOUT_MODE,
      name = AppModuleAndroidUtils.getString(R.string.board_selection_controller_grid_layout_mode),
      isCurrentlySelected = PersistableChanState.boardSelectionGridMode.get()
    )

    val floatingListMenuController = FloatingListMenuController(
      context = context,
      constraintLayoutBias = globalWindowInsetsManager.lastTouchCoordinatesAsConstraintLayoutBias(),
      items = drawerOptions,
      itemClickListener = { item -> onDrawerOptionClicked(item) }
    )

    presentController(floatingListMenuController)
  }

  private fun onDrawerOptionClicked(item: FloatingListMenuItem) {
    when (item.key) {
      ACTION_TOGGLE_LAYOUT_MODE -> {
        PersistableChanState.boardSelectionGridMode.toggle()
        updateRecyclerLayoutMode()
      }
    }
  }

  private fun updateRecyclerLayoutMode() {
    val isGridMode = PersistableChanState.boardSelectionGridMode.get()
    if (isGridMode) {
      val screenWidth = AndroidUtils.getDisplaySize(context).x
      val spanCount = (screenWidth / GRID_COLUMN_WIDTH).coerceIn(MIN_SPAN_COUNT, MAX_SPAN_COUNT)

      epoxyRecyclerView.layoutManager = GridLayoutManager(context, spanCount).apply {
        spanSizeLookup = controller.spanSizeLookup
      }

      presenter.reloadBoards()
      return
    }

    epoxyRecyclerView.layoutManager = LinearLayoutManager(context)
    presenter.reloadBoards()
  }

  override fun onDestroy() {
    super.onDestroy()

    epoxyRecyclerView.clear()
    presenter.onDestroy()
    themeEngine.removeListener(this)
  }

  override fun onThemeChanged() {
    openSettingsButton.setImageDrawable(
      themeEngine.tintDrawable(context, R.drawable.ic_more_vert_white_24dp)
    )
  }

  private fun onStateChanged(state: BoardSelectionControllerState) {
    controller.callback = {
      when (state) {
        BoardSelectionControllerState.Empty -> {
          epoxyTextView {
            id("boards_selection_empty_text_view")
            message(context.getString(R.string.controller_boards_selection_no_boards))
          }
        }
        is BoardSelectionControllerState.Error -> {
          epoxyErrorView {
            id("boards_selection_error_view")
            errorMessage(state.errorText)
          }
        }
        is BoardSelectionControllerState.Data -> {
          state.sortedSiteWithBoardsData.entries.forEach { (siteCellData, boardCellDataList) ->
            epoxySiteSelectionView {
              id("boards_selection_site_selection_view_${siteCellData.siteDescriptor}")
              bindIcon(siteCellData.siteIcon)
              bindSiteName(siteCellData.siteName)
              bindRowClickCallback {
                callback.onSiteSelected(siteCellData.siteDescriptor)
                pop()
              }
            }

            val gridMode = PersistableChanState.boardSelectionGridMode.get()

            boardCellDataList.forEach { boardCellData ->
              if (gridMode) {
                epoxyBoardSelectionGridView {
                  id("boards_selection_board_selection_grid_view_${boardCellData.boardDescriptor}")
                  bindBoardCode(boardCellData.boardCodeFormatted)
                  bindBoardName(boardCellData.boardName)
                  bindQuery(boardCellData.searchQuery)
                  bindRowClickCallback {
                    callback.onBoardSelected(boardCellData.boardDescriptor)
                    pop()
                  }
                }
              } else {
                epoxyBoardSelectionListView {
                  id("boards_selection_board_selection_list_view_${boardCellData.boardDescriptor}")
                  bindBoardCode(boardCellData.boardCodeFormatted)
                  bindBoardName(boardCellData.boardName)
                  bindQuery(boardCellData.searchQuery)
                  bindRowClickCallback {
                    callback.onBoardSelected(boardCellData.boardDescriptor)
                    pop()
                  }
                }
              }
            }
          }
        }
      }
    }

    controller.requestModelBuild()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun startListeningForSearchQueries(): Flow<String> {
    return callbackFlow<String> {
      searchView.setCallback { query ->
        offer(query)
      }

      awaitClose()
    }
  }

  private class BoardsSelectionEpoxyController : EpoxyController() {
    var callback: EpoxyController.() -> Unit = {}

    override fun buildModels() {
      callback(this)
    }
  }

  interface UserSelectionListener {
    fun onOpenSitesSettingsClicked()
    fun onSiteSelected(siteDescriptor: SiteDescriptor)
    fun onBoardSelected(boardDescriptor: BoardDescriptor)
  }

  companion object {
    private const val ACTION_TOGGLE_LAYOUT_MODE = 0

    private const val MIN_SPAN_COUNT = 2
    private const val MAX_SPAN_COUNT = 10

    private val GRID_COLUMN_WIDTH = AppModuleAndroidUtils.dp(64f)
  }

}
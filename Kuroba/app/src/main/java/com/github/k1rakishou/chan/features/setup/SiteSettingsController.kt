package com.github.k1rakishou.chan.features.setup

import android.content.Context
import com.airbnb.epoxy.EpoxyController
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.controller.Controller
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.features.settings.BaseSettingsController
import com.github.k1rakishou.chan.features.settings.SettingsGroup
import com.github.k1rakishou.chan.features.settings.epoxy.epoxyLinkSetting
import com.github.k1rakishou.chan.features.settings.epoxy.epoxySettingsGroupTitle
import com.github.k1rakishou.chan.features.settings.setting.InputSettingV2
import com.github.k1rakishou.chan.features.settings.setting.LinkSettingV2
import com.github.k1rakishou.chan.features.settings.setting.ListSettingV2
import com.github.k1rakishou.chan.features.settings.setting.SettingV2
import com.github.k1rakishou.chan.ui.epoxy.epoxyDividerView
import com.github.k1rakishou.chan.ui.settings.SettingNotificationType
import com.github.k1rakishou.chan.ui.theme.widget.ColorizableEpoxyRecyclerView
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.inflate
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SiteSettingsController(
  context: Context,
  private val siteDescriptor: SiteDescriptor
) : BaseSettingsController(context), SiteSettingsView {

  @Inject
  lateinit var siteManager: SiteManager
  @Inject
  lateinit var boardManager: BoardManager
  @Inject
  lateinit var archivesManager: ArchivesManager

  private val presenter by lazy {
    SiteSettingsPresenter(
      siteManager = siteManager,
      boardManager = boardManager,
      archivesManager = archivesManager
    )
  }

  private lateinit var epoxyRecyclerView: ColorizableEpoxyRecyclerView

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onCreate() {
    super.onCreate()
    navigation.title = context.getString(R.string.controller_site_settings_title, siteDescriptor.siteName)

    view = inflate(context, R.layout.controller_site_settings)
    epoxyRecyclerView = view.findViewById(R.id.epoxy_recycler_view)

    presenter.onCreate(this)
  }

  override fun onShow() {
    super.onShow()
    rebuildSettings()
  }

  override fun onDestroy() {
    super.onDestroy()

    epoxyRecyclerView.clear()
    presenter.onDestroy()
  }

  private fun rebuildSettings() {
    mainScope.launch {
      val groups = presenter.showSiteSettings(context, siteDescriptor)
      renderSettingGroups(groups)
    }
  }

  private fun renderSettingGroups(groups: List<SettingsGroup>) {
    epoxyRecyclerView.withModels {
      var globalSettingIndex = 0

      groups.forEach { settingsGroup ->
        epoxySettingsGroupTitle {
          id("epoxy_settings_group_title_${settingsGroup.groupIdentifier.getGroupIdentifier()}")
          groupTitle(settingsGroup.groupTitle)
        }

        var groupSettingIndex = 0

        settingsGroup.iterateSettings { setting ->
          renderSettingInternal(
            setting,
            settingsGroup,
            groupSettingIndex++,
            globalSettingIndex++
          )
        }
      }
    }
  }

  private fun EpoxyController.renderSettingInternal(
    settingV2: SettingV2,
    settingsGroup: SettingsGroup,
    groupSettingIndex: Int,
    globalSettingIndex: Int
  ) {
    when (settingV2) {
      is LinkSettingV2 -> {
        epoxyLinkSetting {
          id("epoxy_link_setting_${settingV2.settingsIdentifier.getIdentifier()}")
          topDescription(settingV2.topDescription)
          bottomDescription(settingV2.bottomDescription)
          settingEnabled(true)
          bindNotificationIcon(SettingNotificationType.Default)

          clickListener {
            mainScope.launch { settingV2.callback.invoke() }
          }
        }
      }
      is ListSettingV2<*> -> {
        epoxyLinkSetting {
          id("epoxy_list_setting_${settingV2.settingsIdentifier.getIdentifier()}")
          topDescription(settingV2.topDescription)
          bottomDescription(settingV2.bottomDescription)
          bindNotificationIcon(SettingNotificationType.Default)
          settingEnabled(true)

          clickListener {
            val prev = settingV2.getValue()

            showListDialog(settingV2) { curr ->
              if (prev == curr) {
                return@showListDialog
              }

              rebuildSettings()
            }
          }
        }
      }
      is InputSettingV2<*> -> {
        epoxyLinkSetting {
          id("epoxy_string_setting_${settingV2.settingsIdentifier.getIdentifier()}")
          topDescription(settingV2.topDescription)
          bottomDescription(settingV2.bottomDescription)
          bindNotificationIcon(SettingNotificationType.Default)
          settingEnabled(true)

          clickListener { view ->
            val prev = settingV2.getCurrent()

            showInputDialog(settingV2) { curr ->
              if (prev == curr) {
                return@showInputDialog
              }

              rebuildSettings()
            }
          }
        }
      }
    }

    if (groupSettingIndex != settingsGroup.lastIndex()) {
      epoxyDividerView {
        id("epoxy_divider_${globalSettingIndex}")
        updateMargins(null)
      }
    }
  }

  override suspend fun showErrorToast(message: String) {
    withContext(Dispatchers.Main) { showToast(message) }
  }

  override fun pushController(controller: Controller) {
    navigationController!!.pushController(controller)
  }

  override fun openControllerWrappedIntoBottomNavAwareController(controller: Controller) {
    requireStartActivity().openControllerWrappedIntoBottomNavAwareController(controller)
    requireStartActivity().setSettingsMenuItemSelected()
  }

}
package com.github.k1rakishou.chan.features.settings.screens

import android.content.Context
import android.widget.Toast
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.helper.DialogFactory
import com.github.k1rakishou.chan.core.manager.PostHideManager
import com.github.k1rakishou.chan.features.settings.BehaviorScreen
import com.github.k1rakishou.chan.features.settings.SettingsGroup
import com.github.k1rakishou.chan.features.settings.setting.BooleanSettingV2
import com.github.k1rakishou.chan.features.settings.setting.InputSettingV2
import com.github.k1rakishou.chan.features.settings.setting.LinkSettingV2
import com.github.k1rakishou.chan.features.setup.SitesSetupController
import com.github.k1rakishou.chan.ui.controller.navigation.NavigationController
import com.github.k1rakishou.chan.ui.controller.settings.captcha.JsCaptchaCookiesEditorController
import com.github.k1rakishou.chan.ui.helper.RefreshUIMessage
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.postToEventBus
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.showToast

class BehaviourSettingsScreen(
  context: Context,
  private val navigationController: NavigationController,
  private val postHideManager: PostHideManager
) : BaseSettingsScreen(
  context,
  BehaviorScreen,
  R.string.settings_screen_behavior
) {

  override suspend fun buildGroups(): List<SettingsGroup.SettingsGroupBuilder> {
    return listOf(
      buildMainSettingsGroup(),
      buildReplySettingsGroup(),
      buildPostSettingsGroup(),
      buildOtherSettingsGroup()
    )
  }

  private fun buildOtherSettingsGroup(): SettingsGroup.SettingsGroupBuilder {
    val identifier = BehaviorScreen.OtherSettingsGroup

    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = identifier,
      buildFunction = {
        val group = SettingsGroup(
          groupTitle = context.getString(R.string.setting_other_settings_group),
          groupIdentifier = identifier
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.OtherSettingsGroup.FullUserRotationEnable,
          topDescriptionIdFunc = { R.string.setting_full_screen_rotation },
          setting = ChanSettings.fullUserRotationEnable,
          requiresRestart = true
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.OtherSettingsGroup.ShowCopyApkUpdateDialog,
          topDescriptionIdFunc = { R.string.settings_show_copy_apk_dialog_title },
          bottomDescriptionIdFunc = { R.string.settings_show_copy_apk_dialog_message },
          setting = ChanSettings.showCopyApkUpdateDialog
        )

        group
      }
    )
  }

  private fun buildPostSettingsGroup(): SettingsGroup.SettingsGroupBuilder {
    val identifier = BehaviorScreen.PostGroup

    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = identifier,
      buildFunction = {
        val group = SettingsGroup(
          groupTitle = context.getString(R.string.settings_group_post),
          groupIdentifier = identifier
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.PostGroup.VolumeKeysScrolling,
          topDescriptionIdFunc = { R.string.setting_volume_key_scrolling },
          setting = ChanSettings.volumeKeysScrolling
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.PostGroup.TapNoReply,
          topDescriptionIdFunc = { R.string.setting_tap_no_rely },
          setting = ChanSettings.tapNoReply
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.PostGroup.MarkUnseenPosts,
          topDescriptionIdFunc = { R.string.setting_mark_unseen_posts_title },
          bottomDescriptionIdFunc = { R.string.setting_mark_unseen_posts_duration },
          setting = ChanSettings.markUnseenPosts,
          requiresUiRefresh = true
        )

        group
      }
    )
  }

  private fun buildReplySettingsGroup(): SettingsGroup.SettingsGroupBuilder {
    val identifier = BehaviorScreen.RepliesGroup

    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = identifier,
      buildFunction = {
        val group = SettingsGroup(
          groupTitle = context.getString(R.string.settings_group_reply),
          groupIdentifier = identifier
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.RepliesGroup.PostPinThread,
          topDescriptionIdFunc = { R.string.setting_post_pin },
          setting = ChanSettings.postPinThread
        )

        group += InputSettingV2.createBuilder<String>(
          context = context,
          identifier = BehaviorScreen.RepliesGroup.PostDefaultName,
          topDescriptionIdFunc = { R.string.setting_post_default_name },
          bottomDescriptionStringFunc = { ChanSettings.postDefaultName.get() },
          setting = ChanSettings.postDefaultName,
          inputType = DialogFactory.DialogInputType.String
        )

        group
      }
    )
  }

  private fun buildMainSettingsGroup(): SettingsGroup.SettingsGroupBuilder {
    val identifier = BehaviorScreen.GeneralGroup

    return SettingsGroup.SettingsGroupBuilder(
      groupIdentifier = identifier,
      buildFunction = {
        val group = SettingsGroup(
          groupTitle = context.getString(R.string.settings_group_general),
          groupIdentifier = identifier
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.AutoRefreshThread,
          topDescriptionIdFunc = { R.string.setting_auto_refresh_thread },
          setting = ChanSettings.autoRefreshThread
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.ControllerSwipeable,
          topDescriptionIdFunc = { R.string.setting_controller_swipeable },
          setting = ChanSettings.controllerSwipeable,
          requiresRestart = true
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.ReplyLayoutOpenCloseGestures,
          topDescriptionIdFunc = { R.string.setting_controller_reply_layout_gestures },
          bottomDescriptionIdFunc = { R.string.setting_controller_reply_layout_gestures_description },
          setting = ChanSettings.replyLayoutOpenCloseGestures
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.OpenLinkConfirmation,
          topDescriptionIdFunc = { R.string.setting_open_link_confirmation },
          setting = ChanSettings.openLinkConfirmation
        )

        group += LinkSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.CaptchaSetup,
          topDescriptionIdFunc = { R.string.setting_captcha_setup },
          bottomDescriptionIdFunc = { R.string.setting_captcha_setup_description },
          callback = { navigationController.pushController(SitesSetupController(context)) }
        )

        group += LinkSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.JsCaptchaCookiesEditor,
          topDescriptionIdFunc = { R.string.setting_js_captcha_cookies_title },
          bottomDescriptionIdFunc = { R.string.setting_js_captcha_cookies_description },
          callback = { navigationController.pushController(JsCaptchaCookiesEditorController(context)) }
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.LoadLastOpenedBoardUponAppStart,
          topDescriptionIdFunc = { R.string.setting_load_last_opened_board_upon_app_start_title },
          bottomDescriptionIdFunc = { R.string.setting_load_last_opened_board_upon_app_start_description },
          setting = ChanSettings.loadLastOpenedBoardUponAppStart
        )

        group += BooleanSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.LoadLastOpenedThreadUponAppStart,
          topDescriptionIdFunc = { R.string.setting_load_last_opened_thread_upon_app_start_title },
          bottomDescriptionIdFunc = { R.string.setting_load_last_opened_thread_upon_app_start_description },
          setting = ChanSettings.loadLastOpenedThreadUponAppStart
        )

        group += LinkSettingV2.createBuilder(
          context = context,
          identifier = BehaviorScreen.GeneralGroup.ClearPostHides,
          topDescriptionIdFunc = { R.string.setting_clear_post_hides },
          callback = {
            postHideManager.clearAllPostHides()
            showToast(context, R.string.setting_cleared_post_hides, Toast.LENGTH_LONG)
            postToEventBus(RefreshUIMessage("clearhides"))
          }
        )

        group
      }
    )
  }
}
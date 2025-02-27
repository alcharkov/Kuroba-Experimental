package com.github.k1rakishou.chan.ui.cell

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.UnderlineSpan
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.base.RecalculatableLazy
import com.github.k1rakishou.chan.ui.adapter.PostsFilter
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.sp
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.SpannableHelper
import com.github.k1rakishou.common.MurmurHashUtils
import com.github.k1rakishou.common.StringUtils
import com.github.k1rakishou.common.ellipsizeEnd
import com.github.k1rakishou.common.isNotNullNorBlank
import com.github.k1rakishou.core_spannable.AbsoluteSizeSpanHashed
import com.github.k1rakishou.core_spannable.ForegroundColorSpanHashed
import com.github.k1rakishou.core_themes.ChanTheme
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.ChanOriginalPost
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ChanPostHttpIcon
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.util.ChanPostUtils
import java.util.*

data class PostCellData(
  val chanDescriptor: ChanDescriptor,
  val post: ChanPost,
  val postIndex: Int,
  var textSizeSp: Int,
  var highlighted: Boolean,
  var postSelected: Boolean,
  private var markedPostNo: Long?,
  var showDivider: Boolean,
  var boardPostViewMode: ChanSettings.BoardPostViewMode,
  var boardPostsSortOrder: PostsFilter.Order,
  var neverShowPages: Boolean,
  var compact: Boolean,
  var stub: Boolean,
  var theme: ChanTheme,
  var filterHash: Int,
  var filterHighlightedColor: Int,
  var postViewMode: PostViewMode,
  var searchQuery: SearchQuery
) {
  var postCellCallback: PostCellInterface.PostCellCallback? = null

  private var detailsSizePxPrecalculated: Int? = null
  private var postTitlePrecalculated: CharSequence? = null
  private var postFileInfoPrecalculated: MutableMap<ChanPostImage, SpannableString>? = null
  private var postFileInfoHashPrecalculated: MurmurHashUtils.Murmur3Hash? = null
  private var commentTextPrecalculated: CharSequence? = null
  private var catalogRepliesTextPrecalculated: CharSequence? = null

  val postNo: Long
    get() = post.postNo()
  val postSubNo: Long
    get() = post.postSubNo()
  val threadMode: Boolean
    get() = chanDescriptor.isThreadDescriptor()
  val hasColoredFilter: Boolean
    get() = filterHighlightedColor != 0
  val postDescriptor: PostDescriptor
    get() = post.postDescriptor
  val fullPostComment: CharSequence
    get() = post.postComment.comment()
  val postImages: List<ChanPostImage>
    get() = post.postImages
  val postIcons: List<ChanPostHttpIcon>
    get() = post.postIcons
  val isDeleted: Boolean
    get() = post.deleted
  val isSticky: Boolean
    get() = (post as? ChanOriginalPost)?.sticky ?: false
  val isClosed: Boolean
    get() = (post as? ChanOriginalPost)?.closed ?: false
  val isArchived: Boolean
    get() = (post as? ChanOriginalPost)?.archived ?: false
  val catalogRepliesCount: Int
    get() = post.catalogRepliesCount
  val catalogImagesCount: Int
    get() = post.catalogImagesCount
  val repliesFromCount: Int
    get() = post.repliesFromCount
  val singleImageMode: Boolean
    get() = post.postImages.size == 1
  val isInPopup: Boolean
    get() = postViewMode == PostViewMode.RepliesPopup
      || postViewMode == PostViewMode.ExternalPostsPopup
      || postViewMode == PostViewMode.Search
  val isSelectionMode: Boolean
    get() = postViewMode == PostViewMode.PostSelection
  val threadPreviewMode: Boolean
    get() = postViewMode == PostViewMode.ExternalPostsPopup
  val searchMode: Boolean
    get() = postViewMode == PostViewMode.Search
  val markedNo: Long
    get() = markedPostNo ?: -1

  private val _detailsSizePx = RecalculatableLazy { detailsSizePxPrecalculated ?: sp(textSizeSp - 4.toFloat()) }
  private val _postTitle = RecalculatableLazy { postTitlePrecalculated ?: calculatePostTitle() }
  private val _postFileInfoMap = RecalculatableLazy { postFileInfoPrecalculated ?: calculatePostFileInfo() }
  private val _postFileInfoMapHash = RecalculatableLazy { postFileInfoHashPrecalculated ?: calculatePostFileInfoHash(_postFileInfoMap) }
  private val _commentText = RecalculatableLazy { commentTextPrecalculated ?: calculateCommentText() }
  private val _catalogRepliesText = RecalculatableLazy { catalogRepliesTextPrecalculated ?: calculateCatalogRepliesText() }

  val detailsSizePx: Int
    get() = _detailsSizePx.value()
  val postTitle: CharSequence
    get() = _postTitle.value()
  val postFileInfoMap: Map<ChanPostImage, SpannableString>
    get() = _postFileInfoMap.value()
  val postFileInfoMapHash: MurmurHashUtils.Murmur3Hash
    get() = _postFileInfoMapHash.value()
  val commentText: CharSequence
    get() = _commentText.value()
  val catalogRepliesText
    get() = _catalogRepliesText.value()

  fun resetCommentTextCache() {
    commentTextPrecalculated = null
    _commentText.resetValue()
  }

  fun resetPostTitleCache() {
    postTitlePrecalculated = null
    _postTitle.resetValue()
  }

  fun resetPostFileInfoCache() {
    postFileInfoPrecalculated = null
    postFileInfoHashPrecalculated = null
    _postFileInfoMap.resetValue()
    _postFileInfoMapHash.resetValue()
  }

  fun resetCatalogRepliesTextCache() {
    catalogRepliesTextPrecalculated = null
    _catalogRepliesText.resetValue()
  }

  suspend fun preload() {
    BackgroundUtils.ensureBackgroundThread()

    // Force lazily evaluated values to get calculated and cached
    _detailsSizePx.value()
    _postTitle.value()
    _postFileInfoMap.value()
    _postFileInfoMapHash.value()
    _commentText.value()
    _catalogRepliesText.value()
  }

  fun fullCopy(): PostCellData {
    return PostCellData(
      chanDescriptor = chanDescriptor,
      post = post,
      postIndex = postIndex,
      textSizeSp = textSizeSp,
      highlighted = highlighted,
      postSelected = postSelected,
      markedPostNo = markedPostNo,
      showDivider = showDivider,
      boardPostViewMode = boardPostViewMode,
      boardPostsSortOrder = boardPostsSortOrder,
      neverShowPages = neverShowPages,
      compact = compact,
      stub = stub,
      theme = theme,
      filterHash = filterHash,
      filterHighlightedColor = filterHighlightedColor,
      postViewMode = postViewMode,
      searchQuery = searchQuery
    ).also { newPostCellData ->
      newPostCellData.postCellCallback = postCellCallback
      newPostCellData.detailsSizePxPrecalculated = detailsSizePxPrecalculated
      newPostCellData.postTitlePrecalculated = postTitlePrecalculated
      newPostCellData.commentTextPrecalculated = commentTextPrecalculated
      newPostCellData.catalogRepliesTextPrecalculated = catalogRepliesTextPrecalculated
    }
  }

  fun cleanup() {
    postCellCallback = null
  }

  private fun calculatePostTitle(): CharSequence {
    if (stub) {
      if (!TextUtils.isEmpty(post.subject)) {
        return post.subject!!
      }

      return getPostStubTitle()
    }

    val titleParts: MutableList<CharSequence> = ArrayList(5)
    var postIndexText = ""

    if (chanDescriptor.isThreadDescriptor() && postIndex >= 0) {
      postIndexText = String.format(Locale.ENGLISH, "#%d, ", postIndex + 1)
    }

    if (post.subject.isNotNullNorBlank()) {
      val postSubject = SpannableString.valueOf(post.subject!!)

      SpannableHelper.findAllQueryEntriesInsideSpannableStringAndMarkThem(
        inputQueries = listOf(searchQuery.query),
        spannableString = postSubject,
        color = theme.accentColor,
        minQueryLength = searchQuery.queryMinValidLength
      )

      titleParts.add(postSubject)
      titleParts.add("\n")
    }

    if (post.tripcode.isNotNullNorBlank()) {
      titleParts.add(post.tripcode!!)
    }

    val noText = String.format(Locale.ENGLISH, "%sNo. %d", postIndexText, post.postNo())
    val time = calculatePostTime(post)
    val date = SpannableString("$noText $time")

    date.setSpan(ForegroundColorSpanHashed(theme.postDetailsColor), 0, date.length, 0)
    date.setSpan(AbsoluteSizeSpanHashed(detailsSizePx), 0, date.length, 0)

    if (ChanSettings.tapNoReply.get()) {
      date.setSpan(PostCell.PostNumberClickableSpan(postCellCallback, post), 0, noText.length, 0)
    }

    titleParts.add(date)
    return TextUtils.concat(*titleParts.toTypedArray())
  }

  private fun getPostStubTitle(): CharSequence {
    val titleText = post.postComment.comment()

    if (titleText.isEmpty()) {
      val firstImage = post.firstImage()
      if (firstImage != null) {
        var fileName = firstImage.filename
        if (TextUtils.isEmpty(fileName)) {
          fileName = firstImage.serverFilename
        }

        val extension = firstImage.extension
        if (TextUtils.isEmpty(extension)) {
          return fileName!!
        }

        return "$fileName.$extension"
      }
    }

    if (titleText.length > POST_STUB_TITLE_MAX_LENGTH) {
      return titleText.subSequence(0, POST_STUB_TITLE_MAX_LENGTH)
    }

    return titleText
  }

  private fun calculatePostTime(post: ChanPost): CharSequence {
    val postTime = if (ChanSettings.postFullDate.get()) {
      ChanPostUtils.getLocalDate(post)
    } else {
      DateUtils.getRelativeTimeSpanString(post.timestamp * 1000L, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, 0)
        .toString()
    }

    return postTime.replace(' ', StringUtils.UNBREAKABLE_SPACE_SYMBOL)
  }

  private fun calculateCommentText(): CharSequence {
    val commentText = SpannableString.valueOf(calculateCommentTextInternal())

    SpannableHelper.findAllQueryEntriesInsideSpannableStringAndMarkThem(
      inputQueries = listOf(searchQuery.query),
      spannableString = commentText,
      color = theme.accentColor,
      minQueryLength = searchQuery.queryMinValidLength
    )

    return commentText
  }

  private fun calculatePostFileInfo(): Map<ChanPostImage, SpannableString> {
    val postFileInfoTextMap = calculatePostFileInfoInternal()

    postFileInfoTextMap.entries.forEach { (_, postFileInfoSpannable) ->
      SpannableHelper.findAllQueryEntriesInsideSpannableStringAndMarkThem(
        inputQueries = listOf(searchQuery.query),
        spannableString = postFileInfoSpannable,
        color = theme.accentColor,
        minQueryLength = searchQuery.queryMinValidLength
      )
    }

    return postFileInfoTextMap
  }

  private fun calculatePostFileInfoInternal(): Map<ChanPostImage, SpannableString> {
    if (postImages.isEmpty()) {
      return emptyMap()
    }

    val resultMap = mutableMapOf<ChanPostImage, SpannableString>()
    val detailsSizePx = sp(textSizeSp - 4.toFloat())

    postImages.forEach { postImage ->
      val fileInfoText = SpannableStringBuilder()
      fileInfoText.append(postImage.formatFullAvailableFileName(appendExtension = postImages.size != 1))
      fileInfoText.setSpan(UnderlineSpan(), 0, fileInfoText.length, 0)

      if (postImages.size == 1) {
        fileInfoText.append(postImage.formatImageInfo())
      }

      fileInfoText.setSpan(ForegroundColorSpanHashed(theme.postDetailsColor), 0, fileInfoText.length, 0)
      fileInfoText.setSpan(AbsoluteSizeSpanHashed(detailsSizePx), 0, fileInfoText.length, 0)

      resultMap[postImage] = SpannableString.valueOf(fileInfoText)
    }

    return resultMap
  }

  private fun calculateCommentTextInternal(): CharSequence {
    if (boardPostViewMode == ChanSettings.BoardPostViewMode.LIST) {
      if (threadMode || post.postComment.comment().length <= COMMENT_MAX_LENGTH_LIST) {
        return post.postComment.comment()
      }

      return post.postComment.comment().ellipsizeEnd(COMMENT_MAX_LENGTH_LIST)
    }

    val commentText = post.postComment.comment()
    var commentMaxLength = COMMENT_MAX_LENGTH_GRID

    if (boardPostViewMode == ChanSettings.BoardPostViewMode.STAGGER) {
      val spanCount = postCellCallback!!.currentSpanCount()

      // The higher the spanCount the lower the commentMaxLength
      // (but COMMENT_MAX_LENGTH_GRID is the minimum)
      commentMaxLength = COMMENT_MAX_LENGTH_GRID +
        ((COMMENT_MAX_LENGTH_STAGGER - COMMENT_MAX_LENGTH_GRID) / spanCount)
    }

    if (commentText.length <= commentMaxLength) {
      return commentText
    }

    return commentText.ellipsizeEnd(commentMaxLength)
  }

  private fun calculateCatalogRepliesText(): String {
    if (compact) {
      var status = AppModuleAndroidUtils.getString(
        R.string.card_stats,
        catalogRepliesCount,
        catalogImagesCount
      )
      if (!ChanSettings.neverShowPages.get()) {
        val boardPage = postCellCallback?.getPage(postDescriptor)
        if (boardPage != null && boardPostsSortOrder != PostsFilter.Order.BUMP) {
          status += " Pg " + boardPage.currentPage
        }
      }

      return status
    }

    val replyCount = if (threadMode) {
      repliesFromCount
    } else {
      catalogRepliesCount
    }

    val repliesCountText = AppModuleAndroidUtils.getQuantityString(
      R.plurals.reply,
      replyCount,
      replyCount
    )

    val catalogRepliesTextBuilder = StringBuilder(64)
    catalogRepliesTextBuilder.append(repliesCountText)

    if (!threadMode && catalogImagesCount > 0) {
      val imagesCountText = AppModuleAndroidUtils.getQuantityString(
        R.plurals.image,
        catalogImagesCount,
        catalogImagesCount
      )

      catalogRepliesTextBuilder
        .append(", ")
        .append(imagesCountText)
    }

    if (postCellCallback != null && !neverShowPages) {
      if (boardPostsSortOrder != PostsFilter.Order.BUMP) {
        val boardPage = postCellCallback?.getPage(post.postDescriptor)
        if (boardPage != null) {
          catalogRepliesTextBuilder
            .append(", page ")
            .append(boardPage.currentPage)
        }
      }
    }

    return catalogRepliesTextBuilder.toString()
  }

  private fun calculatePostFileInfoHash(
    postFileInfoMapLazy: RecalculatableLazy<Map<ChanPostImage, SpannableString>>
  ): MurmurHashUtils.Murmur3Hash {
    var hash = MurmurHashUtils.Murmur3Hash.EMPTY
    val postFileInfoMapInput = postFileInfoMapLazy.value()

    if (postFileInfoMapInput.isEmpty()) {
      return hash
    }

    postFileInfoMapInput.values.forEach { postFileInfo ->
      hash = hash.combine(MurmurHashUtils.murmurhash3_x64_128(postFileInfo))
    }

    return hash
  }

  enum class PostViewMode {
    Normal,
    RepliesPopup,
    ExternalPostsPopup,
    PostSelection,
    Search;

    fun canShowLastSeenIndicator(): Boolean {
      if (this == Normal) {
        return true
      }

      return false
    }

    fun canShowThreadStatusCell(): Boolean {
      if (this == Normal) {
        return true
      }

      return false
    }

    fun canShowGoToPostButton(): Boolean {
      if (this == RepliesPopup || this == ExternalPostsPopup || this == Search) {
        return true
      }

      return false
    }

    fun consumePostClicks(): Boolean {
      if (this == ExternalPostsPopup || this == Search) {
        return true
      }

      return false
    }

  }

  data class SearchQuery(val query: String = "", val queryMinValidLength: Int = 0) {
    fun isEmpty(): Boolean = query.isEmpty()
  }

  companion object {
    private const val COMMENT_MAX_LENGTH_LIST = 350
    private const val COMMENT_MAX_LENGTH_GRID = 200
    private const val COMMENT_MAX_LENGTH_STAGGER = 500
    private const val POST_STUB_TITLE_MAX_LENGTH = 100
  }

}
package com.github.k1rakishou.chan.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.withTranslation
import androidx.recyclerview.widget.RecyclerView
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.usecase.PostMapInfoHolder
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.dp

class PostInfoMapItemDecoration(
  private val context: Context
) {
  private var postInfoHolder = PostMapInfoHolder()
  private val showHideAnimator = ValueAnimator.ofFloat(0f, 1f)

  private val myPostsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = context.resources.getColor(R.color.my_post_color)
    alpha = DEFAULT_ALPHA
  }

  private val yousPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = context.resources.getColor(R.color.reply_post_color)
    alpha = DEFAULT_ALPHA
  }

  private val crossThreadRepliesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = context.resources.getColor(R.color.cross_thread_reply_post_color)
    alpha = DEFAULT_ALPHA
  }

  private val testPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.MAGENTA
    alpha = DEFAULT_ALPHA
  }

  private var postsTotal = 0

  fun setItems(
    newPostMapInfoHolder: PostMapInfoHolder,
    newPostsTotal: Int
  ) {
    if (postInfoHolder.isTheSame(newPostMapInfoHolder) && postsTotal == newPostsTotal) {
      return
    }

    postInfoHolder = newPostMapInfoHolder
    postsTotal = newPostsTotal
  }

  fun onDrawOver(
    canvas: Canvas,
    recyclerView: RecyclerView
  ) {
    drawRanges(
      canvas,
      recyclerView,
      postInfoHolder.myPostsPositionRanges,
      myPostsPaint
    )

    drawRanges(
      canvas,
      recyclerView,
      postInfoHolder.replyPositionRanges,
      yousPaint
    )

    drawRanges(
      canvas,
      recyclerView,
      postInfoHolder.crossThreadQuotePositionRanges,
      crossThreadRepliesPaint
    )
  }

  private fun drawRanges(
    canvas: Canvas,
    recyclerView: RecyclerView,
    ranges: List<IntRange>,
    paint: Paint
  ) {
    if (ranges.isEmpty() || postsTotal <= 0) {
      return
    }

    val recyclerTopPadding = recyclerView.paddingTop.toFloat()
    val recyclerBottomPadding = recyclerView.paddingBottom.toFloat()
    val recyclerViewHeight = recyclerView.height
    val recyclerViewWidth = recyclerView.width

    paint.alpha = (DEFAULT_ALPHA.toFloat() * showHideAnimator.animatedValue as Float).toInt()

    val onePostHeightRaw = recyclerView.computeVerticalScrollRange() / (postsTotal + THREAD_STATUS_CELL)
    val recyclerHeight = (recyclerViewHeight.toFloat() - (recyclerTopPadding + recyclerBottomPadding))
    val unit = ((recyclerHeight / recyclerView.computeVerticalScrollRange()) * onePostHeightRaw).coerceAtLeast(MIN_LABEL_HEIGHT)
    val halfUnit = unit / 2f

    canvas.withTranslation(y = recyclerTopPadding + halfUnit) {
      ranges.forEach { positionRange ->
        val startPosition = positionRange.first
        val endPosition = positionRange.last

        val top = startPosition * unit - halfUnit
        val bottom = (endPosition * unit) + halfUnit

        canvas.drawRect(
          recyclerViewWidth.toFloat() - DEFAULT_LABEL_WIDTH,
          top,
          recyclerViewWidth.toFloat(),
          bottom,
          paint
        )
      }
    }
  }

  fun show() {
    showHideAnimator.setFloatValues(showHideAnimator.animatedValue as Float, 1f)
    showHideAnimator.duration = SHOW_DURATION_MS.toLong()
    showHideAnimator.startDelay = 0
    showHideAnimator.start()
  }

  fun hide(duration: Int) {
    showHideAnimator.setFloatValues(showHideAnimator.animatedValue as Float, 0f)
    showHideAnimator.duration = duration.toLong()
    showHideAnimator.start()
  }

  fun cancelAnimation() {
    showHideAnimator.cancel()
  }

  private companion object {
    private val MIN_LABEL_HEIGHT = dp(1f).toFloat()
    private val DEFAULT_LABEL_WIDTH = dp(10f).toFloat()

    private const val SHOW_DURATION_MS = 500
    private const val DEFAULT_ALPHA = 160

    private const val THREAD_STATUS_CELL = 1
  }
}
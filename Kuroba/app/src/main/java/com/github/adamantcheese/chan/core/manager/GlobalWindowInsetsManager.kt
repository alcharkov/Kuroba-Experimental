package com.github.adamantcheese.chan.core.manager

import android.graphics.Rect
import androidx.core.view.WindowInsetsCompat
import com.github.adamantcheese.chan.utils.Logger
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.processors.BehaviorProcessor
import io.reactivex.processors.PublishProcessor

class GlobalWindowInsetsManager {
  private var initialized = false

  var requestInsetsApplyFunc: (() -> Unit)? = null

  var isKeyboardOpened = false
    private set
  var keyboardHeight = 0
    private set

  private val currentInsets = Rect()

  private val callbacksAwaitingInsetsDispatch = ArrayList<Runnable>()
  private val callbacksAwaitingKeyboardHidden = ArrayList<Runnable>()
  private val callbacksAwaitingKeyboardVisible = ArrayList<Runnable>()

  private val insetsSubject = PublishProcessor.create<Unit>()
  private val keyboardStateSubject = BehaviorProcessor.createDefault(false)

  fun updateIsKeyboardOpened(opened: Boolean) {
    if (isKeyboardOpened == opened) {
      return
    }

    isKeyboardOpened = opened
    keyboardStateSubject.onNext(opened)

    if (opened) {
      callbacksAwaitingKeyboardVisible.forEach { it.run() }
      callbacksAwaitingKeyboardVisible.clear()
    } else {
      callbacksAwaitingKeyboardHidden.forEach { it.run() }
      callbacksAwaitingKeyboardHidden.clear()
    }
  }

  fun updateKeyboardHeight(height: Int) {
    keyboardHeight = height.coerceAtLeast(0)
  }

  fun updateInsets(insets: WindowInsetsCompat) {
    currentInsets.set(
      insets.systemWindowInsetLeft,
      insets.systemWindowInsetTop,
      insets.systemWindowInsetRight,
      insets.systemWindowInsetBottom
    )

    initialized = true
    insetsSubject.onNext(Unit)
  }

  fun fireCallbacks() {
    callbacksAwaitingInsetsDispatch.forEach { it.run() }
    callbacksAwaitingInsetsDispatch.clear()
  }

  fun requestInsetsDispatch(func: Runnable) {
    callbacksAwaitingInsetsDispatch += func
    requestInsetsApplyFunc!!.invoke()
  }

  fun runWhenKeyboardIsHidden(func: Runnable) {
    if (!isKeyboardOpened) {
      func.run()
      return
    }

    callbacksAwaitingKeyboardHidden += func
  }

  fun runWhenKeyboardIsVisible(func: Runnable) {
    if (isKeyboardOpened) {
      func.run()
      return
    }

    callbacksAwaitingKeyboardVisible += func
  }

  fun listenForKeyboardChanges(): Flowable<Boolean> {
    return keyboardStateSubject
      .onBackpressureBuffer()
      .distinctUntilChanged()
      .observeOn(AndroidSchedulers.mainThread())
      .doOnError { error -> Logger.e(TAG, "keyboardStateSubject unknown error", error) }
      .hide()
  }

  fun listenForInsetsChanges(): Flowable<Unit> {
    return insetsSubject
      .filter { initialized }
      .onBackpressureBuffer()
      .observeOn(AndroidSchedulers.mainThread())
      .doOnError { error -> Logger.e(TAG, "insetsSubject unknown error", error) }
      .hide()
  }

  fun left() = currentInsets.left
  fun right() = currentInsets.right
  fun top() = currentInsets.top
  fun bottom() = currentInsets.bottom

  companion object {
    private const val TAG = "GlobalWindowInsetsManager"
  }
}
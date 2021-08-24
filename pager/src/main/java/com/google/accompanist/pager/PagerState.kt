/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.google.accompanist.pager

import androidx.annotation.IntRange
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

/**
 * Creates a [PagerState] that is remembered across compositions.
 *
 * Changes to the provided values for [initialPage] will **not** result in the state being
 * recreated or changed in any way if it has already
 * been created. Changes to [pageCount] will result in the [PagerState] being updated.
 *
 * @param pageCount the value for [PagerState.pageCount]
 * @param initialPage the initial value for [PagerState.currentPage]
 * @param infiniteLoop Whether to support infinite looping effect.
 */
@ExperimentalPagerApi
@Composable
fun rememberPagerState(
    @IntRange(from = 0) pageCount: Int,
    @IntRange(from = 0) initialPage: Int = 0,
    infiniteLoop: Boolean = false,
): PagerState = rememberSaveable(saver = PagerState.Saver) {
    PagerState(
        pageCount = pageCount,
        currentPage = initialPage,
        infiniteLoop = infiniteLoop,
    )
}.apply {
    this.pageCount = pageCount
}

/**
 * A state object that can be hoisted to control and observe scrolling for [HorizontalPager].
 *
 * In most cases, this will be created via [rememberPagerState].
 *
 * The `offscreenLimit` param defines the number of pages that
 * should be retained on either side of the current page. Pages beyond this limit will be
 * recreated as needed. This value defaults to `1`, but can be increased to enable pre-loading
 * of more content.
 *
 * @param pageCount the initial value for [PagerState.pageCount]
 * @param currentPage the initial value for [PagerState.currentPage]
 * @param currentPageOffset the initial value for [PagerState.currentPageOffset]
 * @param offscreenLimit the number of pages that should be retained on either side of the
 * current page. This value is required to be `1` or greater.
 * @param infiniteLoop Whether to support infinite looping effect.
 */
@ExperimentalPagerApi
@Stable
class PagerState(
    @IntRange(from = 0) pageCount: Int,
    @IntRange(from = 0) currentPage: Int = 0,
    private val infiniteLoop: Boolean = false,
) : ScrollableState {
    internal val lazyListState = LazyListState(firstVisibleItemIndex = currentPage)

    private var _pageCount by mutableStateOf(pageCount)
    private var _currentPage by mutableStateOf(currentPage)

    internal var viewportHeight by mutableStateOf(0)
    internal var viewportWidth by mutableStateOf(0)
    internal var layoutStartSpacing: Int by mutableStateOf(0)
    internal var layoutEndSpacing: Int by mutableStateOf(0)

    internal var snapOffsetForPage: LazyListLayoutInfo.(index: Int) -> Int by mutableStateOf(
        SnappingFlingBehaviorDefaults.snapOffset
    )

    private val currentLayoutPageInfo: LazyListItemInfo? by derivedStateOf {
        val layoutInfo = lazyListState.layoutInfo
        layoutInfo.visibleItemsInfo.asSequence()
            .filter {
                val snapOffset = layoutInfo.snapOffsetForPage(it.index)
                it.offset <= snapOffset && it.offset + it.size > snapOffset
            }
            .firstOrNull()
    }

    private val currentLayoutPageOffset: Float by derivedStateOf {
        currentLayoutPageInfo?.let { current ->
            val offset = current.offset - lazyListState.layoutInfo.snapOffsetForPage(current.index)
            // We coerce we itemSpacing can make the offset > 1f. We don't want to count
            // spacing in the offset so cap it to 1f
            (-offset / current.size.toFloat()).coerceIn(0f, 1f)
        } ?: 0f
    }

    /**
     * When set to true, `page` of [Pager] content can be different in [infiniteLoop] mode.
     */
    internal var testing = false

    /**
     * [InteractionSource] that will be used to dispatch drag events when this
     * list is being dragged. If you want to know whether the fling (or animated scroll) is in
     * progress, use [isScrollInProgress].
     */
    val interactionSource: InteractionSource
        get() = lazyListState.interactionSource

    init {
        require(pageCount >= 0) { "pageCount must be >= 0" }
        requireCurrentPage(currentPage, "currentPage")
    }

    /**
     * The number of pages to display.
     */
    @get:IntRange(from = 0)
    var pageCount: Int
        get() = _pageCount
        set(@IntRange(from = 0) value) {
            require(value >= 0) { "pageCount must be >= 0" }
            if (value != _pageCount) {
                _pageCount = value
                if (DebugLog) {
                    Napier.d(message = "Page count changed: $value")
                }
                currentPage = currentPage.coerceIn(0, pageCount)
            }
        }

    /**
     * The index of the currently selected page. This may not be the page which is
     * currently displayed on screen.
     *
     * To update the scroll position, use [scrollToPage] or [animateScrollToPage].
     */
    @get:IntRange(from = 0)
    var currentPage: Int
        get() = _currentPage
        private set(value) {
            val moddedValue = value.floorMod(pageCount)
            if (moddedValue != _currentPage) {
                _currentPage = moddedValue
                if (DebugLog) {
                    Napier.d(message = "Current page changed: $_currentPage")
                }
            }
        }

    /**
     * The current offset from the start of [currentPage], as a fraction of the page width.
     *
     * To update the scroll position, use [scrollToPage] or [animateScrollToPage].
     */
    val currentPageOffset: Float by derivedStateOf {
        currentLayoutPageInfo?.let { it.index + currentLayoutPageOffset - currentPage } ?: 0f
    }

    /**
     * The target page for any on-going animations.
     */
    private var _animationTargetPage: Int? by mutableStateOf(null)

    /**
     * The target page for any on-going animations or scrolls by the user.
     * Returns the current page if a scroll or animation is not currently in progress.
     */
    val targetPage: Int
        get() = _animationTargetPage ?: when {
            // If a scroll isn't in progress, return the current page
            !isScrollInProgress -> currentPage
            // If the offset is 0f (or very close), return the current page
            currentPageOffset < 0.001f -> currentPage
            // If we're offset towards the start, guess the previous page
            currentPageOffset < 0 -> (currentPage - 1).coerceAtLeast(0)
            // If we're offset towards the end, guess the next page
            else -> (currentPage + 1).coerceAtMost(pageCount)
        }

    /**
     * Animate (smooth scroll) to the given page to the middle of the viewport.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to animate to. Must be between 0 and [pageCount] (inclusive).
     */
    suspend fun animateScrollToPage(@IntRange(from = 0) page: Int) {
        requireCurrentPage(page, "page")
        try {
            lazyListState.animateScrollToItem(index = page)
        } finally {
            onScrollFinished()
        }
    }

    /**
     * Instantly brings the item at [page] to the middle of the viewport.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to snap to. Must be between 0 and [pageCount] (inclusive).
     */
    suspend fun scrollToPage(@IntRange(from = 0) page: Int) {
        requireCurrentPage(page, "page")
        try {
            lazyListState.scrollToItem(index = page)
        } finally {
            onScrollFinished()
        }
    }

    internal fun onScrollFinished() {
        // Then update the current page to our layout page
        currentPage = currentLayoutPageInfo?.index ?: 0
        // Clear the target page
        _animationTargetPage = null
    }

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit
    ) = lazyListState.scroll(scrollPriority, block)

    override fun dispatchRawDelta(delta: Float): Float {
        return lazyListState.dispatchRawDelta(delta)
    }

    override val isScrollInProgress: Boolean
        get() = lazyListState.isScrollInProgress

    override fun toString(): String = "PagerState(" +
        "pageCount=$pageCount, " +
        "currentPage=$currentPage, " +
        "currentPageOffset=$currentPageOffset" +
        ")"

    private fun requireCurrentPage(value: Int, name: String) {
        if (pageCount == 0) {
            require(value == 0) { "$name must be 0 when pageCount is 0" }
        } else {
            require(value in 0 until pageCount) {
                "$name[$value] must be >= firstPageIndex[0] and < lastPageIndex[pageCount]"
            }
        }
    }

    private fun requireCurrentPageOffset(value: Float, name: String) {
        if (pageCount == 0) {
            require(value == 0f) { "$name must be 0f when pageCount is 0" }
        } else {
            require(value in 0f..1f) { "$name must be >= 0 and <= 1" }
        }
    }

    /**
     * Considering infinite loop, returns page between 0 until [pageCount].
     */
    internal fun pageOf(rawPage: Int): Int {
        if (testing) {
            return rawPage
        }
        return rawPage.floorMod(pageCount)
    }

    companion object {
        /**
         * The default [Saver] implementation for [PagerState].
         */
        val Saver: Saver<PagerState, *> = listSaver(
            save = {
                listOf<Any>(
                    it.pageCount,
                    it.currentPage,
                )
            },
            restore = {
                PagerState(
                    pageCount = it[0] as Int,
                    currentPage = it[1] as Int,
                )
            }
        )

        init {
            if (DebugLog) {
                Napier.base(DebugAntilog(defaultTag = "Pager"))
            }
        }

        /**
         * Calculates the floor modulus in the range of -abs([other]) < r < +abs([other]).
         */
        private fun Int.floorMod(other: Int): Int {
            return when (other) {
                0 -> this
                else -> this - this.floorDiv(other) * other
            }
        }
    }
}

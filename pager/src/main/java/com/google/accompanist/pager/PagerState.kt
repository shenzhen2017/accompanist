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

import androidx.annotation.FloatRange
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
 * been created.
 *
 * @param initialPage the initial value for [PagerState.currentPage]
 */
@ExperimentalPagerApi
@Composable
fun rememberPagerState(
    @IntRange(from = 0) initialPage: Int = 0,
): PagerState = rememberSaveable(saver = PagerState.Saver) {
    PagerState(
        currentPage = initialPage,
    )
}

/**
 * A state object that can be hoisted to control and observe scrolling for [HorizontalPager].
 *
 * In most cases, this will be created via [rememberPagerState].
 *
 * @param currentPage the initial value for [PagerState.currentPage]
 */
@ExperimentalPagerApi
@Stable
class PagerState(
    @IntRange(from = 0) currentPage: Int = 0,
) : ScrollableState {
    // Should this be public?
    internal val lazyListState = LazyListState(firstVisibleItemIndex = currentPage)

    private var _currentPage by mutableStateOf(currentPage)

    // TODO: Think about exposing this as public API. Same for SnappingFlingBehavior
    private val snapOffsetForPage: LazyListLayoutInfo.(page: Int) -> Int = { viewportStartOffset }

    private val currentLayoutPageInfo: LazyListItemInfo? by derivedStateOf {
        val layoutInfo = lazyListState.layoutInfo
        layoutInfo.visibleItemsInfo.asSequence()
            .filter {
                val snapOffset = snapOffsetForPage(layoutInfo, it.index)
                it.offset <= snapOffset && it.offset + it.size > snapOffset
            }
            .lastOrNull()
    }

    private val currentLayoutPageOffset: Float by derivedStateOf {
        currentLayoutPageInfo?.let { current ->
            val start = lazyListState.layoutInfo.viewportStartOffset
            // Since the first item might be wider to compensate for the alignment, we need
            // to compute the actual size and offset
            val size = if (current.index == 0) current.size - start else current.size
            val offset = if (current.index == 0) current.offset else current.offset - start
            // We coerce we itemSpacing can make the offset > 1f. We don't want to count
            // spacing in the offset so cap it to 1f
            (-offset / size.toFloat()).coerceIn(0f, 1f)
        } ?: 0f
    }

    /**
     * [InteractionSource] that will be used to dispatch drag events when this
     * list is being dragged. If you want to know whether the fling (or animated scroll) is in
     * progress, use [isScrollInProgress].
     */
    val interactionSource: InteractionSource
        get() = lazyListState.interactionSource

    /**
     * The number of pages to display.
     */
    @get:IntRange(from = 0)
    val pageCount: Int by derivedStateOf {
        lazyListState.layoutInfo.totalItemsCount
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
            if (value != _currentPage) {
                _currentPage = value
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
        currentLayoutPageInfo?.let { it.index + currentLayoutPageOffset - _currentPage } ?: 0f
    }

    /**
     * The target page for any on-going animations.
     */
    private var animationTargetPage: Int? by mutableStateOf(null)

    internal var flingAnimationTarget: (() -> Int?)? by mutableStateOf(null)

    /**
     * The target page for any on-going animations or scrolls by the user.
     * Returns the current page if a scroll or animation is not currently in progress.
     */
    val targetPage: Int
        get() = animationTargetPage
            ?: flingAnimationTarget?.invoke()
            ?: when {
                // If a scroll isn't in progress, return the current page
                !isScrollInProgress -> currentPage
                // If we're offset towards the start, guess the previous page
                currentPageOffset < -0.5f -> (currentPage - 1).coerceAtLeast(0)
                // If we're offset towards the end, guess the next page
                currentPageOffset > 0.5f -> (currentPage + 1).coerceAtMost(pageCount - 1)
                // Else we guess the current page
                else -> currentPage
            }

    /**
     * Animate (smooth scroll) to the given page to the middle of the viewport.
     *
     * Cancels the currently running scroll, if any, and suspends until the cancellation is
     * complete.
     *
     * @param page the page to animate to. Must be between 0 and [pageCount] (inclusive).
     */
    suspend fun animateScrollToPage(
        @IntRange(from = 0) page: Int,
    ) {
        requireCurrentPage(page, "page")

        val firstVisibleItemIndex = lazyListState.firstVisibleItemIndex
        val firstVisibleItemScrollOffset = lazyListState.firstVisibleItemScrollOffset

        animationTargetPage = page

        if (page > 0) {
            // If we have a 'start' spacing, we need to actually scroll to the previous item.
            // We can then look at the laid out page sizes

            var prev = lazyListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == page - 1 }

            var hadToSnap = false
            if (prev == null) {
                lazyListState.scrollToItem(index = page - 1)
                hadToSnap = true
            }

            prev = lazyListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == page - 1 }
            val current = lazyListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == page }

            // We generate the scroll offset using the current page offset (if available),
            // or the previous item size. Using the current page offset is better as it contains
            // any item spacing.
            val targetScrollOffset = if (current != null && prev != null) {
                // If we have both, we can detect the gap between the items
                current.offset - prev.offset
            } else {
                prev!!.size
            }

            if (hadToSnap) {
                // Now snap back to the original scroll position
                lazyListState.scrollToItem(firstVisibleItemIndex, firstVisibleItemScrollOffset)
            }

            // And animate to our chosen page
            lazyListState.animateScrollToItem(
                index = page - 1,
                scrollOffset = targetScrollOffset,
            )
        } else {
            lazyListState.animateScrollToItem(index = page)
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
    suspend fun scrollToPage(
        @IntRange(from = 0) page: Int,
        @FloatRange(from = 0.0, to = 1.0) pageOffset: Float = 0f,
    ) {
        requireCurrentPage(page, "page")

        animationTargetPage = page

        // First scroll to the given page. It will now be laid out at offset 0
        lazyListState.scrollToItem(index = page)

        // If we have a start spacing, we need to offset (scroll) by that too
        if (pageOffset > 0.0001f) {
            scroll {
                currentLayoutPageInfo?.let {
                    scrollBy(it.size * pageOffset)
                }
            }
        }
    }

    internal fun onScrollFinished() {
        // Then update the current page to our layout page
        currentPage = currentLayoutPageInfo?.index ?: 0
        // Clear the animation target page
        animationTargetPage = null
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

    companion object {
        /**
         * The default [Saver] implementation for [PagerState].
         */
        val Saver: Saver<PagerState, *> = listSaver(
            save = {
                listOf<Any>(
                    it.currentPage,
                )
            },
            restore = {
                PagerState(
                    currentPage = it[0] as Int,
                )
            }
        )

        init {
            if (DebugLog) {
                Napier.base(DebugAntilog(defaultTag = "Pager"))
            }
        }
    }
}

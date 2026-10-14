package tv.own.owntv.core.home

/** Shared carousel rules so touch and ten-foot layouts cannot drift apart. */
fun shouldAutoAdvanceTrending(itemCount: Int, userInteracting: Boolean): Boolean =
    itemCount > 1 && !userInteracting

/** Wraps D-pad, swipe and timed movement without ever producing an invalid index. */
fun wrappedTrendingIndex(currentIndex: Int, delta: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return Math.floorMod(currentIndex + delta, itemCount)
}


package com.hortus1556.exilum.ui

/**
 * Pops one entry, unless that would leave the stack empty.
 *
 * NavDisplay throws when its back stack runs out, and each of these screens
 * has more than one way to pop: the system gesture, a back arrow, a child
 * screen's own callback. Guarding only the gesture is not enough - spamming
 * back in a playlist emptied the stack through the others and took the app
 * down. Reaching the root of a nested host is not an exit, so there is
 * nothing to do there; whatever hosts it decides what leaving means.
 */
fun <T> MutableList<T>.popOrKeepRoot() {
    if (size > 1) removeAt(size - 1)
}

/**
 * Puts [key] on top, unless it is the page already there.
 *
 * The page a row was tapped on is still under the finger while the one it
 * opens slides in over it. A second tap on the row in that moment opened the
 * page again, on top of itself, and the first Back from it went to the same
 * page.
 */
fun <T> MutableList<T>.pushOnce(key: T) {
    if (lastOrNull() != key) add(key)
}

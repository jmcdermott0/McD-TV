package com.mcd.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Goes up whenever a profile's Library lists change: on this TV (Detail, the phone's watchlist command) or from
 * another TV (ProfileSync). Home re-reads My List on it, so the row is live without a restart. Thread safe.
 */
object LibraryEvents {
    private val state = MutableStateFlow(0)
    val version: StateFlow<Int> = state
    fun changed() = state.update { it + 1 }
}

/** Home's My List row. Pure; tests in WatchListRowTest. */
object WatchListRow {
    const val LABEL = "My List"
    const val MAX = 20

    /** The row's titles: the list as stored (newest first), without "Not for me" titles. Empty means no row. */
    fun <T> items(list: List<T>, key: (T) -> String, hidden: Set<String>, max: Int = MAX): List<T> =
        list.asSequence().filter { key(it) !in hidden }.distinctBy(key).take(max).toList()
}

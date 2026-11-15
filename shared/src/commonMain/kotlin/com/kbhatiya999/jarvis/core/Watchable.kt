package com.kbhatiya999.jarvis.core

/** A value the UI can observe (Compose on Android, SwiftUI on iOS). Main thread only. */
class Watchable<T>(initial: T) {
    var value: T = initial
        internal set(v) {
            field = v
            watchers.toList().forEach { it(v) }
        }
    private val watchers = mutableListOf<(T) -> Unit>()

    /** Calls [block] now and on every change until the subscription is cancelled. */
    fun watch(block: (T) -> Unit): Subscription {
        watchers.add(block)
        block(value)
        return Subscription { watchers.remove(block) }
    }
}

class Subscription(private val onCancel: () -> Unit) {
    fun cancel() = onCancel()
}

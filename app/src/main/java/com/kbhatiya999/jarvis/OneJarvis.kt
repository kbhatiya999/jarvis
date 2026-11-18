package com.kbhatiya999.jarvis

import com.kbhatiya999.jarvis.core.LiveSession

/**
 * Only one Jarvis conversation at a time. Two parts of the app can hold one: active mode (wake phrase, incoming
 * messages, reminders, app-open check-ins) and the chat screen (its mic button, the Talk tile). Without this they
 * didn't check each other, and two Jarvises could talk over each other.
 *
 * Rules: the chat screen, when it starts, ends active mode's conversation. While the chat holds the conversation,
 * active mode doesn't start its own: incoming messages and due reminders go into the chat's conversation as text,
 * and app-open check-ins are skipped. Main thread.
 */
object OneJarvis {
    /** The chat screen's conversation while it's connected; set by JarvisViewModel. */
    var chat: LiveSession? = null
}

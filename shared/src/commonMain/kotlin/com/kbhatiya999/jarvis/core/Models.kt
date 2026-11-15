package com.kbhatiya999.jarvis.core

data class Message(
    val role: String,
    val text: String,
    val time: Long,
    val id: Long = 0,
) {
    val fromUser get() = role == USER
    val isRelay get() = role == SENT || role == RECEIVED

    companion object {
        const val USER = "user"
        const val JARVIS = "jarvis"
        /** A message this Jarvis sent to someone else's Jarvis. */
        const val SENT = "sent"
        /** A message that arrived from someone else's Jarvis. */
        const val RECEIVED = "received"
    }
}

data class Contact(val name: String, val address: String)

/** A message from a Jarvis that isn't in your contacts yet. Accepting adds it and delivers the message. */
data class ContactRequest(val name: String, val address: String, val text: String, val time: Long)

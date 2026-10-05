package com.example.blap.venue

import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class DiscussionThread(
    val id: String = "",
    val title: String = "",
    val authorId: String = "",
    @ServerTimestamp
    val createdAt: Date? = null,
    val replyCount: Long = 0,
    val upvoteCount: Long = 0,
)

data class Reply(
    val id: String = "",
    val text: String = "",
    val authorId: String = "",
    @ServerTimestamp
    val createdAt: Date? = null,
)
package com.example.blap.ui.screens.events

import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

internal fun formatEventTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))

internal val EVENT_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM")

internal val EVENT_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

package com.mobile.codeeditor

import android.net.Uri

/** An in-memory representation of a file open in an editor tab. */
data class OpenFile(
    var name: String,
    var uri: Uri?,
    var content: String,
    var modified: Boolean = false
)

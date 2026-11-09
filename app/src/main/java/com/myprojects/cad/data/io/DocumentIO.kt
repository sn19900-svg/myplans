package com.myprojects.cad.data.io

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Reads/writes user-chosen files through the Storage Access Framework (no storage permission needed). */
@Singleton
class DocumentIO @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun write(uri: Uri, block: (OutputStream) -> Unit) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open the output file")
        stream.use(block)
    }

    fun read(uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the file")
        return stream.use { it.readBytes() }
    }
}

package com.github.barteksc.pdfviewer.source

import com.sovworks.projecteds.domain.common.util.wrapperDescription
import com.sovworks.projecteds.domain.filemanager.RandomAccessReader
import kotlinx.coroutines.flow.MutableSharedFlow

class DocumentSourceRandomAccessReaderWrapper(
    private val base: RandomAccessReader,
    private val documentSourceReadFlow: MutableSharedFlow<Unit>
) : RandomAccessReader by base {

    override fun toString() = wrapperDescription(base)

    override suspend fun read(
        buffer: ByteArray,
        bufferOffset: Int,
        count: Int,
        startPosition: Long
    ): Int? =
        base.read(buffer, bufferOffset, count, startPosition).also {
            documentSourceReadFlow.emit(Unit)
        }

    companion object {

        fun RandomAccessReader.toDocumentSourceRandomAccessReaderWrapper(
            documentSourceReadFlow: MutableSharedFlow<Unit>
        ): RandomAccessReader =
            DocumentSourceRandomAccessReaderWrapper(this, documentSourceReadFlow)
    }
}
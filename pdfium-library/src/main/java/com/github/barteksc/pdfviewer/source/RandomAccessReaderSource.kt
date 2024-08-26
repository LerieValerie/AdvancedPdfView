package com.github.barteksc.pdfviewer.source

import android.content.Context
import com.github.barteksc.pdfviewer.source.DocumentSourceRandomAccessReaderWrapper.Companion.toDocumentSourceRandomAccessReaderWrapper
import com.sovworks.projecteds.data.common.threadcoroutinecontext.LaunchBlockingProviderImpl.Companion.toBlockingRandomAccessReader
import com.sovworks.projecteds.data.common.threadcoroutinecontext.launchBlocking
import com.sovworks.projecteds.data.filemanager.local.common.JavaIOException
import com.sovworks.projecteds.domain.common.Failure
import com.sovworks.projecteds.domain.common.coroutinedispatcher.NoRedispatchMarker
import com.sovworks.projecteds.domain.common.util.logging.Log.e
import com.sovworks.projecteds.domain.filemanager.MediaRandomAccessReaderDataSource
import kotlinx.coroutines.flow.MutableSharedFlow
import org.benjinus.pdfium.PdfiumSDK
import java.io.IOException

class RandomAccessReaderSource(
    private val mediaRandomAccessReaderDataSource: MediaRandomAccessReaderDataSource,
    private val documentSourceReadFlow: MutableSharedFlow<Unit>
) : DocumentSource {
    @Throws(IOException::class)
    override fun createDocument(
        context: Context,
        password: String?
    ): PdfiumSDK {
        return mediaRandomAccessReaderDataSource.run {
            convertException {
                val blockingRandomAccessReader = serviceCoroutineScope.launchBlocking {
                    openRandomAccessReaderAction(path)
                        .toDocumentSourceRandomAccessReaderWrapper(documentSourceReadFlow)
                        .toBlockingRandomAccessReader(NoRedispatchMarker())
                }

                PdfiumSDK(
                    blockingRandomAccessReader = blockingRandomAccessReader,
                    fileSize = blockingRandomAccessReader.length,
                    pdfPassword = password
                )
            }
        }
    }

    private inline fun <T> convertException(block: () -> T): T {
        return try {
            block()
        } catch (e: Failure) {
            e(e)
            throw JavaIOException(e)
        } catch (e: InterruptedException) {
            throw JavaIOException(e)
        }
    }
}
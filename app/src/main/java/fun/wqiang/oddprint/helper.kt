package `fun`.wqiang.oddprint

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

suspend fun openPdfRenderer(context: Context, uri: Uri): PdfRenderer? {
    return withContext(Dispatchers.IO) {
        val contentResolver: ContentResolver = context.contentResolver
        try {
            val parcelFileDescriptor: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
            parcelFileDescriptor?.let {
                PdfRenderer(it)
            }
        } catch (e: Exception) {
            // 处理异常，例如记录日志或显示错误消息
            e.printStackTrace()
            null
        }
    }
}

suspend fun calculatePdfPageCount(context: Context, pdfFile: Uri): Int {
    var pageCount = 0
    val pdfRenderer = openPdfRenderer(context, pdfFile)
    if (pdfRenderer != null) {
        // 使用 pdfRenderer 访问 PDF 文件内容
        pageCount = pdfRenderer.pageCount
        pdfRenderer.close()
    } else {
        // 处理文件无法打开的情况
    }
    return pageCount
}

fun stringToPageNumbers(input: String): List<Int> {
    val pageNumbers = mutableSetOf<Int>() // 使用 Set 避免重复页码

    input.split(",").forEach { range ->
        if (range.contains("-")) {
            val (start, end) = range.split("-").map { it.trim().toIntOrNull() }
            if (start != null && end != null && start <= end) {
                pageNumbers.addAll(start..end)
            }
        } else {
            val pageNumber = range.trim().toIntOrNull()
            if (pageNumber != null) {
                pageNumbers.add(pageNumber)
            }
        }
    }

    return pageNumbers.map { it - 1 }.toList().sorted() // 返回排序后的页码列表
}

/**
 * 计算页面在总页面上的位置和变换矩阵。
 *
 * @param totalPages 总页数。
 * @param pageIndex 当前页面的索引（从 0 开始）。
 * @param pageWidth 页面的宽度。
 * @param pageHeight 页面的高度。
 * @param margin 页面之间的边距。
 * @return 一个 Pair，包含页面在总页面上的位置 (Rect) 和对应的变换矩阵 (Matrix)。
 */
fun calculatePageLayout(
    totalPages: Int,
    pageIndex: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
    pageWidth: Int,
    pageHeight: Int,
    margin: Int
): Pair<Rect?, Matrix?> {
    //一个页面对应一个页面
    val matrix = Matrix()
    val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
    val pageRatio = pageWidth.toFloat() / pageHeight
    var pWidth = pageWidth
    var pHeight = pageHeight
    if (bitmapRatio > 1 && pageRatio < 1 || bitmapRatio < 1 && pageRatio > 1) {
        matrix.postRotate(-90f )
        matrix.postTranslate(0f, pageWidth.toFloat())
        pWidth = pageHeight
        pHeight = pageWidth
    }
    matrix.postScale(bitmapWidth.toFloat()/ pWidth, bitmapHeight.toFloat()/pHeight)
    if (totalPages == 1) {
        return Pair(null, matrix)
    }

    // 计算行数和列数
    val numRows = ceil(sqrt(totalPages.toDouble())).toInt()
    val numCols = ceil(totalPages.toDouble() / numRows).toInt()


    // 计算总宽度和总高度
    val totalWidthPortrait = numCols * bitmapWidth + (numCols + 1) * margin
    val totalHeightPortrait = numRows * bitmapHeight + (numRows + 1) * margin
    val ratioPortrait = max(totalWidthPortrait.toFloat()/bitmapWidth, totalHeightPortrait.toFloat()/bitmapHeight)

    val totalWidthLandscape = numCols * bitmapHeight + (numCols + 1) * margin
    val totalHeightLandscape = numRows * bitmapWidth + (numRows + 1) * margin
    val ratioLandscape = max(totalWidthLandscape.toFloat()/bitmapWidth, totalHeightLandscape.toFloat()/bitmapHeight)

    val mode = if (ratioPortrait < ratioLandscape) 0 else 1
    val realRatio = if (mode == 0) ratioPortrait else ratioLandscape
    val realWidth = if (mode == 0)  ((bitmapWidth - (numCols+ 1) * margin) / realRatio).toInt() else ((bitmapHeight - (numCols + 1) * margin) / realRatio).toInt()
    val realHeight = if (mode == 0)  ((bitmapHeight- (numRows+ 1) * margin) / realRatio).toInt() else ((bitmapWidth - (numRows + 1) * margin) / realRatio).toInt()

    // 计算页面位置
    val row = pageIndex / numCols
    val col = pageIndex % numCols
    val extraMarginX = (bitmapWidth - (numCols * (realWidth + margin) + margin )) / 2
    val extraMarginY = (bitmapHeight - (numRows * (realHeight + margin) + margin)) / 2
    val left = margin + col * (realWidth + margin) + extraMarginX
    val top = margin + row * (realHeight + margin) + extraMarginY
    val pageRect = Rect(left , top , left + realWidth, top + realHeight)

    // 创建变换矩阵
    if (mode == 1) {
        matrix.postRotate(-90f )
        matrix.postTranslate(0f, bitmapWidth.toFloat())
    }
    matrix.postScale(1/realRatio, 1/realRatio)
    matrix.postTranslate(left.toFloat(), top.toFloat())

    return Pair(pageRect, matrix)
}

fun render(
    pdfRenderer: PdfRenderer,
    pageNumber: Int,
    singlePagePrintCount: Int,
    pageRangeSelection: String?,
    pageRangeCustom: String?,
    renderMode: Int
): Pair<Bitmap, Size> {
    val totalPages = ceil(pdfRenderer.pageCount.toFloat() / singlePagePrintCount)
    val pages = range2list(totalPages.toInt(), pageRangeSelection, pageRangeCustom)
    val thisPageList = ((pages[pageNumber] * singlePagePrintCount) ..< ((pages[pageNumber]+1)*singlePagePrintCount)).toList().filter { it < pdfRenderer.pageCount }
    var bitmap: Bitmap? = null
    val dpi = if (renderMode == PdfRenderer.Page.RENDER_MODE_FOR_PRINT ) 300 else 72
    Log.d("TEST", "thisPageList: $thisPageList dpi: $dpi")
    var width=0
    var height=0
    for ( index in thisPageList.indices) {
        val page = pdfRenderer.openPage(thisPageList[index])
        if (bitmap == null) {
            width = page.width * dpi / 72
            height = page.height * dpi / 72
            val config = Bitmap.Config.ARGB_8888
            bitmap = Bitmap.createBitmap(width, height, config)
        }
        val (pageRect, pageMatrix) = calculatePageLayout(singlePagePrintCount, index, width, height, page.width, page.height,10 * dpi / 72)
        Log.d("TEST", "pageRect: $pageRect pageMatrix: $pageMatrix pageWidth: ${page.width} pageHeight: ${page.height}")
        page.render(bitmap, pageRect, pageMatrix, renderMode)
        page.close()
    }
    return Pair(bitmap!!, Size(width, height))
}

fun getPdfFileName(context: Context, uri: Uri): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor = contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (displayNameIndex != -1) {
                fileName = it.getString(displayNameIndex)
            }
        }
    }
    return fileName
}

fun range2list(total: Int, pageRangeSelection: String?, pageRangeCustom: String?): List<Int> {
    if (pageRangeSelection == "ALL") return (0..<total).toList()
    if (pageRangeSelection == "ODD") return (0..<total step 2).toList()
    if (pageRangeSelection == "EVEN") return (1..<total step 2).toList()
    return stringToPageNumbers(pageRangeCustom!!)
}

suspend fun printPdf(context : Context, uri: Uri?, singlePagePrintCount: Int, pageRangeSelection: String?, pageRangeCustom: String?) {
    val pdfRenderer = openPdfRenderer(context, uri!!)
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager

    val printAdapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: android.os.CancellationSignal?,
            callback: LayoutResultCallback,
            var5: Bundle
        ) {
            if (pdfRenderer == null) return
            val total = range2list(ceil(pdfRenderer.pageCount.toFloat() / singlePagePrintCount).toInt() , pageRangeSelection, pageRangeCustom).size
            val builder = PrintDocumentInfo.Builder("sample_pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(total)

            val info = builder.build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback
        ) {
            if (pdfRenderer == null) return
            try {
                val document = PdfDocument()
                val selectedPages = range2list(ceil(pdfRenderer.pageCount.toFloat() / singlePagePrintCount).toInt() , pageRangeSelection, pageRangeCustom)
                for (index in selectedPages.indices) {
                    val (bitmap,pageSize) = render(pdfRenderer, index, singlePagePrintCount, pageRangeSelection, pageRangeCustom,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        pageSize.width, pageSize.height, index
                    ).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, Rect(0,0,bitmap.width, bitmap.height),
                        Rect(0,0,page.canvas.width, page.canvas.height),  null)
                    document.finishPage(page)
                    bitmap.recycle()
                }
                document.writeTo(FileOutputStream(destination.fileDescriptor))
                callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.toString())
            }
        }
        override fun onFinish() {
            super.onFinish()
            pdfRenderer?.close()
        }
    }

    printManager.print("Pdf Print", printAdapter, PrintAttributes.Builder().build())
}
package `fun`.wqiang.oddprint

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `fun`.wqiang.oddprint.ui.theme.奇偶打印Theme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import android.provider.OpenableColumns
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

enum class PageRangeSelection(val displayName: String) {
    ALL("全部"),
    ODD("奇数页"),
    EVEN("偶数页"),
    CUSTOM("自定义")
}

class MainActivity : ComponentActivity() {
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("TEST", "Activity created")
        var pdfUri: Uri? = null
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND ) {
            pdfUri = intent.data
            if (pdfUri == null) {
                @Suppress("DEPRECATION")
                pdfUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Log.d("TEST", "git uri from intent: $pdfUri")
        }
        setContent {
            奇偶打印Theme {
                Scaffold(modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()) {
                    _ ->
                    Greeting(pdfUri)
                }
            }
        }
    }
}

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

    return pageNumbers.map({it - 1}).toList().sorted() // 返回排序后的页码列表
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
    pageWidth: Int,
    pageHeight: Int,
    margin: Int
): Pair<Rect?, Matrix?> {
    if (totalPages == 1) {
        return Pair(null, null)
    }

    // 计算行数和列数
    val numRows = ceil(sqrt(totalPages.toDouble())).toInt()
    val numCols = ceil(totalPages.toDouble() / numRows).toInt()


    val ratio = pageWidth.toFloat() / pageHeight.toFloat()
    // 计算总宽度和总高度
    val totalWidthPortrait = numCols * pageWidth + (numCols + 1) * margin
    val totalHeightPortrait = numRows * pageHeight + (numRows + 1) * margin
    val ratioPortrait = max(totalWidthPortrait.toFloat()/pageWidth, totalHeightPortrait.toFloat()/pageHeight)

    val totalWidthLandscape = numCols * pageHeight + (numCols + 1) * margin
    val totalHeightLandscape = numRows * pageWidth + (numRows + 1) * margin
    val ratioLandscape = max(totalWidthLandscape.toFloat()/pageWidth, totalHeightLandscape.toFloat()/pageHeight)

    val mode = if (ratioPortrait < ratioLandscape) 0 else 1
    val realRatio = if (mode == 0) ratioPortrait else ratioLandscape
    val realWidth = if (mode == 0)  ((pageWidth - (numCols+ 1) * margin) / realRatio).toInt() else ((pageHeight - (numCols + 1) * margin) / realRatio).toInt()
    val realHeight = if (mode == 0)  ((pageHeight- (numRows+ 1) * margin) / realRatio).toInt() else ((pageWidth - (numRows + 1) * margin) / realRatio).toInt()

    // 计算页面位置
    val row = pageIndex / numCols
    val col = pageIndex % numCols
    val extraMarginX = (pageWidth - (numCols * (realWidth + margin) + margin )) / 2
    val extraMarginY = (pageHeight - (numRows * (realHeight + margin) + margin)) / 2
    val left = margin + col * (realWidth + margin) + extraMarginX
    val top = margin + row * (realHeight + margin) + extraMarginY
    val pageRect = Rect(left , top , left + realWidth, top + realHeight)

    // 创建变换矩阵
    val matrix = Matrix()
    if (mode == 1) {
        matrix.postRotate(-90f )
        matrix.postTranslate(0f, pageWidth.toFloat())
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
): Bitmap {
    val totalPages = ceil(pdfRenderer.pageCount.toFloat() / singlePagePrintCount)
    val pages = range2list(totalPages.toInt(), pageRangeSelection, pageRangeCustom);
    val thisPageList = ((pages[pageNumber] * singlePagePrintCount) ..< ((pages[pageNumber]+1)*singlePagePrintCount)).toList().filter { it < pdfRenderer.pageCount }
    var bitmap: Bitmap? = null
    Log.d("TEST", "thisPageList: $thisPageList")
    for ( index in thisPageList.indices) {
        val page = pdfRenderer.openPage(thisPageList[index])
        if (bitmap == null) {
            val width = page.width
            val height = page.height
            val config = Bitmap.Config.ARGB_8888
            bitmap = Bitmap.createBitmap(width, height, config)
        }
        val (pageRect, pageMatrix) = calculatePageLayout(singlePagePrintCount, index, page.width, page.height,10)
        Log.d("TEST", "pageRect: $pageRect pageMatrix: $pageMatrix pageWidth: ${page.width} pageHeight: ${page.height}")
        page.render(bitmap, pageRect, pageMatrix, renderMode)
        page.close()
    }
    return bitmap!!
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

suspend fun printPdf(context :Context, uri: Uri?, singlePagePrintCount: Int, pageRangeSelection: String?, pageRangeCustom: String?) {
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
            destination: android.os.ParcelFileDescriptor,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback
        ) {
            if (pdfRenderer == null) return
            try {
                val document = PdfDocument()
                val selectedPages = range2list(ceil(pdfRenderer.pageCount.toFloat() / singlePagePrintCount).toInt() , pageRangeSelection, pageRangeCustom)
                for (index in selectedPages.indices) {
                    val bitmap = render(pdfRenderer, index, singlePagePrintCount, pageRangeSelection, pageRangeCustom,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                        bitmap.width, bitmap.height, index
                    ).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
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

@Composable
fun Greeting(uri: Uri?) {
    var singlePagePrintCount by remember { mutableIntStateOf(1) }
    var pageRangeSelection by remember { mutableStateOf(PageRangeSelection.ALL) }
    var customPageRange by remember { mutableStateOf("") }
    val scrollableState = rememberScrollableState { delta ->
        // 处理滚动事件
        delta
    }
    var selectedPdfUri by remember { mutableStateOf(uri) }
    var pageCount by remember { mutableIntStateOf(0) }
    var fileName by remember { mutableStateOf<String?>("") }
    val context = LocalContext.current
    var printPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(key1 = selectedPdfUri) {
        if (selectedPdfUri != null) {
            withContext(Dispatchers.IO) {
                pageCount = calculatePdfPageCount(context,selectedPdfUri!!)
                fileName = getPdfFileName(context, selectedPdfUri!!)
                Log.d("Test", "pageCount: $pageCount fileName: $fileName")
            }
        } else {
            pageCount = 0
        }
    }

    fun updatePages() {
        if (pageCount <= 0) return;
        val totalPages = ceil(pageCount.toFloat() / singlePagePrintCount)
        printPages = range2list(totalPages.toInt(), pageRangeSelection.name, customPageRange)
    }


    LaunchedEffect(key1 = pageCount, key2 = singlePagePrintCount, key3 = pageRangeSelection) {
        updatePages()
    }
    LaunchedEffect(key1 = customPageRange) {
        updatePages()
    }


    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri0 ->
            selectedPdfUri = uri0
        }
    )
    Box(modifier = Modifier.fillMaxSize()) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .scrollable(
                state = scrollableState,
                orientation = Orientation.Vertical
            ),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (pageCount) {
        0-> Button(
            onClick = { launcher.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择一个PDF文件")
        }
            else -> Text("已选择文件：$fileName (共 $pageCount 页)")
        }

        // 单页打印页数选择
        Text("单页打印页数：")
        Row (verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..4) {
                RadioButton(
                    selected = singlePagePrintCount == i,
                    onClick = { singlePagePrintCount = i }
                )
                Text("$i", modifier = Modifier.clickable {  singlePagePrintCount = i })
                Spacer(Modifier.width(8.dp))
            }
        }

        // 页码范围选择
        Text("打印范围：")
        Column {
            PageRangeSelection.entries.forEach { selection ->
                Row(modifier = Modifier.clickable{  pageRangeSelection = selection }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = pageRangeSelection == selection,
                        onClick = { pageRangeSelection = selection }
                    )
                    Text(selection.displayName)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        // 自定义页码范围输入框
        if (pageRangeSelection == PageRangeSelection.CUSTOM) {
            OutlinedTextField(
                value = customPageRange,
                singleLine = true,
                onValueChange = { customPageRange = it },
                label = { Text("自定义页码范围") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        if (printPages.isNotEmpty()) {
            val pages = printPages.flatMap { v: Int -> v*singlePagePrintCount..<(v+1)*singlePagePrintCount }.filter{it<pageCount}

            Text("共需 ${printPages.size} 页，原书的 ${pages.map{it+1}.joinToString(", ")} 页将被打印")
        }
    }


        Button(
            enabled = pageCount>0,
            onClick = {
                coroutineScope.launch {
                    printPdf(
                        context,
                        selectedPdfUri,
                        singlePagePrintCount,
                        pageRangeSelection.name,
                        customPageRange
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Text("预览")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    奇偶打印Theme {
        Greeting(null)
    }
}
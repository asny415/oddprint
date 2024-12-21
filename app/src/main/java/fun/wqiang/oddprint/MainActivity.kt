package `fun`.wqiang.oddprint

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import `fun`.wqiang.oddprint.ui.theme.OddPrintTheme
import kotlinx.coroutines.launch
import kotlin.math.ceil

enum class PageRangeSelection(val displayName: String) {
    ODD("奇数页"),
    EVEN("偶数页"),
    ALL("全部"),
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
            OddPrintTheme {
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

@Composable
fun Greeting(uri: Uri?) {
    var singlePagePrintCount by remember { mutableIntStateOf(1) }
    var pageRangeSelection by remember { mutableStateOf(PageRangeSelection.ODD) }
    var customPageRange by remember { mutableStateOf("") }
    val scrollableState = rememberScrollableState { delta ->
        // 处理滚动事件
        delta
    }
    var selectedPdfUri by remember { mutableStateOf(uri) }
    var showMore by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    var fileName by remember { mutableStateOf<String?>("") }
    val context = LocalContext.current
    var printPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(key1 = selectedPdfUri) {
        if (selectedPdfUri.toString().startsWith("test://")) {
            val params = uri.toString().split("/")
            pageCount = params[params.lastIndex - 1].toInt()
            fileName = params[params.lastIndex]
        } else {
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
    }

    fun updatePages() {
        if (pageCount <= 0) return
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

        // 页码范围选择
        Text("打印范围：",style = MaterialTheme.typography.labelMedium)
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

        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Text(if (showMore) "收起更多选项" else "展开更多选项", style = MaterialTheme.typography.bodySmall,  modifier = Modifier.clickable {  showMore = !showMore })
        }

        if (showMore) {
            // 单页打印页数选择
            Text("单页打印页数：", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 1..4) {
                    RadioButton(
                        selected = singlePagePrintCount == i,
                        onClick = { singlePagePrintCount = i }
                    )
                    Text("$i", modifier = Modifier.clickable { singlePagePrintCount = i })
                    Spacer(Modifier.width(8.dp))
                }
            }
        }

        if (printPages.isNotEmpty()) {
            val pages = printPages.flatMap { v: Int -> v*singlePagePrintCount..<(v+1)*singlePagePrintCount }.filter{it<pageCount}
            Text("共需 ${printPages.size} 页，原书的 ${pages.map{it+1}.joinToString(", ")} 页将被打印", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 50.dp) )
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

@Preview(showBackground = true, backgroundColor = 0xFFFFFF)
@Composable
fun GreetingPreview() {
    OddPrintTheme {
        Greeting(Uri.parse("test://5/测试文件.pdf"))
    }
}

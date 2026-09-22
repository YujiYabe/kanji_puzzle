package com.example.kanjikumitate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

private data class KanjiKumitate(
    val target: String,
    val reading: String,
    val grade: Int,
    val promptBefore: String,
    val targetKana: String,
    val promptAfter: String,
    val parts: List<String>,
    val distractors: List<String>,
    val hint: String,
    val layout: CompositionLayout = CompositionLayout.Horizontal,
)

private data class QuestionTargetConfig(
    val sourceHash: Int,
    val includedByGrade: Map<Int, Set<String>>,
    val excludedByGrade: Map<Int, Set<String>>,
) {
    fun allows(grade: Int, target: String): Boolean {
        val included = includedByGrade[grade].orEmpty()
        return (included.isEmpty() || target in included) &&
            target !in excludedByGrade[grade].orEmpty()
    }
}

private enum class CompositionLayout {
    Horizontal,
    Vertical,
    OneAboveTwo,
}

private enum class JudgeState {
    Playing,
    Correct,
    Wrong,
}

private enum class AppScreen {
    Settings,
    Quiz,
    Result,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KanjiKumitateApp()
        }
    }
}

@Composable
private fun KanjiKumitateApp() {
    MaterialTheme {
        Surface(color = Color(0xFFF7FAF6)) {
            KanjiKumitateScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KanjiKumitateScreen(modifier: Modifier = Modifier) {
    var appScreen by remember { mutableStateOf(AppScreen.Settings) }
    var selectedGrade by rememberSaveable { mutableIntStateOf(1) }
    var questionCount by rememberSaveable { mutableIntStateOf(10) }
    val context = LocalContext.current
    val rootView = LocalView.current
    val textRecognizer = remember {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    val puzzles = remember(context) { loadKumitateQuestions(context) }
    val availableGrades = remember(puzzles) { puzzles.mapTo(sortedSetOf()) { it.grade } }
    val questionHistory = remember(context) { QuestionHistoryStore(context) }
    var sessionPuzzles by remember { mutableStateOf(emptyList<KanjiKumitate>()) }
    var puzzleIndex by rememberSaveable { mutableIntStateOf(0) }
    var judgeState by rememberSaveable(puzzleIndex) { mutableStateOf(JudgeState.Playing) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var isRecognizing by remember(puzzleIndex) { mutableStateOf(false) }
    val currentPuzzle = sessionPuzzles.getOrNull(puzzleIndex)
    val selectedParts = remember(puzzleIndex, currentPuzzle) {
        mutableStateListOf<String?>().apply {
            repeat(currentPuzzle?.parts?.size ?: 0) { add(null) }
        }
    }
    val placedPartPositions = remember(puzzleIndex, currentPuzzle) {
        mutableStateListOf<Offset?>().apply {
            repeat(currentPuzzle?.parts?.size ?: 0) { add(null) }
        }
    }
    var canvasBounds by remember(puzzleIndex) { mutableStateOf(Rect.Zero) }
    val scrollState = rememberScrollState()

    DisposableEffect(textRecognizer) {
        onDispose { textRecognizer.close() }
    }

    LaunchedEffect(appScreen, puzzleIndex, currentPuzzle) {
        if (appScreen == AppScreen.Quiz && currentPuzzle != null) {
            questionHistory.markShown(currentPuzzle)
        }
    }
    LaunchedEffect(availableGrades) {
        if (selectedGrade !in availableGrades) {
            selectedGrade = availableGrades.firstOrNull() ?: 1
        }
    }

    Column(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFEAF7ED), Color(0xFFFFFBEC), Color(0xFFE8F1FF)),
                ),
            )
            .verticalScroll(scrollState)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(score = score)

        when (appScreen) {
            AppScreen.Settings -> SettingsScreen(
                selectedGrade = selectedGrade,
                availableGrades = availableGrades,
                questionCount = questionCount,
                onGradeSelected = { selectedGrade = it },
                onQuestionCountChanged = { questionCount = it },
                onStart = {
                    sessionPuzzles = questionHistory.selectQuestions(
                        questions = puzzles.filter { it.grade == selectedGrade },
                        count = questionCount,
                    )
                    puzzleIndex = 0
                    score = 0
                    judgeState = JudgeState.Playing
                    appScreen = AppScreen.Quiz
                },
            )

            AppScreen.Quiz -> if (currentPuzzle != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${puzzleIndex + 1} / ${sessionPuzzles.size}問",
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF49645C),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                    QuestionCard(currentPuzzle)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            selectedParts.indices.forEach { selectedParts[it] = null }
                            placedPartPositions.indices.forEach { placedPartPositions[it] = null }
                            judgeState = JudgeState.Playing
                        },
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("やりなおす")
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val tiles = remember(currentPuzzle) {
                        (currentPuzzle.parts + currentPuzzle.distractors).shuffled()
                    }

                    BuildArea(
                        selectedParts = selectedParts,
                        placedPartPositions = placedPartPositions,
                        onRemoveLast = {
                            val lastFilledIndex = selectedParts.indexOfLast { it != null }
                            if (lastFilledIndex >= 0) {
                                selectedParts[lastFilledIndex] = null
                                placedPartPositions[lastFilledIndex] = null
                                judgeState = JudgeState.Playing
                            }
                        },
                        onCanvasPositioned = { canvasBounds = it },
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        tiles.forEachIndexed { tileIndex, part ->
                            val allowedUseCount = maxOf(
                                currentPuzzle.parts.count { it == part },
                                currentPuzzle.distractors.count { it == part },
                            )
                            PartTile(
                                text = part,
                                enabled = judgeState != JudgeState.Correct,
                                dragKey = "$tileIndex-$part",
                                onDrop = { dropPosition ->
                                    if (canvasBounds.contains(dropPosition)) {
                                        val normalizedDrop = Offset(
                                            ((dropPosition.x - canvasBounds.left) / canvasBounds.width)
                                                .coerceIn(0.06f, 0.94f),
                                            ((dropPosition.y - canvasBounds.top) / canvasBounds.height)
                                                .coerceIn(0.10f, 0.90f),
                                        )
                                        val existingCount = selectedParts.count { it == part }
                                        val slotIndex = if (existingCount >= allowedUseCount) {
                                            selectedParts.indices
                                                .filter { selectedParts[it] == part }
                                                .minByOrNull { index ->
                                                    val position = placedPartPositions[index] ?: Offset.Zero
                                                    (position - normalizedDrop).getDistanceSquared()
                                                }
                                        } else {
                                            selectedParts.indexOfFirst { it == null }.takeIf { it >= 0 }
                                        }
                                            ?: placedPartPositions.indices.minByOrNull { index ->
                                                val position = placedPartPositions[index] ?: Offset.Zero
                                                (position - normalizedDrop).getDistanceSquared()
                                            }
                                            ?: return@PartTile
                                        selectedParts[slotIndex] = part
                                        placedPartPositions[slotIndex] = normalizedDrop
                                        judgeState = JudgeState.Playing
                                    }
                                },
                            )
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val bitmap = captureViewArea(rootView, canvasBounds)
                            if (bitmap == null) {
                                judgeState = JudgeState.Wrong
                            } else {
                                isRecognizing = true
                                textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                                    .addOnSuccessListener { result ->
                                        val recognized = result.text
                                            .filterNot { it.isWhitespace() }
                                            .normalizeForOcrComparison()
                                        val ocrMatches = recognized.contains(
                                            currentPuzzle.target.normalizeForOcrComparison(),
                                        )
                                        val structureMatches = isRecognizableCompositionFallback(
                                            puzzle = currentPuzzle,
                                            selectedParts = selectedParts,
                                            positions = placedPartPositions,
                                        )
                                        judgeState = if (
                                            ocrMatches || structureMatches
                                        ) {
                                            score += 10
                                            JudgeState.Correct
                                        } else {
                                            JudgeState.Wrong
                                        }
                                    }
                                    .addOnFailureListener {
                                        judgeState = JudgeState.Wrong
                                    }
                                    .addOnCompleteListener {
                                        isRecognizing = false
                                        bitmap.recycle()
                                    }
                            }
                        },
                        enabled = selectedParts.all { it != null } &&
                            judgeState != JudgeState.Correct &&
                            !isRecognizing,
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(if (isRecognizing) "OCRで判定中…" else "こたえあわせ")
                    }

                    FeedbackPanel(
                        puzzle = currentPuzzle,
                        judgeState = judgeState,
                        onNext = {
                            if (puzzleIndex + 1 >= sessionPuzzles.size) {
                                appScreen = AppScreen.Result
                            } else {
                                puzzleIndex += 1
                                judgeState = JudgeState.Playing
                            }
                        },
                    )
                }
            }
            }

            AppScreen.Result -> ResultScreen(
                score = score,
                questionCount = sessionPuzzles.size,
                onBackToSettings = { appScreen = AppScreen.Settings },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    selectedGrade: Int,
    availableGrades: Set<Int>,
    questionCount: Int,
    onGradeSelected: (Int) -> Unit,
    onQuestionCountChanged: (Int) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text(
            text = "学年をえらぶ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFF1D3C34),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (1..6).forEach { grade ->
                FilterChip(
                    selected = selectedGrade == grade,
                    onClick = { onGradeSelected(grade) },
                    enabled = grade in availableGrades,
                    label = { Text("${grade}年") },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "問題数",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF1D3C34),
                )
                Text(
                    text = "${questionCount}問",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF1D3C34),
                )
            }
            Slider(
                value = questionCount.toFloat(),
                onValueChange = {
                    onQuestionCountChanged(((it / 5).roundToInt() * 5).coerceIn(5, 100))
                },
                valueRange = 5f..100f,
                steps = 18,
            )
        }

        Button(
            onClick = onStart,
            enabled = selectedGrade in availableGrades,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF238754),
                contentColor = Color.White,
            ),
        ) {
            Text(text = "スタート", fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ResultScreen(
    score: Int,
    questionCount: Int,
    onBackToSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "おつかれさまでした",
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF1D3C34),
        )
        Text(
            text = "${questionCount}問 クリア",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF49645C),
        )
        Text(
            text = "${score}点",
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFFB4472D),
        )
        Button(
            onClick = onBackToSettings,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Text("設定にもどる", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Header(score: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "漢字くみたて",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF1D3C34),
            )
            Text(
                text = "部品をえらんで、漢字を完成させよう",
                color = Color(0xFF49645C),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFFFE08A),
            border = BorderStroke(2.dp, Color(0xFFE0A900)),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                text = "${score}点",
                color = Color(0xFF4D3900),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun QuestionCard(puzzle: KanjiKumitate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "問題",
                color = Color(0xFF3F6F5F),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = buildAnnotatedString {
                    append(puzzle.promptBefore)
                    append("「")
                    withStyle(
                        SpanStyle(
                            color = Color(0xFFD12B1F),
                            fontWeight = FontWeight.Black,
                        ),
                    ) {
                        append(puzzle.targetKana)
                    }
                    append("」")
                    append(puzzle.promptAfter)
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF203832),
            )
            Text(
                text = "よみ: ${puzzle.reading}",
                color = Color(0xFF52645F),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "ヒント: ${puzzle.hint}",
                color = Color(0xFF7A5A00),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun BuildArea(
    selectedParts: List<String?>,
    placedPartPositions: List<Offset?>,
    onRemoveLast: () -> Unit,
    onCanvasPositioned: (Rect) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDF7DF)),
        border = BorderStroke(2.dp, Color(0xFFD9BE5F)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "完成エリア",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF51430D),
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(onClick = onRemoveLast, enabled = selectedParts.any { it != null }) {
                    Text("1つもどす")
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE4D9A8), RoundedCornerShape(8.dp))
                    .onGloballyPositioned { onCanvasPositioned(it.boundsInRoot()) },
                contentAlignment = Alignment.TopStart,
            ) {
                selectedParts.forEachIndexed { index, part ->
                    val position = placedPartPositions.getOrNull(index)
                    if (part != null && position != null) {
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = maxWidth * position.x - 40.dp,
                                    y = maxHeight * position.y - 40.dp,
                                )
                                .size(80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = part,
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF222222),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartTile(
    text: String,
    enabled: Boolean,
    dragKey: String,
    onDrop: (Offset) -> Unit,
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var tilePosition by remember { mutableStateOf(Offset.Zero) }
    var tileSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    val background = when {
        isDragging -> Color.Transparent
        enabled -> Color.White
        else -> Color(0xFFE5E5E5)
    }
    val border = when {
        isDragging -> Color.Transparent
        enabled -> Color(0xFF3C8C68)
        else -> Color(0xFFBBBBBB)
    }

    Box(
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .onGloballyPositioned {
                if (!isDragging) tilePosition = it.positionInRoot()
                tileSize = it.size
            }
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .size(width = 76.dp, height = 68.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(2.dp, border, RoundedCornerShape(8.dp))
            .pointerInput(enabled, dragKey) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            val dropPosition = tilePosition + dragOffset + Offset(
                                x = tileSize.width / 2f,
                                y = tileSize.height / 2f,
                            )
                            onDrop(dropPosition)
                            dragOffset = Offset.Zero
                            isDragging = false
                        },
                        onDragCancel = {
                            dragOffset = Offset.Zero
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (isDragging) 54.sp else 28.sp,
            fontWeight = FontWeight.Black,
            color = if (enabled) Color(0xFF173A2D) else Color(0xFF909090),
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedbackPanel(
    puzzle: KanjiKumitate,
    judgeState: JudgeState,
    onNext: () -> Unit,
) {
    if (judgeState == JudgeState.Playing) return
    val nextButtonRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(judgeState) {
        if (judgeState == JudgeState.Correct) {
            nextButtonRequester.bringIntoView()
        }
    }

    val (message, color) = when (judgeState) {
        JudgeState.Correct -> "正解。${puzzle.target} が完成しました" to Color(0xFF0F7C42)
        JudgeState.Wrong -> "もう一度ならべかえてみよう" to Color(0xFFB33825)
        JudgeState.Playing -> return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFD6DED9)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message,
                color = color,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (judgeState == JudgeState.Correct) {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(nextButtonRequester),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF257B58)),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Text("つぎの問題")
                }
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFF0B8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = puzzle.target,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF1E2F28),
                    )
                }
                Text(
                    text = "${puzzle.parts.joinToString(" + ")} で ${puzzle.target}",
                    color = Color(0xFF4E5C57),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

private data class IdsNode(
    val raw: String,
    val value: String? = null,
    val children: List<IdsNode> = emptyList(),
)

private class QuestionHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "kanji_kumitate_question_history",
        Context.MODE_PRIVATE,
    )

    fun selectQuestions(
        questions: List<KanjiKumitate>,
        count: Int,
    ): List<KanjiKumitate> {
        if (questions.isEmpty() || count <= 0) return emptyList()

        val questionsByTarget = questions.groupBy { it.target }
        val targetCounts = questionsByTarget.keys.associateWithTo(mutableMapOf()) { target ->
            preferences.getInt(targetKey(questions.first().grade, target), 0)
        }
        val questionCounts = questions.associateWithTo(mutableMapOf()) { question ->
            preferences.getInt(questionKey(question), 0)
        }
        val selected = buildList {
            repeat(count) {
                val lowestTargetCount = targetCounts.values.minOrNull() ?: 0
                val target = targetCounts
                    .filterValues { it == lowestTargetCount }
                    .keys
                    .random()
                val variants = questionsByTarget.getValue(target)
                val lowestQuestionCount = variants.minOf { questionCounts.getValue(it) }
                val question = variants
                    .filter { questionCounts.getValue(it) == lowestQuestionCount }
                    .random()

                add(question)
                targetCounts[target] = targetCounts.getValue(target) + 1
                questionCounts[question] = questionCounts.getValue(question) + 1
            }
        }

        return selected
    }

    fun markShown(question: KanjiKumitate) {
        val targetKey = targetKey(question.grade, question.target)
        val questionKey = questionKey(question)
        preferences.edit()
            .putInt(targetKey, preferences.getInt(targetKey, 0) + 1)
            .putInt(questionKey, preferences.getInt(questionKey, 0) + 1)
            .apply()
    }

    private fun targetKey(grade: Int, target: String): String = "target:$grade:$target"

    private fun questionKey(question: KanjiKumitate): String = buildString {
        append("question:")
        append(question.grade)
        append(':')
        append(question.target)
        append(':')
        append(question.reading)
        append(':')
        append(question.promptBefore)
        append(':')
        append(question.promptAfter)
    }
}

private fun loadKumitateQuestions(context: Context): List<KanjiKumitate> {
    val dataDirectory = context.getExternalFilesDir(null) ?: context.filesDir
    val jsonFile = File(dataDirectory, "kanji_questions.json")
    val targetConfig = loadQuestionTargetConfig(context)
    val questionSource = context.assets.open("kanji_yomi_questions.json")
        .bufferedReader()
        .use { it.readText() }
    val questionSourceHash = questionSource.hashCode()
    if (jsonFile.isFile) {
        runCatching {
            readQuestionsFromJson(
                json = jsonFile.readText(),
                expectedTargetConfigHash = targetConfig.sourceHash,
                expectedQuestionSourceHash = questionSourceHash,
            )
        }
            .getOrNull()
            ?.let { return it }
    }

    val generatedQuestions = buildQuestionsFromSourceData(context, targetConfig, questionSource)
    runCatching {
        writeQuestionsToJson(
            file = jsonFile,
            questions = generatedQuestions,
            targetConfigHash = targetConfig.sourceHash,
            questionSourceHash = questionSourceHash,
        )
    }
    return generatedQuestions
}

private fun buildQuestionsFromSourceData(
    context: Context,
    targetConfig: QuestionTargetConfig,
    questionSource: String,
): List<KanjiKumitate> {
    val idsByKanji = loadIdsData(context)
    val reverseIds = idsByKanji.entries.associate { (kanji, ids) -> ids to kanji }
    val distractorPool = listOf("亻", "木", "日", "月", "口", "心", "力", "土", "山", "石", "田", "女", "子", "言", "糸", "氵", "艹")

    val root = JSONObject(questionSource)
    require(root.optInt("schemaVersion") == 1)
    val sourceQuestions = root.getJSONArray("questions")

    return buildList {
        repeat(sourceQuestions.length()) { index ->
            val item = sourceQuestions.getJSONObject(index)
            if (!item.optBoolean("vaild", true)) return@repeat
            val grade = item.optInt("grade").takeIf { it in 1..6 } ?: return@repeat
            val target = item.optString("target")
            val reading = item.optString("reading")
            val markedSentence = item.optString("sentence")
            if (target.isEmpty() || reading.isEmpty()) return@repeat
            if (!targetConfig.allows(grade, target)) return@repeat
            val structure = idsByKanji[target]?.firstOrNull()
            if (structure !in IDS_OPERATORS) return@repeat
            val targetMarker = "[$target]"
            val targetIndex = markedSentence.indexOf(targetMarker)
            val promptBefore = if (targetIndex >= 0) {
                markedSentence.substring(0, targetIndex).stripRubyMarkers()
            } else {
                ""
            }
            val promptAfter = if (targetIndex >= 0) {
                markedSentence.substring(targetIndex + targetMarker.length).stripRubyMarkers()
            } else {
                markedSentence.stripRubyMarkers()
            }
            val decomposition = createIdsPuzzle(
                target = target,
                ids = idsByKanji[target],
                reverseIds = reverseIds,
                distractorPool = distractorPool,
            ) ?: return@repeat

            add(KanjiKumitate(
                target = target,
                reading = reading,
                grade = grade,
                promptBefore = promptBefore,
                targetKana = reading,
                promptAfter = promptAfter,
                parts = decomposition.parts,
                distractors = decomposition.distractors,
                hint = decomposition.hint,
                layout = decomposition.layout,
            ))
        }
    }
}

private fun readQuestionsFromJson(
    json: String,
    expectedTargetConfigHash: Int,
    expectedQuestionSourceHash: Int,
): List<KanjiKumitate> {
    val root = JSONObject(json)
    require(root.optInt("schemaVersion") == QUESTION_SCHEMA_VERSION)
    require(root.optInt("targetConfigHash") == expectedTargetConfigHash)
    require(root.optInt("questionSourceHash") == expectedQuestionSourceHash)
    val questions = root.getJSONArray("questions")
    return buildList {
        repeat(questions.length()) { index ->
            val item = questions.getJSONObject(index)
            val parts = item.getJSONArray("parts").toStringList()
            if (parts.size < 2) return@repeat
            add(
                KanjiKumitate(
                    target = item.getString("target"),
                    reading = item.getString("reading"),
                    grade = item.getInt("grade"),
                    promptBefore = item.getString("promptBefore"),
                    targetKana = item.optString("targetKana", item.getString("reading")),
                    promptAfter = item.getString("promptAfter"),
                    parts = parts,
                    distractors = item.getJSONArray("distractors").toStringList(),
                    hint = item.optString("hint"),
                    layout = CompositionLayout.valueOf(item.getString("layout")),
                ),
            )
        }
    }
}

private fun writeQuestionsToJson(
    file: File,
    questions: List<KanjiKumitate>,
    targetConfigHash: Int,
    questionSourceHash: Int,
) {
    val items = JSONArray()
    questions.forEach { question ->
        items.put(
            JSONObject()
                .put("target", question.target)
                .put("reading", question.reading)
                .put("grade", question.grade)
                .put("promptBefore", question.promptBefore)
                .put("targetKana", question.targetKana)
                .put("promptAfter", question.promptAfter)
                .put("parts", JSONArray(question.parts))
                .put("distractors", JSONArray(question.distractors))
                .put("hint", question.hint)
                .put("layout", question.layout.name),
        )
    }
    val root = JSONObject()
        .put("schemaVersion", QUESTION_SCHEMA_VERSION)
        .put("targetConfigHash", targetConfigHash)
        .put("questionSourceHash", questionSourceHash)
        .put("questions", items)
    file.parentFile?.mkdirs()
    val temporaryFile = File(file.parentFile, "${file.name}.tmp")
    temporaryFile.writeText(root.toString(2))
    if (!temporaryFile.renameTo(file)) {
        file.writeText(temporaryFile.readText())
        temporaryFile.delete()
    }
}

private fun JSONArray.toStringList(): List<String> =
    List(length()) { index -> getString(index) }

private fun loadQuestionTargetConfig(context: Context): QuestionTargetConfig {
    val source = runCatching {
        context.assets.open("question_targets.json").bufferedReader().use { it.readText() }
    }.getOrNull() ?: return QuestionTargetConfig(
        sourceHash = 0,
        includedByGrade = emptyMap(),
        excludedByGrade = emptyMap(),
    )

    return runCatching {
        val root = JSONObject(source)
        require(root.optInt("schemaVersion") == 1)
        val grades = root.getJSONObject("grades")
        val included = mutableMapOf<Int, Set<String>>()
        val excluded = mutableMapOf<Int, Set<String>>()
        for (grade in 1..6) {
            val settings = grades.optJSONObject(grade.toString()) ?: JSONObject()
            included[grade] = settings.optJSONArray("include")?.toStringList()?.toSet().orEmpty()
            excluded[grade] = settings.optJSONArray("exclude")?.toStringList()?.toSet().orEmpty()
        }
        QuestionTargetConfig(
            sourceHash = source.hashCode(),
            includedByGrade = included,
            excludedByGrade = excluded,
        )
    }.getOrElse {
        QuestionTargetConfig(
            sourceHash = 0,
            includedByGrade = emptyMap(),
            excludedByGrade = emptyMap(),
        )
    }
}

private fun loadIdsData(context: Context): Map<String, String> =
    context.assets.open("kanji_ids.txt").bufferedReader().useLines { lines ->
        lines.mapNotNull { line ->
            val columns = line.split('\t')
            val target = columns.getOrNull(0).orEmpty()
            val primaryIds = columns.getOrNull(1)
                ?.substringBefore(';')
                ?.replace(Regex("\\([^)]*\\)"), "")
                .orEmpty()
            if (target.isEmpty() || primaryIds.isEmpty()) null else target to primaryIds
        }.toMap()
    }

private fun createIdsPuzzle(
    target: String,
    ids: String?,
    reverseIds: Map<String, String>,
    distractorPool: List<String>,
): KanjiKumitate? {
    val parsed = ids?.let { parseIdsNode(it, 0).first } ?: return null
    val parts = parsed.children.mapNotNull { child ->
        child.value ?: reverseIds[child.raw]
    }.takeIf { candidates ->
        candidates.size == parsed.children.size &&
            candidates.size >= 2 &&
            candidates.all(String::isDeviceSafeKanjiPart)
    } ?: return null
    val distractors = distractorPool.filterNot { it in parts }.take(2)
    val layout = CompositionLayout.Horizontal
    val hint = "完成した漢字に見えるよう、自由な位置に部品を置こう"
    return KanjiKumitate(
        target, "", 1, "", "", "", parts, distractors, hint,
        layout,
    )
}

private fun parseIdsNode(text: String, start: Int): Pair<IdsNode, Int> {
    if (start >= text.length) return IdsNode("") to start
    val codePoint = text.codePointAt(start)
    val token = String(Character.toChars(codePoint))
    val next = start + Character.charCount(codePoint)
    val childCount = when (token) {
        "⿲", "⿳" -> 3
        "⿰", "⿱", "⿴", "⿵", "⿶", "⿷", "⿸", "⿹", "⿺", "⿻" -> 2
        else -> 0
    }
    if (childCount == 0) return IdsNode(raw = token, value = token) to next

    var cursor = next
    val children = buildList {
        repeat(childCount) {
            val (child, childEnd) = parseIdsNode(text, cursor)
            add(child)
            cursor = childEnd
        }
    }
    return IdsNode(raw = text.substring(start, cursor), children = children) to cursor
}

/**
 * Android's default Japanese fonts do not reliably contain CJK Extension-B+
 * glyphs. Keeping generated tiles in the BMP CJK/radical/stroke ranges avoids
 * tofu boxes without requiring a very large bundled font.
 */
private fun String.isDeviceSafeKanjiPart(): Boolean {
    if (codePointCount(0, length) != 1) return false
    return when (codePointAt(0)) {
        in 0x2E80..0x2FFF, // CJK radicals and ideographic description area
        in 0x3005..0x3007, // iteration mark and ideographic zero
        in 0x31C0..0x31EF, // CJK strokes
        in 0x4E00..0x9FFF, // common CJK unified ideographs
        in 0xF900..0xFAFF, // CJK compatibility ideographs
        -> true
        else -> false
    }
}

private const val QUESTION_SCHEMA_VERSION = 7
private val IDS_OPERATORS = setOf('⿰', '⿱', '⿲', '⿳', '⿴', '⿵', '⿶', '⿷', '⿸', '⿹', '⿺', '⿻')

private fun String.stripRubyMarkers(): String = filterNot { it == '[' || it == ']' || it == '{' || it == '}' }

private fun captureViewArea(view: View, bounds: Rect): Bitmap? {
    if (view.width <= 0 || view.height <= 0 || bounds.width <= 0f || bounds.height <= 0f) return null
    val left = bounds.left.roundToInt().coerceIn(0, view.width - 1)
    val top = bounds.top.roundToInt().coerceIn(0, view.height - 1)
    val right = bounds.right.roundToInt().coerceIn(left + 1, view.width)
    val bottom = bounds.bottom.roundToInt().coerceIn(top + 1, view.height)
    val fullView = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(fullView))
    val cropped = Bitmap.createBitmap(fullView, left, top, right - left, bottom - top)
    fullView.recycle()
    return cropped
}

private fun String.normalizeForOcrComparison(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC)
        .replace('+', '十')
        .replace('＋', '十')

/**
 * OCR engines commonly classify a font-rendered 十 as a plus sign or return no
 * text. Accept it when the correct horizontal and vertical components actually
 * overlap; this remains position-sensitive without snapping to a fixed point.
 */
private fun isRecognizableCompositionFallback(
    puzzle: KanjiKumitate,
    selectedParts: List<String?>,
    positions: List<Offset?>,
): Boolean {
    if (puzzle.target != "十") return false
    if (selectedParts.filterNotNull().sorted() != puzzle.parts.sorted()) return false

    val horizontalIndex = selectedParts.indexOfFirst { it == "一" }
    val verticalIndex = selectedParts.indexOfFirst { it == "丨" || it == "｜" }
    val horizontal = positions.getOrNull(horizontalIndex) ?: return false
    val vertical = positions.getOrNull(verticalIndex) ?: return false
    return (horizontal - vertical).getDistance() <= 0.22f
}

private val kumitateQuestionBank = listOf(
    KanjiKumitate("休", "やすむ", 1, "人が木のそばで", "やすむ", "ときの漢字", listOf("亻", "木"), listOf("日", "口"), "にんべん と 木"),
    KanjiKumitate("明", "あかるい", 2, "日と月で", "あかるい", "ことを表す漢字", listOf("日", "月"), listOf("火", "目"), "空にある明るいもの"),
    KanjiKumitate("林", "はやし", 1, "木が二つならぶ", "はやし", "の漢字", listOf("木", "木"), listOf("人", "本"), "木がふえると景色もふえる"),
    KanjiKumitate("森", "もり", 1, "木が三つ集まった", "もり", "の漢字", listOf("木", "木", "木"), listOf("人", "本"), "上に木が一つ、下に木が二つ", CompositionLayout.OneAboveTwo),
    KanjiKumitate("男", "おとこ", 1, "田んぼで力を出す", "おとこ", "の漢字", listOf("田", "力"), listOf("日", "人"), "上に田、下に力", CompositionLayout.Vertical),
    KanjiKumitate("花", "はな", 1, "きれいにさく", "はな", "の漢字", listOf("艹", "化"), listOf("木", "火"), "上にくさかんむり、下に化", CompositionLayout.Vertical),
    KanjiKumitate("空", "そら", 1, "鳥や雲が見える", "そら", "の漢字", listOf("穴", "工"), listOf("天", "土"), "上に穴、下に工", CompositionLayout.Vertical),
    KanjiKumitate("音", "おと", 1, "耳で聞く", "おと", "の漢字", listOf("立", "日"), listOf("口", "目"), "上に立、下に日", CompositionLayout.Vertical),
    KanjiKumitate("草", "くさ", 1, "野原にはえる", "くさ", "の漢字", listOf("艹", "早"), listOf("木", "日"), "上にくさかんむり、下に早", CompositionLayout.Vertical),
    KanjiKumitate("岩", "いわ", 1, "大きくてかたい", "いわ", "の漢字", listOf("山", "石"), listOf("土", "口"), "上に山、下に石", CompositionLayout.Vertical),
    KanjiKumitate("校", "こう", 1, "学校の", "こう", "の漢字", listOf("木", "交"), listOf("本", "文"), "木へん と 交"),
    KanjiKumitate("村", "むら", 1, "人が集まって住む", "むら", "の漢字", listOf("木", "寸"), listOf("本", "土"), "木へん と 寸"),
    KanjiKumitate("町", "まち", 1, "家や店が集まる", "まち", "の漢字", listOf("田", "丁"), listOf("日", "力"), "田 と 丁"),
    KanjiKumitate("早", "はやい", 1, "時間がまだ", "はやい", "ことを表す漢字", listOf("日", "十"), listOf("口", "木"), "上に日、下に十", CompositionLayout.Vertical),
    KanjiKumitate("好", "すき", 4, "", "すき", "、よい、という意味の漢字", listOf("女", "子"), listOf("口", "心"), "すき、よい、という意味"),
    KanjiKumitate("海", "うみ", 2, "水に関係する大きな場所、", "うみ", "の漢字", listOf("氵", "毎"), listOf("木", "羊"), "さんずい がつく"),
    KanjiKumitate("鳴", "なく", 2, "鳥が", "なく", "ことを表す漢字", listOf("口", "鳥"), listOf("日", "馬"), "口と鳥"),
    KanjiKumitate("時", "とき", 2, "時間を表す", "とき", "の漢字", listOf("日", "寺"), listOf("土", "糸"), "日へん がつく"),
    KanjiKumitate("語", "ことば", 2, "", "ことば", "に関係する漢字", listOf("言", "吾"), listOf("口", "五"), "ごんべん がつく"),
    KanjiKumitate("想", "おもう", 3, "心で", "おもう", "ことを表す漢字", listOf("相", "心"), listOf("日", "目"), "下に心がある", CompositionLayout.Vertical),
    KanjiKumitate("線", "せん", 2, "糸のようにつながる", "せん", "の漢字", listOf("糸", "泉"), listOf("水", "白"), "いとへん がつく"),
    KanjiKumitate("働", "はたらく", 4, "人が動いて", "はたらく", "ことを表す漢字", listOf("亻", "動"), listOf("力", "休"), "人が動く"),
    KanjiKumitate("親", "おや", 2, "家族の", "おや", "を表す漢字", listOf("立", "木", "見"), listOf("日", "子"), "立つ、木、見る"),
    KanjiKumitate("晴", "はれる", 2, "空が明るく", "はれる", "天気の漢字", listOf("日", "青"), listOf("雨", "月"), "日と青"),
    KanjiKumitate("橋", "はし", 3, "川をわたるための", "はし", "の漢字", listOf("木", "喬"), listOf("水", "土"), "木へん がつく"),
    KanjiKumitate("館", "やかた", 3, "人が集まる建物、", "やかた", "を表す漢字", listOf("食", "官"), listOf("門", "舎"), "食へん に似た部品がある"),
    KanjiKumitate("機", "き", 4, "しかけや道具を表す", "き", "の漢字", listOf("木", "幾"), listOf("糸", "力"), "木へん がつく"),
    KanjiKumitate("燃", "もえる", 5, "火がついて", "もえる", "ことを表す漢字", listOf("火", "然"), listOf("水", "草"), "火へん がつく"),
    KanjiKumitate("職", "しょく", 5, "仕事や役目を表す", "しょく", "の漢字", listOf("耳", "音", "戈"), listOf("言", "心"), "耳と音が見える"),
    KanjiKumitate("樹", "じゅ", 6, "大きな木を表す", "じゅ", "の漢字", listOf("木", "尌"), listOf("土", "寺"), "木へん がつく"),
    KanjiKumitate("優", "やさしい", 6, "人の", "やさしい", "気持ちを表す漢字", listOf("亻", "憂"), listOf("心", "友"), "にんべん がつく"),
)

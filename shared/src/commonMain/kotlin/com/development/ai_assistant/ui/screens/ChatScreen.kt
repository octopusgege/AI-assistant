package com.development.ai_assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.development.ai_assistant.domain.model.Message
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.preat.peekaboo.image.picker.SelectionMode
import com.preat.peekaboo.image.picker.rememberImagePickerLauncher
import com.preat.peekaboo.image.picker.toImageBitmap
import org.koin.compose.koinInject

/**
 * 聊天对话主界面
 * 负责渲染多模态对话信息流、处理用户长按交互录音机制、协调软键盘的弹出与收起，
 * 调度跨端系统图库选择器（支持多图并发），并提供流式输出时的列表自适应滚动状态管理。
 */
class ChatScreen : Screen {

    @Composable
    override fun Content() {
        val viewModel = koinInject<ChatViewModel>()

        val turns by viewModel.conversationTurns.collectAsState()
        val indexOverrides by viewModel.displayIndexOverrides.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val isAutoSpeakEnabled by viewModel.isAutoSpeakEnabled.collectAsState()
        val isListening by viewModel.isListening.collectAsState()

        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()

        var autoScrollEnabled by remember { mutableStateOf(true) }

        // 多模态图像数据队列，持有系统图库回调的多个底层字节数组
        var selectedImages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
        val scope = rememberCoroutineScope()

        // 跨端图像选择器生命周期与回调绑定，采用多选模式，硬性限制最大张数为 4，保障渲染性能与带宽
        val imagePicker = rememberImagePickerLauncher(
            selectionMode = SelectionMode.Multiple(maxSelection = 4),
            scope = scope,
            onResult = { byteArrays ->
                if (byteArrays.isNotEmpty()) {
                    // 追加合并逻辑：将新选择的图片加入队列，但整体队列长度不超过 4 张
                    val currentList = selectedImages.toMutableList()
                    currentList.addAll(byteArrays)
                    selectedImages = currentList.take(4)
                }
            }
        )

        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                autoScrollEnabled = false
                keyboardController?.hide()
            }
        }

        val turnCount = turns.size
        LaunchedEffect(turnCount) {
            autoScrollEnabled = true
        }

        val lastTurnContent = turns.lastOrNull()?.aiMessages?.lastOrNull()?.content
        LaunchedEffect(lastTurnContent) {
            if (turns.isNotEmpty()) {
                if (!listState.canScrollForward) {
                    autoScrollEnabled = true
                }
                if (autoScrollEnabled) {
                    listState.scrollToItem(turns.size - 1, scrollOffset = 32767)
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                ChatTopBar(
                    isAutoSpeakEnabled = isAutoSpeakEnabled,
                    onToggleAutoSpeak = { viewModel.toggleAutoSpeak() }
                )
            },
            bottomBar = {
                ChatBottomBar(
                    inputText = inputText,
                    isListening = isListening,
                    selectedImages = selectedImages,
                    onInputChanged = viewModel::onInputTextChanged,
                    onSendClicked = {
                        // 向 ViewModel 投递文本与多图集合
                        viewModel.sendMessage(inputText, selectedImages)
                        selectedImages = emptyList() // 发送完毕后清空缓存区
                        keyboardController?.hide()
                    },
                    onMicPress = viewModel::startVoiceInput,
                    onMicRelease = viewModel::stopAndSendVoiceInput,
                    onLaunchImagePicker = { imagePicker.launch() },
                    onClearImage = { indexToRemove ->
                        // 根据索引安全移除队列中的特定图像
                        val currentList = selectedImages.toMutableList()
                        if (indexToRemove in currentList.indices) {
                            currentList.removeAt(indexToRemove)
                            selectedImages = currentList
                        }
                    }
                )
            },
            containerColor = Color.White
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(turns) { turn ->
                        ChatBubble(message = turn.userMessage)
                        Spacer(modifier = Modifier.height(16.dp))

                        if (turn.aiMessages.isNotEmpty()) {
                            val displayIndex = indexOverrides[turn.groupId] ?: turn.currentDisplayIndex
                            val displayedAiMsg = turn.aiMessages[displayIndex]

                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                ChatBubble(message = displayedAiMsg)

                                AiActionBar(
                                    message = displayedAiMsg,
                                    currentVersionIndex = displayIndex,
                                    totalVersions = turn.aiMessages.size,
                                    onPreviousVersion = { viewModel.changeDisplayIndex(turn.groupId, displayIndex - 1) },
                                    onNextVersion = { viewModel.changeDisplayIndex(turn.groupId, displayIndex + 1) },
                                    onRegenerate = { viewModel.regenerateResponse(turn.groupId, turn.userMessage.content) },
                                    onLike = { viewModel.onInteractionClicked(displayedAiMsg.id, displayedAiMsg.interactionStatus, 1) },
                                    onDislike = { viewModel.onInteractionClicked(displayedAiMsg.id, displayedAiMsg.interactionStatus, 2) },
                                    onSpeakClicked = { viewModel.onSpeakMessageClicked(displayedAiMsg.id, displayedAiMsg.content) }
                                )

                                if (displayedAiMsg.followUpQuestions.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    displayedAiMsg.followUpQuestions.forEach { question ->
                                        FollowUpChip(text = question, onClick = { viewModel.sendMessage(question) })
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isListening,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color(0x00FFFFFF), Color(0xFF42A5F5), Color(0xFF1E88E5))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = inputText,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "松开即可发送",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    isAutoSpeakEnabled: Boolean,
    onToggleAutoSpeak: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = { }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一页", tint = Color.Black)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "新对话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Text(text = "内容由 AI 生成", fontSize = 12.sp, color = Color.Gray)
        }

        Row {
            IconButton(onClick = { }) {
                Icon(Icons.Default.Call, contentDescription = "语音通话", tint = Color.Black)
            }
            IconButton(onClick = onToggleAutoSpeak) {
                val icon = if (isAutoSpeakEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff
                val tint = if (isAutoSpeakEnabled) Color.Blue else Color.Black
                Icon(icon, contentDescription = "自动语音播报开关", tint = tint)
            }
        }
    }
}

@Composable
private fun AiActionBar(
    message: Message,
    currentVersionIndex: Int,
    totalVersions: Int,
    onPreviousVersion: () -> Unit,
    onNextVersion: () -> Unit,
    onRegenerate: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSpeakClicked: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "复制内容",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp).clickable {
                    clipboardManager.setText(AnnotatedString(message.content))
                }
            )

            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = "有帮助",
                tint = if (message.interactionStatus == 1) Color.Blue else Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onLike() }
            )

            Icon(
                imageVector = Icons.Default.ThumbDown,
                contentDescription = "无帮助",
                tint = if (message.interactionStatus == 2) Color.Red else Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onDislike() }
            )

            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "朗读当前回答",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onSpeakClicked() }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (totalVersions > 1) {
                Text(
                    text = "${currentVersionIndex + 1} / $totalVersions",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

                Text(
                    text = "<",
                    color = if (currentVersionIndex > 0) Color.Black else Color.LightGray,
                    modifier = Modifier.clickable(enabled = currentVersionIndex > 0) { onPreviousVersion() }
                )
            }

            if (currentVersionIndex < totalVersions - 1) {
                Text(
                    text = ">",
                    color = Color.Black,
                    modifier = Modifier.clickable { onNextVersion() }
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "请求重新生成",
                    tint = Color.Blue,
                    modifier = Modifier.size(20.dp).clickable { onRegenerate() }
                )
            }
        }
    }
}

@Composable
private fun FollowUpChip(text: String, onClick: () -> Unit) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF0F4FF))
            .clickable {
                onClick()
                keyboardController?.hide()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = Color.Blue, fontSize = 13.sp)
    }
}

@Composable
private fun ChatBubble(message: Message) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ))
                .background(if (isUser) Color(0xFFE3F2FD) else Color(0xFFF4F5F7))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    color = Color.Black,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            } else {
                Markdown(
                    content = message.content,
                    typography = markdownTypography(
                        h1 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                        h2 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                        h3 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                        h4 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                        h5 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                        h6 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black),
                        text = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Color.Black)
                    )
                )
            }
        }
    }
}

/**
 * 底部多模态交互控制台
 * 融合文本、音频及多图像的并发输入调度。采用横向滚动容器处理图像溢出。
 */
@Composable
private fun ChatBottomBar(
    inputText: String,
    isListening: Boolean,
    selectedImages: List<ByteArray>,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    onLaunchImagePicker: () -> Unit,
    onClearImage: (Int) -> Unit
) {
    var showAddMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        // 动态视口：利用 LazyRow 渲染横向扩展的缩略图矩阵
        AnimatedVisibility(visible = selectedImages.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(selectedImages) { index, bytes ->
                    Box {
                        Image(
                            bitmap = bytes.toImageBitmap(),
                            contentDescription = "待发送的图像源 $index",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop // 居中裁剪，保证所有缩略图视觉统一
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                                .clickable { onClearImage(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "清除该图像", tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF4F5F7))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            BasicTextField(
                value = inputText,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = Color.Black, lineHeight = 20.sp),
                cursorBrush = SolidColor(Color.Blue),
                maxLines = 5,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (inputText.isEmpty()) {
                            Text(text = "发消息或按住说话...", color = Color.Gray, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                }
            )

            val iconPadding = Modifier.padding(bottom = 2.dp)
            if ((inputText.isNotEmpty() || selectedImages.isNotEmpty()) && !isListening && inputText != "正在聆听..." && !inputText.startsWith("[")) {
                Box(
                    modifier = iconPadding
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Blue)
                        .clickable { onSendClicked() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送请求", tint = Color.White, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                }
            } else {
                Box(
                    modifier = iconPadding.size(28.dp).clip(CircleShape).background(if (isListening) Color.Blue else Color.White)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onMicPress()
                                    tryAwaitRelease()
                                    onMicRelease()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "启动声学传感器", tint = if (isListening) Color.White else Color.Black, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = iconPadding.size(28.dp).clip(CircleShape).background(Color.White)
                            .clickable { showAddMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "展开扩展功能栈", tint = Color.Black, modifier = Modifier.size(18.dp))
                    }

                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🖼️ 从相册选择") },
                            onClick = {
                                showAddMenu = false
                                onLaunchImagePicker()
                            }
                        )
                    }
                }
            }
        }
    }
}
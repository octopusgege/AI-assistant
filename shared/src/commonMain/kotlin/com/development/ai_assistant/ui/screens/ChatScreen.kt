package com.development.ai_assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import org.koin.compose.koinInject
import com.mikepenz.markdown.m3.markdownTypography

/**
 * 聊天对话主界面
 * 这是整个 AI 助手应用的核心 UI 页面。
 * 主要功能包括：渲染用户与 AI 的对话流、处理文本输入、协调软键盘的弹出与收起、
 * 管理流式输出时的列表自动滚动状态，以及对接底层的语音播报和大模型引擎。
 */
class ChatScreen : Screen {

    @Composable
    override fun Content() {
        // 从 Koin 依赖注入容器中获取全局唯一的 ChatViewModel 实例
        val viewModel = koinInject<ChatViewModel>()

        // 观察 ViewModel 中的核心数据流，当数据变化时自动触发 UI 重绘
        val turns by viewModel.conversationTurns.collectAsState()
        val indexOverrides by viewModel.displayIndexOverrides.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val isAutoSpeakEnabled by viewModel.isAutoSpeakEnabled.collectAsState()

        // 获取系统软键盘控制器，用于在发送消息或滑动列表时手动收起键盘
        val keyboardController = LocalSoftwareKeyboardController.current
        // 维护聊天列表的滚动状态，用于实现“自动跟随最新消息”的功能
        val listState = rememberLazyListState()

        /**
         * 自动滚动策略开关
         * 当该值为 true 时，列表会紧紧跟随 AI 正在生成的最新文字滚动到底部。
         * 当用户主动往上翻阅历史消息时，该值会被置为 false，避免强制滚动打断用户阅读。
         */
        var autoScrollEnabled by remember { mutableStateOf(true) }

        /**
         * 监听底层列表系统的滑动状态
         * 如果检测到列表正在滚动（isScrollInProgress 为 true），说明用户手指在拖拽屏幕。
         * 此时我们需要立刻关闭自动滚动功能，并收起软键盘，把视图控制权交还给用户。
         */
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                autoScrollEnabled = false
                keyboardController?.hide()
            }
        }

        /**
         * 监听对话轮次的数量变化
         * 无论是用户发送了新问题，还是点击了底部的“追问”标签，都会导致轮次（turnCount）增加。
         * 只要开启了新一轮对话，我们就将自动滚动策略重新激活，确保用户能看到最新的回复。
         */
        val turnCount = turns.size
        LaunchedEffect(turnCount) {
            autoScrollEnabled = true
        }

        /**
         * 监听 AI 最新回复内容的长度变化
         * 这是流式输出体验的核心：当 AI 不断吐出新字时，此逻辑会被高频触发。
         * 如果当前允许自动滚动，我们会给出一个极大的偏移量（32767），
         * 强制让系统把列表的底部顶到屏幕的最下方，确保新生成的文字始终在视口内。
         */
        val lastTurnContent = turns.lastOrNull()?.aiMessages?.lastOrNull()?.content
        LaunchedEffect(lastTurnContent) {
            if (turns.isNotEmpty()) {
                // 如果列表已经滚动到了最底部（无法继续向下滚动），说明用户已经看完了内容，此时恢复自动滚动追踪
                if (!listState.canScrollForward) {
                    autoScrollEnabled = true
                }

                if (autoScrollEnabled) {
                    listState.scrollToItem(turns.size - 1, scrollOffset = 32767)
                }
            }
        }

        // 页面基础骨架，包含顶部的导航栏、底部的输入框以及中间的内容区
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
                    onInputChanged = viewModel::onInputTextChanged,
                    onSendClicked = {
                        viewModel.sendMessage(inputText)
                        keyboardController?.hide()
                    }
                )
            },
            containerColor = Color.White
        ) { paddingValues ->
            // 聊天内容长列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color.White),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 遍历每一个“对话轮次”（一轮包含一条用户提问和一条或多条 AI 回复）
                items(turns) { turn ->
                    // 渲染用户发送的提问气泡
                    ChatBubble(message = turn.userMessage)

                    Spacer(modifier = Modifier.height(16.dp))

                    // 如果 AI 已经开始回复，则渲染 AI 的回答区域
                    if (turn.aiMessages.isNotEmpty()) {
                        // 确定当前应该展示 AI 的哪一个版本的回答（用于多版本切换功能）
                        val displayIndex = indexOverrides[turn.groupId] ?: turn.currentDisplayIndex
                        val displayedAiMsg = turn.aiMessages[displayIndex]

                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            // 渲染 AI 的回答气泡（支持 Markdown 格式）
                            ChatBubble(message = displayedAiMsg)

                            // 渲染气泡下方的一排操作按钮（复制、点赞、播报、切换版本等）
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

                            // 如果大模型生成了相关追问问题，在此处渲染追问按钮
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
        }
    }
}

/**
 * 顶部导航栏组件
 * 包含返回按钮、当前对话标题，以及右上角的全局自动语音播报开关。
 *
 * @param isAutoSpeakEnabled 当前全局自动播报是否已开启
 * @param onToggleAutoSpeak 用户点击喇叭图标时触发的回调，用于切换播报状态
 */
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
        // 左侧返回按钮
        IconButton(onClick = { }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上一页", tint = Color.Black)
        }

        // 中间标题区域
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "新对话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Text(text = "内容由 AI 生成", fontSize = 12.sp, color = Color.Gray)
        }

        // 右侧功能图标区域
        Row {
            IconButton(onClick = { }) {
                Icon(Icons.Default.Call, contentDescription = "语音通话", tint = Color.Black)
            }
            // 自动语音播报总开关：根据状态动态决定显示的图标和颜色（开启为蓝色实心喇叭，关闭为黑色静音喇叭）
            IconButton(onClick = onToggleAutoSpeak) {
                val icon = if (isAutoSpeakEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff
                val tint = if (isAutoSpeakEnabled) Color.Blue else Color.Black
                Icon(icon, contentDescription = "自动语音播报开关", tint = tint)
            }
        }
    }
}

/**
 * AI 消息气泡底部的交互操作栏
 * 整合了各种对当前回答的操作，包括：复制文本、反馈质量（赞/踩）、手动语音播报、多版本回复翻页以及重新生成。
 */
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
    // 获取系统剪贴板管理器，用于实现一键复制功能
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧功能按钮组（复制、赞、踩、朗读）
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // 复制按钮
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "复制内容",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp).clickable {
                    clipboardManager.setText(AnnotatedString(message.content))
                }
            )

            // 点赞按钮：若状态为已点赞，则高亮显示为蓝色
            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = "有帮助",
                tint = if (message.interactionStatus == 1) Color.Blue else Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onLike() }
            )

            // 点踩按钮：若状态为已点踩，则高亮显示为红色
            Icon(
                imageVector = Icons.Default.ThumbDown,
                contentDescription = "无帮助",
                tint = if (message.interactionStatus == 2) Color.Red else Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onDislike() }
            )

            // 手动单次播报按钮：用户可以随时点击此按钮让 TTS 引擎读出当前的这段回答
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "朗读当前回答",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onSpeakClicked() }
            )
        }

        // 右侧版本控制与重试机制按钮组
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // 如果该提问下有多个版本的回答，展示翻页器
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

            // 若不是最后一个版本，展示下一页按钮；若是最后一个版本，展示“重新生成”刷新按钮
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

/**
 * 关联追问标签（Chip）组件
 * 渲染在 AI 回答下方，展示大模型推测用户可能感兴趣的后续问题。
 * 点击该标签后，等同于用户手动在输入框输入该问题并点击了发送。
 */
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
                // 点击快捷追问后，主动收起键盘以让出屏幕空间
                keyboardController?.hide()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = Color.Blue, fontSize = 13.sp)
    }
}

/**
 * 基础聊天气泡组件
 * 这是一个核心组件，负责区分并绘制用户消息和 AI 消息的不同外观。
 * * 渲染策略：
 * 1. 用户消息：背景浅蓝色，居右侧展示。由于用户输入通常为纯文本，此处采用原生的 Text 组件以保证最高性能。
 * 2. AI 消息：背景灰白色，居左侧展示。AI 生成的回答可能包含标题、加粗、列表等格式，因此此处接入第三方 Markdown 组件进行富文本解析。
 */
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
                // 注入自定义排版，限制联网搜索到的标题在markdown渲染后的大小
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
 * 底部输入栏组件
 * 提供一个带有背景的容器，内部包含一个文本输入框以及状态动态切换的操作图标集合。
 *
 * 交互逻辑：
 * - 当输入框为空时，右侧展示麦克风和加号（扩展菜单）图标。
 * - 当用户开始输入内容时，右侧图标会平滑过渡为一个蓝色的发送按钮。
 */
@Composable
private fun ChatBottomBar(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSendClicked: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            // 自动避让 Android 系统的底部导航条，防止输入框被遮挡
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF4F5F7))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 基础无边框输入框
            BasicTextField(
                value = inputText,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = Color.Black, lineHeight = 20.sp),
                cursorBrush = SolidColor(Color.Blue),
                // 限制输入框最大行数，超过后内部支持滑动，避免输入框无限长撑满屏幕
                maxLines = 5,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        // 占位符提示文案
                        if (inputText.isEmpty()) {
                            Text(text = "发消息或按住说话...", color = Color.Gray, fontSize = 15.sp)
                        }
                        // 真正的输入框宿主
                        innerTextField()
                    }
                }
            )

            // 右侧动态图标区域：依据是否已输入文本进行分支渲染
            val iconPadding = Modifier.padding(bottom = 2.dp)
            if (inputText.isNotEmpty()) {
                // 输入不为空时：展示圆形的发送按钮
                Box(
                    modifier = iconPadding
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Blue)
                        .clickable { onSendClicked() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送消息", tint = Color.White, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                }
            } else {
                // 输入为空时：展示语音输入和附加功能面板唤起图标
                Box(
                    modifier = iconPadding.size(28.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "语音输入", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = iconPadding.size(28.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "更多功能", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
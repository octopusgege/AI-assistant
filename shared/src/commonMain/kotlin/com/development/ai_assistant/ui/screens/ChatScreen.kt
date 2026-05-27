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
import org.koin.compose.koinInject

/**
 * 聊天对话主界面
 * 负责渲染对话流、处理用户输入及协调软键盘交互状态
 */
class ChatScreen : Screen {

    @Composable
    override fun Content() {
        val viewModel = koinInject<ChatViewModel>()
        val turns by viewModel.conversationTurns.collectAsState()
        val indexOverrides by viewModel.displayIndexOverrides.collectAsState()
        val inputText by viewModel.inputText.collectAsState()

        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()

        // 监听列表滚动状态，当用户主动滑动查看历史消息时收起软键盘
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                keyboardController?.hide()
            }
        }

        // 监听对话轮次变化，自动将视图滚动至最新消息的底部
        val lastTurnContent = turns.lastOrNull()?.aiMessages?.lastOrNull()?.content
        LaunchedEffect(turns.size, lastTurnContent) {
            if (turns.isNotEmpty()) {
                listState.scrollToItem(turns.size - 1)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = { ChatTopBar() },
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color.White),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(turns) { turn ->
                    // 用户提问视图
                    ChatBubble(message = turn.userMessage)

                    Spacer(modifier = Modifier.height(16.dp))

                    //  AI 响应视图及其关联的交互组件
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
                                onDislike = { viewModel.onInteractionClicked(displayedAiMsg.id, displayedAiMsg.interactionStatus, 2) }
                            )

                            // 仅在当前展示为最新版本回复时，展示关联的追问建议列表
                            if (displayIndex == turn.aiMessages.size - 1 && displayedAiMsg.followUpQuestions.isNotEmpty()) {
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
 * AI 消息交互操作栏
 * 复制、评价（赞/踩）、多版本回复切换功能
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
    onDislike: () -> Unit
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

/**
 * 追问标签组件
 *
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
                keyboardController?.hide()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = Color.Blue, fontSize = 13.sp)
    }
}

/**
 * 基础聊天气泡组件
 * 根据消息发送方身份（用户/AI）调整背景色
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
            Text(
                text = message.content,
                color = Color.Black,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

/**
 * 顶部导航栏组件
 */
@Composable
private fun ChatTopBar() {
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
            IconButton(onClick = { }) {
                Icon(Icons.Default.NotificationsOff, contentDescription = "通知设置", tint = Color.Black)
            }
        }
    }
}

/**
 * 底部输入栏
 * 处理文本输入、发送逻辑及输入入口
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
            if (inputText.isNotEmpty()) {
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
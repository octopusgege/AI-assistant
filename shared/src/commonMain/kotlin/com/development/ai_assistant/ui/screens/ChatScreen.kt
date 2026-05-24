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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import com.development.ai_assistant.domain.model.Message
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import org.koin.compose.koinInject

class ChatScreen : Screen {

    @Composable
    override fun Content() {
        val viewModel = koinInject<ChatViewModel>()
        val messages by viewModel.messages.collectAsState()
        val inputText by viewModel.inputText.collectAsState()

        val keyboardController = LocalSoftwareKeyboardController.current
        val listState = rememberLazyListState()

        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                keyboardController?.hide()
            }
        }

        val lastMessageContent = messages.lastOrNull()?.content
        LaunchedEffect(messages.size, lastMessageContent) {
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        }

        Scaffold(
            // 给整个脚手架加上 imePadding()
            // 键盘弹出时，Scaffold 的底部会被顶起，但 TopBar 依然牢牢钉在屏幕最顶部
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            topBar = { ChatTopBar() },
            bottomBar = {
                ChatBottomBar(
                    inputText = inputText,
                    onInputChanged = viewModel::onInputTextChanged,
                    onSendClicked = {
                        viewModel.sendMessage()
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
                items(messages) { msg ->
                    ChatBubble(message = msg)
                }
            }
        }
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
            Text(
                text = message.content,
                color = Color.Black,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

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
        IconButton(onClick = { }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.Black) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("新对话", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            Text("内容由 AI 生成", fontSize = 12.sp, color = Color.Gray)
        }
        Row {
            IconButton(onClick = { }) { Icon(Icons.Default.Call, "电话", tint = Color.Black) }
            IconButton(onClick = { }) { Icon(Icons.Default.NotificationsOff, "静音", tint = Color.Black) }
        }
    }
}

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
            // 保证键盘动画不卡顿，这里同时加上 navigationBarsPadding
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
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = Color.Black, lineHeight = 20.sp),
                cursorBrush = SolidColor(Color.Blue),
                maxLines = 5,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (inputText.isEmpty()) {
                            Text("发消息或按住说话...", color = Color.Gray, fontSize = 15.sp)
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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).padding(start = 2.dp)
                    )
                }
            } else {
                Box(modifier = iconPadding.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, "语音", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = iconPadding.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, "更多", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
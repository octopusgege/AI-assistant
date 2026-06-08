/**
 * 文件名：ChatScreen.kt
 * 功能描述：智能对话核心视图层 (Presentation Layer)。
 * 核心作用：作为 MVI 架构的 View 节点，负责监听 ViewModel 的状态变更并驱动声明式 UI 渲染。
 * 业务用途：承载用户多模态输入（文本、语音、图片）与大模型流式响应结果的展示。
 */
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
 * 聊天主屏幕类，实现 Voyager 路由库的 Screen 接口。
 */
class ChatScreen : Screen {

    /**
     * 构建核心界面，组装 Scaffold 及底层状态监听网络。
     */
    @Composable
    override fun Content() {
        // 通过 Koin 依赖注入容器，获取具有对应生命周期的 ViewModel 实例
        val viewModel = koinInject<ChatViewModel>()

        // ---------------------------------------------------------------------
        // 状态订阅区 (State Observers)：将 ViewModel 的单向数据流转化为 UI 状态
        // ---------------------------------------------------------------------

        // 全量对话轮次数据，驱动中间瀑布流列表渲染
        val turns by viewModel.conversationTurns.collectAsState()

        // 多版本回退显示索引，维护每个对话组当前应显示的回答版本
        val indexOverrides by viewModel.displayIndexOverrides.collectAsState()

        // 用户输入框内的实时文本状态
        val inputText by viewModel.inputText.collectAsState()

        // 全局语音自动播报功能的开关状态
        val isAutoSpeakEnabled by viewModel.isAutoSpeakEnabled.collectAsState()

        // 麦克风物理硬件是否处于监听占用的状态标志
        val isListening by viewModel.isListening.collectAsState()

        // 大模型部署环境隔离标志 (True = 本地端侧离线模型, False = 远端云节点)
        val isLocalModelEnabled by viewModel.isLocalModelEnabled.collectAsState()

        // ---------------------------------------------------------------------
        // 本地环境依赖与内部状态区 (Local Dependencies & States)
        // ---------------------------------------------------------------------

        // 系统软键盘控制器，用于在滚动或发生特定交互时强制收起键盘
        val keyboardController = LocalSoftwareKeyboardController.current

        // 瀑布流列表状态，维护滚动位置、偏移量等物理属性
        val listState = rememberLazyListState()

        // 自动滚动锚定开关，控制在大模型流式输出时是否强制 UI 追尾最新行
        var autoScrollEnabled by remember { mutableStateOf(true) }

        // 多图交互缓冲堆栈，持有用户从系统相册选取返回的图片二进制数据
        var selectedImages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }

        // 绑定当前 Composable 生命周期的协程作用域
        val scope = rememberCoroutineScope()

        // ---------------------------------------------------------------------
        // 外部能力桥接器 (External Capability Bridges)
        // ---------------------------------------------------------------------

        // 初始化跨端图库选择器 (Peekaboo)
        // 约束：最高同时选择 4 张图片，防范底层转换 OOM
        val imagePicker = rememberImagePickerLauncher(
            selectionMode = SelectionMode.Multiple(maxSelection = 4),
            scope = scope,
            onResult = { byteArrays ->
                // 底层相册回调处理：采用左侧压入，强制右侧抛弃超出极值 (4) 的元素
                if (byteArrays.isNotEmpty()) {
                    val currentList = selectedImages.toMutableList()
                    currentList.addAll(byteArrays)
                    selectedImages = currentList.take(4)
                }
            }
        )

        // ---------------------------------------------------------------------
        // 副作用与生命周期观测区 (Side Effects & Lifecycle Observers)
        // ---------------------------------------------------------------------

        // 物理滚动拦截机制：当探测到用户主动拖拽干预列表时，中断流式自动追尾并收拢软键盘
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                autoScrollEnabled = false
                keyboardController?.hide()
            }
        }

        // 会话轮次膨胀拦截：当产生全新的问答轮次时，重置自动追尾锁，允许页面跟随新消息滚动
        val turnCount = turns.size
        LaunchedEffect(turnCount) {
            autoScrollEnabled = true
        }

        // 推流动态锚定器：观测最后一条 AI 消息的内容变动轨迹，持续驱动视图位置重绘
        val lastTurnContent = turns.lastOrNull()?.aiMessages?.lastOrNull()?.content
        LaunchedEffect(lastTurnContent) {
            if (turns.isNotEmpty()) {
                // 如果列表已经触底，允许恢复自动滚动
                if (!listState.canScrollForward) {
                    autoScrollEnabled = true
                }
                // 执行强制滚动至表尾索引操作
                if (autoScrollEnabled) {
                    listState.scrollToItem(turns.size - 1, scrollOffset = 32767)
                }
            }
        }

        // ---------------------------------------------------------------------
        // 视图渲染树 (UI Composition Tree)
        // ---------------------------------------------------------------------

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                ChatTopBar(
                    isAutoSpeakEnabled = isAutoSpeakEnabled,
                    isLocalModelEnabled = isLocalModelEnabled,
                    onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
                    onToggleEngineMode = { viewModel.toggleEngineMode() }
                )
            },
            bottomBar = {
                ChatBottomBar(
                    inputText = inputText,
                    isListening = isListening,
                    selectedImages = selectedImages,
                    onInputChanged = viewModel::onInputTextChanged,
                    onSendClicked = {
                        // 构建并派发核心交互体数据
                        viewModel.sendMessage(inputText, selectedImages)
                        // 手动触发垃圾回收标记：抛弃已推入底层的瞬态大内存图片缓存
                        selectedImages = emptyList()
                        keyboardController?.hide()
                    },
                    onMicPress = viewModel::startVoiceInput,
                    onMicRelease = viewModel::stopAndSendVoiceInput,
                    // 点击+号时，启动相册选择器
                    onLaunchImagePicker = { imagePicker.launch() },
                    //删除选中的图片
                    onClearImage = { indexToRemove ->
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

                // 主对话层：呈现所有时序历史块的瀑布流
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(Color.White),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(turns) { turn ->
                        // 1. 渲染：客户端用户提交的指令气泡
                        ChatBubble(message = turn.userMessage)
                        Spacer(modifier = Modifier.height(16.dp))

                        // 2. 渲染：服务端或端侧响应的推流气泡
                        if (turn.aiMessages.isNotEmpty()) {
                            // 校验版本索引表，提取当前所需展示的历史覆盖版本
                            val displayIndex = indexOverrides[turn.groupId] ?: turn.currentDisplayIndex
                            val displayedAiMsg = turn.aiMessages[displayIndex]

                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                // 2.1 渲染气泡载体主体 (包含 Markdown 引擎)
                                ChatBubble(message = displayedAiMsg)

                                // 2.2 挂载底层状态控制扩展坞 (复制/重试/评估等功能集)
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

                                // 2.3 渲染大模型基于当前上下文推断生成的动态追问胶囊组
                                if (displayedAiMsg.followUpQuestions.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    displayedAiMsg.followUpQuestions.forEach { question ->
                                        FollowUpChip(
                                            text = question,
                                            onClick = { viewModel.sendMessage(question) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 遮罩层：高优状态覆盖。在 STT(声学) 监听阶段挂载全覆盖阻断态视觉层
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

/**
 * 顶部全局环境控制器组件。
 * * 功能描述：提供应用层级状态反馈，明确当前模型计算的源网域，并暴露配置热切换入口。
 * @param isAutoSpeakEnabled 当前全局 TTS 播报开关状态
 * @param isLocalModelEnabled 当前引擎源策略状态 (本地/云端)
 * @param onToggleAutoSpeak 开关播报事件回调
 * @param onToggleEngineMode 切换引擎环境事件回调
 */
@Composable
private fun ChatTopBar(
    isAutoSpeakEnabled: Boolean,
    isLocalModelEnabled: Boolean,
    onToggleAutoSpeak: () -> Unit,
    onToggleEngineMode: () -> Unit
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
        IconButton(onClick = { /* 视情况接入 Voyager.pop 路由栈销毁逻辑 */ }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "新对话",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            // 实时节点状态上报
            Text(
                text = if (isLocalModelEnabled) "端侧大模型" else "远程大模型",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        Row {
            IconButton(onClick = onToggleEngineMode) {
                val icon = if (isLocalModelEnabled) Icons.Default.PhoneAndroid else Icons.Default.CloudQueue
                val tint = if (isLocalModelEnabled) Color(0xFF00C853) else Color.Blue
                Icon(icon, contentDescription = "切换底层模型调度引擎", tint = tint)
            }
            IconButton(onClick = onToggleAutoSpeak) {
                val icon = if (isAutoSpeakEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff
                val tint = if (isAutoSpeakEnabled) Color.Blue else Color.Black
                Icon(icon, contentDescription = "全局声学播报阻断开关", tint = tint)
            }
        }
    }
}

/**
 * 单条会话对象附属操作面板组件。
 * * 功能描述：挂载于单体 AI 会话气泡底部，提供细粒度的功能入口（如点赞/踩/播报/翻页操作）。
 * 参数说明略去基础传参，直接映射 ViewModel 中的业务逻辑回调。
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
    // 注入平台层原生剪贴板管理器
    val clipboardManager = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：核心工具集
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "提取节点内容至剪贴板",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp).clickable {
                    clipboardManager.setText(AnnotatedString(message.content))
                }
            )

            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = "上报正向体验评估反馈",
                tint = if (message.interactionStatus == 1) Color.Blue else Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onLike() }
            )

            Icon(
                imageVector = Icons.Default.ThumbDown,
                contentDescription = "上报负向阻击体验反馈",
                tint = if (message.interactionStatus == 2) Color.Red else Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onDislike() }
            )

            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = "即时呼出声学传感器针对当前块进行单例播报",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp).clickable { onSpeakClicked() }
            )
        }

        // 右侧：多版本生成结果轮询控制台
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (totalVersions > 1) {
                Text(text = "${currentVersionIndex + 1} / $totalVersions", color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = "<",
                    color = if (currentVersionIndex > 0) Color.Black else Color.LightGray,
                    modifier = Modifier.clickable(enabled = currentVersionIndex > 0) { onPreviousVersion() }
                )
            }

            if (currentVersionIndex < totalVersions - 1) {
                Text(text = ">", color = Color.Black, modifier = Modifier.clickable { onNextVersion() })
            } else {
                // 若处于链表尾部，暴露出重建推流管线的入口
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "废弃当前结果并重建底层推流管线",
                    tint = Color.Blue,
                    modifier = Modifier.size(20.dp).clickable { onRegenerate() }
                )
            }
        }
    }
}

/**
 * 启发式追问快捷按钮组件。
 * * @param text 建议问题文本
 * @param onClick 发起快捷询问回调
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
                // 防御性操作：避免点击后键盘维持前台导致遮挡视图流
                keyboardController?.hide()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text = text, color = Color.Blue, fontSize = 13.sp)
    }
}

/**
 * 底层会话渲染器组件。
 * * 功能描述：提供视图级的视觉呈现，隔离用户气泡与 AI 气泡的样式，并在此接管 Markdown AST 构建。
 * @param message 标准化业务消息体实体
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
                // 客户端请求载荷为纯文本，降低渲染层开销
                Text(text = message.content, color = Color.Black, fontSize = 15.sp, lineHeight = 22.sp)
            } else {
                // 服务端/端侧生成数据需利用 M3 架构下的 Markdown 引擎执行富文本规则树排版
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
 * 复合多模态信息采集底层组件。
 * * 功能描述：高集成度的底操作栏，统一接管键盘事件、图册调用队列、和物理手势(如长按触发 STT 硬件层)。
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
    // 组件级下拉菜单显示控制状态
    var showAddMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        // ---------------------------------------------------------------------
        // 视觉数据缓冲区视图 (Image Queue Rendering Block)
        // ---------------------------------------------------------------------
        AnimatedVisibility(visible = selectedImages.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(selectedImages) { index, bytes ->
                    Box {
                        // 承载底层 ByteArray，并转换为可供 Compose 渲染的 ImageBitmap 实例
                        Image(
                            bitmap = bytes.toImageBitmap(),
                            contentDescription = "处理中的队列图像内存块序列 $index",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )

                        // 创建悬停层：销毁指定数组索引位置的内存块
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
                            Icon(Icons.Default.Close, contentDescription = "解绑并释放图像块内存", tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // 核心信息录入终端 (Input Terminal & Controllers Block)
        // ---------------------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFFF4F5F7))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {

            // 文本捕获器，将软键盘产生的变动事件委托给 ViewModel 处理
            BasicTextField(
                value = inputText,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = Color.Black, lineHeight = 20.sp),
                cursorBrush = SolidColor(Color.Blue),
                maxLines = 5,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        // 占位空态视图
                        if (inputText.isEmpty()) {
                            Text(text = "向大模型分发指令集或请求...", color = Color.Gray, fontSize = 15.sp)
                        }
                        innerTextField()
                    }
                }
            )

            val iconPadding = Modifier.padding(bottom = 2.dp)

            // 语义防呆互斥判定：当检测到合法字符缓冲/图像内存块注入，且非硬件监听挂起态时，切换为发射通道
            if ((inputText.isNotEmpty() || selectedImages.isNotEmpty()) && !isListening && inputText != "正在聆听..." && !inputText.startsWith("[")) {
                Box(
                    modifier = iconPadding
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Blue)
                        .clickable { onSendClicked() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "向业务总线推送当前参数集合", tint = Color.White, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                }
            } else {
                // 处于空载环境时，暴露出多模态硬件通道 (STT/外接设备读写)

                // 麦克风控制域：接管底层的 Pointer 物理触控事件模拟
                Box(
                    modifier = iconPadding.size(28.dp).clip(CircleShape).background(if (isListening) Color.Blue else Color.White)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onMicPress()
                                    tryAwaitRelease() // 持续挂起线程上下文直至物理触控传感器释放
                                    onMicRelease()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "压栈激活底层声学监听管道", tint = if (isListening) Color.White else Color.Black, modifier = Modifier.size(18.dp))
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 扩展坞控制域
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = iconPadding.size(28.dp).clip(CircleShape).background(Color.White)
                            .clickable { showAddMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "展示多模态指令扩充坞", tint = Color.Black, modifier = Modifier.size(18.dp))
                    }

                    // 环境拓展项面板
                    DropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = { showAddMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🖼️ 相册") },
                            onClick = {
                                showAddMenu = false
                                onLaunchImagePicker() // 拉起原生系统级进程交互
                            }
                        )
                    }
                }
            }
        }
    }
}
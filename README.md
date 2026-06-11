# AI Assistant (端云双擎智能助手)

这是一个基于 Android 平台的单体架构智能对话应用。项目最大的亮点在于实现了**“端云双擎”**算力架构：在有网环境下，可通过网络调用阿里云百炼 API 提供强大的多模态（图文）推理能力；在无网或隐私敏感环境下，可通过底层 JNI 调用 `llama.cpp` 引擎，利用手机本地算力运行量化后的大语言模型（如 Qwen2.5-0.5B），实现完全离线的对话交互。

## ✨ 核心特性

* **端云双擎热切换**
  * **云端模式 (Remote):** 基于 Ktor 实现 SSE (Server-Sent Events) 长连接，接入阿里云百炼平台，支持多模态（图片上传 + 文本）流式推流。
  * **端侧模式 (Local):** 深度集成 `llama.cpp`，通过 JNI 跨语言桥接，支持在 Android 本地加载并运行 `.gguf` 格式的离线大模型。
* **原生语音交互 (Voice I/O)**
  * **STT (语音转文本):** 接入 Android 原生 SpeechRecognizer，支持长按录音与增量文字上屏。
  * **TTS (文本转语音):** 结合大模型流式输出特性，通过正则表达式进行标点符号断句，实现“边想边读”的分片连读播报。
* **极简流畅的 UI 交互**
  * 采用 **MVI 架构** (Model-View-Intent)，通过 `StateFlow` 实现单向数据流闭环，彻底解耦视图与逻辑。
  * 配合 Jetpack Compose 构建打字机渲染效果与平滑的列表滚动体验。
* **可靠的本地会话管理**
  * 采用单体数据库（SQLite）进行消息持久化。
  * 设计 `groupId` 会话绑定机制，支持对同一问题进行多次重新生成，并通过 UI 左右滑动无缝切换历史回答版本。
* **端侧性能压测工具**
  * 内置 `LLMBenchmarkRunner`，自动捕获端侧模型推理的首字延时 (TTFT) 与解码吞吐率 (Tokens/sec)，并自动将性能报告导出至应用沙盒。

## 🛠️ 技术栈

* **UI 层:** Kotlin, Jetpack Compose, Voyager (路由导航)
* **架构层:** MVI, Coroutines, Flow (StateFlow / CallbackFlow)
* **网络与注入:** Ktor (HTTP & SSE), Koin (依赖注入)
* **底层与硬件:** JNI, C/C++ (`llama.cpp`), Android TextToSpeech, SpeechRecognizer
* **存储层:** SQLite 单体数据库

## 📂 架构设计说明

本项目采用纯单体架构，按业务逻辑划分为清晰的层级，并确保高频数据流写入时不阻塞 UI 主线程：
1. **表现层 (Presentation):** 捕获用户意图 (Intent) 并订阅状态 (State)。
2. **逻辑中枢 (ViewModel):** 负责端云策略路由、节流控制 (Throttling)、降级处理（如端侧拦截图片解析）。
3. **引擎实现层 (Engine):** 抽象 `LLMEngine` 接口，分别实现网络流解析与本地 JNI 回调。
4. **底层算力层 (Native):** C++ 端负责内存检测，遇 OOM 风险时主动清空一半 KV Cache 进行静默重算（滑动窗口）。


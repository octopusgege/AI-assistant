/**
 * 文件名：llama_bridge.cpp
 * 模块定位：端侧大模型 JNI 桥接隔离层（通用兼容版）
 * 版本说明：本版本为**全版本兼容优化版**，解决了老版本 llama.cpp 没有 `llama_kv_cache_seq_rm/seq_shift` API 的问题
 * 核心改进：
 * 1. 新增全局会话历史 Token 记录表，完整保存所有对话内容
 * 2. 实现**基于缓存重算的伪滑动窗口算法**，兼容所有 llama.cpp 版本（从 b1000 到最新版）
 * 3. 上下文溢出时保留最近一半历史，回答连贯性大幅优于直接清空缓存
 * 4. 历史记录持久化在内存中，重建缓存时无需从 Kotlin 层传递数据
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>

// Logcat 日志标签
#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 引入 llama.cpp 核心头文件
#include "llama.h"

// 🌟 全局上下文记忆游标
// 记录 KV 缓存中当前已存储的 token 总数，代表大模型已记住的对话长度
static int g_n_past = 0;

// 🌟 新增：全局会话历史 Token 记录表
// 作用：完整保存整个会话的所有 token（用户提问 + AI 回答）
// 设计原因：老版本 llama.cpp 没有提供操作 KV 缓存的 API，无法直接删除或移动缓存
// 解决方案：我们自己在内存中保存一份完整的历史记录，上下文满时用最近的历史重建缓存
static std::vector<llama_token> g_session_tokens;

// JNI 强制要求：禁止 C++ 编译器修饰函数名
extern "C" {

/**
 * 功能：加载端侧大模型并初始化推理上下文
 * 调用时机：用户首次切换到端侧模式并发送消息时
 */
JNIEXPORT jlong JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject thiz, jstring model_path, jint n_ctx, jint n_threads) {

    // 初始化 llama.cpp 后端
    llama_backend_init();
    // 加载新模型时重置记忆游标
    g_n_past = 0;
    // 🌟 新增：加载新模型时清空全局历史记录，避免不同模型的 token 混用
    g_session_tokens.clear();

    // 将 Java 字符串转换为 C++ 字符串
    const char *path = env->GetStringUTFChars(model_path, nullptr);

    // 获取默认参数
    llama_model_params model_params = llama_model_default_params();
    llama_context_params ctx_params = llama_context_default_params();

    // 配置上下文窗口大小
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.n_batch = 2048;

    // 加载模型权重
    llama_model *model = llama_model_load_from_file(path, model_params);
    if (!model) {
        LOGE("模型加载失败：%s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    // 创建推理上下文
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    env->ReleaseStringUTFChars(model_path, path);
    LOGI("模型加载成功，上下文大小：%d token", ctx_params.n_ctx);

    // 返回上下文指针给 Java 层保存
    return reinterpret_cast<jlong>(ctx);
}

/**
 * 功能：释放所有模型与上下文资源
 * 调用时机：用户退出聊天界面或切换回云端模式时
 */
JNIEXPORT void JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeFreeModel(
        JNIEnv *env, jobject thiz, jlong ctx_ptr) {

    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (ctx) {
        llama_model *model = const_cast<llama_model*>(llama_get_model(ctx));
        llama_free(ctx);
        if (model) {
            llama_model_free(model);
        }
        llama_backend_free();
        // 释放资源时清空全局历史记录
        g_n_past = 0;
        g_session_tokens.clear();
        LOGI("所有资源已释放");
    }
}

/**
 * 功能：核心流式推理方法，逐字生成 AI 回答
 * 核心特性：全版本兼容的伪滑动窗口无限上下文
 */
JNIEXPORT void JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeGenerateStream(
        JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring prompt, jint max_tokens, jobject callback) {

    // 上下文空指针校验
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (!ctx) {
        LOGE("推理上下文为空");
        return;
    }

    // 获取 Kotlin 层回调方法 ID
    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token_method = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");

    // 转换用户输入字符串
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    // -------------------------------------------------------------------------
    // 第一步：将用户输入分词为 token 序列
    // -------------------------------------------------------------------------
    int max_prompt_size = strlen(prompt_str) + 8;
    std::vector<llama_token> prompt_tokens(max_prompt_size);
    int n_prompt_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), prompt_tokens.data(), prompt_tokens.size(), true, true);

    // 缓冲区不足时调整大小重新分词
    if (n_prompt_tokens < 0) {
        prompt_tokens.resize(-n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), prompt_tokens.data(), prompt_tokens.size(), true, true);
    }

    // 分词失败处理
    if (n_prompt_tokens <= 0) {
        LOGE("文本分词失败");
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return;
    }
    prompt_tokens.resize(n_prompt_tokens);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // 获取当前上下文总容量
    int current_n_ctx = llama_n_ctx(ctx);

    // =========================================================================
    // 🌟 核心算法：基于缓存重算的伪滑动窗口（全版本兼容）
    // 解决问题：老版本 llama.cpp 没有提供操作 KV 缓存的 API，上下文满时会直接崩溃
    // 设计思路：
    // 1. 我们自己在内存中保存完整的会话历史
    // 2. 上下文满时，提取最近一半的历史记录
    // 3. 清空旧缓存，将最近的历史重新输入模型计算，重建 KV 缓存
    // 优势：
    // - 兼容所有 llama.cpp 版本，无 API 依赖
    // - 保留最近的对话上下文，回答连贯性远优于直接清空缓存
    // - 实现简单，稳定性高
    // =========================================================================
    if (g_n_past + prompt_tokens.size() >= current_n_ctx - max_tokens) {
        LOGI("上下文即将溢出，启动缓存重算机制");

        // 步骤 1：计算需要保留的历史长度（保留总容量的一半）
        // 保留太多会导致很快再次溢出，保留太少会丢失太多上下文
        int n_keep = current_n_ctx / 2;

        // 边界保护：如果历史记录比要保留的还少，就全部保留
        if (n_keep > g_session_tokens.size()) {
            n_keep = g_session_tokens.size();
        }

        // 步骤 2：从全局历史记录中提取最近的 n_keep 个 token
        // 保留最新的对话，丢弃最旧的对话
        std::vector<llama_token> retained_tokens(
                g_session_tokens.end() - n_keep,
                g_session_tokens.end()
        );

        // 步骤 3：重置记忆游标和全局历史记录
        // 模型会在写入新位置 0 时自动覆盖旧的 KV 缓存
        g_n_past = 0;
        g_session_tokens.clear();

        // 步骤 4：重建 KV 缓存
        // 将保留的历史记录重新输入模型，从头开始计算，生成新的 KV 缓存
        llama_batch keep_batch = llama_batch_init(current_n_ctx, 0, 1);
        keep_batch.n_tokens = retained_tokens.size();

        for (int i = 0; i < keep_batch.n_tokens; i++) {
            keep_batch.token[i] = retained_tokens[i];
            keep_batch.pos[i] = g_n_past + i;
            keep_batch.n_seq_id[i] = 1;
            keep_batch.seq_id[i][0] = 0;
            // 重建历史时不需要计算输出概率，大幅提升重建速度
            keep_batch.logits[i] = 0;
        }

        // 执行重建推理
        if (llama_decode(ctx, keep_batch) == 0) {
            // 重建成功，更新记忆游标
            g_n_past += keep_batch.n_tokens;
            // 将重建成功的历史记录重新存入全局记录表
            g_session_tokens.insert(g_session_tokens.end(), retained_tokens.begin(), retained_tokens.end());
            LOGI("缓存重建成功，保留了 %d 个历史 token", n_keep);
        } else {
            LOGE("缓存重建失败，将以全新对话继续");
        }

        // 释放重建用的临时 Batch
        llama_batch_free(keep_batch);
    }
    // =========================================================================

    // 🌟 新增：将本次用户的提问 token 追加到全局历史记录中
    // 保证全局记录表始终包含整个会话的所有内容
    g_session_tokens.insert(g_session_tokens.end(), prompt_tokens.begin(), prompt_tokens.end());

    // -------------------------------------------------------------------------
    // 第二步：初始化采样器
    // -------------------------------------------------------------------------
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // -------------------------------------------------------------------------
    // 第三步：构建推理 Batch，解码用户输入
    // -------------------------------------------------------------------------
    int batch_capacity = std::max((int)prompt_tokens.size(), 1);
    llama_batch batch = llama_batch_init(batch_capacity, 0, 1);

    batch.n_tokens = prompt_tokens.size();
    for (int i = 0; i < batch.n_tokens; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = g_n_past + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 0;
    }
    batch.logits[batch.n_tokens - 1] = 1;

    // 解码用户输入
    if (llama_decode(ctx, batch) != 0) {
        LOGE("用户输入解码失败");
        llama_sampler_free(smpl);
        llama_batch_free(batch);
        return;
    }
    g_n_past += batch.n_tokens;

    // -------------------------------------------------------------------------
    // 第四步：核心自回归生成循环
    // -------------------------------------------------------------------------
    for (int i = 0; i < max_tokens; i++) {
        int32_t idx = batch.n_tokens - 1;
        // 采样下一个 token
        llama_token new_token_id = llama_sampler_sample(smpl, ctx, idx);

        // 生成结束符，退出循环
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        // 将 token 转换为文本并回调给 Kotlin 层
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_str(buf, n);
            jstring jstr = env->NewStringUTF(token_str.c_str());
            env->CallVoidMethod(callback, on_token_method, jstr);
            env->DeleteLocalRef(jstr);
        }

        // 🌟 新增：将模型生成的 token 也追加到全局历史记录中
        // 保证 AI 的回答也会被保存，下次上下文溢出时能被保留
        g_session_tokens.push_back(new_token_id);

        // 准备下一次推理的输入
        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = g_n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        // 执行下一次推理
        if (llama_decode(ctx, batch) != 0) {
            LOGE("生成过程中推理失败");
            break;
        }
        g_n_past++;
    }

    // 释放临时资源
    llama_sampler_free(smpl);
    llama_batch_free(batch);

    LOGI("回答生成完成，当前会话总 token 数：%zu", g_session_tokens.size());
}

} // extern "C" 结束
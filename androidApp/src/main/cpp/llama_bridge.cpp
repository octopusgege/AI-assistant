/**
 * 文件名：llama_bridge.cpp
 * 功能描述：Android JNI 桥接层，连接上层 Kotlin 业务与底层 C++ llama.cpp 推理引擎。
 * 核心职责：模型加载与卸载、文本分词(Tokenization)、张量解码(Decode)、以及通过 JNI 逆向回调实现流式输出。
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>

// -----------------------------------------------------------------------------
// 宏定义区：配置 Android native 层的 Logcat 输出通道，便于底层调试
// -----------------------------------------------------------------------------
#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "llama.h"

// -----------------------------------------------------------------------------
// 全局状态区
// -----------------------------------------------------------------------------
// 记忆游标 (KV Cache 游标)
// 记录当前大模型上下文中已经处理的 Token 数量，用于维持连续对话的上下文记忆。
static int g_n_past = 0;

extern "C" {

/**
 * 方法：加载大模型权重到手机内存。
 * 注意：JNI 函数签名已严格映射至 Kotlin 类的绝对路径：com.development.ai_assistant.domain.ai.LlamaBridge
 *
 * @param model_path 模型在手机本地的绝对路径
 * @param n_ctx 期望分配的上下文最大容量（如 2048）
 * @param n_threads 参与矩阵运算的 CPU 核心线程数
 * @return jlong 返回 llama_context 的内存指针（作为长整型交给 Kotlin 保管）
 */
JNIEXPORT jlong JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject thiz, jstring model_path, jint n_ctx, jint n_threads) {

    // 1. 初始化底层引擎后端
    llama_backend_init();
    // 每次加载新模型时，强制重置记忆游标
    g_n_past = 0;

    // 2. 将 Java 的 String 转换为 C++ 的字符指针
    const char *path = env->GetStringUTFChars(model_path, nullptr);

    // 3. 配置模型参数与上下文参数
    llama_model_params model_params = llama_model_default_params();
    llama_context_params ctx_params = llama_context_default_params();

    // 强行分配上下文空间和线程资源
    ctx_params.n_ctx = 2048; // 固定分配 2048 的上下文窗口
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.n_batch = 2048; // 设置最大批处理大小以匹配上下文

    // 4. 执行物理文件的内存映射 (mmap) 与权重加载
    llama_model *model = llama_model_load_from_file(path, model_params);
    if (!model) {
        LOGE("Failed to load model from: %s", path);
        env->ReleaseStringUTFChars(model_path, path);
        return 0; // 加载失败，向 Kotlin 返回 0L
    }

    // 5. 根据模型创建计算上下文
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    // 6. 释放 C++ 字符串内存防泄漏，并向上层返回结构体指针
    env->ReleaseStringUTFChars(model_path, path);
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

/**
 * 方法：销毁上下文，释放底层 C++ 内存。
 * 防止 Android App 退出或重置大模型时发生内存泄漏 (OOM)。
 */
JNIEXPORT void JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeFreeModel(
        JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    // 将 Kotlin 传来的 Long 类型强转回 C++ 的上下文指针
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (ctx) {
        llama_model *model = const_cast<llama_model*>(llama_get_model(ctx));
        llama_free(ctx);
        if (model) {
            llama_model_free(model);
        }
        llama_backend_free();
        LOGI("Context and Model freed successfully");
    }
}

/**
 * 方法：同步阻塞式生成完整文本（普通请求方式）。
 * 注：项目中主要使用 Stream 流式生成，此方法保留作备用或一次性任务调用。
 */
JNIEXPORT jstring JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring prompt, jint max_tokens) {

    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (!ctx) return env->NewStringUTF("Error: no context");

    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    // 1. 分词 (Tokenization)：将人类文本转化为模型识别的整型 Token 数组
    int max_prompt_size = strlen(prompt_str) + 8;
    std::vector<llama_token> prompt_tokens(max_prompt_size);
    int n_prompt_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), prompt_tokens.data(), prompt_tokens.size(), true, true);

    if (n_prompt_tokens < 0) { // 如果预估空间不足，扩容并重试
        prompt_tokens.resize(-n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), prompt_tokens.data(), prompt_tokens.size(), true, true);
    }

    if (n_prompt_tokens > 0) {
        prompt_tokens.resize(n_prompt_tokens);
    } else {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("[C++ Error: Tokenization failed]");
    }
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // 2. 容量越界保护：拦截超过 2048 长度的对话，防止底层段错误崩溃
    int current_n_ctx = llama_n_ctx(ctx);
    if (g_n_past + prompt_tokens.size() >= current_n_ctx - max_tokens) {
        return env->NewStringUTF("\n\n[系统提示：大模型的上下文记忆容量已满。请重新启动 App 开启新一轮对话！]");
    }

    // 3. 采样器配置：控制生成的随机性与创造力
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f)); // Temperature=0.8
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234)); // 固定随机种子

    // 4. 构建批处理块 (Batch)：将新输入的 Prompt 塞入计算队列
    int batch_capacity = std::max((int)prompt_tokens.size(), 1);
    llama_batch batch = llama_batch_init(batch_capacity, 0, 1);
    batch.n_tokens = prompt_tokens.size();
    for (int i = 0; i < batch.n_tokens; i++) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = g_n_past + i;      // 记录在记忆流中的绝对位置
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 0;              // 历史 Token 不需要计算输出概率
    }
    batch.logits[batch.n_tokens - 1] = 1; // 仅要求网络对最后一个 Token 计算下一个字的概率分布

    // 5. 执行首次前向传播 (KV Cache 填充)
    if (llama_decode(ctx, batch) != 0) {
        llama_sampler_free(smpl);
        llama_batch_free(batch);
        return env->NewStringUTF("[C++ Error: Failed to decode prompt]");
    }
    g_n_past += batch.n_tokens;

    // 6. 循环预测生成序列
    std::string result;
    for (int i = 0; i < max_tokens; i++) {
        int32_t idx = batch.n_tokens - 1;
        // 依据概率分布“抽选”下一个 Token ID
        llama_token new_token_id = llama_sampler_sample(smpl, ctx, idx);

        // 如果抽中停止符(EOG)，生成结束
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        // 反分词：将数字 ID 转换回可读文本并追加至结果集
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) result += std::string(buf, n);

        // 构建单个 Token 的 Batch 用于下一轮推断
        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = g_n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(ctx, batch) != 0) {
            result += "\n[C++ Error: Decode failed during generation]";
            break;
        }
        g_n_past++;
    }

    // 清理临时资源并向 Kotlin 返回完整文本
    llama_sampler_free(smpl);
    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

/**
 * 核心方法：异步流式生成并触发 JNI 逆向回调 (Stream 机制)。
 * 该方法负责每次算出一个字，就通过传入的 Kotlin Callback 接口向上层 UI 推送。
 */
JNIEXPORT void JNICALL
Java_com_development_ai_1assistant_domain_ai_LlamaBridge_nativeGenerateStream(
        JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring prompt, jint max_tokens, jobject callback) {

    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (!ctx) return;

    // -------------------------------------------------------------------------
    // JNI 反射调用预备工作
    // -------------------------------------------------------------------------
    // 1. 获取传入对象 (TokenCallback 实例) 的具体类引用
    jclass cb_class = env->GetObjectClass(callback);
    // 2. 定位类中的方法：寻找名为 "onToken"，参数为 String，返回值为 void(V) 的方法签名
    jmethodID on_token_method = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)V");

    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    const llama_model *model = llama_get_model(ctx);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    // 执行分词
    int max_prompt_size = strlen(prompt_str) + 8;
    std::vector<llama_token> prompt_tokens(max_prompt_size);
    int n_prompt_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), prompt_tokens.data(), prompt_tokens.size(), true, true);
    if (n_prompt_tokens < 0) {
        prompt_tokens.resize(-n_prompt_tokens);
        n_prompt_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), prompt_tokens.data(), prompt_tokens.size(), true, true);
    }
    if (n_prompt_tokens > 0) prompt_tokens.resize(n_prompt_tokens);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    // 容量越界保护机制
    if (g_n_past + prompt_tokens.size() >= llama_n_ctx(ctx) - max_tokens) {
        // 如果记忆溢出，触发回调向 UI 发送警告
        jstring jmsg = env->NewStringUTF("\n\n[记忆已满，请重启App]");
        env->CallVoidMethod(callback, on_token_method, jmsg);
        env->DeleteLocalRef(jmsg);
        return;
    }

    // 初始化采样器链
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // 构建首次 Prompt 张量并解码填充 Cache
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

    if (llama_decode(ctx, batch) != 0) {
        llama_sampler_free(smpl);
        llama_batch_free(batch);
        return;
    }
    g_n_past += batch.n_tokens;

    // -------------------------------------------------------------------------
    // 核心流式循环：计算单个 Token -> JNI 回调 Kotlin -> 计算下一个 Token
    // -------------------------------------------------------------------------
    for (int i = 0; i < max_tokens; i++) {
        int32_t idx = batch.n_tokens - 1;

        // 推理出一个新的 Token ID
        llama_token new_token_id = llama_sampler_sample(smpl, ctx, idx);

        if (llama_vocab_is_eog(vocab, new_token_id)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            std::string token_str(buf, n);

            // 【逆向通讯关键点】将 C++ 字符串包装为 Java 字符串
            jstring jstr = env->NewStringUTF(token_str.c_str());
            // 利用反射得到的方法 ID，正式调用 Kotlin 中的 callback.onToken("...")
            env->CallVoidMethod(callback, on_token_method, jstr);

            // 【极度重要】每次循环必须销毁本次创建的 Java 局部对象引用，否则循环几百次必定 OOM 崩溃！
            env->DeleteLocalRef(jstr);
        }

        // 把刚算出来的单个 Token 作为已知量，喂给引擎推导下一步
        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = g_n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(ctx, batch) != 0) break;
        g_n_past++;
    }

    // 执行完毕，清理内存结构体
    llama_sampler_free(smpl);
    llama_batch_free(batch);
}

} // extern "C" 结束
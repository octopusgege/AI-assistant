cmake_minimum_required(VERSION 3.22.1)
project(llama_assistant)

set(CMAKE_CXX_STANDARD 17)

# 关闭 PC 端测试工具和服务器编译
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# 关闭不需要的加速后端（Android不支持CUDA/OpenBLAS/Metal等）
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

# llama.cpp 在 Android 上的专用选项
set(LLAMA_SUPPORT_UART OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
        llama_cpp
        URL ${CMAKE_CURRENT_SOURCE_DIR}/llama_cpp-master.zip
)
FetchContent_MakeAvailable(llama_cpp)

# 定义我们的桥接库
add_library(llama_bridge SHARED llama_bridge.cpp)

# 链接核心库与安卓日志库
target_link_libraries(llama_bridge PRIVATE llama log)

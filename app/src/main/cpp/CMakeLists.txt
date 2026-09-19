cmake_minimum_required(VERSION 3.22.1)
project(adsh C)

find_library(log-lib log)

# PTY 桥（终端页）
add_library(adshpty SHARED pty_bridge.c)
target_link_libraries(adshpty ${log-lib})

# 写围栏 shim（LD_PRELOAD，见 fence.c 顶部说明）：
# 只跟 libc 打交道，不依赖别的库；-ldl 在安卓上由 libc 提供。
add_library(adshfence SHARED fence.c)
target_link_libraries(adshfence ${log-lib} dl)

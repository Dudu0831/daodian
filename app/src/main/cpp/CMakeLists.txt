# 只有一件事：语音模型放掉之后，让原生堆把空页马上还给系统（见 heap_purge.c）
cmake_minimum_required(VERSION 3.22.1)
project(daodian_heap C)
add_library(daodian_heap SHARED heap_purge.c)

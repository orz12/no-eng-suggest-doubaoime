cmake_minimum_required(VERSION 3.22.1)
project(noensuggest)

add_library(noensuggest SHARED
    noensuggest_hooks.cpp
    noensuggest_jni.cpp
)

find_package(shadowhook REQUIRED CONFIG)

target_compile_features(noensuggest PRIVATE cxx_std_17)
target_compile_options(noensuggest PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti)

target_link_libraries(noensuggest
    shadowhook::shadowhook
    log
    dl
)

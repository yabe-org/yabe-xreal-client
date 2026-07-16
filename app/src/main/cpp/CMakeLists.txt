cmake_minimum_required(VERSION 3.22)
project("xreal_client")

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_COLOR_DIAGNOSTICS ON)
set(CMAKE_CXX_STANDARD 23)

add_library(${CMAKE_PROJECT_NAME} SHARED
    xreal_client.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log)

cmake_minimum_required(VERSION 3.22)
project("xreal_client")

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
set(CMAKE_COLOR_DIAGNOSTICS ON)
set(CMAKE_CXX_STANDARD 23)

include(FetchContent)
FetchContent_Declare(
    xkbcommon_src
    GIT_REPOSITORY https://github.com/xkbcommon/libxkbcommon.git
    GIT_TAG xkbcommon-1.13.2
    GIT_SHALLOW true)
FetchContent_GetProperties(xkbcommon_src)
if(NOT xkbcommon_src_POPULATED)
    if(POLICY CMP0169)
        cmake_policy(SET CMP0169 OLD)
    endif()
    FetchContent_Populate(xkbcommon_src)
endif()

configure_file("${CMAKE_CURRENT_LIST_DIR}/meson_cross.txt.in"
    ${CMAKE_CURRENT_BINARY_DIR}/meson_cross.txt)
include(ExternalProject)
ExternalProject_Add(
    xkbcommon_project
    SOURCE_DIR ${xkbcommon_src_SOURCE_DIR}
    BUILD_ALWAYS 0
    CONFIGURE_COMMAND meson setup <BINARY_DIR> <SOURCE_DIR> --cross-file ${CMAKE_CURRENT_BINARY_DIR}/meson_cross.txt --default-library=static -Denable-tools=false -Denable-x11=false -Denable-docs=false -Denable-wayland=false -Denable-xkbregistry=false -Denable-bash-completion=false
    BUILD_COMMAND meson compile -C <BINARY_DIR>
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS <BINARY_DIR>/libxkbcommon.a)
ExternalProject_Get_Property(xkbcommon_project BINARY_DIR)
ExternalProject_Get_Property(xkbcommon_project SOURCE_DIR)
add_library(xkbcommon STATIC IMPORTED)
set_target_properties(xkbcommon PROPERTIES
    IMPORTED_LOCATION ${BINARY_DIR}/libxkbcommon.a
    INTERFACE_INCLUDE_DIRECTORIES "$<BUILD_INTERFACE:${SOURCE_DIR}/include>")
add_dependencies(xkbcommon xkbcommon_project)

add_library(${CMAKE_PROJECT_NAME} SHARED
    andlify_process.cpp
    client_environment.cpp
    compositor_service.cpp
    wayland_socket_listener.cpp)
add_dependencies(${CMAKE_PROJECT_NAME} xkbcommon_project)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    xkbcommon)

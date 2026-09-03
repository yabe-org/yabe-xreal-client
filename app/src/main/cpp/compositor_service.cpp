#include "common.hpp"

#include <errno.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <xkbcommon/xkbcommon.h>

#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>

namespace {
int socket_fd = -1;
int epoll_fd = -1;
int lock_fd = -1;

void close_fd(int &fd) {
  if (fd < 0) {
    return;
  }
  close(fd);
  fd = -1;
}

void reset_socket() {
  close_fd(epoll_fd);
  close_fd(socket_fd);
  close_fd(lock_fd);
}
} // namespace

#define JNI_METHOD(return_type, name)                                          \
  extern "C" JNIEXPORT return_type JNICALL                                     \
      Java_org_yabe_xreal_1client_CompositorService_##name

/// @return false if it fails
JNI_METHOD(jboolean, clearRootfsTmpfsDirectories)(JNIEnv *env, jobject obj,
                                                  jstring rootfs_path_value) {
  const auto rootfs_path = jstring_to_string(env, rootfs_path_value);
  // /run directory created by `filesystem` package in Arch Linux
  // may creates it as 0555, so fix it
  chmod((rootfs_path + "/run").c_str(), 0755);
  for (const auto *directory : {"run", "tmp"}) {
    const auto path = std::filesystem::path(rootfs_path) / directory;
    std::error_code error;
    const auto status = std::filesystem::symlink_status(path, error);
    if (error || !std::filesystem::is_directory(status)) {
      LOG_ERR("invalid rootfs tmpfs directory `%s`: %s", path.c_str(),
              error ? error.message().c_str() : "not a directory");
      return JNI_FALSE;
    }

    std::filesystem::directory_iterator entries(path, error);
    if (error) {
      LOG_ERR("failed to open rootfs tmpfs directory `%s`: %s", path.c_str(),
              error.message().c_str());
      return JNI_FALSE;
    }
    for (const auto &entry : entries) {
      std::filesystem::remove_all(entry.path(), error);
      if (error) {
        LOG_ERR("failed to remove `%s`: %s", entry.path().c_str(),
                error.message().c_str());
        return JNI_FALSE;
      }
    }
  }
  return JNI_TRUE;
}

// written in C++ so that create UNIX socket with the same procedure of Wayland
JNI_METHOD(jint, createSocketFd)(JNIEnv *env, jobject obj,
                                 jstring socket_directory_value) {
  const auto socket_directory = jstring_to_string(env, socket_directory_value);
  const auto socket_path = socket_directory + "/wayland-1";
  const auto socket_lock_path = socket_path + ".lock";
  if (socket_path.size() >= sizeof(sockaddr_un::sun_path)) {
    LOG_ERR("socket path is too long: `%s`", socket_path.c_str());
    return -1;
  }

  reset_socket();
  lock_fd = open(socket_lock_path.c_str(), O_CREAT | O_CLOEXEC | O_RDWR, 0666);
  if (lock_fd < 0 || flock(lock_fd, LOCK_EX | LOCK_NB) < 0) {
    LOG_ERR("failed to lock Wayland socket: %s", std::strerror(errno));
    reset_socket();
    return -1;
  }

  unlink(socket_path.c_str());
  LOG_DBG("creating Wayland socket at `%s`", socket_path.c_str());
  socket_fd = socket(PF_LOCAL, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (socket_fd < 0) {
    LOG_ERR("failed to create Wayland socket: %s", std::strerror(errno));
    reset_socket();
    return -1;
  }

  sockaddr_un address{};
  address.sun_family = AF_UNIX;
  std::strncpy(address.sun_path, socket_path.c_str(),
               sizeof(address.sun_path) - 1);
  if (bind(socket_fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) <
          0 ||
      listen(socket_fd, 128) < 0) {
    LOG_ERR("failed to listen on Wayland socket: %s", std::strerror(errno));
    reset_socket();
    return -1;
  }

  epoll_fd = epoll_create1(EPOLL_CLOEXEC);
  epoll_event event{
      .events = EPOLLIN,
      .data = {.fd = socket_fd},
  };
  if (epoll_fd < 0 ||
      epoll_ctl(epoll_fd, EPOLL_CTL_ADD, socket_fd, &event) < 0) {
    LOG_ERR("failed to monitor Wayland socket: %s", std::strerror(errno));
    reset_socket();
    return -1;
  }
  return socket_fd;
}

JNI_METHOD(jint, acceptSocket)(JNIEnv *env, jobject obj) {
  epoll_event event{};
  int count;
  do {
    count = epoll_wait(epoll_fd, &event, 1, -1);
  } while (count < 0 && errno == EINTR);

  if (count < 0) {
    LOG_ERR("failed to wait for Wayland client: %s", std::strerror(errno));
    return -1;
  }
  if (event.data.fd != socket_fd) {
    LOG_ERR("unexpected fd while waiting for Wayland client: %d",
            event.data.fd);
    return -1;
  }
  return accept4(socket_fd, nullptr, nullptr, SOCK_CLOEXEC);
}

JNI_METHOD(jstring, createKeymap)(JNIEnv *env, jobject obj,
                                  jstring include_directory_value,
                                  jstring rules_value, jstring model_value,
                                  jstring layout_value, jstring variant_value,
                                  jstring options_value) {
  const auto include_directory =
      jstring_to_string(env, include_directory_value);
  const auto rules = jstring_to_string(env, rules_value);
  const auto model = jstring_to_string(env, model_value);
  const auto layout = jstring_to_string(env, layout_value);
  const auto variant = jstring_to_string(env, variant_value);
  const auto options = jstring_to_string(env, options_value);
  const xkb_rule_names names = {
      .rules = rules.c_str(),
      .model = model.c_str(),
      .layout = layout.c_str(),
      .variant = variant.c_str(),
      .options = options.c_str(),
  };

  auto *context = xkb_context_new(XKB_CONTEXT_NO_DEFAULT_INCLUDES);
  if (!context) {
    LOG_ERR("failed to create xkb context");
    return nullptr;
  }
  if (xkb_context_include_path_append(context, include_directory.c_str()) !=
      1) {
    LOG_ERR("failed to add xkb include path: %s", include_directory.c_str());
    xkb_context_unref(context);
    return nullptr;
  }

  auto *keymap = xkb_keymap_new_from_names2(
      context, &names, XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
  xkb_context_unref(context);
  if (!keymap) {
    LOG_ERR("failed to create xkb keymap");
    return nullptr;
  }

  char *keymap_text =
      xkb_keymap_get_as_string(keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
  xkb_keymap_unref(keymap);
  if (!keymap_text) {
    LOG_ERR("failed to serialize xkb keymap");
    return nullptr;
  }
  auto *result = env->NewStringUTF(keymap_text);
  free(keymap_text);
  return result;
}

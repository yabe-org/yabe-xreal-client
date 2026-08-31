#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <errno.h>
#include <fcntl.h>
#include <filesystem>
#include <jni.h>
#include <string>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>
#include <xkbcommon/xkbcommon.h>

#include <mutex>
#include <vector>

#define LOG_INFO(...)                                                          \
  __android_log_print(ANDROID_LOG_INFO, "YabeXrealClient", __VA_ARGS__)
#define LOG_DBG(...)                                                           \
  __android_log_print(ANDROID_LOG_DEBUG, "YabeXrealClient", __VA_ARGS__)
#define LOG_ERR(...)                                                           \
  __android_log_print(ANDROID_LOG_ERROR, "YabeXrealClient", __VA_ARGS__)

namespace {
constexpr int COMMAND_CANNOT_EXECUTE_EXIT_STATUS = 126;

int socketFd = -1;
int epollFd = -1;
std::mutex xkbMutex;

std::string jstringToString(JNIEnv *env, jstring value) {
  if (!value) {
    return {};
  }
  const char *chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) {
    return {};
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

void initialize_environmen_variables() {
  setenv("PATH", "/usr/bin:/bin:/usr/sbin:/sbin", 1);
  setenv("XDG_RUNTIME_DIR", "/tmp", true);
  setenv("WAYLAND_DISPLAY", "wayland-1", true);
  setenv("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/0/bus", true);
}
} // namespace

extern "C" {
JNIEXPORT jboolean JNICALL
Java_org_yabe_xreal_1client_CompositorService_clearRootfsTmpfsDirectories(
    JNIEnv *env, jobject obj, jstring jRootfsPath) {
  const auto rootfsPath = jstringToString(env, jRootfsPath);
  // /run directory created by `filesystem` package in Arch Linux
  // may creates it as 0555, so fix it
  chmod((rootfsPath + "/run").c_str(), 0755);
  for (const auto *directory : {"run", "tmp"}) {
    const auto tmpfsDirectory = std::filesystem::path(rootfsPath) / directory;
    std::error_code error;
    const auto status = std::filesystem::symlink_status(tmpfsDirectory, error);
    if (error || !std::filesystem::is_directory(status)) {
      LOG_ERR("invalid rootfs tmpfs directory `%s`: %s", tmpfsDirectory.c_str(),
              error ? error.message().c_str() : "not a directory");
      return JNI_FALSE;
    }

    std::filesystem::directory_iterator entries(tmpfsDirectory, error);
    if (error) {
      LOG_ERR("failed to open rootfs tmpfs directory `%s`: %s",
              tmpfsDirectory.c_str(), error.message().c_str());
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

JNIEXPORT jint JNICALL
Java_org_yabe_xreal_1client_CompositorService_createSocketFd(
    JNIEnv *env, jobject obj, jstring jSocketDir) {
  const std::string socketDir = env->GetStringUTFChars(jSocketDir, nullptr);
  const auto socketPath = socketDir + "/wayland-1";
  if (access(socketPath.c_str(), F_OK) == 0) {
    unlink(socketPath.c_str());
  }

  const auto socketLockPath = socketPath + ".lock";
  if (access(socketLockPath.c_str(), F_OK) == 0) {
    unlink(socketLockPath.c_str());
  }
  LOG_DBG("creating FD from C++; socket path = `%s`", socketPath.c_str());

  const auto lockFd =
      open(socketLockPath.c_str(), O_CREAT | O_CLOEXEC | O_RDWR, 0666);
  if (lockFd < 0) {
    LOG_ERR("failed to open() lock file: %s", std::strerror(errno));
    return -1;
  }
  if (flock(lockFd, LOCK_EX | LOCK_NB) < 0) {
    LOG_ERR("failed to flock(): %s", std::strerror(errno));
    return -1;
  }

  sockaddr_un addr{};
  std::memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  std::strncpy(addr.sun_path, socketPath.c_str(), sizeof(addr.sun_path) - 1);
  socketFd = socket(PF_LOCAL, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (socketFd < 0) {
    LOG_ERR("failed to socket(): %s", std::strerror(errno));
    return -1;
  }
  if (bind(socketFd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) < 0) {
    LOG_ERR("failed to bind(): %s", std::strerror(errno));
    close(socketFd);
    return -1;
  }
  if (listen(socketFd, 128) < 0) {
    LOG_ERR("failed to listen(): %s", std::strerror(errno));
    close(socketFd);
    return -1;
  }

  epollFd = epoll_create1(EPOLL_CLOEXEC);
  if (epollFd < 0) {
    LOG_ERR("failed to epoll_create1(): %s", std::strerror(errno));
    close(socketFd);
    return -1;
  }
  epoll_event event{};
  event.events = EPOLLIN;
  event.data.fd = socketFd;
  if (epoll_ctl(epollFd, EPOLL_CTL_ADD, socketFd, &event) < 0) {
    LOG_ERR("failed to epoll_ctl(): %s", std::strerror(errno));
    close(socketFd);
    close(epollFd);
    return -1;
  }

  return socketFd;
}

JNIEXPORT jint JNICALL
Java_org_yabe_xreal_1client_CompositorService_acceptSocket(JNIEnv *env,
                                                           jobject obj) {
  epoll_event events[1];
  int n = epoll_wait(epollFd, events, 1, -1);
  if (n < 0) {
    LOG_ERR("failed to epoll_wait(): %s", std::strerror(errno));
    return -1;
  }
  if (n == 0) {
    LOG_ERR("epoll_wait() timeout");
    return -1;
  }
  if (events[0].data.fd != socketFd) {
    LOG_ERR("unexpected fd in epoll_wait(): %d", events[0].data.fd);
    return -1;
  }
  return accept(socketFd, nullptr, nullptr);
}

JNIEXPORT jstring JNICALL
Java_org_yabe_xreal_1client_CompositorService_createKeymap(
    JNIEnv *env, jobject obj, jstring includeDir, jstring rules, jstring model,
    jstring layout, jstring variant, jstring options) {
  const auto includeDirectory = jstringToString(env, includeDir);
  const auto rulesString = jstringToString(env, rules);
  const auto modelString = jstringToString(env, model);
  const auto layoutString = jstringToString(env, layout);
  const auto variantString = jstringToString(env, variant);
  const auto optionsString = jstringToString(env, options);
  const xkb_rule_names names = {
      .rules = rulesString.c_str(),
      .model = modelString.c_str(),
      .layout = layoutString.c_str(),
      .variant = variantString.c_str(),
      .options = optionsString.c_str(),
  };

  std::lock_guard lock(xkbMutex);
  auto *context = xkb_context_new(XKB_CONTEXT_NO_DEFAULT_INCLUDES);
  if (!context) {
    LOG_ERR("Failed to create xkb context");
    return nullptr;
  }
  if (xkb_context_include_path_append(context, includeDirectory.c_str()) != 1) {
    LOG_ERR("Failed to add xkb include path: %s", includeDirectory.c_str());
    xkb_context_unref(context);
    return nullptr;
  }
  auto *keymap = xkb_keymap_new_from_names2(
      context, &names, XKB_KEYMAP_FORMAT_TEXT_V1, XKB_KEYMAP_COMPILE_NO_FLAGS);
  xkb_context_unref(context);
  if (!keymap) {
    LOG_ERR("Failed to create xkb keymap");
    return nullptr;
  }
  char *keymapString =
      xkb_keymap_get_as_string(keymap, XKB_KEYMAP_FORMAT_TEXT_V1);
  xkb_keymap_unref(keymap);
  if (!keymapString) {
    LOG_ERR("Failed to serialize xkb keymap");
    return nullptr;
  }
  jstring result = env->NewStringUTF(keymapString);
  free(keymapString);
  return result;
}

JNIEXPORT void JNICALL
Java_org_yabe_xreal_1client_AndlifyEntrypointRunnable_andlifyEntrypoint(
    JNIEnv *env, jobject obj) {
  LOG_DBG("chroot started!");
  initialize_environmen_variables();
  for (const auto &path : {"/run/dbus", "/run/user/0"}) {
    try {
      if (!std::filesystem::create_directories(path)) {
        LOG_ERR("Tried to create existing directory: %s", path);
      }
    } catch (const std::filesystem::filesystem_error &e) {
      LOG_ERR("Failed to create %s: %s", path, e.what());
    }
  }
  while (true) {
    int child = fork();
    if (child < 0) {
      LOG_ERR("fork(): %s", std::strerror(errno));
    }
    if (child == 0) {
      chdir("/tmp");
      setenv("PWD", "/tmp", 1);
      setenv("PS1", R"(\u:\w\$ )", 1);

      execl(
          "/usr/bin/sh", "sh", "-c",
          "dbus-daemon --system  --fork &&"
          "dbus-daemon --session --nofork --address=unix:path=/run/user/0/bus",
          nullptr);
      std::exit(0);
    } else {
      LOG_DBG("child %d started", child);
      int status = 0;
      if (waitpid(child, &status, 0) < 0) {
        LOG_ERR("wait(): %s", std::strerror(errno));
        continue;
      }
      int exit_status = WEXITSTATUS(status);
      LOG_INFO("DBus process (%d) finished with %d", child, exit_status);
      break;
    }
  }
}

JNIEXPORT void JNICALL
Java_org_yabe_xreal_1client_AndlifyExecRunnable_andlifyExec(JNIEnv *env,
                                                            jobject obj,
                                                            jobjectArray args) {
  const auto argc = env->GetArrayLength(args);
  if (argc == 0) {
    _exit(COMMAND_CANNOT_EXECUTE_EXIT_STATUS);
  }
  initialize_environmen_variables();

  std::vector<std::string> strings;
  strings.reserve(argc);
  std::vector<char *> argv;
  argv.reserve(argc + 1);
  for (jsize i = 0; i < argc; ++i) {
    auto *arg = static_cast<jstring>(env->GetObjectArrayElement(args, i));
    strings.emplace_back(jstringToString(env, arg));
    argv.push_back(strings.back().data());
    env->DeleteLocalRef(arg);
  }
  argv.push_back(nullptr);
  execvp(argv[0], argv.data());
  _exit(COMMAND_CANNOT_EXECUTE_EXIT_STATUS);
}
}

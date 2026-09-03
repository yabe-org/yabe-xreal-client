#include "common.hpp"

#include <errno.h>
#include <jni.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

namespace {
constexpr int kCommandCannotExecuteExitStatus = 126;

void initialize_environment() {
  setenv("PATH", "/usr/bin:/bin:/usr/sbin:/sbin", 1);
  setenv("XDG_RUNTIME_DIR", "/tmp", 1);
  setenv("WAYLAND_DISPLAY", "wayland-1", 1);
  setenv("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/0/bus", 1);
}
} // namespace

#define JNI_METHOD(return_type, class_name, name)                              \
  extern "C" JNIEXPORT return_type JNICALL                                     \
      Java_org_yabe_xreal_1client_##class_name##_##name

JNI_METHOD(void, AndlifyEntrypointRunnable, andlifyEntrypoint)
(JNIEnv *env, jobject obj) {
  LOG_DBG("chroot started");
  initialize_environment();
  for (const auto *path : {"/run/dbus", "/run/user/0"}) {
    std::error_code error;
    std::filesystem::create_directories(path, error);
    if (error) {
      LOG_ERR("failed to create %s: %s", path, error.message().c_str());
      return;
    }
  }

  const auto child = fork();
  if (child < 0) {
    LOG_ERR("failed to fork DBus process: %s", std::strerror(errno));
    return;
  }
  if (child == 0) {
    chdir("/tmp");
    setenv("PWD", "/tmp", 1);
    setenv("PS1", R"(\u:\w\$ )", 1);
    execl("/usr/bin/sh", "sh", "-c",
          "dbus-daemon --system --fork && "
          "dbus-daemon --session --nofork --address=unix:path=/run/user/0/bus",
          nullptr);
    _exit(kCommandCannotExecuteExitStatus);
  }

  LOG_DBG("DBus process %d started", child);
  int status = 0;
  while (waitpid(child, &status, 0) < 0) {
    if (errno == EINTR) {
      continue;
    }
    LOG_ERR("failed to wait for DBus process: %s", std::strerror(errno));
    return;
  }
  if (WIFEXITED(status)) {
    LOG_INFO("DBus process %d finished with %d", child, WEXITSTATUS(status));
  } else {
    LOG_INFO("DBus process %d terminated by signal %d", child,
             WTERMSIG(status));
  }
}

JNI_METHOD(void, AndlifyExecRunnable, andlifyExec)
(JNIEnv *env, jobject obj, jobjectArray args) {
  const auto argc = env->GetArrayLength(args);
  if (argc == 0) {
    _exit(kCommandCannotExecuteExitStatus);
  }
  initialize_environment();

  std::vector<std::string> strings;
  strings.reserve(argc);
  std::vector<char *> argv;
  argv.reserve(argc + 1);
  for (jsize i = 0; i < argc; ++i) {
    auto *arg = static_cast<jstring>(env->GetObjectArrayElement(args, i));
    strings.emplace_back(jstring_to_string(env, arg));
    argv.push_back(strings.back().data());
    env->DeleteLocalRef(arg);
  }
  argv.push_back(nullptr);
  execvp(argv[0], argv.data());
  _exit(kCommandCannotExecuteExitStatus);
}

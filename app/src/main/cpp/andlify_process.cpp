#include "common.hpp"

#include <errno.h>
#include <grp.h>
#include <jni.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
#include <string>
#include <vector>

namespace {
constexpr int kCommandCannotExecuteExitStatus = 126;

void initialize_environment() {
  setenv("PATH", "/usr/bin:/bin:/usr/sbin:/sbin", 1);
  setenv("XDG_RUNTIME_DIR", "/tmp", 1);
  setenv("WAYLAND_DISPLAY", "wayland-1", 1);
  setenv("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1000/bus", 1);
}

constexpr uid_t kUserUid = 1000;
constexpr gid_t kUserGid = 1000;

// returns exit status of the child, or -1 if it cannot be waited
int run_child(const std::function<void()> &child_func) {
  const auto child = fork();
  if (child < 0) {
    LOG_ERR("failed to fork: %s", std::strerror(errno));
    return -1;
  }
  if (child == 0) {
    chdir("/tmp");
    setenv("PWD", "/tmp", 1);
    child_func();
    _exit(kCommandCannotExecuteExitStatus);
  }

  LOG_DBG("child %d started", child);
  int status = 0;
  while (waitpid(child, &status, 0) < 0) {
    if (errno != EINTR) {
      LOG_ERR("failed to wait for child %d: %s", child, std::strerror(errno));
      return -1;
    }
  }
  if (!WIFEXITED(status)) {
    LOG_ERR("child %d terminated by signal %d", child, WTERMSIG(status));
    return -1;
  }
  return WEXITSTATUS(status);
}

bool drop_to_user() {
  const gid_t groups[] = {kUserGid};
  if (setgroups(1, groups) != 0 || setgid(kUserGid) != 0 ||
      setuid(kUserUid) != 0) {
    LOG_ERR("failed to drop privileges: %s", std::strerror(errno));
    return false;
  }
  setenv("HOME", "/home/user", 1);
  setenv("USER", "user", 1);
  setenv("LOGNAME", "user", 1);
  return true;
}
} // namespace

#define JNI_METHOD(return_type, class_name, name)                              \
  extern "C" JNIEXPORT return_type JNICALL                                     \
      Java_org_yabe_xreal_1client_##class_name##_##name

// executed as root, like an init process
JNI_METHOD(void, AndlifyEntrypointRunnable, andlifyEntrypoint)
(JNIEnv *env, jobject obj) {
  LOG_DBG("chroot started");
  initialize_environment();
  std::error_code error;
  std::filesystem::create_directories("/run/dbus", error);
  if (error) {
    LOG_ERR("failed to create /run/dbus: %s", error.message().c_str());
    return;
  }

  // system bus drops its privileges by itself according to system.conf.
  // /run persists unlike tmpfs, so a pid file would remain after the app exits
  const int system_bus_status = run_child([]() {
    execl("/usr/bin/dbus-daemon", "dbus-daemon", "--system", "--fork",
          "--nopidfile", nullptr);
  });
  LOG_INFO("DBus system bus launcher finished with %d", system_bus_status);

  const int session_bus_status = run_child([]() {
    if (!drop_to_user()) {
      return;
    }
    setenv("PS1", R"(\u:\w\$ )", 1);
    execl("/usr/bin/dbus-daemon", "dbus-daemon", "--session", "--nofork",
          "--address=unix:path=/run/user/1000/bus", nullptr);
  });
  LOG_INFO("DBus session bus finished with %d", session_bus_status);
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

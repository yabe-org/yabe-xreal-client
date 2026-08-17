#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <string>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define LOG_INFO(...)                                                          \
  __android_log_print(ANDROID_LOG_INFO, "YabeXrealClient", __VA_ARGS__)
#define LOG_DBG(...)                                                           \
  __android_log_print(ANDROID_LOG_DEBUG, "YabeXrealClient", __VA_ARGS__)
#define LOG_ERR(...)                                                           \
  __android_log_print(ANDROID_LOG_ERROR, "YabeXrealClient", __VA_ARGS__)

namespace {
int socketFd = -1;
int epollFd = -1;
} // namespace

extern "C" {
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

JNIEXPORT void JNICALL
Java_org_yabe_xreal_1client_AndlifyEntrypointRunnable_andlifyEntrypoint(
    JNIEnv *env, jobject obj) {
  LOG_DBG("chroot started!");
  setenv("PATH", "/usr/bin:/bin:/usr/sbin:/sbin", 1);
  setenv("XDG_RUNTIME_DIR", "/tmp", true);
  setenv("WAYLAND_DISPLAY", "wayland-1", true);
  LOG_DBG("weston-terminal thread start!");
  while (true) {
    int child = fork();
    if (child < 0) {
      LOG_ERR("fork(): %s", std::strerror(errno));
    }
    if (child == 0) {
      chdir("/tmp");
      setenv("PWD", "/tmp", 1);
      setenv("PS1", R"(\u:\w\$ )", 1);

      execl("/usr/bin/weston-terminal", "weston-terminal",
            "--shell=/usr/bin/bash", nullptr);
      std::exit(0);
    } else {
      LOG_DBG("child %d started", child);
      int status = 0;
      if (waitpid(child, &status, 0) < 0) {
        LOG_ERR("wait(): %s", std::strerror(errno));
      }
      int exit_status = WEXITSTATUS(status);
      LOG_INFO("weston-terminal (%d) finished with %d", child, exit_status);
      if (exit_status != 255) {
        break;
      }
    }
  }
}
}

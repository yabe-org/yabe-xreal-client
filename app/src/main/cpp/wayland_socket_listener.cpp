#include "common.hpp"

#include <errno.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstring>
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
      Java_org_yabe_xreal_1client_WaylandSocketListener_##name

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

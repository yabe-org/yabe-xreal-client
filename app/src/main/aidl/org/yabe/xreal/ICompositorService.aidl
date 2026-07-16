package org.yabe.xreal;
import android.os.ParcelFileDescriptor;

interface ICompositorService {
  boolean isCompositorReady();
  void notifyCompositorReady();
  void addClientFd(in ParcelFileDescriptor pfd);
}

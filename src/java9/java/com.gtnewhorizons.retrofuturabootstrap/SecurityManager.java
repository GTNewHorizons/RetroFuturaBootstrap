package com.gtnewhorizons.retrofuturabootstrap;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.security.Permission;
import java.util.Set;

/** Replacement for {@link java.lang.SecurityManager} */
@SuppressWarnings("unused")
public class SecurityManager {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(
            Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_REFLECT_FRAMES));

    public SecurityManager() {}

    // System methods
    private static SecurityManager INSTANCE = null;

    public static void setSecurityManager(SecurityManager mgr) {
        INSTANCE = mgr;
    }

    public static SecurityManager getSecurityManager() {
        return INSTANCE;
    }

    // SecurityManager methods
    protected Class<?>[] getClassContext() {
        return STACK_WALKER.walk(
                s -> s.skip(1).map(StackWalker.StackFrame::getDeclaringClass).toArray(Class[]::new));
    }

    public Object getSecurityContext() {
        return this;
    }

    public void checkPermission(Permission ignored) {}

    public void checkPermission(Permission ignored, Object ignored2) {}

    public void checkCreateClassLoader() {}

    public void checkAccess(Thread t) {}

    public void checkAccess(ThreadGroup g) {}

    public void checkExit(int status) {}

    public void checkExec(String cmd) {}

    public void checkLink(String lib) {}

    public void checkRead(FileDescriptor fd) {}

    public void checkRead(String file) {}

    public void checkRead(String file, Object context) {}

    public void checkWrite(FileDescriptor fd) {}

    public void checkWrite(String file) {}

    public void checkDelete(String file) {}

    public void checkConnect(String host, int port) {}

    public void checkConnect(String host, int port, Object context) {}

    public void checkListen(int port) {}

    public void checkAccept(String host, int port) {}

    public void checkMulticast(InetAddress maddr) {}

    public void checkMulticast(InetAddress maddr, byte ttl) {}

    public void checkPropertiesAccess() {}

    public void checkPropertyAccess(String key) {}

    public void checkPrintJobAccess() {}

    public void checkPackageAccess(String pkg) {}

    public void checkPackageDefinition(String pkg) {}

    public void checkSetFactory() {}

    public void checkSecurityAccess(String target) {}

    public ThreadGroup getThreadGroup() {
        return Thread.currentThread().getThreadGroup();
    }
}

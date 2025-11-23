package com.gtnewhorizons.retrofuturabootstrap;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;

/**
 * Provides a simple event loop on the main/startup thread and redirects the launch to a new main thread.
 */
public final class MainStartOnFirstThread extends AbstractExecutorService {
    private static MainStartOnFirstThread INSTANCE = null;
    private AtomicBoolean keepRunning = new AtomicBoolean(true);
    private LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    public static MainStartOnFirstThread instance() {
        if (INSTANCE == null) {
            throw new IllegalStateException(
                    "Code expected main() to run from com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread");
        }
        return INSTANCE;
    }

    public static void main(String[] args) {
        if (INSTANCE != null) {
            throw new IllegalStateException("Trying to start RFB twice");
        }
        // Enforce headless AWT on macOS
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            System.setProperty("java.awt.headless", "true");
        }
        INSTANCE = new MainStartOnFirstThread();
        Thread.currentThread().setName("RFB-ActualMain-EventLoop");
        final Thread newMainThread = new Thread(
                () -> {
                    try {
                        Main.main(args);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    } finally {
                        INSTANCE.keepRunning.set(false);
                        INSTANCE.execute(() -> {});
                    }
                },
                "RFB-Main");
        newMainThread.start();
        INSTANCE.eventLoop();
        while (newMainThread.isAlive()) {
            try {
                newMainThread.join();
            } catch (InterruptedException e) {
                // no-op
            }
        }
    }

    private void eventLoop() {
        while (keepRunning.get()) {
            final Runnable task;
            try {
                task = tasks.take();
            } catch (Throwable e) {
                continue;
            }
            if (task == null) {
                continue;
            }
            try {
                task.run();
            } catch (Throwable t) {
                try {
                    System.err.println("Caught exception on the RFB main loop executor:");
                    System.err.println(t.getMessage());
                    t.printStackTrace(System.err);
                } catch (Throwable t2) {
                    /* ignored */
                }
            }
        }
    }

    @Override
    public void shutdown() {
        // no-op
    }

    @Override
    public @NotNull List<Runnable> shutdownNow() {
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        Thread.sleep(unit.toMillis(timeout));
        return false;
    }

    @Override
    public void execute(@NotNull Runnable command) {
        if (command == null) {
            return;
        }
        while (true) {
            try {
                tasks.put(command);
                break;
            } catch (InterruptedException e) {
                // continue
            }
        }
    }
}

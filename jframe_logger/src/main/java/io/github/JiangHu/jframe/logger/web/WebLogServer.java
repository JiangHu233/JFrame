package io.github.JiangHu.jframe.logger.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * host 监听器 —— {@code com.sun.net.httpserver.HttpServer} 的生命周期封装。
 * <p>
 * {@code port=0} 时由 OS 原子分配未占用端口（start 返回实际端口）；
 * 线程池为 cached 守护线程（SSE 连接各占一线程，随连接回收）。
 */
public final class WebLogServer {

    private HttpServer http;
    private ExecutorService pool;

    /**
     * 绑定并启动监听。
     *
     * @param bindHost 绑定地址（如 "0.0.0.0"）
     * @param port     端口（0 = OS 分配）
     * @param handler  路由处理器
     * @return 实际监听端口
     * @throws IOException 绑定失败（端口占用 / 特权端口 / 地址不可用）
     */
    public synchronized int start(String bindHost, int port, HttpHandler handler) throws IOException {
        if (http != null) {
            throw new IllegalStateException("server already started");
        }
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jframe-logger-web-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        pool = Executors.newCachedThreadPool(factory);
        http = HttpServer.create(new InetSocketAddress(bindHost, port), 64);
        http.setExecutor(pool);
        http.createContext("/", handler);
        http.start();
        return http.getAddress().getPort();
    }

    /**
     * 停止监听并释放端口（等待中的 SSE 连接被强制断开）。
     */
    public synchronized void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    /**
     * @return 是否在监听
     */
    public synchronized boolean isRunning() {
        return http != null;
    }
}

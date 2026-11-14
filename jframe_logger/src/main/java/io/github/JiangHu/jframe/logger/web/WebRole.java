package io.github.JiangHu.jframe.logger.web;

/**
 * Web 展示运行角色。
 * <ul>
 *   <li>{@link #OFF} —— 未启动（默认；无监听、无线程，零资源占用）</li>
 *   <li>{@link #HOST} —— 本 JVM 监听端口对外服务（可能同时接收其它实例挂靠）</li>
 *   <li>{@link #AGENT} —— 本 JVM 挂靠到本机其它 JFrame logger host，推送日志</li>
 * </ul>
 */
public enum WebRole {
    OFF,
    HOST,
    AGENT
}

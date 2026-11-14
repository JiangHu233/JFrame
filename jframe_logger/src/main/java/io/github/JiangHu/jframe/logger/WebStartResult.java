package io.github.JiangHu.jframe.logger;

import io.github.JiangHu.jframe.logger.web.WebRole;

/**
 * {@code /jlog start} 的结果。
 *
 * @param role    结果角色：HOST（本机已监听）/ AGENT（已挂靠本机其它 host）/ OFF（失败）
 * @param url     访问地址（host 为监听地址；agent 为 host 地址 + 自身子路由）
 * @param message 附加信息（失败原因 / 提示）
 */
public record WebStartResult(WebRole role, String url, String message) {

    /**
     * @return 是否成功（角色非 OFF）
     */
    public boolean success() {
        return role != WebRole.OFF;
    }

    public static WebStartResult host(String url, String message) {
        return new WebStartResult(WebRole.HOST, url, message);
    }

    public static WebStartResult agent(String url, String message) {
        return new WebStartResult(WebRole.AGENT, url, message);
    }

    public static WebStartResult fail(String message) {
        return new WebStartResult(WebRole.OFF, "", message);
    }
}

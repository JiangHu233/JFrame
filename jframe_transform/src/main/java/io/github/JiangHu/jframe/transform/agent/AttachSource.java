package io.github.JiangHu.jframe.transform.agent;

/**
 * Instrumentation 获取途径。
 */
public enum AttachSource {

    /** 尚未获取（agent 未安装） */
    NONE,

    /** 运维以 -javaagent:jframe_main.jar 预注入（TransformAgent premain 已持有实例） */
    PREMAIN,

    /** 运行中 ByteBuddyAgent 自附加（服务器零启动参数；JDK 21+ 会打一条无害警告） */
    SELF_ATTACH
}

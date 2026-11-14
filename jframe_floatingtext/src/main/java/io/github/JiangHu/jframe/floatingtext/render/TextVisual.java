package io.github.JiangHu.jframe.floatingtext.render;

import io.github.JiangHu.jframe.floatingtext.viewers.TextViewer;

import java.util.List;

/**
 * 渲染输入值对象 — 一次创建视觉所需的全部信息。
 *
 * @param text      文本（Bedrock 颜色码，可含 \n 多行）
 * @param x         X 坐标
 * @param y         Y 坐标
 * @param z         Z 坐标
 * @param levelName 所属 level 名（null = 不限制；对账过滤用）
 * @param viewers   初始观众（逐个发送生成包并记账）
 */
public record TextVisual(String text, double x, double y, double z,
                         String levelName, List<TextViewer> viewers) {
}

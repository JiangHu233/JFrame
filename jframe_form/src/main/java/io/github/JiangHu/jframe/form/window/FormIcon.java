package io.github.JiangHu.jframe.form.window;

import cn.nukkit.form.element.ElementButtonImageData;

/**
 * 表单按钮图标。
 * <p>
 * 封装 Nukkit 原生的 {@link ElementButtonImageData}，屏蔽其「data 在前、type 在后」
 * 的反直觉构造顺序，提供两种语义清晰的工厂方法：
 * <ul>
 *   <li>{@link #path(String)} —— 客户端内置纹理路径（例如 {@code "textures/items/apple"}）</li>
 *   <li>{@link #url(String)} —— 网络图片地址</li>
 * </ul>
 * 该类同时被 {@link Button}（简单表单按钮）与 {@link CustomForm}（自定义表单图标）复用。
 *
 * @see Button#icon(FormIcon)
 */
public final class FormIcon {

    /** 图片类型：客户端内置纹理路径 */
    public static final String TYPE_PATH = ElementButtonImageData.IMAGE_DATA_TYPE_PATH;
    /** 图片类型：网络 URL */
    public static final String TYPE_URL = ElementButtonImageData.IMAGE_DATA_TYPE_URL;

    private final String type;
    private final String data;

    private FormIcon(String type, String data) {
        this.type = type;
        this.data = data;
    }

    /**
     * 使用客户端内置纹理路径创建图标。
     *
     * @param path 纹理路径，例如 {@code "textures/items/apple"}
     * @return 图标实例
     */
    public static FormIcon path(String path) {
        return new FormIcon(TYPE_PATH, path);
    }

    /**
     * 使用网络图片 URL 创建图标。
     *
     * @param url 图片地址
     * @return 图标实例
     */
    public static FormIcon url(String url) {
        return new FormIcon(TYPE_URL, url);
    }

    /** 图片类型（{@link #TYPE_PATH} 或 {@link #TYPE_URL}）。 */
    public String type() {
        return type;
    }

    /** 图片数据（路径或 URL）。 */
    public String data() {
        return data;
    }

    /**
     * 转换为 Nukkit 原生图片数据对象。
     * <p>
     * 注意：{@link ElementButtonImageData} 的构造参数顺序为 {@code (data, type)}，
     * 此处负责正确映射，调用方无需关心。
     *
     * @return Nukkit {@link ElementButtonImageData}
     */
    public ElementButtonImageData toNukkit() {
        return new ElementButtonImageData(data, type);
    }
}

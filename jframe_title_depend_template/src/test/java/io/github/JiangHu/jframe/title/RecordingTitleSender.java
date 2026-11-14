package io.github.JiangHu.jframe.title;

import io.github.JiangHu.jframe.title.nukkit.TitleSender;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制型标题发送器测试桩——记录全部发包调用，供断言通道级 diff 与生命周期行为。
 *
 * <p>不接触任何 Nukkit API，纯内存记录。</p>
 */
public class RecordingTitleSender implements TitleSender {

    /** 一次 title/subtitle 通道发送（一个发送单元，携带时序） */
    public static class TitleCall {
        public final String title;
        public final String subtitle;
        public final int fadeIn;
        public final int stay;
        public final int fadeOut;

        TitleCall(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            this.title = title;
            this.subtitle = subtitle;
            this.fadeIn = fadeIn;
            this.stay = stay;
            this.fadeOut = fadeOut;
        }
    }

    /** 全部 title 通道发送记录（按时间顺序） */
    public final List<TitleCall> titleCalls = new ArrayList<>();

    /** 全部 actionbar 通道发送记录（按时间顺序） */
    public final List<String> actionBarCalls = new ArrayList<>();

    /** clearTitle 调用次数 */
    public int clearCount = 0;

    @Override
    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        titleCalls.add(new TitleCall(title, subtitle, fadeIn, stay, fadeOut));
    }

    @Override
    public void sendActionBar(String text) {
        actionBarCalls.add(text);
    }

    @Override
    public void clearTitle() {
        clearCount++;
    }

    /** 最近一次 title 通道发送（无发送时为 null） */
    public TitleCall lastTitleCall() {
        return titleCalls.isEmpty() ? null : titleCalls.get(titleCalls.size() - 1);
    }

    /** 最近一次 actionbar 通道发送（无发送时为 null） */
    public String lastActionBarCall() {
        return actionBarCalls.isEmpty() ? null : actionBarCalls.get(actionBarCalls.size() - 1);
    }
}

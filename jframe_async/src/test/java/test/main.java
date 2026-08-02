package test;

import io.github.JiangHu.jframe.async.thread.ThreadAPI;

public class main {
    public static void main(String[] args) {
        ThreadAPI threadAPI = new ThreadAPI();
        threadAPI.createThreadTask("test");
        threadAPI.pushTask("test", () -> {
            System.out.println("test");
        });
        threadAPI.pushTask("test", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        threadAPI.pushTask("test", () -> {
            System.out.println("test3");
        });

        threadAPI.stopAll();
    }
}

package test;

import io.github.JiangHu.jframe.thread.ThreadService;

public class main {
    public static void main(String[] args) {
        ThreadService threadService = new ThreadService();
        threadService.createThreadTask("test");
        threadService.pushTask("test", () -> {
            System.out.println("test");
        });
        threadService.pushTask("test", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        threadService.pushTask("test", () -> {
            System.out.println("test3");
        });

        threadService.stopAll();
    }
}

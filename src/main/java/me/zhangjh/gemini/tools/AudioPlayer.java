package me.zhangjh.gemini.tools;


import javazoom.jl.player.Player;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author njhxzhangjihong@126.com
 * @date 10:27 2023/12/29
 * @Description
 */
@Slf4j
public class AudioPlayer {

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    private static AtomicInteger runningTasks = new AtomicInteger(0);

    public static void playMp3(String file) {
        log.info("play file: {}", file);
        EXECUTOR_SERVICE.submit(() -> {
            runningTasks.incrementAndGet();
            try {
                Player player = new Player(new FileInputStream(file));
                player.play();
            } catch (Exception e) {
                throw new RuntimeException("播放MP3异常：", e);
            }
        });
    }

    public static void stop() {
        Thread.currentThread().interrupt();
    }

    public static boolean isPlaying() {
        return runningTasks.get() > 0;
    }

    public static void main(String[] args) {
        playMp3("src/main/resources/mp3/tmp/1703819913582.mp3");
    }
}

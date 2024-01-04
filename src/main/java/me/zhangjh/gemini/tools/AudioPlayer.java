package me.zhangjh.gemini.tools;


import javazoom.jl.player.Player;

import java.io.FileInputStream;

/**
 * @author njhxzhangjihong@126.com
 * @date 10:27 2023/12/29
 * @Description
 */
public class AudioPlayer {

    public static boolean isPlaying = false;

    public static void playMp3(String file) {
        try {
            isPlaying = true;
            Player player = new Player(new FileInputStream(file));
            player.play();
            isPlaying = false;
        } catch (Exception e) {
            throw new RuntimeException("播放MP3异常：", e);
        }

    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public static void main(String[] args) {
        playMp3("src/main/resources/mp3/tmp/1703819913582.mp3");
    }
}

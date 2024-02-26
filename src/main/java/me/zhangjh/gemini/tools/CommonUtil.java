package me.zhangjh.gemini.tools;

import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.*;

/**
 * @author njhxzhangjihong@126.com
 * @date 22:21 2024/2/22
 * @Description
 */
@Slf4j
public class CommonUtil {
    public static boolean vad(byte[] data) {
        return silenceCheck(data);
    }

    private static void removeNoise(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] *= 0.5;
        }
    }

    public static boolean silenceCheck(byte[] raw) {
        removeNoise(raw);
        double thread = 20d;
        double sum = 0d;
        if (raw.length == 0) {
            return true;
        }
        for (double v : raw) {
            sum += v;
        }
        double average = sum/raw.length;
        double sumMeanSquare = 0d;
        for (byte b : raw) {
            sumMeanSquare += Math.pow(b - average, 2d);
        }
        double averageMeanSquare = sumMeanSquare / raw.length;

        return Math.sqrt(averageMeanSquare) <= thread;
    }

    /**
     * 开启麦克风准备拾音，设备异常抛错
     * */
    public static TargetDataLine startMicrophone() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Line not support, may be there is no microphone exist.");
        }
        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format, microphone.getBufferSize());
        microphone.start();
        return microphone;
    }
}

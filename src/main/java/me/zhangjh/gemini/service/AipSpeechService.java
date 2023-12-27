package me.zhangjh.gemini.service;

import com.baidu.aip.speech.AipSpeech;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.sound.sampled.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author njhxzhangjihong@126.com
 * @date 11:34 2023/12/27
 * @Description
 */
public class AipSpeechService {

    public static final String APP_ID = "9950069";

    public static final String API_KEY = "NbIFbltl3G1F6VfPqumH31eo";

    public static final String SECRET_KEY = "15e814f7cc821087542fa9b5c476939a";

    public static final int SAMPLE_RATE = 16000;

    public static final String SUCCESS_FLAG = "success.";

    public static final List<String> WAKEUP_WORDS = Arrays.asList("小张小张", "小张同学", "你好小张");

    public static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    private static void handleData(AipSpeech client, byte[] data) {
        // 识别收音的语音二进制数据流
        JSONObject asrRes = client.asr(data, "pcm", SAMPLE_RATE, null);
        if(asrRes!= null) {
            System.out.println("asrRes: " + asrRes);
            String errMsg = asrRes.getString("err_msg");
            if(StringUtils.isNotEmpty(errMsg) && !Objects.equals(errMsg, SUCCESS_FLAG)) {
                System.out.println("errMsg: " + errMsg);
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) {
                }
            } else {
                JSONArray jsonArray = asrRes.getJSONArray("result");
                for (int i = 0; i < jsonArray.length(); i++) {
                    String text = jsonArray.getString(i);
                    if (WAKEUP_WORDS.contains(text)) {
                        System.out.println("收到唤醒词：" + text);
                        break;
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws LineUnavailableException {
        // 初始化一个AipSpeech
        AipSpeech client = new AipSpeech(APP_ID, API_KEY, SECRET_KEY);

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Line not support");
        }
        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
        byte[] data = new byte[microphone.getBufferSize()];
        microphone.open(format, microphone.getBufferSize());
        microphone.start();

        // 麦克风一直处于收音状态，监听唤醒词的出现
        EXECUTOR_SERVICE.submit(() -> {
            // 识别收音的语音二进制数据流
            while (true) {
                microphone.read(data, 0, data.length);
//                AudioInputStream inputStream = new AudioInputStream(microphone);
//                AudioSystem.write(inputStream, AudioFileFormat.Type.WAVE, new File("./output.wav"));
                // 识别收音的语音二进制数据流
               handleData(client, data);
            }
        });
    }
}

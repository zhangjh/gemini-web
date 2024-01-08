package me.zhangjh.gemini.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * @author njhxzhangjihong@126.com
 * @date 21:53 2024/1/8
 * @Description
 */
@Component
@Slf4j
public class HsSpeechService {

    private static final String appid = "9531957882";
    private static final String token = "1liPUFfhNDXcDib6eyb2_-OhyAyI5CkH";
    private static final String cluster = "volcengine_input_common";
    private static final String audio_format = "pcm";
    private static volatile AsrClient asrClient = null;

    private AsrClient getAsrClient() {
        if(asrClient != null) {
            return asrClient;
        }
        synchronized (HsSpeechService.class) {
            try {
                asrClient = AsrClient.build();
                asrClient.setAppid(appid);
                asrClient.setToken(token);
                asrClient.setCluster(cluster);
                asrClient.setFormat(audio_format);
                asrClient.setShow_utterances(true);

                asrClient.asr_sync_connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return asrClient;
        }
    }

    {
        asrClient = getAsrClient();
    }

    public String asr(byte[] data) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        if(data != null && data.length > 0) {
            AsrResponse asrResponse = asrClient.asr_send(data, false);
            if(asrResponse.getResult() != null) {
                for (AsrResponse.Result result : asrResponse.getResult()) {
                    sb.append(result.getText());
                }
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        HsSpeechService hsSpeechService = new HsSpeechService();
        File file = new File("src/main/resources/test.pcm");
        FileInputStream fp = new FileInputStream(file);
        byte[] b = new byte[16000];
        while ((fp.read(b)) > 0) {
            String asr = hsSpeechService.asr(b);
            log.info("asr: {}", asr);
        }
    }
}

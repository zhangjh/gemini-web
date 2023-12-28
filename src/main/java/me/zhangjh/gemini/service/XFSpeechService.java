package me.zhangjh.gemini.service;

import com.orctom.vad4j.VAD;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.client.GeminiService;
import me.zhangjh.gemini.pojo.ChatContent;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @author njhxzhangjihong@126.com
 * @date 18:05 2023/12/27
 * @Description
 */
@Slf4j
@Component
public class XFSpeechService {

    private static final String HOST_URL = "https://iat-api.xfyun.cn/v2/iat";
    private static final String API_SECRET = "3760b5e9bd1b5cf4bd1379551b925cc4";
    private static final String APP_KEY = "311d42a308bcca3807b4a4e457bd1ece";

    private List<String> bufferList = new ArrayList<>();

    @Autowired
    private GeminiService geminiService;

    public String getAuthUrl() throws Exception {
        URL url = new URL(HOST_URL);
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());
        String builder = "host: " + url.getHost() + "\n" +
                "date: " + date + "\n" +
                "GET " + url.getPath() + " HTTP/1.1";
        Charset charset = StandardCharsets.UTF_8;
        Mac mac = Mac.getInstance("hmacsha256");
        SecretKeySpec spec = new SecretKeySpec(API_SECRET.getBytes(charset), "hmacsha256");
        mac.init(spec);
        byte[] hexDigits = mac.doFinal(builder.getBytes(charset));
        String sha = Base64.getEncoder().encodeToString(hexDigits);

        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"", APP_KEY, "hmac-sha256", "host date request-line", sha);
        HttpUrl httpUrl = Objects.requireNonNull(HttpUrl.parse("https://" + url.getHost() + url.getPath())).newBuilder().
                addQueryParameter("authorization", Base64.getEncoder().encodeToString(authorization.getBytes(charset))).
                addQueryParameter("date", date).
                addQueryParameter("host", url.getHost()).
                build();
        return httpUrl.toString();
    }

    private void handleVoiceData(InputStream in, OkHttpClient client, Request request) {
        final String[] preContent = {""};
        final String[] curContent = {""};
        client.newWebSocket(request, new WebIATWS(in, content -> {
            log.info("content: {}", content);
            if(content.length() < preContent[0].length()) {
                return null;
            }
            curContent[0] = content.substring(preContent[0].length());
            preContent[0] = content;
            String newContent = curContent[0];
            // 返回内容无变化可以忽略
            if(StringUtils.isNotEmpty(newContent)) {
                // todo: 在此执行后续的内容召回，curContent为每次最新的流式结果输出
                log.info("curContent: {}", curContent[0]);
                bufferList.add(curContent[0]);
            }
            return null;
        }));
    }

    private void fileTest(OkHttpClient client, Request request) throws FileNotFoundException {
        File file = new File("src/main/resources/test.pcm");
        FileInputStream fs = new FileInputStream(file);
        byte[] data = new byte[1024];
        try {
            int read = fs.read(data);
            if(read > 0) {
                VAD vad = new VAD();
                boolean speech = vad.isSpeech(data);
                log.info("fileTest speech: {}", speech);
            }
        } catch (IOException e) {
            log.error("read exception: ", e);
            return;
        }
        handleVoiceData(fs, client, request);
        executeGeminiTask();
    }

    private void recordTest(OkHttpClient client, Request request) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Line not support");
        }
        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
        byte[] data = new byte[microphone.getBufferSize()];
        microphone.open(format, microphone.getBufferSize());
        microphone.start();

        while (true) {
            int len = microphone.read(data, 0, data.length);
            if (len > 0) {
                VAD vad = new VAD();
                boolean speech = vad.isSpeech(data);
                log.info("recordTest speech: {}", speech);
                if(speech) {
                    handleVoiceData(new ByteArrayInputStream(data, 0, len), client, request);
                } else {
                    // 清空bufferList
                    if(CollectionUtils.isNotEmpty(bufferList)) {
                        executeGeminiTask();
                        bufferList = new ArrayList<>();
                    }
                }
            }
        }
    }

    private void executeGeminiTask() {
        String question = String.join("", bufferList);
        log.info("question: {}", question);
        // todo: 构建上下文
        List<ChatContent> context = new ArrayList<>();
        geminiService.streamChat(question, context,  (res) -> {
            log.info("gemini res: {}", res);
            return null;
        });
    }

    @PostConstruct
    public void init() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder().build();
        String authUrl = getAuthUrl();
        String url = authUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://");
        Request request = new Request.Builder().url(url).build();

        fileTest(client, request);
        Thread.sleep(10000);
        recordTest(client, request);
    }
}

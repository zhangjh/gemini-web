package me.zhangjh.gemini.service;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.common.RoleEnum;
import me.zhangjh.gemini.pojo.ChatContent;
import me.zhangjh.gemini.request.GeminiRequest;
import me.zhangjh.gemini.request.HttpRequest;
import me.zhangjh.gemini.util.HttpClientUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
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

    private static final String GEMINI_WEB_URL = "http://wx.zhangjh.me:8080/gemini/generateStream";

    /**
     * maximum 10, role user & model as one, need 2 elements
     * */
    private static final int MAX_CHAT_CONTEXT = 20;
    /**
     * chat context
     * */
    private List<ChatContent> context = new ArrayList<>(MAX_CHAT_CONTEXT);

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
        client.newWebSocket(request, new WebIATWS(in, content -> {
            log.info("content: {}", content);
            if(StringUtils.isNotEmpty(content)) {
                // todo: 在此执行后续的内容召回，curContent为每次最新的流式结果输出
                executeGeminiTask(content);
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
//                VAD vad = new VAD();
//                boolean speech = vad.isSpeech(data);
//                log.info("fileTest speech: {}", speech);
//                if (speech) {
                    handleVoiceData(fs, client, request);
//                }
            }
        } catch (IOException e) {
            log.error("read exception: ", e);
        }
    }

    private void recordTest(OkHttpClient client, Request request) throws LineUnavailableException {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Line not support");
        }
        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format, microphone.getBufferSize());
        microphone.start();
        byte[] data = new byte[microphone.getBufferSize()];

        while (true) {
            int len = microphone.read(data, 0, data.length);
            if (len > 0) {
//                VAD vad = new VAD();
//                boolean speech = vad.isSpeech(data);
//                if(speech) {
                    log.info("recordTest speech true");
                    handleVoiceData(new ByteArrayInputStream(data, 0, len), client, request);
//                }
            }
        }
    }

    private void executeGeminiTask(String question) {
        log.info("question: {}", question);
        // 调用远端http服务
        HttpRequest httpRequest = new HttpRequest(GEMINI_WEB_URL);
        GeminiRequest geminiRequest = new GeminiRequest();
        geminiRequest.setText(question);
        geminiRequest.setContext(context);
        httpRequest.setReqData(JSONObject.toJSONString(geminiRequest));
        StringBuilder answerBuffer = new StringBuilder();
        HttpClientUtil.sendStream(httpRequest, response -> {
            log.info("response: {}", response);
            if(StringUtils.isNotEmpty(response)) {
                answerBuffer.append(response);
                if(Objects.equals(response, "[done]")) {
                    String answer = answerBuffer.toString();
                    // maximum 10, remove oldest two
                    if(context.size() == MAX_CHAT_CONTEXT) {
                        context.remove(1);
                        context.remove(0);
                    }
                    context.add(ChatContent.buildBySingleText(question, RoleEnum.user.name()));
                    context.add(ChatContent.buildBySingleText(answer, RoleEnum.model.name()));
                }
            }
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

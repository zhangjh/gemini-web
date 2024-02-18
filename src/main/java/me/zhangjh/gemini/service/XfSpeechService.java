package me.zhangjh.gemini.service;

import com.alibaba.fastjson2.JSONObject;
import com.orctom.vad4j.VAD;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.common.RoleEnum;
import me.zhangjh.gemini.pojo.ChatContent;
import me.zhangjh.gemini.request.GeminiRequest;
import me.zhangjh.gemini.request.HttpRequest;
import me.zhangjh.gemini.tools.AudioPlayer;
import me.zhangjh.gemini.tools.Text2Speech;
import me.zhangjh.gemini.util.HttpClientUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author njhxzhangjihong@126.com
 * @date 18:05 2023/12/27
 * @Description
 */
@Slf4j
@Component
public class XfSpeechService {

    private static final String HOST_URL = "https://iat-api.xfyun.cn/v2/iat";

    @Value("${XY_API_SECRET}")
    private String apiSecret;
    @Value("${XY_APP_KEY}")
    private String appKey;

    private static final String GEMINI_WEB_URL = "http://wx.zhangjh.me:8080/gemini/generateStream";

    private static final List<String> WAKEUP_WORDS = Arrays.asList("小张小张", "小张同学", "你好小张");

    private static TargetDataLine microphone = null;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().build();
    private Request request;
    private static final VAD VAD_INSTANCE = new VAD();

    private static final List<String> TRUNCATION_SYMBOLS = Arrays.asList("，", "。", "！", "？", "、", "...", ";", ":");

    /**
     * maximum 10, role user & model as one, need 2 elements
     * */
    private static final int MAX_CHAT_CONTEXT = 20;
    /**
     * chat context
     * */
    private final List<ChatContent> context = new ArrayList<>(MAX_CHAT_CONTEXT);

    /**
     * 录音数据流缓冲区
     * */
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

    private String getAuthUrl() {
        URL url = null;
        try {
            url = new URL(HOST_URL);
        } catch (MalformedURLException ignored) {
        }
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());
        assert url != null;
        String builder = "host: " + url.getHost() + "\n" +
                "date: " + date + "\n" +
                "GET " + url.getPath() + " HTTP/1.1";
        Charset charset = StandardCharsets.UTF_8;
        Mac mac = null;
        try {
            mac = Mac.getInstance("hmacsha256");
            SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(charset), "hmacsha256");
            mac.init(spec);
        } catch (Exception ignored) {
        }
        assert mac != null;
        byte[] hexDigits = mac.doFinal(builder.getBytes(charset));
        String sha = Base64.getEncoder().encodeToString(hexDigits);

        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"", appKey, "hmac-sha256", "host date request-line", sha);
        HttpUrl httpUrl = Objects.requireNonNull(HttpUrl.parse("https://" + url.getHost() + url.getPath())).newBuilder().
                addQueryParameter("authorization", Base64.getEncoder().encodeToString(authorization.getBytes(charset))).
                addQueryParameter("date", date).
                addQueryParameter("host", url.getHost()).
                build();
        return httpUrl.toString();
    }

    /**
     * 开启麦克风准备拾音，设备异常抛错
     * */
    private TargetDataLine startMicrophone() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Line not support, may be there is no microphone exist.");
        }
        microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format, microphone.getBufferSize());
        microphone.start();
        return microphone;
    }

    private boolean isSpeech(byte[] data) {
        return VAD_INSTANCE.isSpeech(data);
    }

    private void recordTask() {
        TargetDataLine microphone;
        try {
            microphone = startMicrophone();
            AtomicBoolean isWakeupWordDetected = new AtomicBoolean(false);
            byte[] data = new byte[microphone.getBufferSize() / 5];
            List<byte[]> questionDataList = new ArrayList<>();

            while (true) {
                int read = microphone.read(data, 0, data.length);
                if(read > 0) {
                    // 不是语音，不处理
                    if(isSpeech(data)) {
                        // 检测到唤醒词则记录到问题语音数据列表中，否则记录到缓冲区
                        if (isWakeupWordDetected.get()) {
                            questionDataList.add(data);
                        } else {
                            bos.write(data);
                        }
                    } else {
                        CLIENT.newWebSocket(request, new WebIATWS(new ByteArrayInputStream(bos.toByteArray()), content -> {
                            log.info("content: {}", content);
                            // 清空音频流缓冲区
                            bos.reset();
                            if (StringUtils.isNotEmpty(content)) {
                                if(!isWakeupWordDetected.get()) {
                                    // 如果检测到了唤醒词则开始累积问题语音数据，否则忽略不处理
                                    for (String wakeupWord : WAKEUP_WORDS) {
                                        if (content.contains(wakeupWord)) {
                                            AudioPlayer.playMp3("src/main/resources/mp3/应答语.mp3");
                                            log.info("播放应答语");
                                            isWakeupWordDetected.set(true);
                                            break;
                                        }
                                    }
                                } else {
                                    isWakeupWordDetected.set(false);
                                    byte[] questionData = combineAudioData(questionDataList);
                                    CLIENT.newWebSocket(request,
                                            new WebIATWS(new ByteArrayInputStream(questionData), question -> {
                                                // 问题结束，开始干活
                                                log.info("start chat, question: {}", question);
                                                executeGeminiTask(question);
                                                questionDataList.clear();
                                                return null;
                                            }));
                                }
                            }
                            return null;
                        }));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] combineAudioData(List<byte[]> audioDataList) {
        int totalSize = 0;
        for (byte[] data : audioDataList) {
            totalSize += data.length;
        }
        byte[] combinedData = new byte[totalSize];
        int offset = 0;

        for (byte[] data : audioDataList) {
            System.arraycopy(data, 0, combinedData, offset, data.length);
            offset += data.length;
        }
        return combinedData;
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
        AtomicReference<StringBuilder> ttsBuffer = new AtomicReference<>(new StringBuilder());
        try {
            HttpClientUtil.sendStream(httpRequest, response -> {
                log.info("response: {}", response);
                if(StringUtils.isNotEmpty(response)) {
                    answerBuffer.append(response);
                    // 结束标记
                    if(Objects.equals(response, "[done]")) {
                        String answer = answerBuffer.toString();
                        // maximum 10, remove oldest two
                        if(context.size() == MAX_CHAT_CONTEXT) {
                            context.remove(1);
                            context.remove(0);
                        }
                        context.add(ChatContent.buildBySingleText(question, RoleEnum.user.name()));
                        context.add(ChatContent.buildBySingleText(answer, RoleEnum.model.name()));
                    } else {
                        // 流式答案
                        ttsBuffer.get().append(response);
                        if(TRUNCATION_SYMBOLS.contains(response)) {
                            // 将当前ttsBuffer的内容拿去做tts转换，播放，清空ttsBuffer
                            if(!ttsBuffer.get().isEmpty()) {
                                String mp3 = new Text2Speech().toTtsMp3(ttsBuffer.toString());
                                AudioPlayer.playMp3(mp3);
                                ttsBuffer.set(new StringBuilder());
                            }
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            // 播放异常
            AudioPlayer.playMp3("src/main/resources/mp3/网络连接超时.mp3");
        }
    }

    @PostConstruct
    public void init() {
        String authUrl = getAuthUrl();
        String url = authUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://");
        request = new Request.Builder().url(url).build();
        // 启动常驻后台任务
        recordTask();
    }

    @PreDestroy
    public void destroy() {
        if(microphone!= null) {
            microphone.close();
        }
    }
}

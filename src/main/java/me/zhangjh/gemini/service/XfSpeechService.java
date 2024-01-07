package me.zhangjh.gemini.service;

import com.alibaba.fastjson2.JSONObject;
import com.orctom.vad4j.VAD;
import jakarta.annotation.PostConstruct;
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
import okhttp3.WebSocket;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String API_SECRET = "3760b5e9bd1b5cf4bd1379551b925cc4";
    private static final String APP_KEY = "311d42a308bcca3807b4a4e457bd1ece";

    private static final String GEMINI_WEB_URL = "http://wx.zhangjh.me:8080/gemini/generateStream";

    private static final List<String> WAKEUP_WORDS = Arrays.asList("小张小张", "小张同学", "你好小张");

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    private static final OkHttpClient CLIENT;
    private static final Request REQUEST;
    private static final VAD VAD_INSTANCE;
    private static WebSocket socket;

    private static final List<String> TRUNCATION_SYMBOLS = Arrays.asList("，", "。", "！", "？", "、", "...", ";", ":");

    static {
        CLIENT = new OkHttpClient.Builder().build();
        String authUrl = getAuthUrl();
        String url = authUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://");
        REQUEST = new Request.Builder().url(url).build();
        VAD_INSTANCE = new VAD();
    }

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
    private ByteArrayOutputStream bos = new ByteArrayOutputStream();

    private static String getAuthUrl() {
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
            SecretKeySpec spec = new SecretKeySpec(API_SECRET.getBytes(charset), "hmacsha256");
            mac.init(spec);
        } catch (Exception ignored) {
        }
        assert mac != null;
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

    /**
     * 开启麦克风准备拾音，设备异常抛错
     * */
    private TargetDataLine startMicrophone() throws LineUnavailableException {
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

    private boolean isSpeech(byte[] data) {
        return VAD_INSTANCE.isSpeech(data);
    }

    private void recordTask() throws LineUnavailableException, IOException {
        TargetDataLine microphone = startMicrophone();
        // 录音状态，[前一状态，当前状态]
        final int[] preState = {0};
        final int[] curState = {0};
        EXECUTOR_SERVICE.submit(() -> {
            byte[] data = new byte[microphone.getBufferSize() / 5];
            while (true) {
                int read = microphone.read(data, 0, data.length);
                if(read > 0) {
                    // 不是语音，不处理，
                    // 从不是语音到是语音再到不是语音状态时证明累积到了一个正常的语音流，即状态为[1,0]开始处理
                    if(isSpeech(data)) {
                        // 当前正在播放时检测到人声，停止播放
//                        if(AudioPlayer.isPlaying()) {
//                            AudioPlayer.stop();
//                        }
                        preState[0] = curState[0];
                        curState[0] = 1;
                        // 累积音频流
                        bos.write(data, 0, read);
                    } else {
                        preState[0] = curState[0];
                        curState[0] = 0;
                        if(preState[0] == 1) {
                            // 从语音到非语音，即状态数组为[1,0]时，证明累积到了一个正常的语音流
                            socket = CLIENT.newWebSocket(REQUEST, new WebIATWS(new ByteArrayInputStream(bos.toByteArray()), content -> {
                                log.info("content: {}", content);
                                // 清空音频流缓冲区
                                bos.reset();
                                if (StringUtils.isNotEmpty(content)) {
                                    // 如果检测到了唤醒词则开始累积问题语音数据，否则忽略不处理
                                    for (String wakeupWord : WAKEUP_WORDS) {
                                        if (content.contains(wakeupWord)) {
                                            AudioPlayer.playMp3("src/main/resources/mp3/应答语.mp3");
                                            log.info("waked, start collect question.");
                                            // 读取后续音频数据流，准备回答
                                            ByteArrayOutputStream questionBos = new ByteArrayOutputStream();
                                            while (true) {
                                                int readBytes = microphone.read(data, 0, data.length);
                                                log.info("read data 2");
                                                if (readBytes > 0) {
                                                    // 一直累积到收音结束
                                                    if (isSpeech(data)) {
                                                        log.info("collecting question data");
                                                        questionBos.write(data, 0, readBytes);
                                                    } else {
                                                        log.info("question ASR");
                                                        CLIENT.newWebSocket(REQUEST,
                                                                new WebIATWS(new ByteArrayInputStream(questionBos.toByteArray()), question -> {
                                                                    // 问题结束，开始干活
                                                                    log.info("start chat, question: {}", question);
                                                                    executeGeminiTask(question);
                                                                    questionBos.reset();
                                                                    return null;
                                                                }));
                                                        break;
                                                    }
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                                return null;
                            }));
                        }
                    }
                }
            }
        });
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
    }

    @PostConstruct
    public void init() {
        // 启动常驻后台任务
        try {
            recordTask();
        } catch (LineUnavailableException | IOException e) {
            e.printStackTrace();
        }
    }
}

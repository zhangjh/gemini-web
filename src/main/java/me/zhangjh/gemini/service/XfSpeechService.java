package me.zhangjh.gemini.service;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognitionResult;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @Value("${SPEECH_KEY}")
    private String speechKey;
    @Value("${SPEECH_REGION}")
    private String speechRegion;

    private static final String GEMINI_WEB_URL = "http://wx.zhangjh.me:8080/gemini/generateStream";

    private static final List<String> WAKEUP_WORDS = Arrays.asList("小张小张", "小张同学", "你好小张");

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

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
    private ByteArrayOutputStream bos = new ByteArrayOutputStream();

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

    @Autowired
    private HsSpeechService hsSpeechService;

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

    private void recordTask() throws LineUnavailableException {
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
                            CLIENT.newWebSocket(request, new WebIATWS(new ByteArrayInputStream(bos.toByteArray()), content -> {
                                log.info("content: {}", content);
                                // 清空音频流缓冲区
                                bos.reset();
                                if (StringUtils.isNotEmpty(content)) {
                                    // 如果检测到了唤醒词则开始累积问题语音数据，否则忽略不处理
                                    for (String wakeupWord : WAKEUP_WORDS) {
                                        if (content.contains(wakeupWord)) {
//                                            AudioPlayer.playMp3("src/main/resources/mp3/应答语.mp3");
                                            log.info("播放应答语");
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
                                                        CLIENT.newWebSocket(request,
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

    private void recordTaskUseAzure() {
        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
        speechConfig.setSpeechRecognitionLanguage("zh-CN");
        AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
        SpeechRecognizer speechRecognizer = new SpeechRecognizer(speechConfig, audioConfig);
        while (true) {
            // 开始收音
            try {
                Future<SpeechRecognitionResult> task = speechRecognizer.recognizeOnceAsync();
                SpeechRecognitionResult speechRecognitionResult = task.get();
                if (speechRecognitionResult.getReason() == ResultReason.RecognizedSpeech) {
                    String text = speechRecognitionResult.getText();
                    // 如果不是唤醒词则不处理，否则开始累积后续的问题语音输入
                    for (String wakeupWord : WAKEUP_WORDS) {
                        if(wakeupWord.contains(text)) {
                            AudioPlayer.playMp3("src/main/resources/mp3/应答语.mp3");
                            log.info("播放应答语");
                            // 紧接着的语音输入认为是问题
                            task = speechRecognizer.recognizeOnceAsync();
                            SpeechRecognitionResult result = task.get();
                            if(result.getReason() == ResultReason.RecognizedSpeech) {
                                String question = result.getText();
                                // 干活，然后结束，继续等待下一次唤醒，暂时不支持一次唤醒后连续对话
                                log.info("start chat, question: {}", question);
                                executeGeminiTask(question);
                            }
                        }
                    }
                } else if (speechRecognitionResult.getReason() == ResultReason.NoMatch) {
                    log.info("NOMATCH: Speech could not be recognized.");
                }
            } catch (Exception e) {
                log.error("recordTaskUseAzure exception: ", e);
            }
        }
    }

    private void recordTaskUseHs() {
        // 开始收音
        try {
            TargetDataLine microphone = startMicrophone();
            int preState = 0;
            int curState = 0;
            boolean isWakeupWordDetected = false;
            byte[] data = new byte[microphone.getBufferSize() / 5];
            List<byte[]> questionDataList = new ArrayList<>();

            while (true) {
                int read = microphone.read(data, 0, data.length);
                if(read > 0) {
                    if(isSpeech(data)) {
                        // 正在说话
                        curState = 1;
                        if (isWakeupWordDetected) {
                            questionDataList.add(data.clone());
                        }
                    } else {
                        preState = curState;
                        // 当前无语音输入
                        curState = 0;
                        // 当前状态表示语音输入结束，开始识别
                        if(preState == 1) {
                            String asr = hsSpeechService.asr(data);
                            log.info("asr: {}", asr);
                            if(!isWakeupWordDetected) {
                                for (String wakeupWord : WAKEUP_WORDS) {
                                    if (asr.contains(wakeupWord)) {
                                        AudioPlayer.playMp3("src/main/resources/mp3/应答语.mp3");
                                        log.info("播放应答语");
                                        isWakeupWordDetected = true;
                                        break;
                                    }
                                }
                            } else {
                                byte[] questionData = combineAudioData(questionDataList);
                                String question = hsSpeechService.asr(questionData);
                                executeGeminiTask(question);
                                isWakeupWordDetected = false;
                                questionDataList.clear();
                            }
                        }
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
//        recordTaskUseAzure();
        recordTaskUseHs();
    }

    @PreDestroy
    public void destroy() {
        if(microphone!= null) {
            microphone.close();
        }
    }
}

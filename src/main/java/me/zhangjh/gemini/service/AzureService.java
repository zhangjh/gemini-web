package me.zhangjh.gemini.service;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.client.GeminiService;
import me.zhangjh.gemini.common.RoleEnum;
import me.zhangjh.gemini.pojo.ChatContent;
import me.zhangjh.gemini.tools.CommonUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author njhxzhangjihong@126.com
 * @date 10:50 2024/2/26
 * @Description
 */
@Slf4j
@Component
public class AzureService {

    private static final List<String> TRUNCATION_SYMBOLS = Arrays.asList("。", "！", "？", "...");

    private static final List<String> EXIT_WORDS = Arrays.asList("退出", "关闭", "停止", "结束");
    /**
     * maximum 10, role user & model as one, need 2 elements
     * */
    private static final int MAX_CHAT_CONTEXT = 20;
    /**
     * chat context
     * */
    private static final List<ChatContent> CONTEXT = new ArrayList<>(MAX_CHAT_CONTEXT);

    private static SpeechSynthesizer synthesizer;

    private static boolean firstStreamPlayed = false;

    @Value("${SPEECH_KEY}")
    private String speechKey;

    @Value("${SPEECH_REGION}")
    private String speechRegion;

    @Value("${WAKEUP_MODEL_FILE}")
    private String wakeupModelFile;

    @Autowired
    private GeminiService geminiService;

    @PostConstruct
    public void init() throws Exception {
        initSpeech();
        // todo: 多语音唤醒
        ClassPathResource resource = new ClassPathResource(wakeupModelFile);
        String fileName = wakeupModelFile.substring(0, wakeupModelFile.lastIndexOf("."));
        log.info("wakeUpFilePath: {}, fileName: {}", wakeupModelFile, fileName);

        KeywordRecognitionModel recognitionModel = KeywordRecognitionModel.fromStream(resource.getInputStream(),
                fileName, false);
        while (true) {
            AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
            KeywordRecognizer keywordRecognizer = new KeywordRecognizer(audioConfig);
            Future<KeywordRecognitionResult> resultFuture = keywordRecognizer.recognizeOnceAsync(recognitionModel);
            KeywordRecognitionResult result = resultFuture.get();
            // 识别到唤醒词
            if (result.getReason() == ResultReason.RecognizedKeyword) {
                log.info("Keyword recognized: {}", result.getText());
                playContent("主人我在，有什么吩咐？");
                log.info("播放应答语");
                long startTime = System.currentTimeMillis();
                while (true) {
                    // 15s内没有识别到语音需要重新唤醒
                    if (System.currentTimeMillis() - startTime > 15000) {
                        log.info("超时退出监听");
                        break;
                    }
                    SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
                    speechConfig.setSpeechRecognitionLanguage("zh-CN");
                    SpeechRecognizer speechRecognizer =
                            new SpeechRecognizer(speechConfig, audioConfig);
                    Future<SpeechRecognitionResult> task = speechRecognizer.recognizeOnceAsync();
                    SpeechRecognitionResult speechRecognitionResult = task.get();
                    if (speechRecognitionResult.getReason() == ResultReason.RecognizedSpeech) {
                        String question = speechRecognitionResult.getText();
                        // 识别到的是退出词，结束多轮对话回到待唤醒状态
                        boolean match = EXIT_WORDS.stream().anyMatch(question::contains);
                        if(match) {
                            speechRecognizer.stopContinuousRecognitionAsync();
                            log.info("退出命令退出监听");
                            break;
                        }
                        log.info("RECOGNIZED: Text=" + question);
                        executeGeminiTask(question);
                        // 识别到语音后续期
                        startTime = System.currentTimeMillis();
                    } else {
                        log.info("NOMATCH: Speech could not be recognized.");
                    }
                }
            }
        }
    }

    private void initSpeech() {
        SpeechConfig config = SpeechConfig.fromSubscription(speechKey, speechRegion);
        config.setSpeechSynthesisVoiceName("zh-CN-YunxiaNeural");
        synthesizer = new SpeechSynthesizer(config);
        Connection connection = Connection.fromSpeechSynthesizer(synthesizer);
        connection.openConnection(true);
    }

    private void executeGeminiTask(String question) {
        log.info("question: {}", question);
        AtomicReference<StringBuilder> ttsBuffer = new AtomicReference<>(new StringBuilder());
        StringBuilder answerBuffer = new StringBuilder();
        geminiService.streamChat(question, CONTEXT, response -> {
            if(StringUtils.isNotEmpty(response)) {
                // 结束标记
                if(Objects.equals(response, "[done]")) {
                    // 还有未播报的开始播报
                    if(!ttsBuffer.get().isEmpty()) {
                        playContent(ttsBuffer.toString());
                        // 重置标记
                        firstStreamPlayed = false;
                    }
                    String answer = answerBuffer.toString();
                    // maximum 10, remove oldest two
                    if(CONTEXT.size() == MAX_CHAT_CONTEXT) {
                        CONTEXT.remove(1);
                        CONTEXT.remove(0);
                    }
                    CONTEXT.add(ChatContent.buildBySingleText(question, RoleEnum.user.name()));
                    CONTEXT.add(ChatContent.buildBySingleText(answer, RoleEnum.model.name()));
                } else {
                    answerBuffer.append(response);
                    // 流式答案
                    ttsBuffer.get().append(response);
                    // 第一句流式播放
                    if(!firstStreamPlayed) {
                        for (String symbol : TRUNCATION_SYMBOLS) {
                            String tmp = ttsBuffer.get().toString();
                            int index = tmp.indexOf(symbol);
                            if(index != -1) {
                                playContent(tmp.substring(0, index));
                                ttsBuffer.set(new StringBuilder(tmp.substring(index)));
                                firstStreamPlayed = true;
                                break;
                            }
                        }
                    }
                }
            }
            return null;
        });
    }

    private void playContent(String text) {
        log.info("playContent: {}", text);
        text = CommonUtil.clean(text);
        text = CommonUtil.markdown2Text(text);
        if(StringUtils.isEmpty(text)) {
            return;
        }
        try (SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text).get()) {
            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                log.info("Speech synthesized for text: {}", text);
            } else if (result.getReason() == ResultReason.Canceled) {
                SpeechSynthesisCancellationDetails cancellation = SpeechSynthesisCancellationDetails.fromResult(result);
                log.info("CANCELED: SpeechSynthesis was canceled: Reason=" + cancellation.getReason());
                if (cancellation.getReason() == CancellationReason.Error) {
                    log.info("ErrorCode: {}, Error Details: {}", cancellation.getErrorCode(), cancellation.getErrorDetails());
                }
            }
        } catch (Exception e) {
            log.error("playContent exception: ", e);
        }
    }
}

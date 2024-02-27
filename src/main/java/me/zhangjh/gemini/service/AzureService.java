package me.zhangjh.gemini.service;

import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.client.GeminiService;
import me.zhangjh.gemini.common.RoleEnum;
import me.zhangjh.gemini.pojo.ChatContent;
import me.zhangjh.gemini.tools.AudioPlayer;
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

    private static final List<String> TRUNCATION_SYMBOLS = Arrays.asList("，", "。", "！", "？", "、", "...", ";", ":");
    /**
     * maximum 10, role user & model as one, need 2 elements
     * */
    private static final int MAX_CHAT_CONTEXT = 20;
    /**
     * chat context
     * */
    private static final List<ChatContent> CONTEXT = new ArrayList<>(MAX_CHAT_CONTEXT);

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
        // todo: 多语音唤醒
        ClassPathResource resource = new ClassPathResource(wakeupModelFile);
        KeywordRecognitionModel recognitionModel = KeywordRecognitionModel.fromFile(resource.getFile().getAbsolutePath());
        while (true) {
            AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();
            KeywordRecognizer keywordRecognizer = new KeywordRecognizer(audioConfig);
            Future<KeywordRecognitionResult> resultFuture = keywordRecognizer.recognizeOnceAsync(recognitionModel);
            KeywordRecognitionResult result = resultFuture.get();
            // 识别到唤醒词
            // todo: 唤醒后一定时间内支持连续问答
            if (result.getReason() == ResultReason.RecognizedKeyword) {
                log.info("Keyword recognized: {}", result.getText());
                AudioPlayer.playMp3("src/main/resources/audio/应答.mp3");
                log.info("播放应答语");

                SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
                speechConfig.setSpeechRecognitionLanguage("zh-CN");
                SpeechRecognizer speechRecognizer =
                        new SpeechRecognizer(speechConfig, audioConfig);
                Future<SpeechRecognitionResult> task = speechRecognizer.recognizeOnceAsync();
                SpeechRecognitionResult speechRecognitionResult = task.get();
                if (speechRecognitionResult.getReason() == ResultReason.RecognizedSpeech) {
                    String question = speechRecognitionResult.getText();
                    log.info("RECOGNIZED: Text=" + speechRecognitionResult.getText());
                    executeGeminiTask(question);
                }
            }
        }
    }

    private void executeGeminiTask(String question) {
        log.info("question: {}", question);
        AtomicReference<StringBuilder> ttsBuffer = new AtomicReference<>(new StringBuilder());
        StringBuilder answerBuffer = new StringBuilder();
        geminiService.streamChat(question, CONTEXT, response -> {
            log.info("response: {}", response);
            if(StringUtils.isNotEmpty(response)) {
                // 结束标记
                if(Objects.equals(response, "[done]")) {
                    // 还有未播报的开始播报
                    if(!ttsBuffer.get().isEmpty()) {
                        playContent(ttsBuffer.toString());
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
                    if(TRUNCATION_SYMBOLS.contains(response)) {
                        // 将当前ttsBuffer的内容拿去做tts转换，播放，清空ttsBuffer
                        if(!ttsBuffer.get().isEmpty()) {
                            playContent(ttsBuffer.toString());
                            ttsBuffer.set(new StringBuilder());
                        }
                    }
                }
            }
            return null;
        });
    }

    private void playContent(String text) {
        // 去除换行符
        text = text.replaceAll("\n", "");
        SpeechConfig config = SpeechConfig.fromSubscription(speechKey, speechRegion);
        config.setSpeechSynthesisVoiceName("zh-CN-YunxiaNeural");
        SpeechSynthesizer synthesizer = new SpeechSynthesizer(config);
        try (synthesizer; SpeechSynthesisResult result = synthesizer.SpeakTextAsync(text).get()) {
            if (result.getReason() == ResultReason.SynthesizingAudioCompleted) {
                log.info("Speech synthesized for text [" + text + "]");
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

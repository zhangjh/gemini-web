package me.zhangjh.gemini.service;

import com.alibaba.fastjson.JSONObject;
import com.microsoft.cognitiveservices.speech.*;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.chatgpt.dto.Message;
import me.zhangjh.chatgpt.dto.request.ChatRequest;
import me.zhangjh.chatgpt.dto.response.ChatResponse;
import me.zhangjh.gemini.client.GeminiService;
import me.zhangjh.gemini.common.RoleEnum;
import me.zhangjh.gemini.pojo.ChatContent;
import me.zhangjh.gemini.pojo.TextPart;
import me.zhangjh.gemini.tools.CommonUtil;
import me.zhangjh.share.util.HttpClientUtil;
import me.zhangjh.share.util.HttpRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.*;
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

    /**
     * 用来播放语音
     * */
    private static SpeechSynthesizer ttsSynthesizer;

    /**
     * 用来收音唤醒词
     * */
    private static KeywordRecognizer keywordRecognizer;

    /**
     * 用来收音问题
     * */
    private static SpeechRecognizer speechRecognizer;

    private static boolean firstStreamPlayed = false;

    private static final AtomicReference<StringBuilder> TTS_BUFFER = new AtomicReference<>(new StringBuilder());
    private static final StringBuilder ANSWER_BUFFER = new StringBuilder();

    @Value("${SPEECH_KEY}")
    private String speechKey;

    @Value("${SPEECH_REGION}")
    private String speechRegion;

    @Value("${WAKEUP_MODEL_FILE}")
    private String wakeupModelFile;

    @Value("${OPENAI_KEY}")
    private String openaiApiKey;

    @Autowired
    private GeminiService geminiService;

    @PostConstruct
    public void init() throws Exception {
        // todo: 多语音唤醒
        initSpeech();
        ClassPathResource resource = new ClassPathResource(wakeupModelFile);
        String fileName = wakeupModelFile.substring(0, wakeupModelFile.lastIndexOf("."));
        log.info("wakeUpFilePath: {}, fileName: {}", wakeupModelFile, fileName);
        KeywordRecognitionModel recognitionModel = KeywordRecognitionModel.fromStream(resource.getInputStream(),
                fileName, false);
        while (true) {
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
                        playContent("主人你很久没有说话了，我先退下了，下次再见。");
                        log.info("超时退出监听");
                        break;
                    }
                    Future<SpeechRecognitionResult> task = speechRecognizer.recognizeOnceAsync();
                    SpeechRecognitionResult speechRecognitionResult = task.get();
                    if (speechRecognitionResult.getReason() == ResultReason.RecognizedSpeech) {
                        String question = speechRecognitionResult.getText();
                        // 识别到的是退出词，结束多轮对话回到待唤醒状态
                        boolean match = EXIT_WORDS.stream().anyMatch(question::contains);
                        if(match) {
                            speechRecognizer.stopContinuousRecognitionAsync();
                            playContent("好的，下次再见。");
                            log.info("退出命令退出监听");
                            break;
                        }
                        log.info("RECOGNIZED: Text=" + question);
                        if(StringUtils.isNotEmpty(question)) {
                            doTask(question);
                            // 识别到语音后续期
                            startTime = System.currentTimeMillis();
                        }
                    } else {
                        log.info("NOMATCH: Speech could not be recognized.");
                    }
                }
            }
        }
    }

    private void initSpeech() {
        AudioConfig audioConfig = AudioConfig.fromDefaultMicrophoneInput();

        SpeechConfig config = SpeechConfig.fromSubscription(speechKey, speechRegion);
        config.setSpeechSynthesisVoiceName("zh-CN-YunxiaNeural");
        ttsSynthesizer = new SpeechSynthesizer(config);
        Connection connection = Connection.fromSpeechSynthesizer(ttsSynthesizer);
        connection.openConnection(true);

        keywordRecognizer = new KeywordRecognizer(audioConfig);

        SpeechConfig speechConfig = SpeechConfig.fromSubscription(speechKey, speechRegion);
        speechConfig.setSpeechRecognitionLanguage("zh-CN");
        speechRecognizer =
                new SpeechRecognizer(speechConfig, audioConfig);
    }

    private void doTask(String question) {
        executeChatGptTask(question);
//        executeGeminiTask(question);
    }

    private void executeChatGptTask(String question) {
        log.info("question: {}", question);
        playContent("好的，让我思考一下......");
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel("gpt-4");
        List<Message> messages = new ArrayList<>();
        // 转换Gemini的上下文到ChatGpt的上下文
        for (ChatContent chatContent : CONTEXT) {
            Message message = new Message();
            String role = chatContent.getRole();
            List<TextPart> parts = chatContent.getParts();
            message.setContent(parts.get(0).getText());
            if(role.equals(RoleEnum.user.name())) {
                message.setRole(me.zhangjh.chatgpt.constant.RoleEnum.user.name());
            } else {
                message.setRole(me.zhangjh.chatgpt.constant.RoleEnum.assistant.name());
            }
            messages.add(message);
        }
        Message message = new Message();
        message.setRole(RoleEnum.user.name());
        message.setContent(question);
        messages.add(message);
        chatRequest.setMessages(messages);

        HttpRequest request = new HttpRequest("https://openai.ehco-relay.cc/v1/chat/completions");
        request.setBizHeaderMap(Map.of("Authorization", "Bearer " + openaiApiKey));
        request.setReqData(JSONObject.toJSONString(chatRequest));
        HttpClientUtil.sendStream(request, response -> {
            log.info("response: {}", response);
            if(response.equals("[done]")) {
                handleAnswerResponse(question, response);
                return null;
            }
            ChatResponse chatResponse = JSONObject.parseObject(response, ChatResponse.class);
            if (chatResponse != null && chatResponse.getChoices() != null && chatResponse.getChoices().size() > 0) {
                String answer = chatResponse.getChoices().get(0).getMessage().getContent();
                handleAnswerResponse(question, answer);
            }
            return null;
        }, t -> {
            t.printStackTrace();
            return null;
        });
    }

    private void executeGeminiTask(String question) {
        log.info("question: {}", question);
        geminiService.streamChat(question, CONTEXT, response -> {
            handleAnswerResponse(question, response);
            return null;
        });
    }

    public void handleAnswerResponse(String question, String response) {
        if(StringUtils.isNotEmpty(response)) {
            // 结束标记
            if(Objects.equals(response, "[done]")) {
                // 还有未播报的开始播报
                if(!TTS_BUFFER.get().isEmpty()) {
                    playContent(TTS_BUFFER.toString());
                    // 重置标记
                    firstStreamPlayed = false;
                }
                String answer = ANSWER_BUFFER.toString();
                // maximum 10, remove oldest two
                if(CONTEXT.size() == MAX_CHAT_CONTEXT) {
                    CONTEXT.remove(1);
                    CONTEXT.remove(0);
                }
                CONTEXT.add(ChatContent.buildBySingleText(question, RoleEnum.user.name()));
                CONTEXT.add(ChatContent.buildBySingleText(answer, RoleEnum.model.name()));
            } else {
                ANSWER_BUFFER.append(response);
                // 流式答案
                TTS_BUFFER.get().append(response);
                // 第一句流式播放
                if(!firstStreamPlayed) {
                    for (String symbol : TRUNCATION_SYMBOLS) {
                        String tmp = TTS_BUFFER.get().toString();
                        int index = tmp.indexOf(symbol);
                        if(index != -1) {
                            playContent(tmp.substring(0, index));
                            TTS_BUFFER.set(new StringBuilder(tmp.substring(index)));
                            firstStreamPlayed = true;
                            break;
                        }
                    }
                }
            }
        }
    }

    private void playContent(String text) {
        log.info("playContent: {}", text);
        text = CommonUtil.clean(text);
        text = CommonUtil.markdown2Text(text);
        if(StringUtils.isEmpty(text)) {
            return;
        }
        try (SpeechSynthesisResult result = ttsSynthesizer.SpeakTextAsync(text).get()) {
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

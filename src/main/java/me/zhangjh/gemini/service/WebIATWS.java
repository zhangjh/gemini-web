package me.zhangjh.gemini.service;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.pojo.Decoder;
import me.zhangjh.gemini.pojo.ResponseData;
import me.zhangjh.gemini.pojo.Text;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Function;

/**
 * @author njhxzhangjihong@126.com
 * @date 17:51 2023/12/27
 * @Description
 */
@Slf4j
public class WebIATWS extends WebSocketListener {

    private static final String APP_ID = "596b6932";

    private final InputStream inputStream;
    private final Function<String, Void> cb;
    private Stopwatch sw;

    private static final Decoder DECODER = new Decoder();
    private static final Gson JSON = new Gson();

    private static final int STATUS_FIRST_FRAME = 0;
    private static final int STATUS_CONTINUE_FRAME = 1;
    private static final int STATUS_LAST_FRAME = 2;

    public WebIATWS(InputStream inputStream, Function<String, Void> cb) {
        this.inputStream = inputStream;
        this.cb = cb;
    }

    @Override
    public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
        super.onOpen(webSocket, response);
        sw = Stopwatch.createStarted();

        // 每一帧音频大小，建议每40ms发送122B
        int frameSize = 1280;
        int interval = 40;
        // 音频状态
        int status = 0;
        byte[] buffer = new byte[frameSize];
        boolean flag = false;
        // 发送音频
        while (true) {
            try {
                int len = inputStream.read(buffer);
                if (len == -1) {
                    status = STATUS_LAST_FRAME;
                }
                switch (status) {
                    case STATUS_FIRST_FRAME:
                        JsonObject frame = new JsonObject();
                        //第一帧必须发送
                        JsonObject business = new JsonObject();
                        //第一帧必须发送
                        JsonObject common = new JsonObject();
                        //每一帧都要发送
                        JsonObject data = new JsonObject();
                        // 填充common
                        common.addProperty("app_id", APP_ID);
                        //填充business
                        business.addProperty("language", "zh_cn");
                        business.addProperty("domain", "iat");
                        business.addProperty("accent", "mandarin");
                        // 是否开启动态修正
                        business.addProperty("dwa", "wpgs");
                        //填充data
                        data.addProperty("status", STATUS_FIRST_FRAME);
                        data.addProperty("format", "audio/L16;rate=16000");
                        data.addProperty("encoding", "raw");
                        data.addProperty("audio", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
                        //填充frame
                        frame.add("common", common);
                        frame.add("business", business);
                        frame.add("data", data);
                        webSocket.send(frame.toString());
                        status = STATUS_CONTINUE_FRAME;
                        break;
                    case STATUS_CONTINUE_FRAME:
                        JsonObject frame1 = new JsonObject();
                        JsonObject data1 = new JsonObject();
                        data1.addProperty("status", STATUS_CONTINUE_FRAME);
                        data1.addProperty("format", "audio/L16;rate=16000");
                        data1.addProperty("encoding", "raw");
                        data1.addProperty("audio", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
                        frame1.add("data", data1);
                        webSocket.send(frame1.toString());
                        break;
                    case STATUS_LAST_FRAME:
                        JsonObject frame2 = new JsonObject();
                        JsonObject data2 = new JsonObject();
                        data2.addProperty("status", STATUS_LAST_FRAME);
                        data2.addProperty("audio", "");
                        data2.addProperty("format", "audio/L16;rate=16000");
                        data2.addProperty("encoding", "raw");
                        frame2.add("data", data2);
                        webSocket.send(frame2.toString());
                        flag = true;
                        break;
                    default:
                        throw new RuntimeException("unexpected status");
                }
                if(flag) {
                    break;
                }
                Thread.sleep(interval);
            } catch (IOException | InterruptedException e) {
                log.error("onOpen exception: ", e);
            }
        }

    }

    @Override
    public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
        super.onMessage(webSocket, text);
        ResponseData resp = JSON.fromJson(text, ResponseData.class);
        if (resp != null) {
            if (resp.getCode() != 0) {
                log.error("code: {}, error: {}", resp.getCode(), resp.getMessage());
//                    System.out.println( "错误码查询链接：https://www.xfyun.cn/document/error-code");
                return;
            }
            if (resp.getData() != null) {
                if (resp.getData().getResult() != null) {
                    Text te = resp.getData().getResult().getText();
                    try {
                        DECODER.decode(te);
                        // 中间识别结果
                        log.info("中间结果输出耗时：{}", sw.elapsed());
                        this.cb.apply(DECODER.toString());
                    } catch (Exception e) {
                        log.error("decode exception: ", e);
                    }
                }
                if (resp.getData().getStatus() == 2) {
                    // resp.data.status ==2 说明数据全部返回完毕，可以关闭连接，释放资源
                    log.info("耗时：{}", sw.elapsed());
                    log.info("最终识别结果: {}", DECODER);
                    try {
                        this.cb.apply(DECODER.toString());
                    } catch (Exception e) {
                        log.error("decode exception: ", e);
                    }
                    DECODER.discard();
                    webSocket.close(1000, "");
                }
            }
        }
    }

    @Override
    public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response resp) {
        super.onFailure(webSocket, t, resp);
        log.error("onFailure: ", t);
        if(resp != null) {
            int code = resp.code();
            if (101 != code) {
                log.error("connection failed");
                System.exit(0);
            }
        }
    }
}

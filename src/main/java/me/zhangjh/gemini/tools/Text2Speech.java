package me.zhangjh.gemini.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import me.zhangjh.gemini.pojo.TtsRequest;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;

import java.io.*;
import java.util.Base64;

/**
 * @author njhxzhangjihong@126.com
 * @date 09:52 2023/12/29
 * @Description
 */
public class Text2Speech {

    public static final String HOST = "openspeech.bytedance.com";
    public static final String API_URL = "https://" + HOST + "/api/v1/tts";

    @Value("${bytedance.accessToken}")
    private String accessToken;

    @Value("${bytedance.appId}")
    private String appId;

    public String toTts(String text) {
        TtsRequest ttsRequest = new TtsRequest(text, appId);
        String response = post(JSON.toJSONString(ttsRequest));
        return JSONObject.parseObject(response).getString("data");
    }

    public String toTtsMp3(String text) {
        String data = this.toTts(text);
        byte[] bytes = Base64.getDecoder().decode(data);
        File file = new File("src/main/resources/mp3/tmp/" + System.currentTimeMillis() + ".mp3");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException("写入mp3异常：", e);
        }

        return file.getPath();
    }

    private String post(String json) {
        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(API_URL)
                .post(body)
                .header("Authorization", "Bearer; " + accessToken)
                .build();
        try (Response response = client.newCall(request).execute()) {
            assert response.body() != null;
            return response.body().string();
        } catch (Exception e) {
            throw new RuntimeException("tts exception: ", e);
        }
    }

    public static void main(String[] args) {
        Text2Speech text2Speech = new Text2Speech();
        String mp3 = text2Speech.toTtsMp3("主人，网络连接超时了");
        AudioPlayer.playMp3(mp3);
    }
}

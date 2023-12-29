package me.zhangjh.gemini.pojo;

import java.util.UUID;

public class TtsRequest {
    public static final String CLUSTER = "volcano_tts";

    public TtsRequest() {
    }

    public TtsRequest(String text, String appid) {
        this.request.text = text;
        this.app.appid = appid;
    }

    private App app = new App();
    private User user = new User();
    private Audio audio = new Audio();
    private Request request = new Request();

    public App getApp() {
        return app;
    }

    public void setApp(App app) {
        this.app = app;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Audio getAudio() {
        return audio;
    }

    public void setAudio(Audio audio) {
        this.audio = audio;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public class App {
        private String appid;
        private String token = "access_token"; // 目前未生效，填写默认值：access_token
        private String cluster = CLUSTER;

        public String getAppid() {
            return appid;
        }

        public void setAppid(String appid) {
            this.appid = appid;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getCluster() {
            return cluster;
        }

        public void setCluster(String cluster) {
            this.cluster = cluster;
        }
    }

    public class User {
        private String uid = "388808087185088"; // 目前未生效，填写一个默认值就可以

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }
    }

    public class Audio {
        private String voice_type = "BV061_streaming";
        private String encoding = "mp3";
        private float speed_ratio = 1.2f;
        private float volume_ratio = 20;
        private float pitch_ratio = 10;

        public String getVoice_type() {
            return voice_type;
        }

        public void setVoice_type(String voice_type) {
            this.voice_type = voice_type;
        }

        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        public float getSpeedRatio() {
            return speed_ratio;
        }

        public void setSpeedRatio(int speed_ratio) {
            this.speed_ratio = speed_ratio;
        }

        public float getVolumeRatio() {
            return volume_ratio;
        }

        public void setVolumeRatio(int volume_ratio) {
            this.volume_ratio = volume_ratio;
        }

        public float getPitchRatio() {
            return pitch_ratio;
        }

        public void setPitchRatio(int pitch_ratio) {
            this.pitch_ratio = pitch_ratio;
        }
    }

    public class Request {
        private String reqid = UUID.randomUUID().toString();
        private String text;
        private String text_type = "plain";
        private String operation = "query";

        public String getReqid() {
            return reqid;
        }

        public void setReqid(String reqid) {
            this.reqid = reqid;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getText_type() {
            return text_type;
        }

        public void setText_type(String text_type) {
            this.text_type = text_type;
        }

        public String getOperation() {
            return operation;
        }

        public void setOperation(String operation) {
            this.operation = operation;
        }
    }
}
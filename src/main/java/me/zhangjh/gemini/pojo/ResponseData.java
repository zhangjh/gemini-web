package me.zhangjh.gemini.pojo;

/**
 * @author njhxzhangjihong@126.com
 * @date 17:51 2023/12/27
 * @Description
 */
@lombok.Data
public class ResponseData {
    private int code;
    private String message;
    private String sid;
    private Data data;
}



class Ws {
    Cw[] cw;
    int bg;
    int ed;
}
class Cw {
    int sc;
    String w;
}
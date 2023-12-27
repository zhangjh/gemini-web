package me.zhangjh.gemini.pojo;

import com.google.gson.JsonObject;
import lombok.Data;

import java.util.Arrays;

/**
 * @author njhxzhangjihong@126.com
 * @date 17:56 2023/12/27
 * @Description
 */
@Data
public class Text {
    int sn;
    int bg;
    int ed;
    String text;
    String pgs;
    int[] rg;
    boolean deleted;
    boolean ls;
    JsonObject vad;
    @Override
    public String toString() {
        return "Text{" +
                "bg=" + bg +
                ", ed=" + ed +
                ", ls=" + ls +
                ", sn=" + sn +
                ", text='" + text + '\'' +
                ", pgs=" + pgs +
                ", rg=" + Arrays.toString(rg) +
                ", deleted=" + deleted +
                ", vad=" + (vad==null ? "null" : vad.getAsJsonArray("ws").toString()) +
                '}';
    }
}
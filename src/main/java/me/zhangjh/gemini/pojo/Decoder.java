package me.zhangjh.gemini.pojo;

import lombok.Data;

import java.util.Arrays;

/**
 * @author njhxzhangjihong@126.com
 * @date 17:58 2023/12/27
 * @Description
 */
@Data
public class Decoder {
    private Text[] texts;
    private int defc = 10;
    public Decoder() {
        this.texts = new Text[this.defc];
    }
    public synchronized void decode(Text text) {
        if (text.sn >= this.defc) {
            this.resize();
        }
        if ("rpl".equals(text.pgs)) {
            for (int i = text.rg[0]; i <= text.rg[1]; i++) {
                this.texts[i].deleted = true;
            }
        }
        this.texts[text.sn] = text;
    }
    @Override

    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Text t : this.texts) {
            if (t != null && !t.deleted) {
                sb.append(t.text);
            }
        }
        return sb.toString();
    }
    public void resize() {
        int oc = this.defc;
        this.defc <<= 1;
        Text[] old = this.texts;
        this.texts = new Text[this.defc];
        if (oc >= 0) {
            System.arraycopy(old, 0, this.texts, 0, oc);
        }
    }
    public void discard(){
        Arrays.fill(this.texts, null);
    }
}
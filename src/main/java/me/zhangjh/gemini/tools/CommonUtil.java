package me.zhangjh.gemini.tools;

import com.vladsch.flexmark.ast.Node;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.options.MutableDataSet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Whitelist;

import javax.sound.sampled.*;

/**
 * @author njhxzhangjihong@126.com
 * @date 22:21 2024/2/22
 * @Description
 */
@Slf4j
public class CommonUtil {
    public static boolean vad(byte[] data) {
        return silenceCheck(data);
    }

    private static void removeNoise(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] *= 0.5;
        }
    }

    public static boolean silenceCheck(byte[] raw) {
        removeNoise(raw);
        double thread = 20d;
        double sum = 0d;
        if (raw.length == 0) {
            return true;
        }
        for (double v : raw) {
            sum += v;
        }
        double average = sum/raw.length;
        double sumMeanSquare = 0d;
        for (byte b : raw) {
            sumMeanSquare += Math.pow(b - average, 2d);
        }
        double averageMeanSquare = sumMeanSquare / raw.length;

        return Math.sqrt(averageMeanSquare) <= thread;
    }

    /**
     * 开启麦克风准备拾音，设备异常抛错
     * */
    public static TargetDataLine startMicrophone() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new RuntimeException("Line not support, may be there is no microphone exist.");
        }
        TargetDataLine microphone = (TargetDataLine) AudioSystem.getLine(info);
        microphone.open(format, microphone.getBufferSize());
        microphone.start();
        return microphone;
    }

    public static String markdown2Html(String markdown) {
        if(StringUtils.isEmpty(markdown)) {
            return "";
        }
        MutableDataSet dataSet = new MutableDataSet();
        Parser parser = Parser.builder(dataSet).build();
        HtmlRenderer renderer = HtmlRenderer.builder(dataSet).build();
        Node document = parser.parse(markdown);
        return renderer.render(document);
    }

    public static String html2Text(String html) {
        if(StringUtils.isEmpty(html)) {
            return "";
        }
        Document document = Jsoup.parse(html);
        Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
        document.outputSettings(outputSettings);
        document.select("br").append("\\n");
        document.select("p").prepend("\\n");
        document.select("p").append("\\n");
        String newHtml = document.html().replaceAll("\\\\n", "\n");
        String plainText = Jsoup.clean(newHtml, "", Whitelist.none(), outputSettings);
        return StringEscapeUtils.unescapeHtml(plainText.trim());
    }

    public static String markdown2Text(String markdown) {
        String text = html2Text(markdown2Html(markdown));
        return text.replaceAll("\\*", "");
    }

    public static String clean(String text) {
        if(StringUtils.isEmpty(text)) {
            return "";
        }
        // 将\n回车换成html的回车
        return text.replaceAll("\\n", "<br>");
    }
}

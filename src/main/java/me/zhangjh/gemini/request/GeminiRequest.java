package me.zhangjh.gemini.request;

import lombok.Data;
import me.zhangjh.gemini.pojo.ChatContent;

import java.util.List;

/**
 * @author njhxzhangjihong@126.com
 * @date 10:56 2023/12/24
 * @Description
 */
@Data
public class GeminiRequest {

    private String text;

    private List<ChatContent> context;
}

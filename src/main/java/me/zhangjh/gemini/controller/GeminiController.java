package me.zhangjh.gemini.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import me.zhangjh.gemini.client.GeminiService;
import me.zhangjh.gemini.pojo.Candidate;
import me.zhangjh.gemini.pojo.ChatContent;
import me.zhangjh.gemini.pojo.Content;
import me.zhangjh.gemini.pojo.Part;
import me.zhangjh.gemini.request.GeminiRequest;
import me.zhangjh.gemini.response.TextResponse;
import me.zhangjh.share.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * @author njhxzhangjihong@126.com
 * @date 09:48 2023/12/24
 * @Description
 */
@RestController
@RequestMapping("/gemini")
@Slf4j
public class GeminiController {

    @Autowired
    private GeminiService geminiService;

    @PostMapping("/generateByText")
    public Response<String> generateByText(@RequestBody GeminiRequest request) {
        TextResponse textResponse = geminiService.generateByText(request.getText());
        List<String> res = new ArrayList<>();
        for (Candidate candidate : textResponse.getCandidates()) {
            Content content = candidate.getContent();
            for (Part part : content.getParts()) {
                res.add(part.getText());
            }
        }
        return Response.success(String.join("", res));
    }

    /**
     * 优先使用该接口，支持多轮对话且流式返回
     * */
    @PostMapping(value = "/generateStream")
    public void generateStream(@RequestBody GeminiRequest request, HttpServletResponse response)
            throws IOException {
        response.setContentType("text/plain;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        String text = request.getText();
        List<ChatContent> context = request.getContext();
        geminiService.streamChat(text, context, res -> {
            log.info("res: {}", res);
            writer.write(res);
            writer.flush();
            return null;
        });
    }

    @PostMapping("/multiTurn")
    public Response<String> multiTurn(@RequestBody GeminiRequest request) {
        String chatRes = geminiService.multiTurnChat(request.getText(), request.getContext());
        return Response.success(chatRes);
    }
}

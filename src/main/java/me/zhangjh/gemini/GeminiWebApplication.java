package me.zhangjh.gemini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * @author zhangjh
 */
@SpringBootApplication
@ComponentScan(basePackages = {"me.zhangjh"})
public class GeminiWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(GeminiWebApplication.class, args);
    }

}

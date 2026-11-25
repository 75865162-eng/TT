package com.wimoor.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Wimoor AI 能力模块入口：Skill 中心 / 选品研究 / AI 对话（只读分析，低内存）。 */
@SpringBootApplication
public class WimoorAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WimoorAiApplication.class, args);
    }
}

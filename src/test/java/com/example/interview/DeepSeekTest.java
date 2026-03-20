package com.example.interview;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.chat.client.ChatClient;

@SpringBootTest(properties = {
        "rocketmq.name-server=127.0.0.1:9876",
        "app.a2a.bus.type=inmemory"
})
public class DeepSeekTest {

    @Autowired
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @Test
    public void testLongPrompt() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("这是一个长文本测试，用于触发 DeepSeek 的 prompt cache。");
        }
        try {
            String response = chatClient.prompt()
                    .user(sb.toString() + "\n请总结上面的文本。")
                    .call()
                    .content();
            System.out.println("Response: " + response);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

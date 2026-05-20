package cn.hollis.llm.mentor.know.engine.chat.controller;

import cn.hollis.llm.mentor.know.engine.ai.service.CommonChatService;
import cn.hollis.llm.mentor.know.engine.ai.service.IntentRecognitionService;
import cn.hollis.llm.mentor.know.engine.ai.service.TitleSummaryService;
import cn.hollis.llm.mentor.know.engine.business.entity.CarInfo;
import cn.hollis.llm.mentor.know.engine.business.service.CarInfoService;
import cn.hollis.llm.mentor.know.engine.chat.entity.ChatConversation;
import cn.hollis.llm.mentor.know.engine.chat.entity.ChatMessage;
import cn.hollis.llm.mentor.know.engine.chat.entity.ChatParam;
import cn.hollis.llm.mentor.know.engine.chat.memory.DatabaseChatMemoryStore;
import cn.hollis.llm.mentor.know.engine.chat.service.ChatApplicationService;
import cn.hollis.llm.mentor.know.engine.chat.service.ChatConversationService;
import cn.hollis.llm.mentor.know.engine.chat.service.ChatMessageService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 流式对话接口
 */
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private CommonChatService commonChatService;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String chatModelApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String chatModelBaseUrl;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatApplicationService chatApplicationService;

    private IntentRecognitionService intentRecognitionService;

    @Autowired
    private DatabaseChatMemoryStore databaseChatMemoryStore;

    @PostConstruct
    public void init() {
        intentRecognitionService = AiServices.builder(IntentRecognitionService.class).chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(10).chatMemoryStore(databaseChatMemoryStore).build()).build();
    }

    /**
     * 流式对话接口
     * <p>
     * 入参：userId、content（用户问题）、conversationId（可选）
     * 返回：SSE 流，每个 token 逐字推送；流结束前推送一条 [DONE] 事件携带 conversationId
     * <p>
     * 进度通知格式：{@code [PROGRESS]:xxx...}，用于在前端展示当前处理阶段，减少等待焦虑。
     * 推送环节包括：意图识别、问题改写、问题路由、排序筛选、生成回答等。
     *
     * @param userId         用户ID
     * @param content        用户问题
     * @param conversationId 会话ID（可选，不传则自动创建新会话）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> send(
            @RequestParam String userId,
            @RequestParam String content,
            @RequestParam(required = false) String conversationId) {

        // 1. 处理会话：没有 conversationId 则创建新会话
        final String finalConversationId;
        if (conversationId == null || conversationId.isBlank()) {

            // 同步：先用 content 前 20 个字符作为临时标题，快速建会话
            String tempTitle = content.substring(0, Math.min(content.length(), 20));
            finalConversationId = chatConversationService.createConversation(userId, tempTitle);
            log.info("创建新会话: conversationId={}, tempTitle={}", finalConversationId, tempTitle);

            // 异步：用虚拟线程调用 LLM 生成摘要标题，完成后回写到数据库
            Thread.ofVirtual().name("title-summary-" + finalConversationId).start(() -> {
                try {
                    OpenAiChatModel titleChatModel = OpenAiChatModel.builder()
                            .apiKey(chatModelApiKey)
                            .modelName("qwen3.5-flash")
                            .temperature(0.7)
                            .baseUrl(chatModelBaseUrl)
                            .customParameters(Map.of("enable_thinking", false))
                            .build();
                    TitleSummaryService titleSummaryService = AiServices.builder(TitleSummaryService.class)
                            .chatModel(titleChatModel)
                            .build();
                    String aiTitle = titleSummaryService.generateTitle(content);
                    chatConversationService.updateTitle(finalConversationId, aiTitle);
                    log.info("异步标题更新完成: conversationId={}, title={}", finalConversationId, aiTitle);
                } catch (Exception e) {
                    log.warn("异步标题生成失败, 保留临时标题: conversationId={}", finalConversationId, e);
                }
            });
        } else {
            finalConversationId = conversationId;
        }

        // 2. 保存用户消息
        String messageId = chatMessageService.saveUserMessage(finalConversationId, content);
        String assistantMessageId = chatMessageService.saveAssistantMessage(finalConversationId);

        // 清除该会话的内存缓存，确保从DB重新加载最新消息（含刚保存的用户消息）
        databaseChatMemoryStore.evictCache(finalConversationId);

        // 3. 流式返回：先发送意图识别进度，再执行意图识别
        //    使用 Mono.fromCallable + subscribeOn(boundedElastic) 将阻塞调用移到弹性线程池，
        //    释放 WebFlux 事件循环，确保进度消息能立即 flush 到前端
        return Flux.just("[PROGRESS]:正在识别您的意图...")
                .concatWith(
                        Mono.fromCallable(() -> intentRecognitionService.chat(finalConversationId, content))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMapMany(intentRecognitionResult -> {
                                    // 意图识别完成后清除缓存，避免意图识别的AI响应污染后续RAG对话的历史记忆
                                    databaseChatMemoryStore.evictCache(finalConversationId);

                                    // 4. 如果用户问题不相关，使用一个通用的LLM做对话
                                    if (!intentRecognitionResult.related()) {
                                        StringBuilder contentBuilder = new StringBuilder();
                                        return Flux.concat(
                                                Flux.just("[PROGRESS]:正在为您生成回答..."),
                                                commonChatService.streamChat(userId, content)
                                                        .doOnNext(token -> {
                                                            contentBuilder.append(token);
                                                        })
                                                        .doOnComplete(() -> chatMessageService.updateContent(assistantMessageId, contentBuilder.toString()))
                                        );
                                    }

                                    // 5. 相关问题，走RAG流程（进度由内部组件发出）
                                    return chatApplicationService.chat(new ChatParam(userId, finalConversationId, messageId, content, assistantMessageId, intentRecognitionResult));
                                })
                )
                .doOnError(e -> log.error("流式对话异常: conversationId={}", finalConversationId, e))
                // 6. 在流末尾追加一条 [DONE] 事件，携带 conversationId
                .concatWith(Mono.just("[DONE]:" + finalConversationId));
    }

    /**
     * 查询指定用户的对话列表，按更新时间倒序排序
     *
     * @param userId 用户ID
     */
    @GetMapping("/list")
    public List<ChatConversation> listConversations(@RequestParam String userId) {
        return chatConversationService.getConversationsByUserId(userId);
    }

    /**
     * 查询指定对话的消息列表，按创建时间正序排序
     *
     * @param conversationId 会话ID
     */
    @GetMapping("/messages")
    public List<ChatMessage> listMessages(@RequestParam String conversationId) {
        return chatMessageService.getMessagesByConversationId(conversationId);
    }

    /**
     * 删除对话（同时删除该对话下所有消息）
     *
     * @param conversationId 会话ID
     */
    @DeleteMapping("/{conversationId}")
    public boolean deleteConversation(@PathVariable String conversationId) {
        chatMessageService.deleteMessagesByConversationId(conversationId);
        return chatConversationService.deleteConversation(conversationId);
    }
}

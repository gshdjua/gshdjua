package com.example.demo.controller;

import com.example.demo.entity.AssistantConversation;
import com.example.demo.entity.AssistantMessage;
import com.example.demo.entity.Audio;
import com.example.demo.entity.User;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.AssistantConversationMapper;
import com.example.demo.mapper.UserMapper;
import com.example.demo.service.DeepSeekMusicAgent;
import com.example.demo.service.MusicLibraryAgent;
import com.example.demo.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    @Autowired
    private DeepSeekMusicAgent deepSeekMusicAgent;

    @Autowired
    private MusicLibraryAgent musicLibraryAgent;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AssistantConversationMapper assistantConversationMapper;

    @Autowired
    private AudioMapper audioMapper;

    @PostMapping("/chat")
    @Transactional
    public Map<String, Object> chat(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        Integer userId = getUserId(request);
        Long conversationId = toLong(payload.get("conversationId"));
        AssistantConversation conversation = conversationId == null ? null
                : assistantConversationMapper.selectByIdAndUserId(conversationId, userId);
        if (conversation == null) return result(500, "Conversation not found", null);

        String message = String.valueOf(payload.getOrDefault("message", "")).trim();
        if (message.isEmpty()) return result(500, "Message cannot be empty", null);
        if (message.length() > 2000) return result(500, "Message cannot exceed 2000 characters", null);

        List<Map<String, String>> history = toHistory(assistantConversationMapper.selectMessagesByConversationId(conversationId));
        AssistantMessage userMessage = message(conversationId, "user", message);
        assistantConversationMapper.insertMessage(userMessage);
        String messageForAgent = enrichWithCurrentAudio(message, conversation.getCurrentAudioId());
        String reply = deepSeekMusicAgent.reply(messageForAgent, userId, history, conversationId);
        AssistantMessage assistantMessage = message(conversationId, "assistant", reply);
        assistantConversationMapper.insertMessage(assistantMessage);

        if ("新对话".equals(conversation.getTitle())) {
            assistantConversationMapper.updateTitle(conversationId, userId, message.substring(0, Math.min(message.length(), 18)));
        } else {
            assistantConversationMapper.touchConversation(conversationId);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("reply", reply);
        data.put("userMessage", userMessage);
        data.put("assistantMessage", assistantMessage);
        return result(200, "success", data);
    }

    @GetMapping("/conversations")
    public Map<String, Object> conversations(HttpServletRequest request) {
        return result(200, "success", assistantConversationMapper.selectByUserId(getUserId(request)));
    }

    @PostMapping("/conversations")
    public Map<String, Object> createConversation(HttpServletRequest request) {
        AssistantConversation conversation = new AssistantConversation();
        conversation.setUserId(getUserId(request));
        conversation.setTitle("新对话");
        assistantConversationMapper.insertConversation(conversation);
        AssistantMessage welcomeMessage = message(conversation.getId(), "assistant",
                "你好！我是你的歌库智能助手。我可以查询歌库歌曲、音乐类型、推荐内容和你的收藏。");
        assistantConversationMapper.insertMessage(welcomeMessage);
        return result(200, "Conversation created", conversation);
    }

    @GetMapping("/conversations/{conversationId}")
    public Map<String, Object> conversation(@PathVariable Long conversationId, HttpServletRequest request) {
        AssistantConversation conversation = assistantConversationMapper.selectByIdAndUserId(conversationId, getUserId(request));
        if (conversation == null) return result(500, "Conversation not found", null);
        conversation.setMessages(assistantConversationMapper.selectMessagesByConversationId(conversationId));
        return result(200, "success", conversation);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable Long conversationId, HttpServletRequest request) {
        int deleted = assistantConversationMapper.deleteByIdAndUserId(conversationId, getUserId(request));
        return deleted > 0 ? result(200, "Conversation deleted", null) : result(500, "Conversation not found", null);
    }

    @PostMapping("/conversations/{conversationId}/context")
    public Map<String, Object> updateContext(@PathVariable Long conversationId, @RequestBody Map<String, Object> payload,
                                             HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (assistantConversationMapper.selectByIdAndUserId(conversationId, userId) == null) return result(500, "Conversation not found", null);
        assistantConversationMapper.updateCurrentAudio(conversationId, userId, toInteger(payload.get("audioId")));
        return result(200, "Conversation context updated", null);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("configured", deepSeekMusicAgent.isExternalModelConfigured());
        data.put("model", deepSeekMusicAgent.getConfiguredModelName());
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", data);
        return result;
    }

    @GetMapping("/recommendations")
    public Map<String, Object> recommendations(@RequestParam(value = "message", required = false) String message,
                                               HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String token = request.getHeader("Authorization");
        String username = JwtUtil.getUsernameByToken(token);
        User user = userMapper.selectByUsername(username);
        result.put("code", 200);
        result.put("msg", "success");
        result.put("data", musicLibraryAgent.getRecommendationsForQuery(message, user.getId(), 6));
        return result;
    }

    private List<Map<String, String>> toHistory(List<AssistantMessage> messages) {
        List<Map<String, String>> history = new ArrayList<>();
        for (AssistantMessage item : messages) {
            Map<String, String> message = new HashMap<>();
            message.put("role", item.getRole());
            message.put("content", item.getContent());
            history.add(message);
        }
        return history;
    }

    private AssistantMessage message(Long conversationId, String role, String content) {
        AssistantMessage message = new AssistantMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private Integer getUserId(HttpServletRequest request) {
        String username = JwtUtil.getUsernameByToken(request.getHeader("Authorization"));
        User user = userMapper.selectByUsername(username);
        return user.getId();
    }

    private Long toLong(Object value) {
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }

    private Integer toInteger(Object value) {
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }

    private String enrichWithCurrentAudio(String message, Integer audioId) {
        if (audioId == null) return message;
        Audio audio = audioMapper.selectById(audioId);
        if (audio == null) return message;
        String normalized = message.toLowerCase();
        boolean refersToCurrentSong = message.contains("这首") || message.contains("这歌") || message.contains("它")
                || message.contains("当前") || normalized.contains("this song") || normalized.contains("it");
        if (!refersToCurrentSong) return message;
        return message + "（当前会话歌曲：" + audio.getSongName() + " - " + audio.getSinger()
                + "，类型：" + (audio.getGenre() == null ? "其他" : audio.getGenre()) + "）";
    }

    private Map<String, Object> result(Integer code, String msg, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        result.put("data", data);
        return result;
    }
}

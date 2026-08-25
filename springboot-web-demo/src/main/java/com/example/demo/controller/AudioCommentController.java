package com.example.demo.controller;

import com.example.demo.entity.Audio;
import com.example.demo.entity.AudioComment;
import com.example.demo.entity.User;
import com.example.demo.mapper.AudioCommentMapper;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.UserMapper;
import com.example.demo.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class AudioCommentController {

    @Autowired
    private AudioCommentMapper audioCommentMapper;

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private UserMapper userMapper;

    @GetMapping("/audio/{audioId}/comments")
    public Map<String, Object> comments(@PathVariable Integer audioId,
                                        @RequestParam(defaultValue = "latest") String sort,
                                        HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (audioMapper.selectById(audioId) == null) return result(500, "Audio not found", null);
        List<AudioComment> comments = "hot".equals(sort)
                ? audioCommentMapper.selectHottestByAudioId(audioId, userId)
                : audioCommentMapper.selectLatestByAudioId(audioId, userId);
        return result(200, "Comment list loaded", comments);
    }

    @PostMapping("/audio/{audioId}/comments")
    public Map<String, Object> createComment(@PathVariable Integer audioId, @RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (audioMapper.selectById(audioId) == null) return result(500, "Audio not found", null);
        Object rawContent = body.get("content");
        String content = rawContent instanceof String ? ((String) rawContent).trim() : "";
        if (content.isEmpty()) return result(500, "Comment cannot be empty", null);
        if (content.length() > 500) return result(500, "Comment cannot exceed 500 characters", null);

        AudioComment comment = new AudioComment();
        comment.setAudioId(audioId);
        comment.setUserId(userId);
        comment.setContent(content);
        audioCommentMapper.insert(comment);
        return result(200, "Comment posted", null);
    }

    @DeleteMapping("/comments/{commentId}")
    public Map<String, Object> deleteComment(@PathVariable Integer commentId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        int deleted = audioCommentMapper.deleteByIdAndUserId(commentId, userId);
        return deleted > 0 ? result(200, "Comment deleted", null) : result(500, "You can only delete your own comment", null);
    }

    @PostMapping("/comments/{commentId}/like")
    public Map<String, Object> likeComment(@PathVariable Integer commentId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (audioCommentMapper.selectById(commentId) == null) return result(500, "Comment not found", null);
        if (audioCommentMapper.isLiked(commentId, userId) == 0) audioCommentMapper.addLike(commentId, userId);
        return result(200, "Comment liked", null);
    }

    @PostMapping("/comments/{commentId}/like/toggle")
    @Transactional
    public Map<String, Object> toggleLikeComment(@PathVariable Integer commentId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (audioCommentMapper.selectById(commentId) == null) return result(500, "Comment not found", null);

        boolean liked;
        if (audioCommentMapper.isLiked(commentId, userId) > 0) {
            audioCommentMapper.removeLike(commentId, userId);
            liked = false;
        } else {
            audioCommentMapper.addLike(commentId, userId);
            liked = true;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("liked", liked);
        data.put("likeCount", audioCommentMapper.countLikes(commentId));
        return result(200, liked ? "Comment liked" : "Comment like removed", data);
    }

    @DeleteMapping("/comments/{commentId}/like")
    public Map<String, Object> unlikeComment(@PathVariable Integer commentId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        audioCommentMapper.removeLike(commentId, userId);
        return result(200, "Comment like removed", null);
    }

    private Map<String, Object> result(Integer code, String msg, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        result.put("data", data);
        return result;
    }

    private Integer getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        String username = JwtUtil.getUsernameByToken(token);
        User user = userMapper.selectByUsername(username);
        return user.getId();
    }
}

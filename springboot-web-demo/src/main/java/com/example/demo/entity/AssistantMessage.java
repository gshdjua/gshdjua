package com.example.demo.entity;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

public class AssistantMessage {
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private Date createTime;
    private String promptVersion;
    private String feedbackRating;
    private String feedbackReason;
    private List<Audio> recommendations = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getFeedbackRating() { return feedbackRating; }
    public void setFeedbackRating(String feedbackRating) { this.feedbackRating = feedbackRating; }
    public String getFeedbackReason() { return feedbackReason; }
    public void setFeedbackReason(String feedbackReason) { this.feedbackReason = feedbackReason; }
    public List<Audio> getRecommendations() { return recommendations; }
    public void setRecommendations(List<Audio> recommendations) {
        this.recommendations = recommendations == null ? new ArrayList<>() : new ArrayList<>(recommendations);
    }
}

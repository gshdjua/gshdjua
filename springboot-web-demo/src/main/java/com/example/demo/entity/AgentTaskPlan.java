package com.example.demo.entity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class AgentTaskPlan {
    private Long id;
    private Integer userId;
    private Long conversationId;
    private Long assistantMessageId;
    private String actionType;
    private String title;
    private String playlistName;
    private String confirmationToken;
    private String status;
    private Date expiresAt;
    private Integer resultPlaylistId;
    private String errorMessage;
    private Date createdAt;
    private Date updatedAt;
    private Date executedAt;
    private List<Audio> songs = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPlaylistName() { return playlistName; }
    public void setPlaylistName(String playlistName) { this.playlistName = playlistName; }
    public String getConfirmationToken() { return confirmationToken; }
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
    public Integer getResultPlaylistId() { return resultPlaylistId; }
    public void setResultPlaylistId(Integer resultPlaylistId) { this.resultPlaylistId = resultPlaylistId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
    public Date getExecutedAt() { return executedAt; }
    public void setExecutedAt(Date executedAt) { this.executedAt = executedAt; }
    public List<Audio> getSongs() { return songs; }
    public void setSongs(List<Audio> songs) {
        this.songs = songs == null ? new ArrayList<>() : new ArrayList<>(songs);
    }
}

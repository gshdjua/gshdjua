package com.example.demo.entity;

import java.util.Date;
import java.util.List;

public class AssistantConversation {
    private Long id;
    private Integer userId;
    private String title;
    private Integer currentAudioId;
    private Integer messageCount;
    private Date createTime;
    private Date updateTime;
    private List<AssistantMessage> messages;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getCurrentAudioId() { return currentAudioId; }
    public void setCurrentAudioId(Integer currentAudioId) { this.currentAudioId = currentAudioId; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public List<AssistantMessage> getMessages() { return messages; }
    public void setMessages(List<AssistantMessage> messages) { this.messages = messages; }
}

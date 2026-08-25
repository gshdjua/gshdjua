package com.example.demo.entity;

import java.util.Date;

public class AudioComment {
    private Integer id;
    private Integer audioId;
    private Integer userId;
    private String username;
    private String displayName;
    private String avatarPath;
    private String content;
    private Date createTime;
    private Integer likeCount;
    private Integer liked;
    private Integer own;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getAudioId() { return audioId; }
    public void setAudioId(Integer audioId) { this.audioId = audioId; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarPath() { return avatarPath; }
    public void setAvatarPath(String avatarPath) { this.avatarPath = avatarPath; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Integer getLikeCount() { return likeCount; }
    public void setLikeCount(Integer likeCount) { this.likeCount = likeCount; }
    public Integer getLiked() { return liked; }
    public void setLiked(Integer liked) { this.liked = liked; }
    public Integer getOwn() { return own; }
    public void setOwn(Integer own) { this.own = own; }
}

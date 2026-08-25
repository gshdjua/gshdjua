package com.example.demo.entity;

import java.util.Date;
import java.util.List;

public class UserPlaylist {
    private Integer id;
    private Integer userId;
    private String name;
    private Integer songCount;
    private Date createTime;
    private Date updateTime;
    private List<Audio> songs;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSongCount() { return songCount; }
    public void setSongCount(Integer songCount) { this.songCount = songCount; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public List<Audio> getSongs() { return songs; }
    public void setSongs(List<Audio> songs) { this.songs = songs; }
}

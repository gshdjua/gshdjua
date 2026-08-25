package com.example.demo.entity;

import java.util.Date;

public class Audio {
    private Integer id;
    private String audioName;
    private String savePath;
    private String songName;
    private String singer;
    private String genre;
    private String source;
    private String introduction;
    private String lyricPath;
    private Integer collectCount;
    private Date uploadTime;
    private String coverPath;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getAudioName() { return audioName; }
    public void setAudioName(String audioName) { this.audioName = audioName; }
    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }
    public String getSongName() { return songName; }
    public void setSongName(String songName) { this.songName = songName; }
    public String getSinger() { return singer; }
    public void setSinger(String singer) { this.singer = singer; }
    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getIntroduction() { return introduction; }
    public void setIntroduction(String introduction) { this.introduction = introduction; }
    public String getLyricPath() { return lyricPath; }
    public void setLyricPath(String lyricPath) { this.lyricPath = lyricPath; }
    public Integer getCollectCount() { return collectCount; }
    public void setCollectCount(Integer collectCount) { this.collectCount = collectCount; }
    public Date getUploadTime() { return uploadTime; }
    public void setUploadTime(Date uploadTime) { this.uploadTime = uploadTime; }
    public String getCoverPath() { return coverPath; }
    public void setCoverPath(String coverPath) { this.coverPath = coverPath; }
}

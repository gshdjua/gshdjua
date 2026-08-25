package com.example.demo.controller;

import com.example.demo.entity.Audio;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.UserMapper;
import com.example.demo.service.PersonalizedRecommendationService;
import com.example.demo.service.VectorRagClient;
import com.example.demo.util.JwtUtil;
import com.example.demo.util.MusicGenreUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.HashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AudioController {

    private static final Set<String> SUPPORTED_GENRES = new HashSet<>(Arrays.asList(
            "流行", "摇滚", "电子", "嘻哈", "R&B", "民谣", "爵士", "古典", "动漫", "游戏", "原声", "轻音乐", "其他"
    ));

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PersonalizedRecommendationService personalizedRecommendationService;

    @Autowired
    private VectorRagClient vectorRagClient;

    private Map<String, Object> result(Integer code, String msg, Object data) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", code);
        map.put("msg", msg);
        map.put("data", data);
        return map;
    }

    @PostMapping("/admin/uploadAudio")
    public Map<String, Object> uploadAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam("songName") String songName,
            @RequestParam("singer") String singer,
            @RequestParam("genre") String genre,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "introduction", required = false) String introduction,
            @RequestParam(value = "lyric", required = false) MultipartFile lyricFile,
            @RequestParam(value = "cover", required = false) MultipartFile coverFile
    ) throws Exception {
        genre = MusicGenreUtils.normalize(genre, SUPPORTED_GENRES);
        if (genre.isEmpty()) return result(500, "请至少选择一个有效的音乐类型", null);
        source = normalizeMetadata(source, 255);
        introduction = normalizeMetadata(introduction, 2000);
        String originalName = file.getOriginalFilename();
        String suffix = originalName.substring(originalName.lastIndexOf("."));
        if (!".mp3".equalsIgnoreCase(suffix) && !".wav".equalsIgnoreCase(suffix) && !".flac".equalsIgnoreCase(suffix) && !".ogg".equalsIgnoreCase(suffix)) {
            return result(500, "仅支持 mp3 / wav / flac / ogg 音频文件", null);
        }

        String uniqueFileName = UUID.randomUUID() + suffix;
        String saveDir = new File("../music/").getAbsolutePath() + "/";
        File dir = new File(saveDir);
        if (!dir.exists()) dir.mkdirs();
        File targetFile = new File(saveDir + uniqueFileName);
        file.transferTo(targetFile);

        String accessUrl = "/audio/" + uniqueFileName;

        String coverAccessUrl = "";
        if (coverFile != null && !coverFile.isEmpty()) {
            String coverSuffix = coverFile.getOriginalFilename().substring(coverFile.getOriginalFilename().lastIndexOf("."));
            String coverFileName = "cover_" + UUID.randomUUID() + coverSuffix;
            File coverTarget = new File(saveDir + coverFileName);
            coverFile.transferTo(coverTarget);
            coverAccessUrl = "/audio/" + coverFileName;
        }

        String lyricAccessUrl = saveLyricFile(lyricFile, saveDir);

        Audio audio = new Audio();
        audio.setAudioName(originalName);
        audio.setSavePath(accessUrl);
        audio.setSongName(songName);
        audio.setSinger(singer);
        audio.setGenre(genre);
        audio.setSource(source);
        audio.setIntroduction(introduction);
        audio.setLyricPath(lyricAccessUrl);
        audio.setCollectCount(0);
        audio.setCoverPath(coverAccessUrl);
        audioMapper.insert(audio);
        vectorRagClient.rebuildAsync("audio-uploaded");

        return result(200, "上传成功", accessUrl);
    }

    @GetMapping("/admin/audioList")
    public Map<String, Object> audioList() {
        List<Audio> audioList = audioMapper.selectAll();
        return result(200, "查询成功", audioList);
    }

    @GetMapping("/admin/public/audioList")
    public Map<String, Object> publicAudioList() {
        List<Audio> audioList = audioMapper.selectRecommended();
        return result(200, "查询成功", audioList);
    }

    @PostMapping("/user/play/{audioId}")
    public Map<String, Object> recordPlay(@PathVariable Integer audioId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (userId == null) return result(500, "Not logged in", null);
        if (audioMapper.selectById(audioId) == null) return result(500, "Audio not found", null);
        audioMapper.recordPlay(userId, audioId);
        return result(200, "Playback recorded", null);
    }

    @GetMapping("/admin/monthlyPlayCounts")
    public Map<String, Object> monthlyPlayCounts(@RequestParam String month) {
        return result(200, "Query successful", audioMapper.selectMonthlyPlayCounts(month));
    }

    @GetMapping("/admin/monthlyDailyPlayCounts")
    public Map<String, Object> monthlyDailyPlayCounts(@RequestParam String month) {
        return result(200, "Query successful", audioMapper.selectMonthlyDailyPlayCounts(month));
    }

    @DeleteMapping("/admin/deleteAudio/{id}")
    public Map<String, Object> deleteAudio(@PathVariable Integer id) {
        Audio audio = audioMapper.selectById(id);
        if (audio == null) {
            return result(500, "音频不存在", null);
        }
        String fileName = audio.getSavePath().replace("/audio/", "");
        String localFilePath = new File("../music/").getAbsolutePath() + "/" + fileName;
        File audioFile = new File(localFilePath);
        if (audioFile.exists()) audioFile.delete();

        if (audio.getCoverPath() != null && !audio.getCoverPath().isEmpty()) {
            String coverFile = audio.getCoverPath().replace("/audio/", "");
            File cover = new File(new File("../music/").getAbsolutePath() + "/" + coverFile);
            if (cover.exists()) cover.delete();
        }
        if (audio.getLyricPath() != null && !audio.getLyricPath().isEmpty()) {
            String lyricFile = audio.getLyricPath().replace("/audio/", "");
            File lyric = new File(new File("../music/").getAbsolutePath() + "/" + lyricFile);
            if (lyric.exists()) lyric.delete();
        }

        audioMapper.deleteById(id);
        vectorRagClient.rebuildAsync("audio-deleted");
        return result(200, "删除成功，本地音频文件与数据库记录已清除", null);
    }

    @PostMapping("/user/collect/{audioId}")
    public Map<String, Object> collect(@PathVariable Integer audioId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (userId == null) return result(500, "未登录", null);
        int exists = audioMapper.isCollected(userId, audioId);
        if (exists > 0) return result(500, "已收藏", null);
        audioMapper.addCollect(userId, audioId);
        audioMapper.incrementCollect(audioId);
        return result(200, "收藏成功", null);
    }

    @DeleteMapping("/user/uncollect/{audioId}")
    public Map<String, Object> uncollect(@PathVariable Integer audioId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (userId == null) return result(500, "未登录", null);
        audioMapper.removeCollect(userId, audioId);
        audioMapper.decrementCollect(audioId);
        return result(200, "取消收藏", null);
    }

    @GetMapping("/user/collections")
    public Map<String, Object> collections(HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (userId == null) return result(500, "未登录", null);
        List<Audio> list = audioMapper.selectUserCollects(userId);
        return result(200, "查询成功", list);
    }

    @GetMapping("/user/recommendations")
    public Map<String, Object> personalizedRecommendations(
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (userId == null) return result(500, "Not logged in", null);
        return result(200, "Personalized recommendations loaded", personalizedRecommendationService.recommend(userId, limit));
    }

    @GetMapping("/user/isCollected/{audioId}")
    public Map<String, Object> isCollected(@PathVariable Integer audioId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (userId == null) return result(500, "未登录", null);
        int exists = audioMapper.isCollected(userId, audioId);
        return result(200, "查询成功", exists > 0);
    }

    @PostMapping("/admin/updateCover")
    public Map<String, Object> updateCover(
            @RequestParam("cover") MultipartFile coverFile,
            @RequestParam("id") Integer id
    ) throws Exception {
        Audio audio = audioMapper.selectById(id);
        if (audio == null) return result(500, "not found", null);
        String s = coverFile.getOriginalFilename();
        String suffix = s.substring(s.lastIndexOf("."));
        String fn = "cover_" + UUID.randomUUID() + suffix;
        String dir = new File("../music/").getAbsolutePath() + "/";
        coverFile.transferTo(new File(dir + fn));
        String url = "/audio/" + fn;
        if (audio.getCoverPath() != null && !audio.getCoverPath().isEmpty()) {
            String old = audio.getCoverPath().replace("/audio/", "");
            File of = new File(dir + old);
            if (of.exists()) of.delete();
        }
        audio.setCoverPath(url);
        audioMapper.updateCoverPath(audio);
        return result(200, "ok", url);
    }

    @PostMapping("/admin/updateGenre")
    public Map<String, Object> updateGenre(@RequestBody Map<String, Object> body) {
        Object rawId = body.get("id");
        Object rawGenre = body.get("genre");
        Integer id;
        try {
            id = Integer.valueOf(String.valueOf(rawId));
        } catch (Exception exception) {
            return result(500, "Invalid audio id", null);
        }
        String genre = MusicGenreUtils.normalize(rawGenre, SUPPORTED_GENRES);
        if (genre.isEmpty()) return result(500, "请至少选择一个有效的音乐类型", null);

        Audio audio = audioMapper.selectById(id);
        if (audio == null) return result(500, "Audio not found", null);
        audio.setGenre(genre);
        audioMapper.updateGenre(audio);
        vectorRagClient.rebuildAsync("audio-genre-updated");
        return result(200, "Music genre updated", genre);
    }

    @PostMapping("/admin/updateMetadata")
    public Map<String, Object> updateMetadata(@RequestBody Map<String, Object> body) {
        Integer id = toAudioId(body.get("id"));
        if (id == null) return result(500, "Invalid audio id", null);
        Audio audio = audioMapper.selectById(id);
        if (audio == null) return result(500, "Audio not found", null);
        audio.setSource(normalizeMetadata(body.get("source"), 255));
        audio.setIntroduction(normalizeMetadata(body.get("introduction"), 2000));
        audioMapper.updateMetadata(audio);
        vectorRagClient.rebuildAsync("audio-metadata-updated");
        return result(200, "Music metadata updated", null);
    }

    @PostMapping("/admin/updateSongInfo")
    public Map<String, Object> updateSongInfo(@RequestBody Map<String, Object> body) {
        Integer id = toAudioId(body.get("id"));
        if (id == null) return result(500, "Invalid audio id", null);
        String songName = normalizeMetadata(body.get("songName"), 120);
        String singer = normalizeMetadata(body.get("singer"), 120);
        if (songName.isEmpty() || singer.isEmpty()) return result(500, "Song name and singer are required", null);

        Audio audio = audioMapper.selectById(id);
        if (audio == null) return result(500, "Audio not found", null);
        audio.setSongName(songName);
        audio.setSinger(singer);
        audioMapper.updateSongInfo(audio);
        vectorRagClient.rebuildAsync("audio-song-info-updated");
        return result(200, "Song information updated", null);
    }

    private Integer toAudioId(Object rawId) {
        try { return rawId == null ? null : Integer.valueOf(String.valueOf(rawId)); }
        catch (NumberFormatException exception) { return null; }
    }

    private String normalizeMetadata(Object value, int maxLength) {
        String text = value instanceof String ? ((String) value).trim() : "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    @PostMapping("/admin/updateLyric")
    public Map<String, Object> updateLyric(
            @RequestParam("lyric") MultipartFile lyricFile,
            @RequestParam("id") Integer id
    ) throws Exception {
        Audio audio = audioMapper.selectById(id);
        if (audio == null) return result(500, "Audio not found", null);

        String directory = new File("../music/").getAbsolutePath() + "/";
        String lyricAccessUrl = saveLyricFile(lyricFile, directory);
        if (lyricAccessUrl.isEmpty()) return result(500, "请选择 .lrc 歌词文件", null);

        if (audio.getLyricPath() != null && !audio.getLyricPath().isEmpty()) {
            File oldLyric = new File(directory + audio.getLyricPath().replace("/audio/", ""));
            if (oldLyric.exists()) oldLyric.delete();
        }
        audio.setLyricPath(lyricAccessUrl);
        audioMapper.updateLyricPath(audio);
        return result(200, "歌词更新成功", lyricAccessUrl);
    }

    private String saveLyricFile(MultipartFile lyricFile, String directory) throws Exception {
        if (lyricFile == null || lyricFile.isEmpty()) return "";
        String originalName = lyricFile.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".lrc")) {
            throw new IllegalArgumentException("仅支持 .lrc 歌词文件");
        }
        File dir = new File(directory);
        if (!dir.exists()) dir.mkdirs();
        String lyricFileName = "lyric_" + UUID.randomUUID() + ".lrc";
        lyricFile.transferTo(new File(dir, lyricFileName));
        return "/audio/" + lyricFileName;
    }

    private Integer getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token == null) return null;
        try {
            String username = JwtUtil.getUsernameByToken(token);
            if (username == null) return null;
            com.example.demo.entity.User user = userMapper.selectByUsername(username);
            return user != null ? user.getId() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

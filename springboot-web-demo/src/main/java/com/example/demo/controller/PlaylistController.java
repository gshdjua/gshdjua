package com.example.demo.controller;

import com.example.demo.entity.Audio;
import com.example.demo.entity.User;
import com.example.demo.entity.UserPlaylist;
import com.example.demo.mapper.AudioMapper;
import com.example.demo.mapper.PlaylistMapper;
import com.example.demo.mapper.UserMapper;
import com.example.demo.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/user/playlists")
public class PlaylistController {

    @Autowired
    private PlaylistMapper playlistMapper;

    @Autowired
    private AudioMapper audioMapper;

    @Autowired
    private UserMapper userMapper;

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        return result(200, "Playlist list loaded", playlistMapper.selectByUserId(getUserId(request)));
    }

    @GetMapping("/{playlistId}")
    public Map<String, Object> detail(@PathVariable Integer playlistId, HttpServletRequest request) {
        UserPlaylist playlist = findOwnedPlaylist(playlistId, getUserId(request));
        if (playlist == null) return result(500, "Playlist not found", null);
        playlist.setSongs(playlistMapper.selectSongsByPlaylistId(playlistId));
        return result(200, "Playlist loaded", playlist);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object rawName = body.get("name");
        String name = rawName instanceof String ? ((String) rawName).trim() : "";
        if (name.isEmpty()) return result(500, "Playlist name cannot be empty", null);
        if (name.length() > 80) return result(500, "Playlist name cannot exceed 80 characters", null);

        UserPlaylist playlist = new UserPlaylist();
        playlist.setUserId(getUserId(request));
        playlist.setName(name);
        playlistMapper.insert(playlist);
        return result(200, "Playlist created", playlist);
    }

    @DeleteMapping("/{playlistId}")
    public Map<String, Object> delete(@PathVariable Integer playlistId, HttpServletRequest request) {
        int deleted = playlistMapper.deleteByIdAndUserId(playlistId, getUserId(request));
        return deleted > 0 ? result(200, "Playlist deleted", null) : result(500, "Playlist not found", null);
    }

    @PostMapping("/{playlistId}/songs/{audioId}")
    @Transactional
    public Map<String, Object> addSong(@PathVariable Integer playlistId, @PathVariable Integer audioId,
                                        HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (findOwnedPlaylist(playlistId, userId) == null) return result(500, "Playlist not found", null);
        if (audioMapper.selectById(audioId) == null) return result(500, "Audio not found", null);
        if (playlistMapper.countSong(playlistId, audioId) > 0) return result(500, "Song already exists in this playlist", null);

        playlistMapper.insertSong(playlistId, audioId, playlistMapper.selectMaxSortOrder(playlistId) + 1);
        playlistMapper.touch(playlistId);
        return result(200, "Song added to playlist", null);
    }

    @DeleteMapping("/{playlistId}/songs/{audioId}")
    @Transactional
    public Map<String, Object> removeSong(@PathVariable Integer playlistId, @PathVariable Integer audioId,
                                           HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (findOwnedPlaylist(playlistId, userId) == null) return result(500, "Playlist not found", null);
        int deleted = playlistMapper.deleteSong(playlistId, audioId);
        if (deleted == 0) return result(500, "Song not found in this playlist", null);
        playlistMapper.touch(playlistId);
        return result(200, "Song removed from playlist", null);
    }

    @PutMapping("/{playlistId}/songs/order")
    @Transactional
    public Map<String, Object> reorderSongs(@PathVariable Integer playlistId, @RequestBody Map<String, Object> body,
                                             HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (findOwnedPlaylist(playlistId, userId) == null) return result(500, "Playlist not found", null);
        Object rawIds = body.get("audioIds");
        if (!(rawIds instanceof List)) return result(500, "Invalid song order", null);

        List<Integer> audioIds = new ArrayList<>();
        try {
            for (Object rawId : (List<?>) rawIds) audioIds.add(Integer.valueOf(String.valueOf(rawId)));
        } catch (NumberFormatException exception) {
            return result(500, "Invalid song order", null);
        }

        List<Audio> currentSongs = playlistMapper.selectSongsByPlaylistId(playlistId);
        Set<Integer> currentIds = new HashSet<>();
        for (Audio song : currentSongs) currentIds.add(song.getId());
        if (audioIds.size() != currentSongs.size() || new HashSet<>(audioIds).size() != audioIds.size()
                || !currentIds.equals(new HashSet<>(audioIds))) {
            return result(500, "Song order does not match playlist songs", null);
        }

        for (int index = 0; index < audioIds.size(); index++) {
            playlistMapper.updateSortOrder(playlistId, audioIds.get(index), index + 1);
        }
        playlistMapper.touch(playlistId);
        return result(200, "Playlist order updated", null);
    }

    private UserPlaylist findOwnedPlaylist(Integer playlistId, Integer userId) {
        return playlistMapper.selectByIdAndUserId(playlistId, userId);
    }

    private Integer getUserId(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        String username = JwtUtil.getUsernameByToken(token);
        User user = userMapper.selectByUsername(username);
        return user.getId();
    }

    private Map<String, Object> result(Integer code, String msg, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        result.put("data", data);
        return result;
    }
}

package com.example.demo.mapper;

import com.example.demo.entity.Audio;
import com.example.demo.entity.UserPlaylist;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PlaylistMapper {

    @Select("SELECT p.id, p.user_id AS userId, p.name, p.create_time AS createTime, p.update_time AS updateTime, " +
            "COUNT(ps.id) AS songCount FROM user_playlist p LEFT JOIN playlist_song ps ON ps.playlist_id = p.id " +
            "WHERE p.user_id = #{userId} GROUP BY p.id, p.user_id, p.name, p.create_time, p.update_time " +
            "ORDER BY p.update_time DESC, p.id DESC")
    List<UserPlaylist> selectByUserId(Integer userId);

    @Select("SELECT p.id, p.user_id AS userId, p.name, p.create_time AS createTime, p.update_time AS updateTime, " +
            "COUNT(ps.id) AS songCount FROM user_playlist p LEFT JOIN playlist_song ps ON ps.playlist_id = p.id " +
            "WHERE p.id = #{playlistId} AND p.user_id = #{userId} " +
            "GROUP BY p.id, p.user_id, p.name, p.create_time, p.update_time")
    UserPlaylist selectByIdAndUserId(@Param("playlistId") Integer playlistId, @Param("userId") Integer userId);

    @Insert("INSERT INTO user_playlist(user_id, name) VALUES(#{userId}, #{name})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(UserPlaylist playlist);

    @Delete("DELETE FROM user_playlist WHERE id = #{playlistId} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("playlistId") Integer playlistId, @Param("userId") Integer userId);

    @Select("SELECT a.* FROM playlist_song ps JOIN audio a ON a.id = ps.audio_id " +
            "WHERE ps.playlist_id = #{playlistId} ORDER BY ps.sort_order ASC, ps.id ASC")
    List<Audio> selectSongsByPlaylistId(Integer playlistId);

    @Select("SELECT COALESCE(MAX(sort_order), 0) FROM playlist_song WHERE playlist_id = #{playlistId}")
    int selectMaxSortOrder(Integer playlistId);

    @Select("SELECT COUNT(*) FROM playlist_song WHERE playlist_id = #{playlistId} AND audio_id = #{audioId}")
    int countSong(@Param("playlistId") Integer playlistId, @Param("audioId") Integer audioId);

    @Insert("INSERT INTO playlist_song(playlist_id, audio_id, sort_order) VALUES(#{playlistId}, #{audioId}, #{sortOrder})")
    void insertSong(@Param("playlistId") Integer playlistId, @Param("audioId") Integer audioId, @Param("sortOrder") Integer sortOrder);

    @Delete("DELETE FROM playlist_song WHERE playlist_id = #{playlistId} AND audio_id = #{audioId}")
    int deleteSong(@Param("playlistId") Integer playlistId, @Param("audioId") Integer audioId);

    @Update("UPDATE playlist_song SET sort_order = #{sortOrder} WHERE playlist_id = #{playlistId} AND audio_id = #{audioId}")
    void updateSortOrder(@Param("playlistId") Integer playlistId, @Param("audioId") Integer audioId, @Param("sortOrder") Integer sortOrder);

    @Update("UPDATE user_playlist SET update_time = CURRENT_TIMESTAMP WHERE id = #{playlistId}")
    void touch(Integer playlistId);
}

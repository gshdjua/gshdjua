package com.example.demo.mapper;

import com.example.demo.entity.Audio;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface AudioMapper {

    @Insert("INSERT INTO audio(audio_name, save_path, song_name, singer, genre, source, introduction, lyric_path, cover_path) VALUES(#{audioName}, #{savePath}, #{songName}, #{singer}, #{genre}, #{source}, #{introduction}, #{lyricPath}, #{coverPath})")
    void insert(Audio audio);

    @Select("SELECT * FROM audio ORDER BY upload_time DESC")
    List<Audio> selectAll();

    @Select("SELECT * FROM audio ORDER BY collect_count DESC, upload_time DESC")
    List<Audio> selectRecommended();

    @Select("SELECT a.* FROM audio a WHERE NOT EXISTS (" +
            "SELECT 1 FROM user_collect uc WHERE uc.audio_id = a.id AND uc.user_id = #{userId}) " +
            "ORDER BY a.collect_count DESC, a.upload_time DESC")
    List<Audio> selectRecommendedForUser(Integer userId);

    @Select("SELECT * FROM audio WHERE id = #{id}")
    Audio selectById(Integer id);

    @Delete("DELETE FROM audio WHERE id = #{id}")
    void deleteById(Integer id);

    @Update("UPDATE audio SET cover_path = #{coverPath} WHERE id = #{id}")
    void updateCoverPath(Audio audio);

    @Update("UPDATE audio SET genre = #{genre} WHERE id = #{id}")
    void updateGenre(Audio audio);

    @Update("UPDATE audio SET song_name = #{songName}, singer = #{singer} WHERE id = #{id}")
    void updateSongInfo(Audio audio);

    @Update("UPDATE audio SET source = #{source}, introduction = #{introduction} WHERE id = #{id}")
    void updateMetadata(Audio audio);

    @Update("UPDATE audio SET lyric_path = #{lyricPath} WHERE id = #{id}")
    void updateLyricPath(Audio audio);

    @Update("UPDATE audio SET collect_count = collect_count + 1 WHERE id = #{audioId}")
    void incrementCollect(Integer audioId);

    @Update("UPDATE audio SET collect_count = collect_count - 1 WHERE id = #{audioId} AND collect_count > 0")
    void decrementCollect(Integer audioId);

    @Insert("INSERT INTO user_collect(user_id, audio_id) VALUES(#{userId}, #{audioId})")
    void addCollect(Integer userId, Integer audioId);

    @Delete("DELETE FROM user_collect WHERE user_id = #{userId} AND audio_id = #{audioId}")
    void removeCollect(Integer userId, Integer audioId);

    @Select("SELECT COUNT(*) FROM user_collect WHERE user_id = #{userId} AND audio_id = #{audioId}")
    int isCollected(Integer userId, Integer audioId);

    @Select("SELECT a.* FROM audio a JOIN user_collect uc ON a.id = uc.audio_id WHERE uc.user_id = #{userId} ORDER BY uc.id DESC")
    List<Audio> selectUserCollects(Integer userId);

    @Select("SELECT COALESCE(NULLIF(a.genre, ''), '其他') AS genre, COALESCE(SUM(p.play_count), 0) AS playCount " +
            "FROM user_audio_play p JOIN audio a ON a.id = p.audio_id " +
            "WHERE p.user_id = #{userId} GROUP BY COALESCE(NULLIF(a.genre, ''), '其他')")
    List<Map<String, Object>> selectUserGenrePlayCounts(Integer userId);

    @Insert("INSERT INTO user_audio_play(user_id, audio_id, play_date, play_count) " +
            "VALUES(#{userId}, #{audioId}, CURDATE(), 1) " +
            "ON DUPLICATE KEY UPDATE play_count = play_count + 1")
    void recordPlay(Integer userId, Integer audioId);

    @Select("SELECT a.id AS audioId, a.song_name AS songName, a.singer AS singer, " +
            "COALESCE(SUM(p.play_count), 0) AS playCount " +
            "FROM audio a LEFT JOIN user_audio_play p ON a.id = p.audio_id " +
            "AND DATE_FORMAT(p.play_date, '%Y-%m') = #{month} " +
            "GROUP BY a.id, a.song_name, a.singer " +
            "ORDER BY playCount DESC, a.upload_time DESC")
    List<Map<String, Object>> selectMonthlyPlayCounts(@Param("month") String month);

    @Select("SELECT p.play_date AS playDate, SUM(p.play_count) AS playCount " +
            "FROM user_audio_play p " +
            "WHERE DATE_FORMAT(p.play_date, '%Y-%m') = #{month} " +
            "GROUP BY p.play_date ORDER BY p.play_date")
    List<Map<String, Object>> selectMonthlyDailyPlayCounts(@Param("month") String month);
}

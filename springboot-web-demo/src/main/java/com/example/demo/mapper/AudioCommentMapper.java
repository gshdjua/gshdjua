package com.example.demo.mapper;

import com.example.demo.entity.AudioComment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AudioCommentMapper {

    @Select("SELECT c.id, c.audio_id AS audioId, c.user_id AS userId, c.content, c.create_time AS createTime, " +
            "u.username, COALESCE(NULLIF(u.nickname, ''), u.username) AS displayName, u.avatar_path AS avatarPath, COUNT(l.id) AS likeCount, " +
            "COALESCE(MAX(CASE WHEN l.user_id = #{userId} THEN 1 ELSE 0 END), 0) AS liked, " +
            "CASE WHEN c.user_id = #{userId} THEN 1 ELSE 0 END AS own " +
            "FROM audio_comment c JOIN user u ON u.id = c.user_id " +
            "LEFT JOIN comment_like l ON l.comment_id = c.id " +
            "WHERE c.audio_id = #{audioId} " +
            "GROUP BY c.id, c.audio_id, c.user_id, c.content, c.create_time, u.username, u.nickname, u.avatar_path " +
            "ORDER BY c.create_time DESC")
    List<AudioComment> selectLatestByAudioId(@Param("audioId") Integer audioId, @Param("userId") Integer userId);

    @Select("SELECT c.id, c.audio_id AS audioId, c.user_id AS userId, c.content, c.create_time AS createTime, " +
            "u.username, COALESCE(NULLIF(u.nickname, ''), u.username) AS displayName, u.avatar_path AS avatarPath, COUNT(l.id) AS likeCount, " +
            "COALESCE(MAX(CASE WHEN l.user_id = #{userId} THEN 1 ELSE 0 END), 0) AS liked, " +
            "CASE WHEN c.user_id = #{userId} THEN 1 ELSE 0 END AS own " +
            "FROM audio_comment c JOIN user u ON u.id = c.user_id " +
            "LEFT JOIN comment_like l ON l.comment_id = c.id " +
            "WHERE c.audio_id = #{audioId} " +
            "GROUP BY c.id, c.audio_id, c.user_id, c.content, c.create_time, u.username, u.nickname, u.avatar_path " +
            "ORDER BY likeCount DESC, c.create_time DESC")
    List<AudioComment> selectHottestByAudioId(@Param("audioId") Integer audioId, @Param("userId") Integer userId);

    @Select("SELECT * FROM audio_comment WHERE id = #{id}")
    AudioComment selectById(Integer id);

    @Insert("INSERT INTO audio_comment(audio_id, user_id, content) VALUES(#{audioId}, #{userId}, #{content})")
    void insert(AudioComment comment);

    @Delete("DELETE FROM audio_comment WHERE id = #{commentId} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("commentId") Integer commentId, @Param("userId") Integer userId);

    @Select("SELECT COUNT(*) FROM comment_like WHERE comment_id = #{commentId} AND user_id = #{userId}")
    int isLiked(@Param("commentId") Integer commentId, @Param("userId") Integer userId);

    @Insert("INSERT INTO comment_like(comment_id, user_id) VALUES(#{commentId}, #{userId})")
    void addLike(@Param("commentId") Integer commentId, @Param("userId") Integer userId);

    @Delete("DELETE FROM comment_like WHERE comment_id = #{commentId} AND user_id = #{userId}")
    int removeLike(@Param("commentId") Integer commentId, @Param("userId") Integer userId);

    @Select("SELECT COUNT(*) FROM comment_like WHERE comment_id = #{commentId}")
    int countLikes(Integer commentId);
}

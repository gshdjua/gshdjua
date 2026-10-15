package com.example.demo.mapper;

import com.example.demo.entity.AgentTaskPlan;
import com.example.demo.entity.Audio;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentTaskMapper {

    @Insert("INSERT INTO agent_task_plan(user_id, conversation_id, assistant_message_id, action_type, title, " +
            "playlist_name, confirmation_token, status, expires_at) VALUES(#{userId}, #{conversationId}, " +
            "#{assistantMessageId}, #{actionType}, #{title}, #{playlistName}, #{confirmationToken}, #{status}, #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AgentTaskPlan plan);

    @Insert("INSERT INTO agent_task_plan_song(task_id, audio_id, sort_order) VALUES(#{taskId}, #{audioId}, #{sortOrder})")
    void insertSong(@Param("taskId") Long taskId, @Param("audioId") Integer audioId,
                    @Param("sortOrder") int sortOrder);

    @Select("SELECT id, user_id AS userId, conversation_id AS conversationId, " +
            "assistant_message_id AS assistantMessageId, action_type AS actionType, title, " +
            "playlist_name AS playlistName, confirmation_token AS confirmationToken, status, " +
            "expires_at AS expiresAt, result_playlist_id AS resultPlaylistId, error_message AS errorMessage, " +
            "created_at AS createdAt, updated_at AS updatedAt, executed_at AS executedAt " +
            "FROM agent_task_plan WHERE id=#{taskId} AND user_id=#{userId}")
    AgentTaskPlan selectOwned(@Param("taskId") Long taskId, @Param("userId") Integer userId);

    @Select("SELECT id, user_id AS userId, conversation_id AS conversationId, " +
            "assistant_message_id AS assistantMessageId, action_type AS actionType, title, " +
            "playlist_name AS playlistName, confirmation_token AS confirmationToken, status, " +
            "expires_at AS expiresAt, result_playlist_id AS resultPlaylistId, error_message AS errorMessage, " +
            "created_at AS createdAt, updated_at AS updatedAt, executed_at AS executedAt " +
            "FROM agent_task_plan WHERE assistant_message_id=#{messageId} AND user_id=#{userId} " +
            "ORDER BY id DESC LIMIT 1")
    AgentTaskPlan selectByMessage(@Param("messageId") Long messageId, @Param("userId") Integer userId);

    @Select("SELECT a.* FROM agent_task_plan_song s JOIN audio a ON a.id=s.audio_id " +
            "WHERE s.task_id=#{taskId} ORDER BY s.sort_order ASC, s.id ASC")
    List<Audio> selectSongs(Long taskId);

    @Update("UPDATE agent_task_plan SET status='executing', updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{taskId} AND user_id=#{userId} AND confirmation_token=#{token} " +
            "AND status='pending' AND expires_at>CURRENT_TIMESTAMP")
    int claim(@Param("taskId") Long taskId, @Param("userId") Integer userId,
              @Param("token") String token);

    @Update("UPDATE agent_task_plan SET status='succeeded', result_playlist_id=#{playlistId}, " +
            "confirmation_token=NULL, error_message='', executed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{taskId} AND user_id=#{userId} AND status='executing'")
    int markSucceeded(@Param("taskId") Long taskId, @Param("userId") Integer userId,
                      @Param("playlistId") Integer playlistId);

    @Update("UPDATE agent_task_plan SET status='cancelled', confirmation_token=NULL, " +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{taskId} AND user_id=#{userId} AND status='pending'")
    int cancel(@Param("taskId") Long taskId, @Param("userId") Integer userId);

    @Update("UPDATE agent_task_plan SET status='expired', confirmation_token=NULL, updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{taskId} AND user_id=#{userId} AND status='pending' AND expires_at<=CURRENT_TIMESTAMP")
    int expire(@Param("taskId") Long taskId, @Param("userId") Integer userId);
}

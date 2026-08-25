package com.example.demo.mapper;

import com.example.demo.entity.AssistantConversation;
import com.example.demo.entity.AssistantMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AssistantConversationMapper {

    @Select("SELECT c.id, c.user_id AS userId, c.title, c.current_audio_id AS currentAudioId, " +
            "c.create_time AS createTime, c.update_time AS updateTime, COUNT(m.id) AS messageCount " +
            "FROM assistant_conversation c LEFT JOIN assistant_message m ON m.conversation_id = c.id " +
            "WHERE c.user_id = #{userId} GROUP BY c.id, c.user_id, c.title, c.current_audio_id, c.create_time, c.update_time " +
            "ORDER BY c.update_time DESC, c.id DESC")
    List<AssistantConversation> selectByUserId(Integer userId);

    @Select("SELECT id, user_id AS userId, title, current_audio_id AS currentAudioId, create_time AS createTime, update_time AS updateTime " +
            "FROM assistant_conversation WHERE id = #{conversationId} AND user_id = #{userId}")
    AssistantConversation selectByIdAndUserId(@Param("conversationId") Long conversationId, @Param("userId") Integer userId);

    @Insert("INSERT INTO assistant_conversation(user_id, title) VALUES(#{userId}, #{title})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertConversation(AssistantConversation conversation);

    @Delete("DELETE FROM assistant_conversation WHERE id = #{conversationId} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("conversationId") Long conversationId, @Param("userId") Integer userId);

    @Update("UPDATE assistant_conversation SET title = #{title}, update_time = CURRENT_TIMESTAMP WHERE id = #{conversationId} AND user_id = #{userId}")
    void updateTitle(@Param("conversationId") Long conversationId, @Param("userId") Integer userId, @Param("title") String title);

    @Update("UPDATE assistant_conversation SET current_audio_id = #{audioId}, update_time = CURRENT_TIMESTAMP WHERE id = #{conversationId} AND user_id = #{userId}")
    void updateCurrentAudio(@Param("conversationId") Long conversationId, @Param("userId") Integer userId, @Param("audioId") Integer audioId);

    @Update("UPDATE assistant_conversation SET update_time = CURRENT_TIMESTAMP WHERE id = #{conversationId}")
    void touchConversation(Long conversationId);

    @Select("SELECT id, conversation_id AS conversationId, role, content, create_time AS createTime " +
            "FROM assistant_message WHERE conversation_id = #{conversationId} ORDER BY id ASC")
    List<AssistantMessage> selectMessagesByConversationId(Long conversationId);

    @Insert("INSERT INTO assistant_message(conversation_id, role, content) VALUES(#{conversationId}, #{role}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertMessage(AssistantMessage message);
}

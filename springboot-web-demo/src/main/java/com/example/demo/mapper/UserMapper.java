package com.example.demo.mapper;
import com.example.demo.entity.User;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import java.util.List;

public interface UserMapper {
    @Select("select * from user where username = #{username}")
    User selectByUsername(String username);

    @Insert("insert into user(username,password,role,nickname) values(#{username},#{password},'user',#{nickname})")
    void insert(User user);

    // 查询所有用户
    @Select("select * from user")
    List<User> selectAllUser();

    // 根据id查询用户
    @Select("select * from user where id = #{id}")
    User selectById(Integer id);

    // 更新用户
    @Update("UPDATE user SET username=#{username},password=#{password},role=#{role} WHERE id=#{id}")
    void updateUser(User user);

    @Update("UPDATE user SET avatar_path = #{avatarPath} WHERE id = #{id}")
    void updateAvatarPath(User user);

    @Update("UPDATE user SET nickname = #{nickname} WHERE id = #{id}")
    void updateNickname(User user);

    // 删除用户
    @Delete("DELETE FROM user WHERE id = #{id}")
    void deleteById(Integer id);
}

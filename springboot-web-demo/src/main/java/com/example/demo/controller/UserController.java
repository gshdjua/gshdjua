package com.example.demo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import com.example.demo.entity.User;
import com.example.demo.mapper.UserMapper;
import com.example.demo.util.JwtUtil;
import com.example.demo.vo.PageResult;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.File;
import javax.servlet.http.HttpServletRequest;

@RestController
public class UserController {

    @Autowired
    private UserMapper userMapper;

    private Map<String,Object> result(Integer code, String msg, Object data){
        Map<String,Object> map = new HashMap<>();
        map.put("code",code);
        map.put("msg",msg);
        map.put("data",data);
        return map;
    }

    @PostMapping("/api/login")
    public Map<String,Object> login(@RequestBody User user){
        String username = user.getUsername();
        String password = user.getPassword();

        User dbUser = userMapper.selectByUsername(username);
        if(dbUser == null || !dbUser.getPassword().equals(password)){
            return result(500,"账号密码错误",null);
        }
        String token = JwtUtil.generateToken(username);
        Map<String,String> data = new HashMap<>();
        data.put("token",token);
        data.put("username",username);
        data.put("role",dbUser.getRole());
        data.put("userId", String.valueOf(dbUser.getId()));
        data.put("nickname", displayName(dbUser));
        data.put("avatarPath", dbUser.getAvatarPath() == null ? "" : dbUser.getAvatarPath());
        return result(200,"登录成功",data);
    }

    @PostMapping("/api/register")
    public Map<String,Object> register(@RequestBody User user){
        String username = user.getUsername();
        String password = user.getPassword();

        User exist = userMapper.selectByUsername(username);
        if(exist != null){
            return result(500,"账号已存在",null);
        }
        user.setNickname(username);
        userMapper.insert(user);
        return result(200,"注册成功",null);
    }

    @PostMapping("/api/logout")
    public Map<String,Object> logout(){
        return result(200,"退出登录成功",null);
    }

    @GetMapping("/api/user/profile")
    public Map<String, Object> profile(HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (user == null) return result(500, "User not found", null);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("role", user.getRole());
        data.put("nickname", displayName(user));
        data.put("avatarPath", user.getAvatarPath() == null ? "" : user.getAvatarPath());
        return result(200, "Profile loaded", data);
    }

    @PostMapping("/api/user/nickname")
    public Map<String, Object> updateNickname(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (user == null) return result(500, "User not found", null);
        Object rawNickname = body.get("nickname");
        String nickname = rawNickname instanceof String ? ((String) rawNickname).trim() : "";
        if (nickname.isEmpty() || nickname.length() > 30) return result(500, "Nickname must contain 1 to 30 characters", null);
        user.setNickname(nickname);
        userMapper.updateNickname(user);
        return result(200, "Nickname updated", nickname);
    }

    @PostMapping("/api/user/avatar")
    public Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws Exception {
        User user = getCurrentUser(request);
        if (user == null) return result(500, "User not found", null);
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) return result(500, "Avatar must be an image smaller than 5MB", null);

        String originalName = file.getOriginalFilename();
        String suffix = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf(".")).toLowerCase() : "";
        if (!".jpg".equals(suffix) && !".jpeg".equals(suffix) && !".png".equals(suffix) && !".webp".equals(suffix) && !".gif".equals(suffix)) {
            return result(500, "Only JPG, PNG, WEBP, or GIF images are supported", null);
        }

        File uploadDir = new File("../music/").getAbsoluteFile();
        if (!uploadDir.exists()) uploadDir.mkdirs();
        String fileName = "avatar_" + UUID.randomUUID() + suffix;
        file.transferTo(new File(uploadDir, fileName));

        String oldAvatarPath = user.getAvatarPath();
        user.setAvatarPath("/audio/" + fileName);
        userMapper.updateAvatarPath(user);
        if (oldAvatarPath != null && oldAvatarPath.startsWith("/audio/avatar_")) {
            File oldAvatar = new File(uploadDir, oldAvatarPath.replace("/audio/", ""));
            if (oldAvatar.exists()) oldAvatar.delete();
        }
        return result(200, "Avatar uploaded", user.getAvatarPath());
    }

    // 管理员专属接口：获取全部用户（旧接口，保留兼容，前端不要再调用）
    @GetMapping("/api/admin/userList")
    public Map<String,Object> userList(){
        List<User> userList = userMapper.selectAllUser();
        return result(200,"查询成功",userList);
    }

    //【新增】分页查询接口，每页5条
    @GetMapping("/api/admin/userPage")
    public Map<String,Object> userPage(@RequestParam(defaultValue = "1") Integer pageNum){
        int pageSize = 5;
        PageHelper.startPage(pageNum, pageSize);
        List<User> allUser = userMapper.selectAllUser();
        PageInfo<User> pageInfo = new PageInfo<>(allUser);

        PageResult<User> pageResult = new PageResult<>();
        pageResult.setList(pageInfo.getList());
        pageResult.setTotal(pageInfo.getTotal());
        pageResult.setPages(pageInfo.getPages());
        pageResult.setPageNum(pageInfo.getPageNum());
        pageResult.setPageSize(pageInfo.getPageSize());

        return result(200,"查询成功",pageResult);
    }

    // 根据id查询单个用户
    @GetMapping("/api/admin/getUser/{id}")
    public Map<String,Object> getUser(@PathVariable Integer id){
        User user = userMapper.selectById(id);
        return result(200,"查询成功",user);
    }

    // 新增用户（管理员后台新增，可以指定角色）
    @PostMapping("/api/admin/addUser")
    public Map<String,Object> addUser(@RequestBody User user){
        User exist = userMapper.selectByUsername(user.getUsername());
        if(exist != null){
            return result(500,"用户名已存在",null);
        }
        userMapper.insert(user);
        return result(200,"新增成功",null);
    }

    // 修改用户
    @PostMapping("/api/admin/updateUser")
    public Map<String,Object> updateUser(@RequestBody User user){
        userMapper.updateUser(user);
        return result(200,"修改成功",null);
    }

    // 删除用户
    @DeleteMapping("/api/admin/delete/{id}")
    public Map<String,Object> deleteUser(@PathVariable Integer id){
        userMapper.deleteById(id);
        return result(200,"删除成功",null);
    }

    private User getCurrentUser(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token == null) return null;
        try {
            String username = JwtUtil.getUsernameByToken(token);
            return username == null ? null : userMapper.selectByUsername(username);
        } catch (Exception exception) {
            return null;
        }
    }

    private String displayName(User user) {
        return user.getNickname() == null || user.getNickname().trim().isEmpty()
                ? user.getUsername() : user.getNickname();
    }
}

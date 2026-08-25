package com.example.demo.interceptor;

import com.example.demo.entity.User;
import com.example.demo.mapper.UserMapper;
import com.example.demo.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.alibaba.fastjson.JSON;
import java.util.HashMap;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    @Autowired
    UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("Authorization");
        // 放行OPTIONS跨域预检
        if("OPTIONS".equals(request.getMethod())){
            return true;
        }
        // 登录注册无需校验
        String uri = request.getRequestURI();
        if(uri.contains("/api/login")||uri.contains("/api/register")){
            return true;
        }
        if(token==null){
            response.setContentType("application/json;charset=utf-8");
            Map<String,Object> res=new HashMap<>();
            res.put("code",401);
            res.put("msg","请先登录");
            response.getWriter().write(JSON.toJSONString(res));
            return false;
        }
        String username= JwtUtil.getUsernameByToken(token);
        User user=userMapper.selectByUsername(username);
        // 管理员接口校验角色
        if(uri.startsWith("/api/admin")){
            if(!"admin".equals(user.getRole())){
                response.setContentType("application/json;charset=utf-8");
                Map<String,Object> res=new HashMap<>();
                res.put("code",403);
                res.put("msg","无管理员权限");
                response.getWriter().write(JSON.toJSONString(res));
                return false;
            }
        }
        return true;
    }
}
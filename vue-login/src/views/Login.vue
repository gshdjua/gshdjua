<template>
  <div class="login-page">
    <div class="login-container">
      <div class="login-card">
        <div class="login-header">
          <div class="logo-icon">🎵</div>
          <h2>欢迎回来</h2>
          <p class="subtitle">登录你的音乐账号</p>
        </div>
        <p class="err-msg" v-if="errMsg">
          <span class="err-icon">⚠️</span> {{ errMsg }}
        </p>
        <div class="input-group">
          <div class="input-wrapper">
            <span class="input-icon">👤</span>
            <input v-model="username" placeholder="请输入账号" />
          </div>
          <div class="input-wrapper">
            <span class="input-icon">🔒</span>
            <input v-model="password" type="password" placeholder="请输入密码" />
          </div>
        </div>
        <button class="login-btn" @click="handleLogin">
          <span>登录</span>
        </button>
        <p class="tip">没有账号？<span @click="$router.push('/register')">立即注册 →</span></p>
      </div>
    </div>
  </div>
</template>

<script>
import request from '@/utils/request'
export default {
  data() {
    return {
      username: '',
      password: '',
      errMsg: ''
    }
  },
  methods: {
    async handleLogin() {
      this.errMsg = ''
      try {
        const res = await request.post('/login', {
          username: this.username,
          password: this.password
        })
        if (res.data.code === 200) {
          localStorage.setItem('token', res.data.data.token)
          localStorage.setItem('username', res.data.data.username)
          localStorage.setItem('role', res.data.data.role)
          localStorage.setItem('userId', res.data.data.userId)
          localStorage.setItem('nickname', res.data.data.nickname || res.data.data.username)
          localStorage.setItem('avatarPath', res.data.data.avatarPath || '')
          this.$router.push('/index')
        } else {
          this.errMsg = res.data.msg
        }
      } catch (err) {
        console.error('登录请求失败:', err)
        if (err.code === 'ECONNABORTED') {
          this.errMsg = '请求超时，请检查后端服务是否正常运行'
        } else if (!err.response) {
          this.errMsg = '无法连接后端服务(8082端口)，请确认SpringBoot已启动'
        } else {
          this.errMsg = '服务器错误: ' + (err.response.data?.msg || err.message)
        }
      }
    }
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background-image: url("@/assets/bg.webp");
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  display: flex;
  justify-content: center;
  align-items: center;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  position: relative;
}

.login-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(0,0,0,0.45) 0%, rgba(0,0,0,0.15) 100%);
  backdrop-filter: blur(6px);
}

.login-container {
  position: relative;
  z-index: 1;
  animation: fadeInUp 0.6s ease-out;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(30px); }
  to { opacity: 1; transform: translateY(0); }
}

.login-card {
  width: 400px;
  padding: 48px 40px 40px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 24px;
  color: #1a1a2e;
  text-align: center;
  box-shadow: 0 25px 60px rgba(0, 0, 0, 0.3);
  transition: transform 0.3s ease;
}

.login-card:hover {
  transform: translateY(-2px);
}

.login-header {
  margin-bottom: 32px;
}

.logo-icon {
  font-size: 52px;
  margin-bottom: 16px;
  animation: bounce 2s infinite;
}

@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}

.login-header h2 {
  font-size: 28px;
  font-weight: 700;
  color: #1a1a2e;
  margin: 0 0 8px;
}

.subtitle {
  color: #888;
  font-size: 15px;
  margin: 0;
}

.err-msg {
  background: #fff0f0;
  color: #e53e3e;
  padding: 10px 16px;
  border-radius: 12px;
  font-size: 14px;
  margin: 0 0 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid #ffd4d4;
}

.err-icon {
  font-size: 16px;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 24px;
}

.input-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.input-icon {
  position: absolute;
  left: 16px;
  font-size: 18px;
  z-index: 1;
}

.input-wrapper input {
  width: 100%;
  padding: 14px 16px 14px 48px;
  border: 2px solid #e8e8ec;
  border-radius: 14px;
  font-size: 15px;
  background: #f8f9fc;
  color: #1a1a2e;
  transition: all 0.3s ease;
  outline: none;
  box-sizing: border-box;
}

.input-wrapper input:focus {
  border-color: #fb7299;
  background: #fff;
  box-shadow: 0 0 0 4px rgba(251, 114, 153, 0.12);
}

.input-wrapper input::placeholder {
  color: #aaa;
}

.login-btn {
  width: 100%;
  padding: 15px;
  background: linear-gradient(135deg, #fb7299 0%, #f06292 100%);
  color: #fff;
  border: none;
  border-radius: 14px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.login-btn::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.2), transparent);
  transition: left 0.5s ease;
}

.login-btn:hover::before {
  left: 100%;
}

.login-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(251, 114, 153, 0.4);
}

.login-btn:active {
  transform: translateY(0);
}

.tip {
  color: #888;
  font-size: 14px;
  margin-top: 24px;
}

.tip span {
  color: #fb7299;
  cursor: pointer;
  font-weight: 600;
  transition: color 0.2s;
}

.tip span:hover {
  color: #f06292;
  text-decoration: underline;
}
</style>

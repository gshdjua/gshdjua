<template>
  <div class="register-page">
    <div class="register-container">
      <div class="register-card">
        <div class="register-header">
          <div class="logo-icon">🎵</div>
          <h2>创建账号</h2>
          <p class="subtitle">加入音乐社区</p>
        </div>
        <p class="err-msg" v-if="errMsg">
          <span class="err-icon">⚠️</span> {{ errMsg }}
        </p>
        <p class="succ-msg" v-if="succMsg">
          <span class="succ-icon">✅</span> {{ succMsg }}
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
        <button class="register-btn" @click="handleRegister">注册</button>
        <p class="tip">已有账号？<span @click="$router.push('/login')">去登录 →</span></p>
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
      errMsg: '',
      succMsg: ''
    }
  },
  methods: {
    async handleRegister() {
      this.errMsg = ''
      this.succMsg = ''
      try {
        const res = await request.post('/register', {
          username: this.username,
          password: this.password
        })
        if (res.data.code === 200) {
          this.succMsg = '注册成功，请登录'
          setTimeout(() => this.$router.push('/login'), 1200)
        } else {
          this.errMsg = res.data.msg
        }
      } catch (err) {
        console.error('注册请求失败:', err)
        this.errMsg = '网络请求失败，请检查网络连接或后端服务是否启动'
      }
    }
  }
}
</script>

<style scoped>
.register-page {
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

.register-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(0,0,0,0.45) 0%, rgba(0,0,0,0.15) 100%);
  backdrop-filter: blur(6px);
}

.register-container {
  position: relative;
  z-index: 1;
  animation: fadeInUp 0.6s ease-out;
}

@keyframes fadeInUp {
  from { opacity: 0; transform: translateY(30px); }
  to { opacity: 1; transform: translateY(0); }
}

.register-card {
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

.register-card:hover {
  transform: translateY(-2px);
}

.register-header {
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

.register-header h2 {
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

.succ-msg {
  background: #f0fff4;
  color: #38a169;
  padding: 10px 16px;
  border-radius: 12px;
  font-size: 14px;
  margin: 0 0 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid #c6f6d5;
}

.err-icon { font-size: 16px; }
.succ-icon { font-size: 16px; }

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
  border-color: #667eea;
  background: #fff;
  box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.12);
}

.input-wrapper input::placeholder {
  color: #aaa;
}

.register-btn {
  width: 100%;
  padding: 15px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
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

.register-btn::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.2), transparent);
  transition: left 0.5s ease;
}

.register-btn:hover::before {
  left: 100%;
}

.register-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(102, 126, 234, 0.4);
}

.register-btn:active {
  transform: translateY(0);
}

.tip {
  color: #888;
  font-size: 14px;
  margin-top: 24px;
}

.tip span {
  color: #667eea;
  cursor: pointer;
  font-weight: 600;
  transition: color 0.2s;
}

.tip span:hover {
  color: #764ba2;
  text-decoration: underline;
}
</style>

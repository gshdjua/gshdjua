import axios from 'axios'
const service = axios.create({
  baseURL: '/api', // 走 devServer proxy 到 localhost:8082
  timeout: 15000
})

// 请求拦截器，携带token
service.interceptors.request.use(config=>{
  const token = localStorage.getItem('token')
  if(token){
    config.headers.Authorization = token
  }
  return config
})

export default service

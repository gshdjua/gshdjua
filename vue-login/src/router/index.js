import Vue from 'vue'
import VueRouter from 'vue-router'
import Login from '@/views/Login'
import Register from '@/views/Register'
import Index from '@/views/Index'
import Admin from '@/views/Admin'
import MusicPlayer from '@/views/MusicPlayer'
import MusicComments from '@/views/MusicComments'

Vue.use(VueRouter)

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: Login },
  { path: '/register', component: Register },
  { path: '/index', component: Index },
  { path: '/admin', component: Admin },
  { path: '/player/:id/comments', component: MusicComments },
  { path: '/player/:id', component: MusicPlayer }
]

const router = new VueRouter({
  mode: 'history',
  routes
})

// 路由守卫
router.beforeEach((to,from,next)=>{
  const role = localStorage.getItem('role')
  if(to.path === '/admin'){
    if(role !== 'admin'){
      alert('没有管理员权限')
      next('/index')
    }else{
      next()
    }
  }else{
    next()
  }
})

export default router

<template>
  <div class="comments-page">
    <header class="comments-page-header">
      <button @click="goBack">← 返回播放器</button>
      <div><span>SONG COMMUNITY</span><h1>{{ audio ? audio.songName : '歌曲评论' }}</h1><p v-if="audio">{{ audio.singer }}</p></div>
      <div class="header-spacer"></div>
    </header>

    <main class="comments-main">
      <section class="comment-card">
        <div class="comment-card-header">
          <div><span class="eyebrow">MUSIC COMMUNITY</span><h2>歌曲评论 <small>{{ comments.length }}</small></h2></div>
          <div class="comment-sort"><button :class="{ active: commentSort === 'latest' }" @click="setCommentSort('latest')">最新</button><button :class="{ active: commentSort === 'hot' }" @click="setCommentSort('hot')">最热</button></div>
        </div>
        <form class="comment-composer" @submit.prevent="submitComment">
          <textarea v-model="commentInput" maxlength="500" placeholder="分享你对这首歌的感受..." :disabled="commentSubmitting"></textarea>
          <div><span>{{ commentInput.length }}/500</span><button :disabled="commentSubmitting || !commentInput.trim()">{{ commentSubmitting ? '发布中...' : '发布评论' }}</button></div>
        </form>
        <div v-if="commentsLoading" class="state">正在加载评论...</div>
        <div v-else-if="!comments.length" class="state">还没有评论，来留下第一条感受吧。</div>
        <div v-else class="comment-list">
          <article v-for="comment in comments" :key="comment.id" class="comment-item">
            <div class="avatar"><img v-if="comment.avatarPath" :src="comment.avatarPath" /><span v-else>{{ (comment.displayName || comment.username || '用').slice(0, 1).toUpperCase() }}</span></div>
            <div class="comment-body"><strong>{{ comment.displayName || comment.username }}</strong><p>{{ comment.content }}</p><footer><div><button class="like" :class="{ liked: comment.liked }" @click="toggleCommentLike(comment)">{{ comment.liked ? '♥' : '♡' }} {{ comment.likeCount || 0 }}</button><button v-if="comment.own" class="delete" @click="deleteComment(comment)">删除</button></div><time>{{ formatCommentTime(comment.createTime) }}</time></footer></div>
          </article>
        </div>
      </section>
    </main>
  </div>
</template>

<script>
import request from '@/utils/request'
export default {
  data() { return { audio: null, comments: [], commentInput: '', commentsLoading: false, commentSubmitting: false, commentSort: 'latest' } },
  mounted() { this.loadAudio(); this.loadComments() },
  methods: {
    async loadAudio() { try { const res = await request.get('/admin/public/audioList'); if (res.data.code === 200) this.audio = res.data.data.find(item => item.id == this.$route.params.id) || null } catch (e) {} },
    async loadComments() { this.commentsLoading = true; try { const res = await request.get('/user/audio/' + this.$route.params.id + '/comments', { params: { sort: this.commentSort } }); if (res.data.code === 200) this.comments = (res.data.data || []).map(comment => ({ ...comment, liked: Number(comment.liked) === 1, own: Number(comment.own) === 1 })) } catch (e) { this.comments = [] } finally { this.commentsLoading = false } },
    async submitComment() { const content = this.commentInput.trim(); if (!content || this.commentSubmitting) return; this.commentSubmitting = true; try { const res = await request.post('/user/audio/' + this.$route.params.id + '/comments', { content }); if (res.data.code === 200) { this.commentInput = ''; await this.loadComments() } } finally { this.commentSubmitting = false } },
    async toggleCommentLike(comment) { try { const res = await request.post('/user/comments/' + comment.id + '/like/toggle'); if (res.data.code === 200) { comment.liked = res.data.data.liked; comment.likeCount = res.data.data.likeCount; if (this.commentSort === 'hot') this.loadComments() } } catch (e) {} },
    async deleteComment(comment) { if (!window.confirm('确定删除这条评论吗？')) return; try { const res = await request.delete('/user/comments/' + comment.id); if (res.data.code === 200) this.comments = this.comments.filter(item => item.id !== comment.id) } catch (e) {} },
    setCommentSort(sort) { if (this.commentSort !== sort) { this.commentSort = sort; this.loadComments() } },
    formatCommentTime(value) { if (!value) return ''; const date = new Date(value); if (Number.isNaN(date.getTime())) return value; const pad = number => String(number).padStart(2, '0'); return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}` },
    goBack() { this.$router.push('/player/' + this.$route.params.id) }
  }
}
</script>

<style scoped>
.comments-page { min-height: 100vh; color: #f8f7ff; background: radial-gradient(circle at 80% 0, rgba(133,113,230,.35), transparent 30%), linear-gradient(150deg, #12102e, #292156 55%, #171431); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; }.comments-page-header { display: grid; grid-template-columns: 180px 1fr 180px; align-items: center; min-height: 105px; padding: 0 5vw; border-bottom: 1px solid rgba(255,255,255,.08); text-align: center; }.comments-page-header button { justify-self: start; padding: 9px 14px; border: 1px solid rgba(255,255,255,.16); border-radius: 10px; color: #f2efff; background: rgba(255,255,255,.08); font: inherit; cursor: pointer; }.comments-page-header span, .eyebrow { color: #a99cff; font-size: 10px; font-weight: 800; letter-spacing: 1.7px; }.comments-page-header h1 { margin: 5px 0 2px; font-size: 24px; }.comments-page-header p { margin: 0; color: rgba(255,255,255,.55); font-size: 13px; }.comments-main { width: min(880px, calc(100% - 32px)); margin: 42px auto; }.comment-card { padding: 28px; border: 1px solid rgba(201,193,255,.2); border-radius: 22px; background: rgba(18,15,53,.62); box-shadow: 0 22px 55px rgba(0,0,0,.22); backdrop-filter: blur(17px); }.comment-card-header { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-bottom: 19px; }.comment-card h2 { margin: 5px 0 0; font-size: 22px; }.comment-card h2 small { color: #a99cff; font-size: 13px; }.comment-sort { display: flex; padding: 3px; border: 1px solid rgba(180,170,255,.18); border-radius: 10px; background: rgba(255,255,255,.05); }.comment-sort button { padding: 7px 11px; border: 0; border-radius: 7px; color: rgba(255,255,255,.55); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }.comment-sort button.active { color: #fff; background: #735dc9; }.comment-composer { padding: 12px; border: 1px solid rgba(180,170,255,.2); border-radius: 14px; background: rgba(255,255,255,.05); }.comment-composer textarea { box-sizing: border-box; width: 100%; min-height: 86px; border: 0; outline: 0; color: #fff; background: transparent; font: inherit; line-height: 1.6; resize: vertical; }.comment-composer textarea::placeholder { color: rgba(255,255,255,.38); }.comment-composer > div, footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.comment-composer span, time { color: rgba(255,255,255,.4); font-size: 12px; }.comment-composer button { padding: 8px 14px; border: 0; border-radius: 9px; color: #fff; background: linear-gradient(135deg, #6d73e8, #8051ba); font: inherit; font-weight: 700; cursor: pointer; }.comment-composer button:disabled { opacity: .5; cursor: not-allowed; }.state { padding: 40px 0 16px; color: rgba(255,255,255,.5); text-align: center; }.comment-list { margin-top: 16px; }.comment-item { display: flex; gap: 12px; padding: 17px 3px; border-bottom: 1px solid rgba(255,255,255,.08); }.comment-item:last-child { border-bottom: 0; }.avatar { display: grid; place-items: center; flex: 0 0 38px; width: 38px; height: 38px; overflow: hidden; border-radius: 12px; color: #fff; background: linear-gradient(135deg, #6b75e9, #a75bbd); font-size: 14px; font-weight: 800; }.avatar img { width: 100%; height: 100%; object-fit: cover; }.comment-body { min-width: 0; flex: 1; }.comment-body strong { color: #e2deff; font-size: 14px; }.comment-body p { margin: 8px 0 11px; color: rgba(255,255,255,.83); line-height: 1.65; white-space: pre-wrap; word-break: break-word; }.like, .delete { padding: 0; border: 0; color: rgba(255,255,255,.48); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }.like.liked, .like:hover { color: #fa7ea1; }.delete { margin-left: 12px; }.delete:hover { color: #ff9a9a; }@media (max-width: 600px) { .comments-page-header { grid-template-columns: 1fr auto; min-height: 84px; padding: 0 16px; text-align: left; }.header-spacer { display: none; }.comments-page-header h1 { font-size: 18px; }.comments-main { margin: 22px auto; }.comment-card { padding: 19px 16px; }.comment-card-header { align-items: flex-start; flex-direction: column; }.comment-sort { align-self: flex-end; } }
</style>

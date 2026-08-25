<template>
  <div class="admin-page">
    <aside class="sidebar">
      <div class="sidebar-header">
        <span class="logo-icon">🎵</span>
        <span class="logo-text">管理后台</span>
      </div>
      <nav class="sidebar-nav">
        <div class="nav-item" :class="{active: currentMenu === 'user'}" @click="currentMenu='user'">
          <span class="nav-icon">👥</span>
          <span>用户管理</span>
        </div>
        <div class="nav-item" :class="{active: currentMenu === 'audio'}" @click="currentMenu='audio'">
          <span class="nav-icon">🎵</span>
          <span>音频管理</span>
        </div>
        <div class="nav-item" :class="{active: currentMenu === 'statistics'}" @click="openStatistics">
          <span class="nav-icon">📊</span>
          <span>播放统计</span>
        </div>
        <div class="nav-item" :class="{active: currentMenu === 'evaluation'}" @click="openEvaluation">
          <span class="nav-icon">🧪</span>
          <span>检索评测</span>
        </div>
      </nav>
      <div class="sidebar-footer">
        <button class="back-btn" @click="$router.push('/index')">
          <span>←</span> 返回首页
        </button>
      </div>
    </aside>

    <main class="main-content">
      <header class="content-header" :class="{ 'statistics-header': currentMenu === 'statistics' }">
        <h2>{{ menuTitle }}</h2>
        <span class="header-badge">{{ menuIcon }}</span>
      </header>

      <div v-if="currentMenu==='user'" class="content-body">
        <div class="card">
          <div class="card-header">
            <h3>用户列表</h3>
            <button class="btn-primary" @click="openDialog()">+ 新增用户</button>
          </div>
        <div class="table-wrap">
          <table class="data-table">
            <thead>
                <tr>
                  <th>ID</th>
                  <th>账号</th>
                  <th>角色</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in userList" :key="item.id">
                  <td><span class="cell-id">#{{ item.id }}</span></td>
                  <td>{{ item.username }}</td>
                  <td>
                    <span :class="['role-tag', item.role === 'admin' ? 'role-admin' : 'role-user']">
                      {{ item.role === 'admin' ? '管理员' : '普通用户' }}
                    </span>
                  </td>
                  <td>
                    <div class="action-btns">
                      <button class="btn-edit" @click="openDialog(item)">编辑</button>
                      <button class="btn-delete" @click="handleDelete(item.id)">删除</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="pagination" v-if="pages > 0">
            <button :disabled="pageNum === 1" @click="prevPage" class="page-btn">上一页</button>
            <span class="page-info">第 {{ pageNum }} / {{ pages }} 页</span>
            <button :disabled="pageNum >= pages" @click="nextPage" class="page-btn">下一页</button>
          </div>
        </div>
      </div>

      <div v-if="currentMenu==='audio'">
        <div class="content-body">
          <div class="card">
            <div class="card-header">
              <h3>上传音频</h3>
            </div>
            <div class="upload-form">
              <div class="form-row">
                <div class="form-group">
                  <label>歌名</label>
                  <input v-model="uploadForm.songName" placeholder="输入歌曲名称" />
                </div>
                <div class="form-group">
                  <label>歌手</label>
                  <input v-model="uploadForm.singer" placeholder="输入歌手名称" />
                </div>
                <div class="form-group">
                  <label>音乐类型</label>
                  <div class="genre-checkbox-grid">
                    <label v-for="genre in musicGenres" :key="genre" class="genre-checkbox" :class="{ selected: uploadForm.genres.includes(genre) }">
                      <input v-model="uploadForm.genres" type="checkbox" :value="genre" />
                      <span>{{ genre }}</span>
                    </label>
                  </div>
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>封面图片</label>
                  <div class="cover-upload-wrap">
                    <div class="cover-preview" v-if="coverPreview">
                      <img :src="coverPreview" class="cover-thumb" />
                      <button class="cover-remove" @click="removeCover">✕</button>
                    </div>
                    <button v-if="!coverPreview" class="btn-cover" @click="selectCoverFile">选择封面</button>
                    <input type="file" ref="coverFile" accept=".jpg,.jpeg,.png,.webp" @change="handleCoverSelect" hidden />
                  </div>
                </div>
                <div class="form-group">
                  <label>同步歌词（可选）</label>
                  <div class="lyric-upload-row">
                    <span class="lyric-file-name">{{ lyricFile ? lyricFile.name : '未选择 .lrc 文件' }}</span>
                    <button type="button" class="btn-cover" @click="$refs.lyricFile.click()">选择 LRC</button>
                    <input type="file" ref="lyricFile" accept=".lrc" @change="handleLyricSelect" hidden />
                  </div>
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>出处（可选）</label>
                  <input v-model="uploadForm.source" maxlength="255" placeholder="例如：某部动画 / 游戏 / 专辑" />
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>歌曲简介（可选）</label>
                  <textarea v-model="uploadForm.introduction" maxlength="2000" placeholder="填写歌曲背景、风格或推荐理由，便于 AI 助手理解歌曲"></textarea>
                </div>
              </div>
              <div class="upload-actions">
                <input type="file" ref="audioFile" accept=".mp3,.wav,.flac,.ogg" @change="handleAudioUpload" hidden />
                <button class="btn-upload" @click="selectAudioFile">选择并上传音频</button>
              </div>
            </div>
          </div>
          <div class="card">
            <div class="card-header">
              <h3>已上传音频</h3>
              <span class="count-badge" v-if="fullAudioList.length">共 {{ fullAudioList.length }} 首</span>
            </div>
            <div class="table-wrap">
              <table class="data-table">
                <colgroup>
                  <col class="col-cover" />
                  <col class="col-song" />
                  <col class="col-singer" />
                  <col class="col-genre" />
                  <col class="col-lyric" />
                  <col class="col-favorite" />
                  <col class="col-actions" />
                </colgroup>
                <thead>
                  <tr>
                    <th>封面</th>
                    <th>歌名</th>
                    <th>歌手</th>
                    <th>类型</th>
                    <th>歌词</th>
                    <th>收藏</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in fullAudioList" :key="item.id">
                    <td>
                      <img v-if="item.coverPath" :src="getImageUrl(item.coverPath)" class="table-cover" />
                      <div v-else class="table-cover-placeholder">🎵</div>
                    </td>
                <td class="song-cell"><strong :title="item.songName">{{ item.songName }}</strong></td>
                <td class="singer-cell" :title="item.singer"><span>{{ item.singer }}</span></td>
                    <td><div class="genre-badge-list"><span v-for="genre in splitGenres(item.genre)" :key="genre" class="genre-badge">{{ genre }}</span></div></td>
                    <td><span :class="['lyric-status', { ready: item.lyricPath }]">{{ item.lyricPath ? '已上传' : '未上传' }}</span></td>
                    <td>❤️ {{ item.collectCount || 0 }}</td>
                    <td>
                      <div class="action-btns">
                        <button class="btn-edit" @click="updateCoverDialog(item)">更换封面</button>
                        <button class="btn-song-info" @click="updateSongInfoDialog(item)">修改歌名歌手</button>
                        <button class="btn-genre" @click="updateGenreDialog(item)">修改类型</button>
                        <button class="btn-metadata" @click="updateMetadataDialog(item)">编辑出处简介</button>
                        <button class="btn-lyric" @click="updateLyricDialog(item)">上传歌词</button>
                        <button class="btn-lyric-editor" @click="openLyricEditor(item)">制作歌词</button>
                        <button class="btn-delete" @click="handleDeleteAudio(item.id)">删除</button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

      </div>

            <!-- 新增加封面弹窗 -->
      <section v-if="currentMenu==='statistics'" class="statistics-dashboard">
        <div class="stats-toolbar">
          <div>
            <p class="stats-eyebrow">MUSIC DATA CENTER</p>
            <h2>播放数据总览</h2>
            <p class="stats-subtitle">按月份查看歌曲热度与每日播放趋势</p>
          </div>
          <label class="month-picker">
            <span>统计月份</span>
            <input v-model="selectedMonth" type="month" @change="loadStatistics" />
          </label>
        </div>
        <div class="stats-kpis">
          <div class="kpi-card"><span>总播放次数</span><strong>{{ totalMonthlyPlays() }}</strong><small>selected month</small></div>
          <div class="kpi-card"><span>有播放歌曲</span><strong>{{ playedSongCount() }}</strong><small>tracks played</small></div>
          <div class="kpi-card"><span>最高单曲播放</span><strong>{{ topSongPlays() }}</strong><small>top track plays</small></div>
        </div>
        <div class="chart-card song-chart-card">
          <div class="chart-heading"><div><p>TOP TRACKS</p><h3>歌曲播放排行</h3></div><span>{{ selectedMonth }}</span></div>
          <div v-if="monthlyPlayList.length" class="bar-chart song-bar-chart">
            <div v-for="item in topSongs()" :key="item.audioId" class="bar-column">
              <span class="bar-value">{{ item.playCount }}</span>
              <div class="bar-track"><div class="bar-fill song-bar" :style="{ height: barHeight(item.playCount, topSongPlays()) }"></div></div>
              <strong :title="item.songName">{{ item.songName }}</strong><small>{{ item.singer }}</small>
            </div>
          </div>
          <div v-else class="chart-empty">这个月还没有播放数据</div>
        </div>
        <div class="chart-card daily-chart-card">
          <div class="chart-heading"><div><p>DAILY TREND</p><h3>每日播放趋势</h3></div><span>{{ selectedMonth }}</span></div>
          <div class="daily-chart-wrap">
            <div class="daily-y-axis"><span>{{ maxDailyPlays() }}</span><span>{{ Math.ceil(maxDailyPlays() / 2) }}</span><span>0</span></div>
            <div class="bar-chart daily-bar-chart">
              <div v-for="item in monthDays()" :key="item.day" class="bar-column daily-column">
                <span v-if="item.playCount" class="bar-value">{{ item.playCount }}</span>
                <div class="bar-track"><div class="bar-fill daily-bar" :style="{ height: barHeight(item.playCount, maxDailyPlays()) }"></div></div>
                <small>{{ item.day }}</small>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="currentMenu==='evaluation'" class="evaluation-dashboard">
        <div class="evaluation-hero">
          <div>
            <p class="evaluation-eyebrow">RAG QUALITY LAB</p>
            <h2>检索效果评测</h2>
            <p>运行{{ evaluationCases.length }}题测试集，评估混合检索、Rerank和低置信度拒答。</p>
          </div>
          <div class="evaluation-actions">
            <label>Top-K
              <select v-model.number="evaluationTopK" :disabled="evaluationRunning">
                <option :value="3">3</option><option :value="5">5</option><option :value="10">10</option>
              </select>
            </label>
            <button class="evaluation-run-btn" :disabled="evaluationRunning || !evaluationCases.length" @click="runEvaluation">
              {{ evaluationRunning ? '评测运行中…' : '▶ 开始评测' }}
            </button>
            <button class="evaluation-export-btn" :disabled="evaluationRunning" @click="showEvaluationDataset = !showEvaluationDataset">
              {{ showEvaluationDataset ? '收起测试集' : '管理测试集' }}
            </button>
            <button class="evaluation-add-btn" :disabled="evaluationRunning" @click="openEvaluationCaseDialog()">＋ 新增测试题</button>
            <button v-if="evaluationSummary" class="evaluation-export-btn" @click="downloadEvaluationReport">导出JSON</button>
          </div>
        </div>

        <div v-if="evaluationLoading" class="evaluation-state">正在加载测试集…</div>
        <div v-else-if="evaluationError" class="evaluation-state error">{{ evaluationError }}</div>
        <template v-else>
          <div class="evaluation-progress-card">
            <div class="evaluation-progress-head">
              <span>{{ evaluationRunning ? '正在评测' : evaluationSummary ? '评测完成' : '等待开始' }}</span>
              <strong>{{ evaluationProgress }} / {{ evaluationCases.length }}</strong>
            </div>
            <div class="evaluation-progress-track"><div :style="{width: evaluationProgressPercent + '%'}"></div></div>
            <small v-if="evaluationCurrentCase">当前：{{ evaluationCurrentCase.id }} · {{ evaluationCurrentCase.question }}</small>
          </div>

          <div v-if="showEvaluationDataset" class="evaluation-dataset-panel">
            <div class="evaluation-panel-head">
              <div><p>DATASET MANAGEMENT</p><h3>测试集管理</h3></div>
              <span>{{ evaluationCases.length }} 题</span>
            </div>
            <div class="evaluation-dataset-table-wrap">
              <table class="evaluation-table evaluation-dataset-table">
                <thead><tr><th>ID</th><th>类别</th><th>问题</th><th>标准歌曲</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="item in evaluationCases" :key="item.id">
                    <td><strong>{{ item.id }}</strong></td>
                    <td><span class="evaluation-category-chip">{{ item.category || '未分类' }}</span></td>
                    <td class="evaluation-question-cell" :title="item.question">{{ item.question }}</td>
                    <td>{{ evaluationExpectedLabel(item) }}</td>
                    <td>
                      <div class="evaluation-row-actions">
                        <button @click="openEvaluationCaseDialog(item)">编辑</button>
                        <button class="danger" @click="deleteEvaluationCase(item)">删除</button>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-if="evaluationSummary" class="evaluation-metrics">
            <div class="evaluation-metric"><span>Hit@{{ evaluationTopK }}</span><strong>{{ formatPercent(evaluationSummary.hitAtK) }}</strong><small>至少命中一个标准结果</small></div>
            <div class="evaluation-metric"><span>Recall@{{ evaluationTopK }}</span><strong>{{ formatPercent(evaluationSummary.recallAtK) }}</strong><small>标准歌曲召回比例</small></div>
            <div class="evaluation-metric"><span>MRR</span><strong>{{ formatDecimal(evaluationSummary.mrr) }}</strong><small>首个正确结果排名质量</small></div>
            <div class="evaluation-metric"><span>Top-1 准确率</span><strong>{{ formatPercent(evaluationSummary.top1Accuracy) }}</strong><small>第一名是否正确</small></div>
            <div class="evaluation-metric"><span>拒答准确率</span><strong>{{ formatPercent(evaluationSummary.negativeAccuracy) }}</strong><small>未收录问题正确拒绝</small></div>
            <div class="evaluation-metric primary"><span>总体案例准确率</span><strong>{{ formatPercent(evaluationSummary.overallAccuracy) }}</strong><small>{{ evaluationSummary.passedCases }}/{{ evaluationSummary.scorableCases }} 题通过</small></div>
          </div>

          <div v-if="evaluationSummary" class="evaluation-grid">
            <div class="evaluation-panel">
              <div class="evaluation-panel-head"><div><p>BY CATEGORY</p><h3>分类指标</h3></div><span>{{ evaluationCategoryMetrics.length }} 类</span></div>
              <div class="evaluation-table-wrap">
                <table class="evaluation-table">
                  <thead><tr><th>类别</th><th>题数</th><th>准确率</th><th>Recall@K</th><th>MRR</th></tr></thead>
                  <tbody><tr v-for="item in evaluationCategoryMetrics" :key="item.category"><td>{{ item.category }}</td><td>{{ item.count }}</td><td>{{ formatPercent(item.accuracy) }}</td><td>{{ formatPercent(item.recallAtK) }}</td><td>{{ formatDecimal(item.mrr) }}</td></tr></tbody>
                </table>
              </div>
            </div>
            <div class="evaluation-panel failure-panel">
              <div class="evaluation-panel-head"><div><p>FAILURE ANALYSIS</p><h3>失败案例</h3></div><span>{{ evaluationFailures.length }} 题</span></div>
              <div v-if="!evaluationFailures.length" class="evaluation-empty">当前没有失败案例</div>
              <div v-else class="evaluation-failures">
                <article v-for="item in evaluationFailures" :key="item.id">
                  <div><strong>{{ item.id }}</strong><span>{{ item.category }}</span></div>
                  <p>{{ item.question }}</p>
                  <small>期望 {{ item.expectedDisplay || item.expectedAudioIds }} · 实际 {{ item.retrievedAudioIds }}</small>
                </article>
              </div>
            </div>
          </div>
        </template>
      </section>

      <div class="dialog-overlay" v-if="showEvaluationCaseDialog" @click.self="closeEvaluationCaseDialog">
        <div class="dialog-card evaluation-case-dialog">
          <div class="dialog-header">
            <div>
              <span class="evaluation-dialog-kicker">RAG TEST CASE</span>
              <h3>{{ editingEvaluationCaseId ? `编辑测试题 ${editingEvaluationCaseId}` : '新增测试题' }}</h3>
            </div>
            <button class="dialog-close" @click="closeEvaluationCaseDialog">✕</button>
          </div>
          <div class="dialog-body evaluation-case-form">
            <div class="evaluation-form-grid">
              <div class="dialog-form-group">
                <label>测试类别</label>
                <input v-model.trim="evaluationCaseForm.category" maxlength="50" placeholder="例如：歌手查歌曲、否定事实" />
              </div>
              <div class="dialog-form-group">
                <label>评测模式</label>
                <select v-model="evaluationCaseForm.evaluationMode" @change="handleEvaluationModeChange">
                  <option value="standard">标准检索</option>
                  <option value="collection_ranking">收藏量动态排序</option>
                </select>
              </div>
            </div>
            <div class="dialog-form-group">
              <label>用户问题 <em>*</em></label>
              <textarea v-model.trim="evaluationCaseForm.question" maxlength="500" placeholder="输入需要评测的真实用户问题"></textarea>
              <small class="form-hint">{{ evaluationCaseForm.question.length }}/500</small>
            </div>
            <label v-if="evaluationCaseForm.evaluationMode === 'standard'" class="evaluation-negative-toggle">
              <input v-model="evaluationCaseForm.negative" type="checkbox" @change="handleNegativeCaseChange" />
              <span><strong>这是未收录实体问题</strong><small>开启后期望结果为空，用于评测拒答能力。</small></span>
            </label>
            <div v-if="evaluationCaseForm.evaluationMode === 'standard' && !evaluationCaseForm.negative" class="dialog-form-group">
              <label>标准歌曲（可多选）</label>
              <select v-model="evaluationCaseForm.expectedAudioIds" multiple class="evaluation-song-select">
                <option v-for="song in fullAudioList" :key="song.id" :value="Number(song.id)">#{{ song.id }} · {{ song.songName }} - {{ song.singer }}</option>
              </select>
              <small class="form-hint">这里选择的歌曲 ID 用于计算 Hit、Recall 和 MRR。</small>
            </div>
            <div v-if="evaluationCaseForm.evaluationMode === 'collection_ranking'" class="dialog-form-group">
              <label>期望返回数量</label>
              <input v-model.number="evaluationCaseForm.expectedResultCount" type="number" min="1" max="20" />
              <small class="form-hint">系统会根据评测时数据库中的实时收藏量计算正确顺序。</small>
            </div>
            <div class="dialog-form-group">
              <label>标准答案说明（可选）</label>
              <textarea v-model.trim="evaluationCaseForm.expectedAnswer" maxlength="2000" placeholder="用于人工查看，不直接参与当前检索指标计算"></textarea>
            </div>
            <div class="evaluation-form-grid">
              <div class="dialog-form-group">
                <label>答案必须包含（可选）</label>
                <input v-model="evaluationCaseForm.mustInclude" placeholder="多个关键词用逗号分隔" />
              </div>
              <div class="dialog-form-group">
                <label>答案禁止包含（可选）</label>
                <input v-model="evaluationCaseForm.mustNotInclude" placeholder="多个关键词用逗号分隔" />
              </div>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" :disabled="evaluationCaseSaving" @click="closeEvaluationCaseDialog">取消</button>
            <button class="btn-confirm" :disabled="evaluationCaseSaving || !evaluationCaseForm.question" @click="saveEvaluationCase">
              {{ evaluationCaseSaving ? '保存中…' : '保存测试题' }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="false" class="content-body">
        <div class="card">
          <div class="card-header">
            <h3>本月各歌曲播放次数</h3>
            <span class="count-badge">{{ monthlyPlayList.length }} 首歌曲</span>
          </div>
          <div class="table-wrap">
            <table class="data-table">
              <thead><tr><th>歌曲</th><th>歌手</th><th>本月播放次数</th></tr></thead>
              <tbody>
                <tr v-if="monthlyPlayList.length === 0"><td colspan="3" class="empty-cell">本月暂无播放记录</td></tr>
                <tr v-for="item in monthlyPlayList" :key="item.audioId">
                  <td><strong>{{ item.songName }}</strong></td><td>{{ item.singer }}</td><td>{{ item.playCount }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <div class="card">
          <div class="card-header">
            <h3>近 30 天用户每日听歌记录</h3>
            <span class="count-badge">{{ recentDailyPlayList.length }} 条记录</span>
          </div>
          <div class="table-wrap">
            <table class="data-table">
              <thead><tr><th>日期</th><th>用户</th><th>播放次数</th></tr></thead>
              <tbody>
                <tr v-if="recentDailyPlayList.length === 0"><td colspan="3" class="empty-cell">近 30 天暂无播放记录</td></tr>
                <tr v-for="item in recentDailyPlayList" :key="item.playDate + '-' + item.username">
                  <td>{{ formatPlayDate(item.playDate) }}</td><td>{{ item.username }}</td><td>{{ item.playCount }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div class="dialog-overlay" v-if="showCoverDialog" @click.self="showCoverDialog=false">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>更换封面</h3>
            <button class="dialog-close" @click="showCoverDialog=false">✕</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-form-group">
              <label>歌曲：{{ coverDialogAudio?.songName }}</label>
              <div class="cover-upload-wrap">
                <div class="cover-preview" v-if="coverPreview2">
                  <img :src="coverPreview2" class="cover-thumb" />
                </div>
                <button class="btn-cover" @click="$refs.coverFile2.click()">选择封面图片</button>
                <input type="file" ref="coverFile2" accept=".jpg,.jpeg,.png,.webp" @change="handleCoverSelect2" hidden />
              </div>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" @click="showCoverDialog=false">取消</button>
            <button class="btn-confirm" @click="submitCoverUpdate">确认上传</button>
          </div>
        </div>
      </div>

      <div class="dialog-overlay" v-if="showGenreDialog" @click.self="showGenreDialog=false">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>修改音乐类型</h3>
            <button class="dialog-close" @click="showGenreDialog=false">✕</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-form-group">
              <label>歌曲：{{ genreDialogAudio?.songName }}</label>
              <div class="genre-checkbox-grid dialog-genre-grid">
                <label v-for="genre in musicGenres" :key="genre" class="genre-checkbox" :class="{ selected: genreInput.includes(genre) }">
                  <input v-model="genreInput" type="checkbox" :value="genre" />
                  <span>{{ genre }}</span>
                </label>
              </div>
              <small class="form-hint">可同时选择多个类型，例如“动漫”和“轻音乐”。</small>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" @click="showGenreDialog=false">取消</button>
            <button class="btn-confirm" @click="submitGenreUpdate">保存类型</button>
          </div>
        </div>
      </div>

      <div class="dialog-overlay" v-if="showMetadataDialog" @click.self="showMetadataDialog=false">
        <div class="dialog-card metadata-dialog-card">
          <div class="dialog-header">
            <h3>编辑出处与简介</h3>
            <button class="dialog-close" @click="showMetadataDialog=false">✕</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-form-group">
              <label>歌曲：{{ metadataDialogAudio?.songName }}</label>
            </div>
            <div class="dialog-form-group">
              <label>出处</label>
              <input v-model="metadataInput.source" maxlength="255" placeholder="例如：动画《作品名》主题曲 / 专辑名称" />
            </div>
            <div class="dialog-form-group">
              <label>简介</label>
              <textarea v-model="metadataInput.introduction" maxlength="2000" placeholder="填写歌曲背景、情绪、适用场景等内容"></textarea>
              <small class="form-hint">{{ metadataInput.introduction.length }}/2000</small>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" @click="showMetadataDialog=false">取消</button>
            <button class="btn-confirm" @click="submitMetadataUpdate">保存</button>
          </div>
        </div>
      </div>

      <div class="dialog-overlay" v-if="showSongInfoDialog" @click.self="showSongInfoDialog=false">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>修改歌名和歌手</h3>
            <button class="dialog-close" @click="showSongInfoDialog=false">✕</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-form-group">
              <label>歌名</label>
              <input v-model="songInfoInput.songName" maxlength="120" placeholder="请输入歌曲名称" />
            </div>
            <div class="dialog-form-group">
              <label>歌手</label>
              <input v-model="songInfoInput.singer" maxlength="120" placeholder="请输入歌手名称" />
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" @click="showSongInfoDialog=false">取消</button>
            <button class="btn-confirm" @click="submitSongInfoUpdate">保存</button>
          </div>
        </div>
      </div>

      <div class="dialog-overlay" v-if="showLyricDialog" @click.self="showLyricDialog=false">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>上传同步歌词</h3>
            <button class="dialog-close" @click="showLyricDialog=false">✕</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-form-group">
              <label>歌曲：{{ lyricDialogAudio?.songName }}</label>
              <input type="file" ref="lyricFile2" accept=".lrc" @change="handleLyricSelect2" />
              <small class="form-hint">仅支持带时间戳的 .lrc 文件；重新上传会替换旧歌词。</small>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" @click="showLyricDialog=false">取消</button>
            <button class="btn-confirm" @click="submitLyricUpdate">确认上传</button>
          </div>
        </div>
      </div>

      <div class="dialog-overlay" v-if="showLyricEditor" @click.self="closeLyricEditor">
        <div class="lyric-editor-dialog">
          <div class="dialog-header">
            <div><span class="lyrics-editor-kicker">AUTHORIZED LYRICS ONLY</span><h3>同步歌词制作器</h3></div>
            <button class="dialog-close" @click="closeLyricEditor">✕</button>
          </div>
          <div class="lyric-editor-body">
            <p class="lyric-editor-description">《{{ lyricEditorAudio?.songName }}》：粘贴你有权使用的歌词，每行一句；播放歌曲后，为每行记录当前时间。</p>
            <audio v-if="lyricEditorAudio" ref="lyricEditorPlayer" class="lyric-editor-player" :src="lyricEditorAudio.savePath" controls @timeupdate="updateLyricEditorTime" @loadedmetadata="updateLyricEditorTime"></audio>
            <div class="lyric-editor-clock">当前播放时间 <strong>{{ formatLrcTime(lyricEditorCurrentTime) }}</strong></div>
            <label class="lyric-editor-label">歌词文本</label>
            <textarea v-model="lyricSourceText" class="lyric-source-input" placeholder="每行一句歌词，例如：&#10;第一句歌词&#10;第二句歌词"></textarea>
            <button class="btn-prepare-lyrics" @click="prepareLyricLines">载入并开始标记</button>
            <div v-if="lyricEditorLines.length" class="lyric-timeline">
              <div v-for="(line, index) in lyricEditorLines" :key="index" class="lyric-timeline-row" :class="{ stamped: line.time !== null }">
                <span class="lyric-line-number">{{ index + 1 }}</span>
                <span class="lyric-line-text">{{ line.text }}</span>
                <button type="button" class="timestamp-btn" @click="stampLyricLine(index)">{{ line.time === null ? '记录当前时间' : formatLrcTime(line.time) }}</button>
              </div>
            </div>
            <p v-else class="lyric-editor-empty">先载入歌词文本，再逐行记录时间。</p>
          </div>
          <div class="dialog-footer lyric-editor-footer">
            <button class="btn-cancel" @click="closeLyricEditor">取消</button>
            <button class="btn-cancel" :disabled="!lyricEditorLines.length" @click="downloadLrc">下载 .lrc</button>
            <button class="btn-confirm" :disabled="!canSaveLyrics || lyricSaving" @click="saveCreatedLyrics">{{ lyricSaving ? '保存中...' : '保存到歌曲' }}</button>
          </div>
        </div>
      </div>

      <!-- 用户弹窗 -->
      <div class="dialog-overlay" v-if="showDialog" @click.self="closeDialog">
        <div class="dialog-card">
          <div class="dialog-header">
            <h3>{{ dialogForm.id ? '编辑用户' : '新增用户' }}</h3>
            <button class="dialog-close" @click="closeDialog">✕</button>
          </div>
          <div class="dialog-body">
            <div class="dialog-form-group">
              <label>账号</label>
              <input v-model="dialogForm.username" placeholder="请输入账号" />
            </div>
            <div class="dialog-form-group">
              <label>密码</label>
              <input v-model="dialogForm.password" type="password" placeholder="请输入密码" />
            </div>
            <div class="dialog-form-group">
              <label>角色</label>
              <select v-model="dialogForm.role">
                <option value="user">普通用户</option>
                <option value="admin">管理员</option>
              </select>
            </div>
          </div>
          <div class="dialog-footer">
            <button class="btn-cancel" @click="closeDialog">取消</button>
            <button class="btn-confirm" @click="saveUser">保存</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>
<script>
import request from '@/utils/request'
export default {
  data() {
    return {
      currentMenu: 'audio',
      userList: [],
      pageNum: 1,
      pageSize: 8,
      pages: 1,
      showDialog: false,
      dialogForm: { id: null, username: '', password: '', role: 'user' },
      fullAudioList: [],
      uploadForm: { songName: '', singer: '', genres: ['流行'], source: '', introduction: '' },
      musicGenres: ['流行', '摇滚', '电子', '嘻哈', 'R&B', '民谣', '爵士', '古典', '动漫', '游戏', '原声', '轻音乐', '其他'],
      coverPreview: null,
      coverFile: null,
      lyricFile: null,
      coverPreview2: null,
      coverFile2: null,
      showCoverDialog: false,
      coverDialogAudio: null,
      showGenreDialog: false,
      genreDialogAudio: null,
      genreInput: ['其他'],
      showMetadataDialog: false,
      metadataDialogAudio: null,
      metadataInput: { source: '', introduction: '' },
      showSongInfoDialog: false,
      songInfoDialogAudio: null,
      songInfoInput: { songName: '', singer: '' },
      showLyricDialog: false,
      lyricDialogAudio: null,
      lyricFile2: null,
      showLyricEditor: false,
      lyricEditorAudio: null,
      lyricSourceText: '',
      lyricEditorLines: [],
      lyricEditorCurrentTime: 0,
      lyricSaving: false,
      monthlyPlayList: [],
      recentDailyPlayList: [],
      monthlyDailyPlayList: [],
      selectedMonth: new Date().toISOString().slice(0, 7),
      evaluationCases: [],
      evaluationResults: [],
      evaluationSummary: null,
      evaluationCategoryMetrics: [],
      evaluationTopK: 5,
      evaluationProgress: 0,
      evaluationCurrentCase: null,
      evaluationRunning: false,
      evaluationLoading: false,
      evaluationError: '',
      showEvaluationDataset: false,
      showEvaluationCaseDialog: false,
      editingEvaluationCaseId: null,
      evaluationCaseSaving: false,
      evaluationCaseForm: {
        category: '', question: '', expectedAnswer: '', expectedAudioIds: [],
        mustInclude: '', mustNotInclude: '', negative: false,
        evaluationMode: 'standard', expectedResultCount: 3
      }
    }
  },
  computed: {
    menuTitle() {
      return { user: '用户管理', audio: '音频管理', statistics: '播放统计', evaluation: '检索评测' }[this.currentMenu] || '管理后台'
    },
    menuIcon() {
      return { user: '👥', audio: '🎵', statistics: '📊', evaluation: '🧪' }[this.currentMenu] || '🎵'
    },
    canSaveLyrics() {
      return this.lyricEditorLines.length > 0 && this.lyricEditorLines.every(line => line.time !== null)
    },
    evaluationProgressPercent() {
      if (!this.evaluationCases.length) return 0
      return Math.round(this.evaluationProgress / this.evaluationCases.length * 100)
    },
    evaluationFailures() {
      return this.evaluationResults.filter(item => item.scorable && !item.passed)
    }
  },
  mounted() {
    this.loadUsers();
    this.loadFullAudioList();
  },
  methods: {
    async openEvaluation() {
      this.currentMenu = 'evaluation'
      if (!this.evaluationCases.length) await this.loadEvaluationCases()
    },
    async loadEvaluationCases() {
      this.evaluationLoading = true
      this.evaluationError = ''
      try {
        const res = await request.get('/admin/evaluation/cases')
        if (res.data.code !== 200) throw new Error(res.data.msg || '测试集加载失败')
        this.evaluationCases = res.data.data || []
      } catch (error) {
        this.evaluationError = error.response?.data?.msg || error.message || '测试集加载失败'
      } finally {
        this.evaluationLoading = false
      }
    },
    emptyEvaluationCaseForm() {
      return {
        category: '', question: '', expectedAnswer: '', expectedAudioIds: [],
        mustInclude: '', mustNotInclude: '', negative: false,
        evaluationMode: 'standard', expectedResultCount: 3
      }
    },
    openEvaluationCaseDialog(testCase = null) {
      this.editingEvaluationCaseId = testCase?.id || null
      this.evaluationCaseForm = testCase ? {
        category: testCase.category || '',
        question: testCase.question || '',
        expectedAnswer: testCase.expected_answer || '',
        expectedAudioIds: (testCase.expected_audio_ids || []).map(Number),
        mustInclude: (testCase.must_include || []).join('，'),
        mustNotInclude: (testCase.must_not_include || []).join('，'),
        negative: testCase.category === '否定事实' && !(testCase.expected_audio_ids || []).length,
        evaluationMode: testCase.evaluation_mode || 'standard',
        expectedResultCount: Number(testCase.expected_result_count || 3)
      } : this.emptyEvaluationCaseForm()
      this.showEvaluationCaseDialog = true
    },
    closeEvaluationCaseDialog() {
      if (this.evaluationCaseSaving) return
      this.showEvaluationCaseDialog = false
      this.editingEvaluationCaseId = null
      this.evaluationCaseForm = this.emptyEvaluationCaseForm()
    },
    handleNegativeCaseChange() {
      if (this.evaluationCaseForm.negative) {
        this.evaluationCaseForm.category = '否定事实'
        this.evaluationCaseForm.expectedAudioIds = []
      } else if (this.evaluationCaseForm.category === '否定事实') {
        this.evaluationCaseForm.category = '未分类'
      }
    },
    handleEvaluationModeChange() {
      if (this.evaluationCaseForm.evaluationMode === 'collection_ranking') {
        this.evaluationCaseForm.negative = false
        this.evaluationCaseForm.expectedAudioIds = []
        if (!this.evaluationCaseForm.category || this.evaluationCaseForm.category === '否定事实') this.evaluationCaseForm.category = '收藏排序'
      }
    },
    evaluationExpectedLabel(testCase) {
      if (testCase.evaluation_mode === 'collection_ranking') return `动态排序 · ${testCase.expected_result_count || 3} 首`
      const ids = testCase.expected_audio_ids || []
      if (testCase.category === '否定事实' && !ids.length) return '空结果（拒答）'
      return ids.length ? ids.map(id => `#${id}`).join('、') : '未设置'
    },
    splitEvaluationKeywords(value) {
      return String(value || '').split(/[,，\n]/).map(item => item.trim()).filter(Boolean)
    },
    async saveEvaluationCase() {
      if (this.evaluationCaseSaving || !this.evaluationCaseForm.question.trim()) return
      if (this.evaluationCaseForm.evaluationMode === 'standard' && !this.evaluationCaseForm.negative && !this.evaluationCaseForm.expectedAudioIds.length) {
        alert('请选择至少一首标准歌曲，或将该题设置为未收录实体问题。')
        return
      }
      const payload = {
        category: this.evaluationCaseForm.category || '未分类',
        question: this.evaluationCaseForm.question,
        expected_answer: this.evaluationCaseForm.expectedAnswer,
        expected_audio_ids: this.evaluationCaseForm.expectedAudioIds,
        must_include: this.splitEvaluationKeywords(this.evaluationCaseForm.mustInclude),
        must_not_include: this.splitEvaluationKeywords(this.evaluationCaseForm.mustNotInclude),
        negative: this.evaluationCaseForm.negative,
        evaluation_mode: this.evaluationCaseForm.evaluationMode,
        expected_result_count: this.evaluationCaseForm.expectedResultCount
      }
      this.evaluationCaseSaving = true
      try {
        const res = this.editingEvaluationCaseId
          ? await request.put(`/admin/evaluation/cases/${this.editingEvaluationCaseId}`, payload)
          : await request.post('/admin/evaluation/cases', payload)
        if (res.data.code !== 200) throw new Error(res.data.msg || '测试题保存失败')
        await this.loadEvaluationCases()
        this.evaluationSummary = null
        this.evaluationResults = []
        this.evaluationProgress = 0
        this.showEvaluationCaseDialog = false
        this.editingEvaluationCaseId = null
        this.evaluationCaseForm = this.emptyEvaluationCaseForm()
      } catch (error) {
        alert(error.response?.data?.msg || error.message || '测试题保存失败')
      } finally {
        this.evaluationCaseSaving = false
      }
    },
    async deleteEvaluationCase(testCase) {
      if (this.evaluationRunning || !confirm(`确定删除 ${testCase.id}“${testCase.question}”吗？此操作会直接修改评测集文件。`)) return
      try {
        const res = await request.delete(`/admin/evaluation/cases/${testCase.id}`)
        if (res.data.code !== 200) throw new Error(res.data.msg || '删除失败')
        await this.loadEvaluationCases()
        this.evaluationSummary = null
        this.evaluationResults = []
        this.evaluationProgress = 0
      } catch (error) {
        alert(error.response?.data?.msg || error.message || '删除测试题失败')
      }
    },
    async runEvaluation() {
      if (this.evaluationRunning || !this.evaluationCases.length) return
      this.evaluationRunning = true
      this.evaluationError = ''
      this.evaluationProgress = 0
      this.evaluationResults = []
      this.evaluationSummary = null
      this.evaluationCategoryMetrics = []
      try {
        for (const testCase of this.evaluationCases) {
          this.evaluationCurrentCase = testCase
          const res = await request.post('/admin/evaluation/retrieve', {
            question: testCase.question,
            history: testCase.history || [],
            topK: this.evaluationTopK
          }, { timeout: 60000 })
          if (res.data.code !== 200) throw new Error(`${testCase.id}: ${res.data.msg || '检索失败'}`)
          const rawResults = res.data.data?.results || []
          const retrievedAudioIds = rawResults.map(item => Number(item.audioId)).filter(Number.isFinite)
          const metrics = this.scoreEvaluationCase(testCase, retrievedAudioIds, rawResults, res.data.data)
          this.evaluationResults.push({
            id: testCase.id,
            category: testCase.category || '未分类',
            question: testCase.question,
            expectedAudioIds: (testCase.expected_audio_ids || []).map(Number),
            retrievedAudioIds,
            expectedDisplay: testCase.evaluation_mode === 'collection_ranking'
              ? `动态规则：收藏量降序，共 ${res.data.data?.expectedResultCount ?? testCase.expected_result_count ?? 3} 首`
              : (testCase.expected_audio_ids || []).map(Number).join(','),
            results: rawResults,
            ...metrics
          })
          this.evaluationProgress++
        }
        this.buildEvaluationSummary()
      } catch (error) {
        this.evaluationError = error.response?.data?.msg || error.message || '评测运行失败'
      } finally {
        this.evaluationCurrentCase = null
        this.evaluationRunning = false
      }
    },
    scoreEvaluationCase(testCase, retrievedIds, rawResults = [], responseData = {}) {
      if (testCase.evaluation_mode === 'collection_ranking') {
        const expectedCount = Number(responseData.expectedResultCount ?? testCase.expected_result_count ?? 3)
        const exactCount = retrievedIds.length === expectedCount
        const correctlySorted = rawResults.every((item, index) => {
          if (index === 0) return true
          const previous = rawResults[index - 1]
          const previousCount = Number(previous.collectCount || 0)
          const currentCount = Number(item.collectCount || 0)
          if (previousCount !== currentCount) return previousCount > currentCount
          return new Date(previous.uploadTime || 0).getTime() >= new Date(item.uploadTime || 0).getTime()
        })
        return {
          scorable: true,
          negative: false,
          passed: exactCount && correctlySorted,
          hitAtK: null,
          recallAtK: null,
          reciprocalRank: null,
          top1Correct: null
        }
      }
      const expectedIds = [...new Set((testCase.expected_audio_ids || []).map(Number))]
      const negative = testCase.category === '否定事实' && expectedIds.length === 0
      if (!expectedIds.length && !negative) return { scorable: false, negative: false, passed: null, hitAtK: null, recallAtK: null, reciprocalRank: null, top1Correct: null }
      if (negative) {
        const passed = retrievedIds.length === 0
        return { scorable: true, negative: true, passed, hitAtK: null, recallAtK: null, reciprocalRank: null, top1Correct: null }
      }
      const ranks = []
      retrievedIds.forEach((id, index) => { if (expectedIds.includes(id)) ranks.push(index + 1) })
      const uniqueHits = new Set(retrievedIds.filter(id => expectedIds.includes(id))).size
      const passed = ranks.length > 0
      return {
        scorable: true,
        negative: false,
        passed,
        hitAtK: passed ? 1 : 0,
        recallAtK: uniqueHits / expectedIds.length,
        reciprocalRank: passed ? 1 / Math.min(...ranks) : 0,
        top1Correct: retrievedIds.length > 0 && expectedIds.includes(retrievedIds[0])
      }
    },
    averageEvaluation(values) {
      const valid = values.filter(value => value !== null && value !== undefined)
      return valid.length ? valid.reduce((sum, value) => sum + Number(value), 0) / valid.length : null
    },
    buildEvaluationSummary() {
      const scorable = this.evaluationResults.filter(item => item.scorable)
      const positive = scorable.filter(item => !item.negative)
      const negative = scorable.filter(item => item.negative)
      const passedCases = scorable.filter(item => item.passed).length
      this.evaluationSummary = {
        totalCases: this.evaluationResults.length,
        scorableCases: scorable.length,
        passedCases,
        hitAtK: this.averageEvaluation(positive.map(item => item.hitAtK)),
        recallAtK: this.averageEvaluation(positive.map(item => item.recallAtK)),
        mrr: this.averageEvaluation(positive.map(item => item.reciprocalRank)),
        top1Accuracy: this.averageEvaluation(positive.map(item => item.top1Correct ? 1 : 0)),
        negativeAccuracy: this.averageEvaluation(negative.map(item => item.passed ? 1 : 0)),
        overallAccuracy: this.averageEvaluation(scorable.map(item => item.passed ? 1 : 0))
      }
      const grouped = this.evaluationResults.filter(item => item.scorable).reduce((map, item) => {
        if (!map[item.category]) map[item.category] = []
        map[item.category].push(item)
        return map
      }, {})
      this.evaluationCategoryMetrics = Object.keys(grouped).sort().map(category => {
        const items = grouped[category]
        return {
          category,
          count: items.length,
          accuracy: this.averageEvaluation(items.map(item => item.passed ? 1 : 0)),
          recallAtK: this.averageEvaluation(items.map(item => item.recallAtK)),
          mrr: this.averageEvaluation(items.map(item => item.reciprocalRank))
        }
      })
    },
    formatPercent(value) {
      return value === null || value === undefined ? 'N/A' : `${(Number(value) * 100).toFixed(1)}%`
    },
    formatDecimal(value) {
      return value === null || value === undefined ? 'N/A' : Number(value).toFixed(4)
    },
    downloadEvaluationReport() {
      if (!this.evaluationSummary) return
      const report = {
        generatedAt: new Date().toISOString(),
        topK: this.evaluationTopK,
        summary: this.evaluationSummary,
        categories: this.evaluationCategoryMetrics,
        cases: this.evaluationResults
      }
      const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `retrieval-evaluation-${new Date().toISOString().replace(/[:.]/g, '-')}.json`
      link.click()
      URL.revokeObjectURL(url)
    },
    async openStatistics() {
      this.currentMenu = 'statistics'
      await this.loadStatistics()
    },
    async loadStatistics() {
      try {
        const [monthlyRes, dailyRes] = await Promise.all([
          request.get('/admin/monthlyPlayCounts?month=' + this.selectedMonth),
          request.get('/admin/monthlyDailyPlayCounts?month=' + this.selectedMonth)
        ])
        if (monthlyRes.data.code === 200) this.monthlyPlayList = monthlyRes.data.data
        if (dailyRes.data.code === 200) this.monthlyDailyPlayList = dailyRes.data.data
      } catch (err) { console.error('load statistics failed:', err) }
    },
    topSongs() { return this.monthlyPlayList.slice(0, 8) },
    totalMonthlyPlays() {
      return this.monthlyPlayList.reduce((total, item) => total + Number(item.playCount || 0), 0)
    },
    playedSongCount() { return this.monthlyPlayList.filter(item => Number(item.playCount) > 0).length },
    topSongPlays() { return Math.max(1, ...this.monthlyPlayList.map(item => Number(item.playCount || 0))) },
    maxDailyPlays() {
      return Math.max(1, ...this.monthlyDailyPlayList.map(item => Number(item.playCount || 0)))
    },
    barHeight(value, max) {
      return (Number(value || 0) / Math.max(1, Number(max))) * 100 + '%'
    },
    monthDays() {
      const [year, month] = this.selectedMonth.split('-').map(Number)
      const dayCount = new Date(year, month, 0).getDate()
      const playMap = this.monthlyDailyPlayList.reduce((map, item) => {
        const day = String(item.playDate).slice(8, 10)
        map[Number(day)] = Number(item.playCount || 0)
        return map
      }, {})
      return Array.from({ length: dayCount }, (_, index) => ({ day: index + 1, playCount: playMap[index + 1] || 0 }))
    },
    formatPlayDate(value) {
      if (!value) return ''
      return String(value).slice(0, 10)
    },
    // ========== Users ==========
    async loadUsers() {
      try {
        const res = await request.get('/admin/userPage?pageNum=' + this.pageNum)
        if (res.data.code === 200) {
          this.userList = res.data.data.list
          this.pages = res.data.data.pages
        }
      } catch (err) { console.error('load users failed:', err) }
    },
    prevPage() { if (this.pageNum > 1) { this.pageNum--; this.loadUsers() } },
    nextPage() { if (this.pageNum < this.pages) { this.pageNum++; this.loadUsers() } },
    openDialog(item) {
      if (item) { this.dialogForm = { id: item.id, username: item.username, password: '', role: item.role } }
      else { this.dialogForm = { id: null, username: '', password: '', role: 'user' } }
      this.showDialog = true
    },
    closeDialog() { this.showDialog = false },
    async saveUser() {
      if (!this.dialogForm.username) return alert('请输入账号')
      try {
        const res = this.dialogForm.id
          ? await request.post('/admin/updateUser', this.dialogForm)
          : await request.post('/admin/addUser', this.dialogForm)
        if (res.data.code === 200) {
          this.closeDialog(); this.loadUsers()
        } else { alert(res.data.msg) }
      } catch (err) { alert('操作失败') }
    },
    async handleDelete(id) {
      if (!confirm('确认删除该用户？')) return
      await request.delete('/admin/delete/' + id)
      this.loadUsers()
    },
    // ========== Audio ==========
    async loadFullAudioList() {
      try {
        const res = await request.get('/admin/audioList')
        if (res.data.code === 200) this.fullAudioList = res.data.data
      } catch (err) { console.error('load audio failed:', err) }
    },
    getImageUrl(path) {
      if (!path) return ''
      return path
    },
    openLyricEditor(item) {
      this.lyricEditorAudio = item
      this.lyricSourceText = ''
      this.lyricEditorLines = []
      this.lyricEditorCurrentTime = 0
      this.showLyricEditor = true
      this.$nextTick(() => {
        const player = this.$refs.lyricEditorPlayer
        if (player) player.currentTime = 0
      })
    },
    closeLyricEditor() {
      const player = this.$refs.lyricEditorPlayer
      if (player) player.pause()
      this.showLyricEditor = false
    },
    updateLyricEditorTime() {
      const player = this.$refs.lyricEditorPlayer
      if (player) this.lyricEditorCurrentTime = player.currentTime || 0
    },
    prepareLyricLines() {
      const lines = this.lyricSourceText.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
      if (!lines.length) return alert('请先粘贴至少一行歌词')
      this.lyricEditorLines = lines.map(text => ({ text, time: null }))
    },
    stampLyricLine(index) {
      if (!this.lyricEditorLines[index]) return
      this.lyricEditorLines[index].time = Number(this.lyricEditorCurrentTime.toFixed(2))
      const next = this.$el.querySelectorAll('.lyric-timeline-row')[index + 1]
      if (next) next.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    },
    formatLrcTime(seconds) {
      const value = Number(seconds || 0)
      const minutes = Math.floor(value / 60)
      const secondPart = (value % 60).toFixed(2).padStart(5, '0')
      return `${String(minutes).padStart(2, '0')}:${secondPart}`
    },
    buildLrcContent() {
      return this.lyricEditorLines
        .map(line => `[${this.formatLrcTime(line.time)}]${line.text}`)
        .join('\n') + '\n'
    },
    downloadLrc() {
      if (!this.lyricEditorLines.length) return
      const blob = new Blob([this.buildLrcContent()], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${this.lyricEditorAudio.songName || 'lyrics'}.lrc`
      link.click()
      URL.revokeObjectURL(url)
    },
    async saveCreatedLyrics() {
      if (!this.canSaveLyrics || !this.lyricEditorAudio || this.lyricSaving) return
      this.lyricSaving = true
      const formData = new FormData()
      const file = new File([this.buildLrcContent()], `lyrics-${this.lyricEditorAudio.id}.lrc`, { type: 'text/plain' })
      formData.append('id', this.lyricEditorAudio.id)
      formData.append('lyric', file)
      try {
        const res = await request.post('/admin/updateLyric', formData)
        if (res.data.code === 200) {
          this.closeLyricEditor()
          this.loadFullAudioList()
          alert('同步歌词已保存')
        } else alert(res.data.msg || '歌词保存失败')
      } catch (error) {
        alert('歌词保存失败，请稍后重试')
      } finally {
        this.lyricSaving = false
      }
    },
    selectCoverFile() { this.$refs.coverFile.click() },
    handleCoverSelect(e) {
      const file = e.target.files[0]
      if (!file) return
      this.coverFile = file
      const reader = new FileReader()
      reader.onload = (ev) => { this.coverPreview = ev.target.result }
      reader.readAsDataURL(file)
    },
    removeCover() { this.coverPreview = null; this.coverFile = null },
    selectAudioFile() { this.$refs.audioFile.click() },
    async handleAudioUpload(e) {
      const file = e.target.files[0]
      if (!file) return
      if (!this.uploadForm.songName) return alert('请输入歌名')
      if (!this.uploadForm.singer) return alert('请输入歌手')
      if (!this.uploadForm.genres.length) return alert('请至少选择一个音乐类型')
      const formData = new FormData()
      formData.append('file', file)
      formData.append('songName', this.uploadForm.songName)
      formData.append('singer', this.uploadForm.singer)
      formData.append('genre', this.uploadForm.genres.join(','))
      formData.append('source', this.uploadForm.source)
      formData.append('introduction', this.uploadForm.introduction)
      if (this.coverFile) formData.append('cover', this.coverFile)
      if (this.lyricFile) formData.append('lyric', this.lyricFile)
      try {
        const res = await request.post('/admin/uploadAudio', formData)
        if (res.data.code === 200) {
          alert('上传成功！')
          this.uploadForm = { songName: '', singer: '', genres: ['流行'], source: '', introduction: '' }
          this.coverPreview = null; this.coverFile = null; this.lyricFile = null
          this.$refs.audioFile.value = ''
          this.$refs.lyricFile.value = ''
          this.loadFullAudioList()
        } else { alert(res.data.msg) }
      } catch (err) { alert('上传失败') }
    },
    async handleDeleteAudio(id) {
      if (!confirm('确认删除该音频？')) return
      try {
        const res = await request.delete('/admin/deleteAudio/' + id)
        if (res.data.code === 200) { alert('删除成功'); this.loadFullAudioList() }
      } catch (err) { alert('删除失败') }
    },
    updateCoverDialog(item) {
      this.coverDialogAudio = item
      this.coverPreview2 = null
      this.coverFile2 = null
      this.showCoverDialog = true
    },
    updateGenreDialog(item) {
      this.genreDialogAudio = item
      this.genreInput = this.splitGenres(item.genre)
      this.showGenreDialog = true
    },
    updateMetadataDialog(item) {
      this.metadataDialogAudio = item
      this.metadataInput = { source: item.source || '', introduction: item.introduction || '' }
      this.showMetadataDialog = true
    },
    updateSongInfoDialog(item) {
      this.songInfoDialogAudio = item
      this.songInfoInput = { songName: item.songName || '', singer: item.singer || '' }
      this.showSongInfoDialog = true
    },
    async submitSongInfoUpdate() {
      if (!this.songInfoDialogAudio) return
      if (!this.songInfoInput.songName.trim() || !this.songInfoInput.singer.trim()) {
        return alert('歌名和歌手不能为空')
      }
      try {
        const res = await request.post('/admin/updateSongInfo', {
          id: this.songInfoDialogAudio.id,
          songName: this.songInfoInput.songName,
          singer: this.songInfoInput.singer
        })
        if (res.data.code === 200) {
          this.showSongInfoDialog = false
          this.loadFullAudioList()
        } else alert(res.data.msg || '歌曲信息更新失败')
      } catch (err) {
        alert('歌曲信息更新失败，请稍后重试')
      }
    },
    async submitMetadataUpdate() {
      if (!this.metadataDialogAudio) return
      try {
        const res = await request.post('/admin/updateMetadata', {
          id: this.metadataDialogAudio.id,
          source: this.metadataInput.source,
          introduction: this.metadataInput.introduction
        })
        if (res.data.code === 200) {
          this.showMetadataDialog = false
          this.loadFullAudioList()
        } else alert(res.data.msg || '出处简介更新失败')
      } catch (err) {
        alert('出处简介更新失败，请稍后重试')
      }
    },
    handleLyricSelect(e) {
      const file = e.target.files[0]
      if (file) this.lyricFile = file
    },
    updateLyricDialog(item) {
      this.lyricDialogAudio = item
      this.lyricFile2 = null
      this.showLyricDialog = true
    },
    handleLyricSelect2(e) {
      this.lyricFile2 = e.target.files[0] || null
    },
    async submitLyricUpdate() {
      if (!this.lyricDialogAudio || !this.lyricFile2) return alert('请选择 .lrc 歌词文件')
      const formData = new FormData()
      formData.append('id', this.lyricDialogAudio.id)
      formData.append('lyric', this.lyricFile2)
      try {
        const res = await request.post('/admin/updateLyric', formData)
        if (res.data.code === 200) {
          this.showLyricDialog = false
          this.loadFullAudioList()
        } else alert(res.data.msg || '歌词上传失败')
      } catch (err) {
        alert('歌词上传失败，请确认文件格式正确')
      }
    },
    async submitGenreUpdate() {
      if (!this.genreDialogAudio || !this.genreInput.length) return alert('请至少选择一个音乐类型')
      try {
        const res = await request.post('/admin/updateGenre', { id: this.genreDialogAudio.id, genre: this.genreInput })
        if (res.data.code === 200) {
          this.showGenreDialog = false
          this.loadFullAudioList()
        } else alert(res.data.msg || '类型更新失败')
      } catch (err) {
        alert('类型更新失败，请稍后重试')
      }
    },
    splitGenres(value) {
      const genres = String(value || '').split(/[,，;；/|]/).map(item => item.trim()).filter(Boolean)
      return genres.length ? [...new Set(genres)] : ['其他']
    },
    handleCoverSelect2(e) {
      const file = e.target.files[0]
      if (!file) return
      this.coverFile2 = file
      const reader = new FileReader()
      reader.onload = (ev) => { this.coverPreview2 = ev.target.result }
      reader.readAsDataURL(file)
    },
    async submitCoverUpdate() {
      if (!this.coverFile2 || !this.coverDialogAudio) return
      const formData = new FormData()
      formData.append('cover', this.coverFile2)
      formData.append('id', this.coverDialogAudio.id)
      try {
        const res = await request.post('/admin/updateCover', formData)
        if (res.data.code === 200) {
          alert('封面更新成功！')
          this.showCoverDialog = false
          this.loadFullAudioList()
        } else { alert(res.data.msg) }
      } catch (err) { alert('更新失败') }
    }
  }
}
</script>
<style scoped>
.admin-page { display: flex; min-height: 100vh; background: #f5f7fa; }
.sidebar { width: 220px; background: #1a1a2e; color: #fff; display: flex; flex-direction: column; flex-shrink: 0; }
.sidebar-header { padding: 20px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid rgba(255,255,255,0.06); }
.sidebar-header .logo-icon { font-size: 24px; }
.sidebar-header .logo-text { font-size: 16px; font-weight: 700; }
.sidebar-nav { flex: 1; padding: 12px; display: flex; flex-direction: column; gap: 4px; }
.sidebar-nav .nav-item { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 10px; cursor: pointer; transition: all 0.2s ease; color: rgba(255,255,255,0.6); font-size: 14px; font-weight: 500; }
.sidebar-nav .nav-item:hover { background: rgba(255,255,255,0.06); color: #fff; }
.sidebar-nav .nav-item.active { background: rgba(102,126,234,0.2); color: #fff; font-weight: 600; }
.nav-icon { font-size: 18px; }
.sidebar-footer { padding: 16px; border-top: 1px solid rgba(255,255,255,0.06); }
.sidebar-footer .back-btn { width: 100%; padding: 10px; border: 1px solid rgba(255,255,255,0.15); background: transparent; color: rgba(255,255,255,0.6); border-radius: 10px; font-size: 13px; cursor: pointer; transition: all 0.2s ease; }
.sidebar-footer .back-btn:hover { background: rgba(255,255,255,0.08); color: #fff; }

.main-content { flex: 1; padding: 24px; overflow-y: auto; }
.content-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.content-header h2 { font-size: 22px; font-weight: 700; color: #1a1a2e; margin: 0; }
.header-badge { font-size: 28px; }
.content-body { display: flex; flex-direction: column; gap: 24px; }
.card { background: #fff; border-radius: 16px; box-shadow: 0 2px 12px rgba(0,0,0,0.04); overflow: hidden; }
.card-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 24px; border-bottom: 1px solid #f0f2f5; }
.card-header h3 { margin: 0; font-size: 16px; font-weight: 600; color: #1a1a2e; }
.count-badge { font-size: 13px; color: #888; background: #f0f2f5; padding: 4px 12px; border-radius: 20px; }
.btn-primary { padding: 8px 18px; border: none; background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.btn-primary:hover { transform: translateY(-1px); box-shadow: 0 4px 15px rgba(102,126,234,0.3); }

.table-wrap { overflow-x: auto; border-radius: 14px; }
.data-table { width: 100%; border-collapse: collapse; }
.table-wrap .data-table:has(.col-actions) { min-width: 1420px; table-layout: fixed; }
.col-cover { width: 92px; }
.col-song { width: 340px; }
.col-singer { width: 250px; }
.col-genre { width: 150px; }.col-lyric, .col-favorite { width: 80px; }
.col-actions { width: 500px; }
.data-table th { padding: 12px 20px; text-align: left; font-size: 13px; font-weight: 600; color: #888; background: #fafbfc; border-bottom: 2px solid #f0f2f5; white-space: nowrap; }
.data-table td { padding: 14px 20px; font-size: 14px; color: #333; border-bottom: 1px solid #f0f2f5; vertical-align: middle; }
.data-table tr:last-child td { border-bottom: none; }
.data-table tr:hover td { background: #fafbfc; }
.song-cell strong, .singer-cell span { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-height: 1.45; word-break: break-word; }
.song-cell strong { color: inherit; font-size: inherit; font-weight: 400; }
.cell-id { color: #888; font-weight: 500; }
.role-tag { padding: 4px 12px; border-radius: 20px; font-size: 12px; font-weight: 600; }
.role-admin { background: rgba(102,126,234,0.1); color: #667eea; }
.role-user { background: rgba(67,233,123,0.1); color: #43e97b; }
.action-btns { display: grid; grid-template-columns: repeat(4, max-content); gap: 8px; align-items: center; }
.action-btns button { white-space: nowrap; }
.btn-edit { padding: 6px 14px; border: 1px solid #667eea; background: rgba(102,126,234,0.06); color: #667eea; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.btn-edit:hover { background: rgba(102,126,234,0.12); }
.btn-genre { padding: 6px 14px; border: 1px solid #8b69d5; background: rgba(139,105,213,0.06); color: #7852c3; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }.btn-genre:hover { background: rgba(139,105,213,0.13); }
.btn-song-info { padding: 6px 14px; border: 1px solid #5e86d8; background: rgba(94,134,216,0.07); color: #486fbf; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }.btn-song-info:hover { background: rgba(94,134,216,0.15); }
.btn-metadata { padding: 6px 14px; border: 1px solid #4f9b9a; background: rgba(79,155,154,0.07); color: #317d7c; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }.btn-metadata:hover { background: rgba(79,155,154,0.15); }
.btn-delete { padding: 6px 14px; border: 1px solid #e53e3e; background: rgba(229,62,62,0.06); color: #e53e3e; border-radius: 8px; font-size: 12px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.btn-delete:hover { background: rgba(229,62,62,0.12); }

.pagination { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 16px 24px; border-top: 1px solid #f0f2f5; }
.page-btn { padding: 8px 18px; border: 1px solid #e8e8ec; background: #fff; border-radius: 8px; font-size: 13px; color: #666; cursor: pointer; transition: all 0.2s ease; }
.page-btn:hover:not(:disabled) { border-color: #667eea; color: #667eea; }
.page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.page-info { font-size: 14px; color: #888; font-weight: 500; }

.upload-form { display: flex; flex-direction: column; gap: 16px; padding: 20px 24px; }
.form-row { display: flex; gap: 16px; }
.form-group { flex: 1; }
.form-group label { display: block; font-size: 14px; font-weight: 600; color: #555; margin-bottom: 6px; }
.form-group input, .form-group select, .form-group textarea { width: 100%; padding: 10px 14px; border: 2px solid #e8e8ec; border-radius: 10px; color: #333; background: #fff; font: inherit; font-size: 14px; outline: none; transition: all 0.2s ease; box-sizing: border-box; }
.form-group textarea { min-height: 88px; line-height: 1.5; resize: vertical; }
.form-group input:focus, .form-group select:focus, .form-group textarea:focus { border-color: #667eea; box-shadow: 0 0 0 3px rgba(102,126,234,0.1); }
.genre-badge-list { display: flex; flex-wrap: wrap; gap: 5px; }.genre-badge { display: inline-block; padding: 4px 9px; border-radius: 20px; color: #6a55b5; background: #eeeaff; font-size: 12px; font-weight: 700; white-space: nowrap; }
.genre-checkbox-grid { display: flex; flex-wrap: wrap; gap: 8px; padding: 11px; border: 2px solid #e8e8ec; border-radius: 10px; background: #fbfbfd; }.genre-checkbox { display: inline-flex !important; align-items: center; gap: 6px; margin: 0 !important; padding: 6px 10px; border: 1px solid #ded8ee; border-radius: 18px; color: #716785 !important; background: #fff; font-size: 12px !important; cursor: pointer; transition: .2s; }.genre-checkbox input { width: 14px !important; margin: 0; accent-color: #7657c5; }.genre-checkbox.selected { color: #6546ac !important; border-color: #9b82d3; background: #f0ebff; box-shadow: 0 3px 9px rgba(105,76,174,.12); }.dialog-genre-grid { max-height: 190px; overflow-y: auto; }
.lyric-upload-row { display: flex; align-items: center; gap: 10px; }.lyric-file-name { overflow: hidden; flex: 1; color: #888; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }.lyric-status { display: inline-block; padding: 4px 9px; border-radius: 20px; color: #999; background: #f1f2f4; font-size: 12px; font-weight: 600; }.lyric-status.ready { color: #378a68; background: #e6f8ee; }.btn-lyric { padding: 6px 14px; border: 1px solid #48a37a; border-radius: 8px; color: #378a68; background: #edf9f2; font-size: 12px; font-weight: 600; cursor: pointer; }.btn-lyric:hover { background: #ddf4e7; }.form-hint { display: block; margin-top: 8px; color: #999; font-size: 12px; line-height: 1.5; }
.btn-lyric-editor { padding: 6px 14px; border: 1px solid #7657c5; border-radius: 8px; color: #6d52b6; background: #f2efff; font-size: 12px; font-weight: 600; cursor: pointer; }.btn-lyric-editor:hover { background: #e8e2ff; }.lyric-editor-dialog { width: min(760px, calc(100vw - 40px)); max-height: calc(100vh - 40px); overflow: hidden; border-radius: 20px; background: #fff; box-shadow: 0 25px 60px rgba(0,0,0,.25); animation: scaleIn .2s ease; }.lyrics-editor-kicker { color: #846bd0; font-size: 10px; font-weight: 800; letter-spacing: 1.3px; }.lyric-editor-body { max-height: calc(100vh - 210px); overflow-y: auto; padding: 18px 24px; }.lyric-editor-description { margin: 0 0 14px; color: #777; font-size: 13px; line-height: 1.6; }.lyric-editor-player { width: 100%; margin-bottom: 8px; }.lyric-editor-clock { margin-bottom: 14px; padding: 9px 12px; border-radius: 10px; color: #756b89; background: #f5f2ff; font-size: 13px; }.lyric-editor-clock strong { margin-left: 8px; color: #6d51ba; font-variant-numeric: tabular-nums; }.lyric-editor-label { display: block; margin-bottom: 6px; color: #555; font-size: 14px; font-weight: 700; }.lyric-source-input { box-sizing: border-box; width: 100%; min-height: 116px; padding: 12px; border: 2px solid #e8e8ec; border-radius: 10px; color: #333; font: inherit; font-size: 14px; line-height: 1.6; outline: 0; resize: vertical; }.lyric-source-input:focus { border-color: #667eea; box-shadow: 0 0 0 3px rgba(102,126,234,.1); }.btn-prepare-lyrics { margin: 10px 0 16px; padding: 9px 14px; border: 0; border-radius: 9px; color: #fff; background: linear-gradient(135deg, #667eea, #764ba2); font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }.lyric-timeline { display: flex; flex-direction: column; overflow: auto; max-height: 280px; border: 1px solid #eeeaf5; border-radius: 11px; }.lyric-timeline-row { display: grid; grid-template-columns: 26px minmax(0,1fr) 104px; align-items: center; gap: 10px; padding: 10px 12px; border-bottom: 1px solid #f0edf6; }.lyric-timeline-row:last-child { border-bottom: 0; }.lyric-timeline-row.stamped { background: #f9f7ff; }.lyric-line-number { color: #aaa2b6; font-size: 12px; text-align: center; }.lyric-line-text { overflow: hidden; color: #4b435b; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }.timestamp-btn { padding: 7px 8px; border: 1px solid #d8d1ee; border-radius: 8px; color: #6d52b6; background: #fff; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.timestamp-btn:hover { color: #fff; border-color: #7657c5; background: #7657c5; }.lyric-editor-empty { margin: 5px 0 0; padding: 20px; border: 1px dashed #ddd6ef; border-radius: 10px; color: #9a93a8; font-size: 13px; text-align: center; }.lyric-editor-footer button:disabled { cursor: not-allowed; opacity: .5; }
.btn-upload { align-self: flex-start; padding: 10px 24px; border: none; background: linear-gradient(135deg, #43e97b, #38f9d7); color: #fff; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.btn-upload:hover { transform: translateY(-1px); box-shadow: 0 4px 15px rgba(67,233,123,0.35); }

.table-cover { width: 48px; height: 48px; border-radius: 8px; object-fit: cover; }
.table-cover-placeholder { width: 48px; height: 48px; border-radius: 8px; background: #f0f2f5; display: flex; align-items: center; justify-content: center; font-size: 20px; }
.cover-upload-wrap { display: flex; align-items: center; gap: 12px; }
.cover-preview { position: relative; display: inline-block; }
.cover-thumb { width: 80px; height: 80px; border-radius: 10px; object-fit: cover; border: 2px solid #e8e8ec; }
.cover-remove { position: absolute; top: -6px; right: -6px; width: 22px; height: 22px; border-radius: 50%; border: none; background: #e53e3e; color: #fff; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.btn-cover { padding: 10px 20px; border: 2px dashed #ccc; background: #fafafa; color: #888; border-radius: 10px; font-size: 14px; cursor: pointer; transition: all 0.2s ease; }
.btn-cover:hover { border-color: #667eea; color: #667eea; background: #f0f2ff; }
.upload-actions { display: flex; gap: 12px; padding: 0 24px 20px; }

.dialog-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); display: flex; justify-content: center; align-items: center; z-index: 1000; animation: fadeIn 0.2s ease; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
.dialog-card { background: #fff; border-radius: 20px; width: 420px; box-shadow: 0 25px 60px rgba(0,0,0,0.25); overflow: hidden; animation: scaleIn 0.2s ease; }
@keyframes scaleIn { from { transform: scale(0.9); opacity: 0; } to { transform: scale(1); opacity: 1; } }
.dialog-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px 0; }
.dialog-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #1a1a2e; }
.dialog-close { width: 32px; height: 32px; border: none; background: #f0f2f5; border-radius: 8px; font-size: 16px; cursor: pointer; display: flex; align-items: center; justify-content: center; color: #888; transition: all 0.2s ease; }
.dialog-close:hover { background: #e53e3e; color: #fff; }
.dialog-body { padding: 20px 24px; display: flex; flex-direction: column; gap: 16px; }
.dialog-form-group label { display: block; font-size: 14px; font-weight: 600; color: #555; margin-bottom: 6px; }
.dialog-form-group input, .dialog-form-group select, .dialog-form-group textarea { width: 100%; padding: 10px 14px; border: 2px solid #e8e8ec; border-radius: 10px; font: inherit; font-size: 14px; outline: none; transition: all 0.2s ease; box-sizing: border-box; }
.dialog-form-group textarea { min-height: 120px; line-height: 1.5; resize: vertical; }
.dialog-form-group input:focus, .dialog-form-group select:focus, .dialog-form-group textarea:focus { border-color: #667eea; box-shadow: 0 0 0 3px rgba(102,126,234,0.1); }
.metadata-dialog-card { width: min(560px, calc(100vw - 40px)); }
.dialog-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px 20px; }
.btn-cancel { padding: 10px 24px; border: 2px solid #e8e8ec; background: #fff; color: #666; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.btn-cancel:hover { background: #f0f2f5; }
.btn-confirm { padding: 10px 24px; border: none; background: linear-gradient(135deg, #667eea, #764ba2); color: #fff; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.btn-confirm:hover { transform: translateY(-1px); box-shadow: 0 4px 15px rgba(102,126,234,0.3); }
.empty-cell { text-align: center; color: #999; padding: 32px !important; }
.statistics-header h2 { font-size: 0; }.statistics-header h2::after { content: '播放统计'; font-size: 22px; }.statistics-header .header-badge { font-size: 0; }.statistics-header .header-badge::after { content: '📊'; font-size: 28px; }
.statistics-dashboard { min-height: calc(100vh - 48px); padding: 30px; border-radius: 24px; background: radial-gradient(circle at 85% 0%, rgba(28, 207, 255, .18), transparent 30%), radial-gradient(circle at 10% 100%, rgba(123, 87, 255, .22), transparent 32%), #070d1c; color: #edf7ff; overflow: hidden; position: relative; }
.statistics-dashboard::before { content: ''; position: absolute; inset: 0; pointer-events: none; opacity: .18; background-image: linear-gradient(rgba(72, 181, 255, .18) 1px, transparent 1px), linear-gradient(90deg, rgba(72, 181, 255, .18) 1px, transparent 1px); background-size: 34px 34px; }
.stats-toolbar, .stats-kpis, .chart-card { position: relative; z-index: 1; }
.stats-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.stats-eyebrow, .chart-heading p { margin: 0 0 7px; color: #48d8ff; font-size: 11px; letter-spacing: 2px; font-weight: 700; }
.stats-toolbar h2 { margin: 0; font-size: 30px; letter-spacing: 1px; color: #fff; }
.stats-subtitle { margin: 8px 0 0; color: #91a8bc; font-size: 14px; }
.month-picker { display: flex; align-items: center; gap: 12px; padding: 10px 14px; border: 1px solid rgba(72, 216, 255, .45); background: rgba(6, 24, 44, .78); box-shadow: 0 0 24px rgba(44, 173, 255, .12); border-radius: 10px; color: #a9d8ec; font-size: 13px; }
.month-picker input { background: transparent; border: 0; outline: none; color: #fff; font: inherit; color-scheme: dark; }
.stats-kpis { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 18px; }
.kpi-card { padding: 20px 22px; border: 1px solid rgba(104, 174, 255, .25); border-radius: 14px; background: linear-gradient(135deg, rgba(21, 50, 82, .9), rgba(8, 22, 44, .84)); box-shadow: inset 0 1px 0 rgba(255,255,255,.08), 0 12px 30px rgba(0,0,0,.18); }
.kpi-card span, .kpi-card small { display: block; color: #8ea8c1; font-size: 13px; }.kpi-card strong { display: block; margin: 8px 0 5px; color: #68ecff; font-size: 32px; text-shadow: 0 0 16px rgba(72,216,255,.35); }.kpi-card small { font-size: 10px; text-transform: uppercase; letter-spacing: 1.2px; color: #52718f; }
.chart-card { padding: 22px; margin-top: 18px; border: 1px solid rgba(104, 174, 255, .24); border-radius: 14px; background: rgba(8, 22, 44, .88); box-shadow: inset 0 1px 0 rgba(255,255,255,.06), 0 18px 36px rgba(0,0,0,.2); }
.chart-heading { display: flex; align-items: start; justify-content: space-between; margin-bottom: 20px; }.chart-heading h3 { margin: 0; font-size: 18px; color: #f3f8ff; }.chart-heading span { color: #78cce9; font: 12px monospace; padding: 5px 8px; border: 1px solid rgba(72,216,255,.25); border-radius: 4px; }
.bar-chart { display: flex; align-items: flex-end; min-height: 250px; border-bottom: 1px solid rgba(112, 174, 219, .28); background: repeating-linear-gradient(to top, transparent 0, transparent 61px, rgba(112,174,219,.1) 62px); }.bar-column { flex: 1; min-width: 0; height: 250px; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; gap: 7px; padding: 0 7px; }.bar-track { width: 100%; height: 196px; display: flex; align-items: flex-end; }.bar-fill { width: 100%; min-height: 0; border-radius: 5px 5px 1px 1px; transition: height .45s ease; }.song-bar { background: linear-gradient(to top, #1e72ff, #3ce6ff); box-shadow: 0 0 16px rgba(59, 224, 255, .48); }.daily-bar { background: linear-gradient(to top, #6f4cff, #e96cff); box-shadow: 0 0 14px rgba(189, 91, 255, .4); }.bar-value { min-height: 16px; color: #c7f6ff; font-size: 12px; font-family: monospace; }.bar-column strong, .bar-column small { max-width: 100%; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; text-align: center; }.bar-column strong { color: #dcecff; font-size: 12px; }.bar-column small { color: #7892a9; font-size: 11px; }.chart-empty { height: 250px; display: grid; place-items: center; border: 1px dashed rgba(87,188,255,.3); color: #7597b4; }
.daily-chart-wrap { display: flex; gap: 12px; }.daily-y-axis { height: 250px; display: flex; width: 28px; flex-direction: column; justify-content: space-between; padding-bottom: 25px; box-sizing: border-box; color: #60809d; font-size: 10px; }.daily-bar-chart { flex: 1; min-width: 0; }.daily-column { height: 250px; padding: 0 2px; gap: 4px; }.daily-column .bar-track { height: 210px; }.daily-column small { color: #6486a2; font-size: 9px; }.daily-column:nth-child(n+16) small { display: none; }
.evaluation-dashboard { min-height: calc(100vh - 110px); padding: 26px; border: 1px solid #e8e5f5; border-radius: 22px; color: #282340; background: radial-gradient(circle at 96% 0%, rgba(118,87,197,.14), transparent 28%), linear-gradient(145deg, #fff, #f8f7fd); box-shadow: 0 20px 50px rgba(66,50,112,.08); }
.evaluation-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 20px; padding: 25px 28px; border-radius: 18px; color: #fff; background: linear-gradient(125deg, #24204f, #553691 62%, #8155cc); box-shadow: 0 16px 34px rgba(69,43,128,.22); }
.evaluation-eyebrow, .evaluation-panel-head p { margin: 0 0 7px; color: #bcb2ff; font-size: 10px; font-weight: 800; letter-spacing: 2px; }.evaluation-hero h2 { margin: 0; font-size: 27px; }.evaluation-hero p:not(.evaluation-eyebrow) { margin: 8px 0 0; color: #d8d2ec; font-size: 13px; }
.evaluation-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-end; }.evaluation-actions label { display: flex; align-items: center; gap: 8px; color: #ddd7f2; font-size: 12px; }.evaluation-actions select { padding: 9px 28px 9px 10px; border: 1px solid rgba(255,255,255,.22); border-radius: 9px; color: #fff; background: rgba(20,15,55,.36); outline: 0; }.evaluation-actions select option { color: #222; }
.evaluation-run-btn, .evaluation-export-btn, .evaluation-add-btn { padding: 10px 16px; border-radius: 10px; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.evaluation-run-btn { border: 0; color: #5635a0; background: #fff; box-shadow: 0 6px 18px rgba(20,10,50,.2); }.evaluation-run-btn:disabled, .evaluation-export-btn:disabled, .evaluation-add-btn:disabled { cursor: not-allowed; opacity: .55; }.evaluation-export-btn { color: #fff; border: 1px solid rgba(255,255,255,.38); background: transparent; }.evaluation-add-btn { border: 1px solid rgba(255,255,255,.7); color: #fff; background: rgba(255,255,255,.14); }
.evaluation-state { padding: 50px; border: 1px dashed #d8d2ec; border-radius: 16px; color: #807793; text-align: center; }.evaluation-state.error { color: #c3455a; border-color: #efc5cd; background: #fff7f8; }
.evaluation-progress-card { margin-bottom: 18px; padding: 17px 20px; border: 1px solid #ebe7f5; border-radius: 14px; background: #fff; }.evaluation-progress-head { display: flex; justify-content: space-between; margin-bottom: 10px; color: #716984; font-size: 13px; }.evaluation-progress-head strong { color: #6947b6; font-variant-numeric: tabular-nums; }.evaluation-progress-track { overflow: hidden; height: 8px; border-radius: 99px; background: #efecf6; }.evaluation-progress-track div { height: 100%; border-radius: inherit; background: linear-gradient(90deg, #667eea, #9a59d4); box-shadow: 0 0 12px rgba(118,87,197,.4); transition: width .25s ease; }.evaluation-progress-card small { display: block; overflow: hidden; margin-top: 9px; color: #9a93a8; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.evaluation-dataset-panel { overflow: hidden; margin-bottom: 18px; border: 1px solid #e4ddf5; border-radius: 15px; background: #fff; box-shadow: 0 10px 30px rgba(66,50,112,.06); }.evaluation-dataset-table-wrap { overflow: auto; max-height: 360px; }.evaluation-dataset-table th { z-index: 2; }.evaluation-dataset-table td:first-child strong { color: #6f4ab9; }.evaluation-question-cell { overflow: hidden; max-width: 430px; text-overflow: ellipsis; white-space: nowrap; }.evaluation-category-chip { display: inline-block; padding: 3px 8px; border-radius: 99px; color: #7557b5; background: #f0ebfb; font-size: 10px; white-space: nowrap; }.evaluation-row-actions { display: flex; gap: 7px; }.evaluation-row-actions button { padding: 5px 10px; border: 1px solid #b8a6e4; border-radius: 7px; color: #6847ac; background: #fff; cursor: pointer; }.evaluation-row-actions button.danger { color: #c64f62; border-color: #e9a8b2; }
.evaluation-case-dialog { width: min(720px, calc(100vw - 36px)); max-height: calc(100vh - 40px); }.evaluation-case-dialog .dialog-body { overflow-y: auto; }.evaluation-dialog-kicker { display: block; margin-bottom: 5px; color: #8161c5; font-size: 9px; font-weight: 800; letter-spacing: 1.8px; }.evaluation-case-form { gap: 14px; }.evaluation-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }.evaluation-case-form label em { color: #d95168; font-style: normal; }.evaluation-negative-toggle { display: flex; align-items: flex-start; gap: 11px; padding: 12px 14px; border: 1px solid #ded5f2; border-radius: 11px; background: #faf8ff; cursor: pointer; }.evaluation-negative-toggle input { width: 17px; height: 17px; margin-top: 2px; accent-color: #7550c2; }.evaluation-negative-toggle span, .evaluation-negative-toggle small { display: block; }.evaluation-negative-toggle strong { color: #40345f; font-size: 13px; }.evaluation-negative-toggle small { margin-top: 3px; color: #8d849d; font-size: 11px; }.evaluation-song-select { min-height: 132px; }.evaluation-song-select option { padding: 7px; }
.evaluation-metrics { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 13px; margin-bottom: 18px; }.evaluation-metric { padding: 18px; border: 1px solid #e9e5f4; border-radius: 14px; background: #fff; box-shadow: 0 8px 22px rgba(66,50,112,.05); }.evaluation-metric span, .evaluation-metric small { display: block; color: #8d849d; font-size: 12px; }.evaluation-metric strong { display: block; margin: 8px 0 5px; color: #392d64; font-size: 27px; font-variant-numeric: tabular-nums; }.evaluation-metric small { font-size: 10px; }.evaluation-metric.primary { border-color: #8565cb; background: linear-gradient(135deg, #f5f1ff, #fff); }.evaluation-metric.primary strong { color: #754fc2; }
.evaluation-grid { display: grid; grid-template-columns: minmax(0,1.35fr) minmax(320px,.65fr); gap: 16px; }.evaluation-panel { overflow: hidden; border: 1px solid #e9e5f4; border-radius: 15px; background: #fff; }.evaluation-panel-head { display: flex; align-items: center; justify-content: space-between; padding: 17px 19px; border-bottom: 1px solid #efecf6; }.evaluation-panel-head p { color: #8c70ca; }.evaluation-panel-head h3 { margin: 0; font-size: 16px; }.evaluation-panel-head > span { padding: 4px 9px; border-radius: 99px; color: #7657bd; background: #f0ebfb; font-size: 11px; }
.evaluation-table-wrap { overflow: auto; max-height: 430px; }.evaluation-table { width: 100%; border-collapse: collapse; }.evaluation-table th, .evaluation-table td { padding: 11px 14px; border-bottom: 1px solid #f0edf6; font-size: 12px; text-align: left; }.evaluation-table th { position: sticky; top: 0; color: #8a8295; background: #faf9fc; }.evaluation-table td:not(:first-child) { font-variant-numeric: tabular-nums; }
.evaluation-empty { display: grid; min-height: 180px; place-items: center; color: #9e97aa; font-size: 13px; }.evaluation-failures { overflow-y: auto; max-height: 430px; padding: 12px; }.evaluation-failures article { margin-bottom: 10px; padding: 12px; border: 1px solid #f0dbe0; border-radius: 11px; background: #fff9fa; }.evaluation-failures article:last-child { margin-bottom: 0; }.evaluation-failures article div { display: flex; align-items: center; gap: 8px; }.evaluation-failures article strong { color: #c04b61; font-size: 12px; }.evaluation-failures article span { padding: 2px 6px; border-radius: 99px; color: #98717a; background: #f8e8eb; font-size: 9px; }.evaluation-failures p { margin: 7px 0; color: #544b5e; font-size: 12px; line-height: 1.45; }.evaluation-failures small { color: #9b8690; font-size: 10px; }
@media (max-width: 800px) { .statistics-dashboard { padding: 18px; }.stats-toolbar { align-items: flex-start; flex-direction: column; }.stats-kpis { grid-template-columns: 1fr; }.song-bar-chart { overflow-x: auto; }.song-bar-chart .bar-column { min-width: 84px; }.daily-chart-wrap { overflow-x: auto; }.daily-bar-chart { min-width: 600px; } }
@media (max-width: 1100px) { .evaluation-grid { grid-template-columns: 1fr; }.evaluation-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (max-width: 800px) { .evaluation-dashboard { padding: 15px; }.evaluation-hero { align-items: flex-start; flex-direction: column; }.evaluation-actions { justify-content: flex-start; }.evaluation-metrics { grid-template-columns: 1fr; }.evaluation-form-grid { grid-template-columns: 1fr; } }
</style>

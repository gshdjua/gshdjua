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
        <div class="nav-item" :class="{active: currentMenu === 'llmCost'}" @click="openLlmCostEvaluation">
          <span class="nav-icon">🪙</span>
          <span>LLM 成本评测</span>
        </div>
        <div class="nav-item" :class="{active: currentMenu === 'prompts'}" @click="openPromptVersions">
          <span class="nav-icon">📝</span>
          <span>Prompt 版本管理</span>
        </div>
        <div class="nav-item" :class="{active: currentMenu === 'models'}" @click="openModelCatalog">
          <span class="nav-icon">🧠</span>
          <span>模型目录</span>
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
          <div v-if="topSongs().length" class="bar-chart song-bar-chart">
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
          <div v-if="maxDailyPlays() > 0" class="daily-chart-wrap">
            <div class="daily-y-axis"><span>{{ maxDailyPlays() }}</span><span>{{ Math.ceil(maxDailyPlays() / 2) }}</span><span>0</span></div>
            <div class="bar-chart daily-bar-chart">
              <div v-for="item in monthDays()" :key="item.day" class="bar-column daily-column">
                <span v-if="item.playCount" class="bar-value">{{ item.playCount }}</span>
                <div class="bar-track"><div class="bar-fill daily-bar" :style="{ height: barHeight(item.playCount, maxDailyPlays()) }"></div></div>
                <small>{{ item.day }}</small>
              </div>
            </div>
          </div>
          <div v-else class="chart-empty">这个月还没有播放数据</div>
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

      <section v-if="currentMenu==='llmCost'" class="evaluation-dashboard">
        <div class="evaluation-hero llm-cost-hero">
          <div>
            <p class="evaluation-eyebrow">LLM COST LAB V2</p>
            <h2>策略与 LLM 成本评测</h2>
            <p>基于当前执行链检查本地绕过、Direct/ReAct 路由、工具调用、预算、Token、延迟和预估费用。</p>
          </div>
          <div class="evaluation-actions">
            <label class="llm-cost-mode"><input v-model="llmCostRealCall" type="checkbox" :disabled="llmCostRunning" /> 真实调用 {{ llmProviderDisplay }}</label>
            <button class="evaluation-run-btn" :disabled="llmCostRunning || !llmCostSelectedCases.length" @click="runLlmCostEvaluation">
              {{ llmCostRunning ? '评测运行中…' : '▶ 开始成本评测' }}
            </button>
            <button class="evaluation-export-btn" :disabled="llmCostRunning" @click="showLlmCostDataset = !showLlmCostDataset">
              {{ showLlmCostDataset ? '收起题库' : '管理题库' }}
            </button>
            <button class="evaluation-add-btn" :disabled="llmCostRunning" @click="openLlmCostCaseDialog()">＋ {{ llmCostAddButtonText }}</button>
            <button v-if="llmCostSummary" class="evaluation-export-btn" @click="downloadLlmCostReport">导出JSON</button>
          </div>
        </div>

        <div class="llm-cost-settings">
          <label>评测模型
            <select v-model="llmSelectedModelId" :disabled="llmCostRunning" @change="applySelectedModelPricing">
              <option v-for="model in llmModels" :key="model.id" :value="model.id">{{ model.displayName }}</option>
            </select>
          </label>
          <label>评测题组
            <select v-model="llmCostScope" :disabled="llmCostRunning" @change="resetLlmCostRun">
              <option value="production">生产链路（{{ llmCostProductionCount }}题）</option>
              <option value="agent_native">Agent 原生执行（{{ llmCostAgentNativeCount }}题）</option>
              <option value="all">全部题目（{{ llmCostCases.length }}题）</option>
            </select>
          </label>
          <label>价格方案
            <select v-model="llmCostPricePreset" @change="applyLlmCostPricePreset">
              <option value="flash-peak">DeepSeek V4 Flash 高峰（保守估算）</option>
              <option value="flash-offpeak">DeepSeek V4 Flash 闲时</option>
              <option value="pro-peak">DeepSeek V4 Pro 高峰</option>
              <option value="pro-offpeak">DeepSeek V4 Pro 闲时</option>
              <option value="custom">自定义</option>
            </select>
          </label>
          <label>输入价格（元/百万 Token）<input v-model.number="llmCostInputPrice" type="number" min="0" step="0.01" @input="llmCostPricePreset='custom'" /></label>
          <label>输出价格（元/百万 Token）<input v-model.number="llmCostOutputPrice" type="number" min="0" step="0.01" @input="llmCostPricePreset='custom'" /></label>
          <small>当前评测模型：{{ llmProviderDisplay }}。预设仅适用于对应的 DeepSeek 型号；使用其他 Provider 时请选择“自定义”并按其官方价格填写。模拟模式不会调用模型，真实模式会产生实际 API 消耗。</small>
        </div>

        <div v-if="llmCostLoading" class="evaluation-state">正在加载成本测试集…</div>
        <div v-else-if="llmCostError" class="evaluation-state error">{{ llmCostError }}</div>
        <template v-else>
          <div class="evaluation-progress-card">
            <div class="evaluation-progress-head">
              <span>{{ llmCostRunning ? '正在评测' : llmCostSummary ? '评测完成' : '等待开始' }}</span>
              <strong>{{ llmCostProgress }} / {{ llmCostSelectedCases.length }}</strong>
            </div>
            <div class="evaluation-progress-track"><div :style="{width: llmCostProgressPercent + '%'}"></div></div>
            <small v-if="llmCostCurrentCase">当前：{{ llmCostCurrentCase.id }} · {{ llmCostCurrentCase.question }}</small>
          </div>

          <div v-if="showLlmCostDataset" class="evaluation-dataset-panel">
            <div class="evaluation-panel-head"><div><p>COST DATASET</p><h3>{{ llmCostScopeLabel }}题库管理</h3></div><span>{{ llmCostSelectedCases.length }} 题</span></div>
            <div class="evaluation-dataset-table-wrap">
              <table class="evaluation-table evaluation-dataset-table">
                <thead><tr><th>ID</th><th>执行目标</th><th>类别</th><th>问题</th><th>期望意图</th><th>期望策略</th><th>期望工具</th><th>模型调用</th><th>操作</th></tr></thead>
                <tbody><tr v-for="item in llmCostSelectedCases" :key="item.id">
                  <td><strong>{{ item.id }}</strong></td><td>{{ item.execution_target === 'agent_native' ? 'Agent 原生' : '生产链路' }}</td><td>{{ item.category }}</td>
                  <td class="evaluation-question-cell" :title="item.question">{{ item.question }}</td>
                  <td>{{ item.expected_intent }}</td><td>{{ item.expected_strategy || 'AUTO' }}</td><td>{{ (item.expected_tools || []).join(', ') || '—' }}</td>
                  <td>{{ item.expected_model_call ? '是' : '否' }}</td>
                  <td><div class="evaluation-row-actions"><button @click="openLlmCostCaseDialog(item)">编辑</button><button class="danger" @click="deleteLlmCostCase(item)">删除</button></div></td>
                </tr><tr v-if="!llmCostSelectedCases.length"><td colspan="9" class="evaluation-state">当前题组暂无测试题，可点击右上角新增。</td></tr></tbody>
              </table>
            </div>
          </div>

          <div v-if="llmCostSummary" class="evaluation-metrics llm-cost-metrics">
            <div class="evaluation-metric"><span>模型调用次数</span><strong>{{ llmCostSummary.modelCalls }}</strong><small>其余问题由本地逻辑回答</small></div>
            <div class="evaluation-metric"><span>本地绕过率</span><strong>{{ formatPercent(llmCostSummary.localBypassRate) }}</strong><small>未消耗外部模型 Token</small></div>
            <div class="evaluation-metric"><span>输入 Token</span><strong>{{ llmCostSummary.inputTokens }}</strong><small>{{ llmCostRealCall ? 'API 实际值优先' : '提示词估算值' }}</small></div>
            <div class="evaluation-metric"><span>输出 Token</span><strong>{{ llmCostSummary.outputTokens }}</strong><small>{{ llmCostRealCall ? 'API 实际值优先' : '按测试题预算' }}</small></div>
            <div class="evaluation-metric"><span>平均 Token/题</span><strong>{{ formatDecimal(llmCostSummary.averageTokens) }}</strong><small>输入与输出合计</small></div>
            <div class="evaluation-metric"><span>平均 Token/模型调用</span><strong>{{ formatDecimal(llmCostSummary.averageTokensPerModelCall) }}</strong><small>排除本地零 Token 问题</small></div>
            <div class="evaluation-metric"><span>Direct / ReAct</span><strong>{{ llmCostSummary.directCases }} / {{ llmCostSummary.reactCases }}</strong><small>真实或模拟策略分布</small></div>
            <div class="evaluation-metric"><span>工具调用</span><strong>{{ llmCostSummary.toolCalls }}</strong><small>{{ llmCostSummary.toolRounds }} 个执行轮次</small></div>
            <div class="evaluation-metric"><span>P95 延迟</span><strong>{{ formatDecimal(llmCostSummary.p95LatencyMs) }}ms</strong><small>端到端执行时间</small></div>
            <div class="evaluation-metric"><span>预算停止</span><strong>{{ llmCostSummary.budgetExceededCases }}</strong><small>达到 Token、时间或调用上限</small></div>
            <div class="evaluation-metric"><span>规则通过率</span><strong>{{ formatPercent(llmCostSummary.passRate) }}</strong><small>{{ llmCostSummary.passedCases }}/{{ llmCostSummary.totalCases }} 题通过</small></div>
            <div class="evaluation-metric primary"><span>预估总费用</span><strong>{{ formatLlmCost(llmCostSummary.estimatedCost) }}</strong><small>{{ hasLlmTokenPrice() ? '根据上方价格计算' : '请先填写输入、输出价格' }}</small></div>
          </div>

          <div v-if="llmCostResults.length" class="evaluation-panel">
            <div class="evaluation-panel-head"><div><p>CASE DETAILS</p><h3>逐题成本明细</h3></div><span>{{ llmCostResults.length }} 条</span></div>
            <div class="evaluation-table-wrap llm-cost-result-table">
              <table class="evaluation-table">
                <thead><tr><th>ID</th><th>问题</th><th>意图</th><th>路径 / 策略</th><th>Prompt 版本</th><th>模型/工具/轮次</th><th>证据（实际/上限）</th><th>输入</th><th>输出</th><th>总计</th><th>耗时</th><th>费用</th></tr></thead>
                <tbody><tr v-for="item in llmCostResults" :key="item.id" :class="{'llm-cost-mismatch': !item.passed}">
                  <td><strong>{{ item.id }}</strong></td><td class="evaluation-question-cell" :title="item.question">{{ item.question }}</td>
                  <td>{{ item.intent }}</td>
                  <td :title="`${item.strategyReason || ''} · ${item.traceId || '无 traceId'}`">{{ item.executionPath }} / {{ item.selectedStrategy }}</td>
                  <td>{{ item.promptVersion || 'none' }}</td>
                  <td :title="toolExecutionTitle(item)">{{ item.modelCalls }}/{{ item.toolCalls }}/{{ item.toolRounds }} · {{ item.costBudget }}</td>
                  <td :title="`系统动态预算：${item.plannedEvidenceTopK}`">{{ item.evidenceCount }}/{{ item.expectedEvidenceLimit }}</td>
                  <td>{{ item.inputTokens }}</td><td>{{ item.outputTokens }}</td><td>{{ item.totalTokens }}</td>
                  <td>{{ item.elapsedMs }}ms</td><td>{{ formatLlmCost(item.estimatedCost) }}</td>
                </tr></tbody>
              </table>
            </div>
          </div>
        </template>
      </section>

      <section v-if="currentMenu === 'prompts'" class="evaluation-dashboard prompt-dashboard">
        <div class="evaluation-hero prompt-hero">
          <div>
            <p class="evaluation-eyebrow">PROMPT VERSION CONTROL</p>
            <h2>正式回答 Prompt</h2>
            <p>仅管理生产聊天链路的 music_answer；本地直答与 Agent 原生评测不受影响。</p>
          </div>
          <div class="evaluation-actions">
            <button type="button" class="evaluation-export-btn" :disabled="promptLoading" @click="loadPromptVersions">刷新版本</button>
            <button type="button" class="evaluation-add-btn" @click="startPromptDraft">+ 新建草稿</button>
          </div>
        </div>

        <p class="prompt-notice">发布后新请求立即使用新版本；停用当前版本会切回内置兜底规则。固定的隐私与本地证据安全规则不能在这里修改。</p>
        <div class="evaluation-panel prompt-rollout-panel">
          <div class="evaluation-panel-head"><div><p>STAGED RELEASE</p><h3>灰度发布</h3></div><span>{{ promptRollout.enabled ? '运行中' : '未开启' }}</span></div>
          <div class="prompt-rollout-body">
            <p>同一用户稳定分流；未命中候选版本的用户继续使用已发布基线。灰度期间不能停用或更换基线。</p>
            <template v-if="promptRollout.enabled">
              <p>基线 v{{ promptRollout.baselineVersion }} · 候选 v{{ promptRollout.candidateVersion }} · 候选流量 {{ promptRollout.trafficPercent }}%</p>
              <label>候选流量 <input v-model.number="promptTrafficPercent" type="number" min="1" max="99" />%</label>
              <button type="button" :disabled="promptBusy || promptTrafficPercent === Number(promptRollout.trafficPercent)" @click="updatePromptRollout">调整比例</button>
              <button type="button" :disabled="promptBusy" @click="stopPromptRollout">停止灰度并保留基线</button>
              <button type="button" class="prompt-promote-btn" :disabled="promptBusy || !promptCanDecide" :title="promptCanDecide ? '将候选设为正式版本' : '基线和候选都达到最小样本量后才能发布'" @click="promotePromptRollout">全量发布候选</button>
            </template>
            <template v-else>
              <select v-model.number="promptCandidateId" aria-label="灰度候选版本"><option :value="null">选择草稿或已停用版本</option><option v-for="item in promptVersions.filter(version => ['draft', 'inactive'].includes(version.status))" :key="item.id" :value="item.id">v{{ item.version }} · {{ promptStatusLabel(item.status) }}</option></select>
              <label>候选流量 <input v-model.number="promptTrafficPercent" type="number" min="1" max="99" />%</label>
              <button type="button" :disabled="promptBusy || !promptCandidateId" @click="startPromptRollout">开始灰度</button>
            </template>
          </div>
        </div>
        <div class="evaluation-panel prompt-metrics-panel">
          <div class="evaluation-panel-head">
            <div><p>ONLINE OBSERVABILITY</p><h3>线上灰度效果</h3></div>
            <div class="prompt-metrics-filters"><select v-model.number="promptMetricsDays" @change="loadPromptObservability"><option :value="1">最近 24 小时</option><option :value="7">最近 7 天</option><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option></select><button type="button" :disabled="promptMetricsLoading || promptFeedbackLoading" @click="loadPromptObservability">刷新</button><button type="button" class="danger" :disabled="promptMetricsLoading || !promptMetricRows.length" @click="deletePromptMetrics">清除当前范围</button></div>
          </div>
          <p class="prompt-metrics-privacy">{{ promptMetrics.privacy || '只统计版本号、调用状态、Token 与耗时，不保存用户、问题或回答。' }}</p>
          <p v-if="promptMetricsError" class="evaluation-state error">{{ promptMetricsError }}</p>
          <div v-if="promptMetricsLoading" class="evaluation-state">正在加载线上指标…</div>
          <div v-else-if="!promptMetricRows.length" class="evaluation-state">当前时间范围内暂无使用 Prompt 的真实聊天请求。请先产生实际聊天流量。</div>
          <div v-else class="evaluation-table-wrap"><table class="evaluation-table"><thead><tr><th>版本</th><th>Provider / 模型</th><th>角色</th><th>请求数</th><th>模型调用</th><th>输入 / 输出 Token</th><th>费用</th><th>平均 / P95 延迟</th><th>模型错误率</th><th>决策状态</th></tr></thead><tbody><tr v-for="row in promptMetricRows" :key="`${row.promptVersion}-${row.provider}-${row.model}`"><td>{{ row.promptVersion }}</td><td>{{ row.provider }} / {{ row.model }}</td><td>{{ promptMetricRole(row.promptVersion) }}</td><td>{{ row.requestCount }}</td><td>{{ row.modelCalls }}</td><td>{{ row.inputTokens }} / {{ row.outputTokens }}</td><td>{{ formatLlmCost(row.estimatedCost) }}</td><td>{{ formatDecimal(row.averageLatencyMs) }} / {{ row.p95LatencyMs }}ms</td><td>{{ formatPercent(row.errorRate) }}</td><td><span class="prompt-sample-status" :class="{'ready': row.sampleSufficient}">{{ row.sampleSufficient ? '样本充足' : `数据不足（至少 ${promptMetrics.minimumSampleSize || 30} 条）` }}</span></td></tr></tbody></table></div>
          <p class="prompt-decision-note">系统只提供客观运行指标，不自动判断回答质量。全量发布前还应结合固定题评测和人工阅读；两个版本都达到最小样本量后才开放发布按钮。</p>
        </div>
        <div class="evaluation-panel prompt-metrics-panel">
          <div class="evaluation-panel-head"><div><p>QUALITY FEEDBACK</p><h3>低打扰反馈质量</h3></div><span>{{ promptFeedbackRows.reduce((sum, row) => sum + Number(row.feedbackCount || 0), 0) }} 条反馈</span></div>
          <p class="prompt-metrics-privacy">{{ promptFeedbackMetrics.privacy || '只统计消息对应的 Prompt 版本、评价和预设原因，不复制问题或回答正文。' }}</p>
          <p v-if="promptFeedbackError" class="evaluation-state error">{{ promptFeedbackError }}</p>
          <div v-if="promptFeedbackLoading" class="evaluation-state">正在加载用户反馈…</div>
          <div v-else-if="!promptFeedbackRows.length" class="evaluation-state">当前时间范围内暂无自愿反馈。反馈按钮不会强制用户操作。</div>
          <div v-else class="evaluation-table-wrap"><table class="evaluation-table"><thead><tr><th>版本</th><th>角色</th><th>反馈数</th><th>有帮助</th><th>没帮助</th><th>好评率</th><th>主要差评原因</th><th>参考状态</th></tr></thead><tbody><tr v-for="row in promptFeedbackRows" :key="row.promptVersion"><td>{{ row.promptVersion }}</td><td>{{ promptMetricRole(row.promptVersion) }}</td><td>{{ row.feedbackCount }}</td><td>{{ row.helpfulCount }}</td><td>{{ row.notHelpfulCount }}</td><td>{{ formatPercent(row.helpfulRate) }}</td><td>{{ promptFeedbackReasonSummary(row) }}</td><td><span class="prompt-sample-status" :class="{'ready': row.sampleSufficient}">{{ row.sampleSufficient ? '可供参考' : `反馈较少（建议至少 ${promptFeedbackMetrics.minimumSampleSize || 5} 条）` }}</span></td></tr></tbody></table></div>
          <p class="prompt-decision-note">反馈为自愿样本，可能存在选择偏差，只作为固定题评测、人工审阅、错误率、延迟和成本之外的补充证据，不会自动改变灰度比例或发布版本。</p>
        </div>
        <div class="evaluation-panel prompt-compare-panel">
          <div class="evaluation-panel-head"><div><p>PROMPT COMPARISON</p><h3>同题版本对比</h3></div><span>可比较 {{ promptComparableCases.length }} 题</span></div>
          <div class="prompt-rollout-body">
            <p>用相同生产链路题对比结构规则、Token、费用和延迟；规则通过率不等于回答准确率。模拟模式不调用模型，仅估算成本；真实模式才可人工阅读回答。</p>
            <select v-model.number="promptCompareBase" aria-label="基线 Prompt 版本" @change="promptCompareResults=[]"><option :value="null">选择基线版本</option><option v-for="item in promptVersions" :key="item.id" :value="item.version">v{{ item.version }}</option></select>
            <select v-model.number="promptCompareCandidate" aria-label="候选 Prompt 版本" @change="promptCompareResults=[]"><option :value="null">选择候选版本</option><option v-for="item in promptVersions" :key="item.id" :value="item.version">v{{ item.version }}</option></select>
            <select v-model="llmSelectedModelId" aria-label="固定评测模型" @change="promptCompareResults=[]; applySelectedModelPricing()"><option v-for="model in llmModels" :key="model.id" :value="model.id">固定模型：{{ model.displayName }}</option></select>
            <label><input v-model="promptCompareRealCall" type="checkbox" :disabled="promptCompareRunning" /> 真实调用 {{ llmProviderDisplay }}（产生费用）</label>
            <button type="button" :disabled="promptCompareRunning || !promptCompareBase || !promptCompareCandidate || promptCompareBase === promptCompareCandidate" @click="comparePromptVersions">{{ promptCompareRunning ? '对比中…' : '开始对比' }}</button>
            <span v-if="promptCompareRunning">{{ promptCompareProgress }} / {{ promptComparableCases.length * 2 }}</span>
          </div>
          <p v-if="promptCompareError" class="evaluation-state error">{{ promptCompareError }}</p>
          <div v-if="promptCompareResults.length" class="evaluation-table-wrap"><table class="evaluation-table"><thead><tr><th>版本</th><th>结构规则</th><th>输入 / 输出 Token</th><th>费用</th><th>平均延迟</th></tr></thead><tbody><tr v-for="group in promptCompareSummary" :key="group.version"><td>v{{ group.version }}</td><td>{{ group.passed }}/{{ group.count }}</td><td>{{ group.inputTokens }} / {{ group.outputTokens }}</td><td>{{ formatLlmCost(group.cost) }}</td><td>{{ formatDecimal(group.averageLatency) }}ms</td></tr></tbody></table></div>
          <div v-if="promptCompareRealCall && promptCompareResults.length" class="prompt-answer-review"><details v-for="row in promptCompareResults" :key="`${row.caseId}-${row.version}`"><summary>{{ row.caseId }} · v{{ row.version }} · {{ row.passed ? '规则通过' : '规则未通过' }}</summary><p>问题：{{ row.question }}</p><p>回答：{{ row.answerPreview || '无模型回答' }}</p></details></div>
        </div>
        <p v-if="promptError" class="evaluation-state error">{{ promptError }}</p>
        <p v-if="promptSuccess" class="prompt-success">{{ promptSuccess }}</p>
        <div v-if="promptLoading" class="evaluation-state">正在加载 Prompt 版本…</div>
        <div v-else class="prompt-layout">
          <div class="evaluation-panel prompt-list-panel">
            <div class="evaluation-panel-head"><div><p>VERSION HISTORY</p><h3>版本记录</h3></div><span>{{ promptVersions.length }} 个版本</span></div>
            <div class="prompt-version-list">
              <p v-if="!promptVersions.length" class="evaluation-state">暂无版本，可在右侧创建草稿。</p>
              <article v-for="item in promptVersions" :key="item.id" class="prompt-version-item" :class="{ 'prompt-version-current': item.status === 'published' }">
                <div class="prompt-version-heading">
                  <div><strong>{{ item.name }} · v{{ item.version }}</strong><span class="prompt-status" :class="`prompt-status-${item.status}`">{{ promptStatusLabel(item.status) }}</span></div>
                  <small>{{ item.publishedAt ? `发布时间：${formatPromptDate(item.publishedAt)}` : `创建时间：${formatPromptDate(item.createdAt)}` }}</small>
                </div>
                <p class="prompt-version-preview">{{ item.templateText }}</p>
                <div class="prompt-version-actions">
                  <button type="button" @click="viewPromptVersion(item)">查看全文</button>
                  <button v-if="item.status === 'draft'" type="button" :disabled="promptBusy" @click="editPromptDraft(item)">编辑草稿</button>
                  <button v-if="item.status === 'draft'" type="button" :disabled="promptBusy" @click="publishPrompt(item)">发布</button>
                  <button v-if="item.status === 'published'" type="button" class="danger" :disabled="promptBusy" @click="disablePrompt(item)">停用</button>
                  <button v-if="item.status === 'inactive'" type="button" :disabled="promptBusy" @click="restorePrompt(item)">{{ canRollbackPrompt(item) ? '回滚到此版本' : '重新发布' }}</button>
                  <button type="button" class="danger" :disabled="promptBusy || ['published', 'gray'].includes(item.status)" :title="['published', 'gray'].includes(item.status) ? '先停用或停止灰度才能删除' : '删除后不可恢复模板正文'" @click="deletePromptVersion(item)">删除此版本</button>
                </div>
              </article>
            </div>
          </div>

          <div class="evaluation-panel prompt-editor-panel">
            <div class="evaluation-panel-head"><div><p>{{ viewingPromptId ? 'VERSION VIEWER' : 'DRAFT EDITOR' }}</p><h3>{{ editingPromptId ? `编辑草稿 v${editingPromptVersion}` : (viewingPromptId ? `查看版本 v${viewingPromptVersion}` : '新建草稿') }}</h3></div></div>
            <div class="prompt-editor-body">
              <label for="prompt-template-editor">回答模板</label>
              <textarea id="prompt-template-editor" v-model="promptTemplate" :readonly="!!viewingPromptId" maxlength="12000" placeholder="输入正式回答规则；建议保留回答格式、推荐说明和不确定时的处理方式。"></textarea>
              <small>{{ viewingPromptId ? '只读版本；如需调整，请基于此版本创建草稿。' : `${promptTemplate.length}/12000 字符。已发布版本不可直接修改，请新建草稿。` }}</small>
              <div class="prompt-editor-actions">
                <button v-if="viewingPromptId" type="button" class="btn-confirm" @click="startPromptDraftFromViewed">基于此版本新建草稿</button>
                <template v-else>
                  <button type="button" class="btn-cancel" @click="clearPromptDraft">清空</button>
                  <button type="button" class="btn-confirm" :disabled="promptBusy || promptTemplate.trim().length < 20" @click="savePromptDraft">{{ promptBusy ? '保存中…' : (editingPromptId ? '保存草稿' : '创建草稿') }}</button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="currentMenu === 'models'" class="evaluation-dashboard model-catalog-dashboard">
        <div class="evaluation-hero model-catalog-hero">
          <div>
            <p class="evaluation-eyebrow">UNIFIED MODEL CATALOG</p>
            <h2>统一模型目录</h2>
            <p>集中维护聊天、Prompt 对比与成本评测可使用的 Provider、模型标识和计费单价。</p>
          </div>
          <div class="evaluation-actions">
            <button type="button" class="evaluation-export-btn" :disabled="llmModelSaving" @click="openModelCatalog">刷新目录</button>
          </div>
        </div>

        <p v-if="llmModelError" class="evaluation-state error">{{ llmModelError }}</p>
        <div class="evaluation-panel llm-model-catalog">
          <div class="evaluation-panel-head"><div><p>MODEL CATALOG</p><h3>可用模型</h3></div><span>{{ llmAdminModels.length }} 个模型</span></div>
          <div class="llm-model-form">
            <label><span>Provider</span><input v-model.trim="llmModelForm.provider" placeholder="例如 deepseek" /></label>
            <label><span>API 模型名</span><input v-model.trim="llmModelForm.model" placeholder="例如 deepseek-chat" /></label>
            <label><span>用户看到的名称</span><input v-model.trim="llmModelForm.displayName" placeholder="例如 DeepSeek Chat" /></label>
            <label><span>输入价格（元/百万 Token）</span><input v-model.number="llmModelForm.inputPricePerMillion" type="number" min="0" step="0.01" placeholder="0" /></label>
            <label><span>输出价格（元/百万 Token）</span><input v-model.number="llmModelForm.outputPricePerMillion" type="number" min="0" step="0.01" placeholder="0" /></label>
            <label class="llm-model-toggle">
              <input v-model="llmModelForm.userSelectable" type="checkbox" />
              <span><strong>用户可选</strong><small>显示在普通用户聊天页面的模型列表中</small></span>
            </label>
            <label class="llm-model-toggle">
              <input v-model="llmModelForm.default" type="checkbox" />
              <span><strong>默认模型</strong><small>新建会话自动使用；系统只能有一个默认模型</small></span>
            </label>
            <button type="button" class="llm-model-submit" :disabled="llmModelSaving" @click="saveLlmModel">{{ llmModelSaving ? '保存中…' : '添加模型' }}</button>
          </div>
          <div class="evaluation-table-wrap"><table class="evaluation-table"><thead><tr><th>显示名称</th><th>Provider</th><th>API 模型</th><th>价格（输入/输出）</th><th>用户可选</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="model in llmAdminModels" :key="model.id"><td>{{ model.displayName }}</td><td>{{ model.provider }}</td><td>{{ model.model }}</td><td>{{ model.inputPricePerMillion }} / {{ model.outputPricePerMillion }}</td><td>{{ model.userSelectable ? '是' : '否' }}</td><td>{{ model.default ? '默认' : model.enabled ? '已启用' : '已停用' }}</td><td><button v-if="model.enabled && !model.default" type="button" class="danger" @click="disableLlmModel(model)">停用</button></td></tr></tbody></table></div>
          <p class="prompt-decision-note">目录只保存模型标识、开关和价格，不保存 API Key。新增其他 Provider 的模型前，必须先在 Agent Service 注册对应 LlmProvider Adapter。</p>
        </div>
      </section>

      <div class="dialog-overlay" v-if="showLlmCostCaseDialog" @click.self="closeLlmCostCaseDialog">
        <div class="dialog-card evaluation-case-dialog">
          <div class="dialog-header"><div><span class="evaluation-dialog-kicker">LLM COST CASE</span><h3>{{ editingLlmCostCaseId ? `编辑成本题 ${editingLlmCostCaseId}` : '新增成本测试题' }}</h3></div><button class="dialog-close" @click="closeLlmCostCaseDialog">✕</button></div>
          <div class="dialog-body evaluation-case-form">
            <div class="evaluation-form-grid">
              <div class="dialog-form-group"><label>类别</label><input v-model.trim="llmCostCaseForm.category" maxlength="50" /></div>
              <div class="dialog-form-group"><label>期望意图</label><select v-model="llmCostCaseForm.expectedIntent"><option v-for="intent in llmCostIntents" :key="intent" :value="intent">{{ intent }}</option></select></div>
              <div class="dialog-form-group"><label>期望策略</label><select v-model="llmCostCaseForm.expectedStrategy"><option v-for="strategy in llmCostStrategies" :key="strategy" :value="strategy">{{ strategy }}</option></select></div>
              <div class="dialog-form-group"><label>执行目标</label><select v-model="llmCostCaseForm.executionTarget" @change="handleLlmCostExecutionTargetChange"><option value="production">生产链路</option><option value="agent_native">Agent 原生</option></select></div>
              <div class="dialog-form-group"><label>成本预算</label><select v-model="llmCostCaseForm.costBudget"><option value="low">low</option><option value="standard">standard</option><option value="high">high</option></select></div>
            </div>
            <div class="dialog-form-group"><label>用户问题 <em>*</em></label><textarea v-model.trim="llmCostCaseForm.question" maxlength="500"></textarea></div>
            <div class="dialog-form-group"><label>期望工具（逗号分隔）</label><input v-model.trim="llmCostCaseForm.expectedTools" placeholder="例如：favorite_search,recommend_songs" /><small class="form-hint">Agent 原生真实评测会检查这些只读工具是否实际执行。</small></div>
            <div class="evaluation-form-grid">
              <div class="dialog-form-group"><label>证据上限</label><input v-model.number="llmCostCaseForm.expectedEvidenceCount" type="number" min="0" max="12" /><small class="form-hint">实际证据可少于此数量，不会为了凑数加入低质量候选。</small></div>
              <div class="dialog-form-group"><label>预期输出 Token</label><input v-model.number="llmCostCaseForm.expectedOutputTokens" type="number" min="1" max="4000" /></div>
            </div>
            <label class="evaluation-negative-toggle"><input v-model="llmCostCaseForm.expectedModelCall" type="checkbox" /><span><strong>预期调用外部模型</strong><small>关闭表示该问题应该完全由本地逻辑回答。</small></span></label>
          </div>
          <div class="dialog-footer"><button class="btn-cancel" @click="closeLlmCostCaseDialog">取消</button><button class="btn-confirm" :disabled="llmCostCaseSaving || !llmCostCaseForm.question" @click="saveLlmCostCase">{{ llmCostCaseSaving ? '保存中…' : '保存测试题' }}</button></div>
        </div>
      </div>

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
      },
      llmCostCases: [], llmCostResults: [], llmCostSummary: null,
      llmCostProgress: 0, llmCostCurrentCase: null, llmCostRunning: false,
      llmCostLoading: false, llmCostError: '', llmCostRealCall: false,
      llmProviderName: 'LLM', llmModelName: '', llmModels: [], llmSelectedModelId: '',
      llmAdminModels: [], llmModelSaving: false, llmModelError: '',
      llmModelForm: { provider: '', model: '', displayName: '', inputPricePerMillion: 0, outputPricePerMillion: 0, userSelectable: true, default: false },
      llmCostPricePreset: 'flash-peak', llmCostInputPrice: 3, llmCostOutputPrice: 9, showLlmCostDataset: false,
      llmCostScope: 'production',
      showLlmCostCaseDialog: false, editingLlmCostCaseId: null, llmCostCaseSaving: false,
      llmCostIntents: ['AUTO', 'AGENT_NATIVE', 'SONG_METADATA', 'GENERAL', 'RECOMMENDATION', 'SOURCE_QUERY', 'GENRE_QUERY', 'LIBRARY_QUERY', 'FAVORITES'],
      llmCostStrategies: ['AUTO', 'local', 'direct', 'react'],
      llmCostCaseForm: { category: '', question: '', expectedIntent: 'AUTO', expectedStrategy: 'AUTO', executionTarget: 'production', costBudget: 'standard', expectedTools: '', expectedEvidenceCount: 3, expectedOutputTokens: 300, expectedModelCall: true },
      promptVersions: [], promptLoading: false, promptBusy: false, promptError: '', promptSuccess: '',
      editingPromptId: null, editingPromptVersion: null, viewingPromptId: null, viewingPromptVersion: null, promptTemplate: '',
      promptRollout: { enabled: false, trafficPercent: 0 }, promptCandidateId: null, promptTrafficPercent: 10,
      promptMetricsDays: 7, promptMetrics: { versions: [], minimumSampleSize: 30 },
      promptMetricsLoading: false, promptMetricsError: '',
      promptFeedbackMetrics: { versions: [], minimumSampleSize: 5, reasonLabels: {} },
      promptFeedbackLoading: false, promptFeedbackError: '',
      promptCompareBase: null, promptCompareCandidate: null, promptCompareRealCall: false,
      promptCompareRunning: false, promptCompareProgress: 0, promptCompareError: '', promptCompareResults: []
    }
  },
  computed: {
    llmProviderDisplay() {
      const selected = this.llmModels.find(item => item.id === this.llmSelectedModelId)
      return selected ? selected.displayName : (this.llmModelName ? `${this.llmProviderName} / ${this.llmModelName}` : this.llmProviderName)
    },
    menuTitle() {
      return { user: '用户管理', audio: '音频管理', statistics: '播放统计', evaluation: '检索评测', llmCost: 'LLM 调用成本评测', prompts: 'Prompt 版本管理', models: '统一模型目录' }[this.currentMenu] || '管理后台'
    },
    menuIcon() {
      return { user: '👥', audio: '🎵', statistics: '📊', evaluation: '🧪', llmCost: '🪙', prompts: '📝', models: '🧠' }[this.currentMenu] || '🎵'
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
    },
    llmCostProgressPercent() {
      return this.llmCostSelectedCases.length ? Math.round(this.llmCostProgress / this.llmCostSelectedCases.length * 100) : 0
    },
    llmCostSelectedCases() {
      if (this.llmCostScope === 'all') return this.llmCostCases
      return this.llmCostCases.filter(item => (item.execution_target || 'production') === this.llmCostScope)
    },
    llmCostProductionCount() {
      return this.llmCostCases.filter(item => (item.execution_target || 'production') === 'production').length
    },
    llmCostAgentNativeCount() {
      return this.llmCostCases.filter(item => item.execution_target === 'agent_native').length
    },
    llmCostScopeLabel() {
      if (this.llmCostScope === 'agent_native') return 'Agent 原生执行'
      if (this.llmCostScope === 'production') return '生产链路'
      return '全部成本测试'
    },
    llmCostAddButtonText() {
      if (this.llmCostScope === 'agent_native') return '新增 Agent 原生题'
      if (this.llmCostScope === 'production') return '新增生产链路题'
      return '新增成本题'
    },
    promptCompareSummary() {
      return [this.promptCompareBase, this.promptCompareCandidate].map(version => {
        const rows = this.promptCompareResults.filter(row => row.version === version)
        return { version, count: rows.length, passed: rows.filter(row => row.passed).length,
          inputTokens: rows.reduce((sum, row) => sum + Number(row.inputTokens || 0), 0),
          outputTokens: rows.reduce((sum, row) => sum + Number(row.outputTokens || 0), 0),
          cost: rows.reduce((sum, row) => sum + Number(row.estimatedCost || 0), 0),
          averageLatency: rows.length ? rows.reduce((sum, row) => sum + Number(row.elapsedMs || 0), 0) / rows.length : 0 }
      })
    },
    promptComparableCases() {
      return this.llmCostCases.filter(item => (item.execution_target || 'production') === 'production' && item.expected_model_call)
    },
    promptMetricRows() {
      return Array.isArray(this.promptMetrics.versions) ? this.promptMetrics.versions : []
    },
    promptFeedbackRows() {
      return Array.isArray(this.promptFeedbackMetrics.versions) ? this.promptFeedbackMetrics.versions : []
    },
    promptCanDecide() {
      if (!this.promptRollout.enabled) return false
      const baseline = `music_answer:v${this.promptRollout.baselineVersion}`
      const candidate = `music_answer:v${this.promptRollout.candidateVersion}`
      return [baseline, candidate].every(version => this.promptMetricRows.some(row => row.promptVersion === version && row.sampleSufficient))
    }
  },
  mounted() {
    this.loadUsers();
    this.loadFullAudioList();
  },
  methods: {
    async openPromptVersions() {
      this.currentMenu = 'prompts'
      await Promise.all([this.loadPromptVersions(), this.loadPromptRollout(), this.loadPromptMetrics(), this.loadPromptFeedbackMetrics(), this.loadLlmCostCases(), this.loadLlmProviderStatus()])
    },
    async openModelCatalog() {
      this.currentMenu = 'models'
      await Promise.all([this.loadAdminLlmModels(), this.loadLlmProviderStatus()])
    },
    async loadLlmProviderStatus() {
      try {
        const [status, models] = await Promise.all([request.get('/assistant/status'), request.get('/assistant/models')])
        if (status.data.code === 200) {
          this.llmProviderName = status.data.data?.provider || 'LLM'
          this.llmModelName = status.data.data?.model || ''
        }
        this.llmModels = models.data.code === 200 ? (models.data.data || []) : []
        if (!this.llmModels.some(item => item.id === this.llmSelectedModelId)) {
          this.llmSelectedModelId = this.llmModels.find(item => item.default)?.id || this.llmModels[0]?.id || ''
        }
        this.applySelectedModelPricing()
      } catch (error) {
        this.llmProviderName = 'LLM'
        this.llmModelName = ''
      }
    },
    applySelectedModelPricing() {
      const model = this.llmModels.find(item => item.id === this.llmSelectedModelId)
      if (!model) return
      this.llmProviderName = model.provider
      this.llmModelName = model.model
      this.llmCostInputPrice = Number(model.inputPricePerMillion || 0)
      this.llmCostOutputPrice = Number(model.outputPricePerMillion || 0)
      this.llmCostPricePreset = model.provider === 'deepseek' ? this.llmCostPricePreset : 'custom'
    },
    async loadPromptRollout() {
      try {
        const res = await request.get('/admin/prompts/rollout')
        if (res.data.code !== 200) throw new Error(res.data.msg || '加载灰度状态失败')
        this.promptRollout = res.data.data || { enabled: false, trafficPercent: 0 }
        if (this.promptRollout.enabled) this.promptTrafficPercent = Number(this.promptRollout.trafficPercent)
      } catch (error) { this.promptError = error.response?.data?.msg || error.message || '加载灰度状态失败' }
    },
    async loadPromptMetrics() {
      this.promptMetricsLoading = true; this.promptMetricsError = ''
      try {
        const res = await request.get('/admin/prompts/metrics', { params: { days: this.promptMetricsDays } })
        if (res.data.code !== 200) throw new Error(res.data.msg || '加载线上指标失败')
        this.promptMetrics = res.data.data || { versions: [], minimumSampleSize: 30 }
      } catch (error) { this.promptMetricsError = error.response?.data?.msg || error.message || '加载线上指标失败' }
      finally { this.promptMetricsLoading = false }
    },
    async deletePromptMetrics() {
      if (this.promptMetricsLoading || !confirm(`确定清除最近 ${this.promptMetricsDays} 天的 Prompt 运行统计吗？此操作不会删除聊天记录、反馈、Prompt 版本或模型目录，且无法恢复。`)) return
      this.promptMetricsLoading = true; this.promptMetricsError = ''; this.promptSuccess = ''
      try {
        const res = await request.delete('/admin/prompts/metrics', { params: { days: this.promptMetricsDays } })
        if (res.data.code !== 200) throw new Error(res.data.msg || '清除线上指标失败')
        const deletedCount = Number(res.data.data?.deletedCount || 0)
        this.promptSuccess = `已清除最近 ${this.promptMetricsDays} 天的 ${deletedCount} 条 Prompt 运行统计。`
        await this.loadPromptObservability()
      } catch (error) {
        this.promptMetricsError = error.response?.data?.msg || error.message || '清除线上指标失败'
      } finally { this.promptMetricsLoading = false }
    },
    async loadPromptFeedbackMetrics() {
      this.promptFeedbackLoading = true; this.promptFeedbackError = ''
      try {
        const res = await request.get('/admin/prompts/feedback-metrics', { params: { days: this.promptMetricsDays } })
        if (res.data.code !== 200) throw new Error(res.data.msg || '加载反馈质量失败')
        this.promptFeedbackMetrics = res.data.data || { versions: [], minimumSampleSize: 5, reasonLabels: {} }
      } catch (error) { this.promptFeedbackError = error.response?.data?.msg || error.message || '加载反馈质量失败' }
      finally { this.promptFeedbackLoading = false }
    },
    loadPromptObservability() {
      return Promise.all([this.loadPromptMetrics(), this.loadPromptFeedbackMetrics()])
    },
    promptFeedbackReasonSummary(row) {
      const labels = this.promptFeedbackMetrics.reasonLabels || {}
      const counts = row.reasonCounts || {}
      const parts = Object.keys(counts).filter(key => Number(counts[key]) > 0)
        .sort((left, right) => Number(counts[right]) - Number(counts[left]))
        .map(key => `${labels[key] || key} ${counts[key]}`)
      return parts.length ? parts.join('、') : '—'
    },
    promptMetricRole(version) {
      if (version === `music_answer:v${this.promptRollout.baselineVersion}`) return '基线'
      if (this.promptRollout.enabled && version === `music_answer:v${this.promptRollout.candidateVersion}`) return '候选'
      return '历史版本'
    },
    async startPromptRollout() {
      if (this.promptBusy || !this.promptCandidateId) return
      if (!Number.isInteger(this.promptTrafficPercent) || this.promptTrafficPercent < 1 || this.promptTrafficPercent > 99) { this.promptError = '灰度比例须在 1% 到 99% 之间'; return }
      if (!confirm(`将 v${this.promptVersions.find(item => item.id === this.promptCandidateId)?.version} 分配给 ${this.promptTrafficPercent}% 用户？`)) return
      this.promptBusy = true; this.promptError = ''
      try {
        const res = await request.post('/admin/prompts/rollout', { candidateId: this.promptCandidateId, trafficPercent: this.promptTrafficPercent })
        if (res.data.code !== 200) throw new Error(res.data.msg || '开启灰度失败')
        await Promise.all([this.loadPromptRollout(), this.loadPromptVersions()])
        this.promptSuccess = '灰度已开启；同一用户稳定命中相同版本。'
      } catch (error) { this.promptError = error.response?.data?.msg || error.message || '开启灰度失败' }
      finally { this.promptBusy = false }
    },
    async stopPromptRollout() {
      if (this.promptBusy || !confirm('停止灰度？候选版本将停用，所有用户回到已发布基线。')) return
      this.promptBusy = true; this.promptError = ''
      try {
        const res = await request.delete('/admin/prompts/rollout')
        if (res.data.code !== 200) throw new Error(res.data.msg || '停止灰度失败')
        await Promise.all([this.loadPromptRollout(), this.loadPromptVersions()])
        this.promptSuccess = '灰度已停止，用户回到基线版本。'
      } catch (error) { this.promptError = error.response?.data?.msg || error.message || '停止灰度失败' }
      finally { this.promptBusy = false }
    },
    async updatePromptRollout() {
      if (this.promptBusy || !Number.isInteger(this.promptTrafficPercent) || this.promptTrafficPercent < 1 || this.promptTrafficPercent > 99) { this.promptError = '灰度比例须在 1% 到 99% 之间'; return }
      this.promptBusy = true; this.promptError = ''; this.promptSuccess = ''
      try {
        const res = await request.put('/admin/prompts/rollout', { trafficPercent: this.promptTrafficPercent })
        if (res.data.code !== 200) throw new Error(res.data.msg || '调整灰度比例失败')
        await this.loadPromptRollout()
        this.promptSuccess = `候选流量已调整为 ${this.promptTrafficPercent}%。`
      } catch (error) { this.promptError = error.response?.data?.msg || error.message || '调整灰度比例失败' }
      finally { this.promptBusy = false }
    },
    async promotePromptRollout() {
      if (this.promptBusy || !this.promptCanDecide || !confirm(`将候选 v${this.promptRollout.candidateVersion} 全量发布？原基线会保留为已停用版本，可在之后回滚。`)) return
      this.promptBusy = true; this.promptError = ''; this.promptSuccess = ''
      try {
        const res = await request.post('/admin/prompts/rollout/promote')
        if (res.data.code !== 200) throw new Error(res.data.msg || '全量发布失败')
        await Promise.all([this.loadPromptRollout(), this.loadPromptVersions(), this.loadPromptMetrics()])
        this.promptSuccess = `v${res.data.data.version} 已全量发布；原基线已保留为历史版本。`
      } catch (error) { this.promptError = error.response?.data?.msg || error.message || '全量发布失败' }
      finally { this.promptBusy = false }
    },
    async comparePromptVersions() {
      if (this.promptCompareRunning || !this.promptCompareBase || !this.promptCompareCandidate || this.promptCompareBase === this.promptCompareCandidate) return
      const cases = this.promptComparableCases
      if (!cases.length) { this.promptCompareError = '题库中没有需要模型回答的生产链路题'; return }
      if (this.promptCompareRealCall && !confirm(`将分别对两个版本调用 ${cases.length} 题，共最多 ${cases.length * 2} 次模型请求，可能产生费用。继续吗？`)) return
      this.promptCompareRunning = true; this.promptCompareProgress = 0; this.promptCompareResults = []; this.promptCompareError = ''
      try {
        for (const testCase of cases) {
          for (const version of [this.promptCompareBase, this.promptCompareCandidate]) {
            const res = await request.post('/admin/llm-cost-evaluation/evaluate', {
              question: testCase.question, history: testCase.history || [], promptVersion: version,
              modelId: this.llmSelectedModelId,
              includeAnswerPreview: this.promptCompareRealCall, realCall: this.promptCompareRealCall,
              expectedOutputTokens: testCase.expected_output_tokens || 300,
              inputPricePerMillion: this.llmCostInputPrice, outputPricePerMillion: this.llmCostOutputPrice,
              userId: Number(localStorage.getItem('userId')) || null, executionTarget: 'production',
              requestedStrategy: testCase.requested_strategy || 'auto', costBudget: testCase.cost_budget || 'standard'
            }, { timeout: this.promptCompareRealCall ? 60000 : 30000 })
            if (res.data.code !== 200) throw new Error(`${testCase.id} v${version}: ${res.data.msg || '评测失败'}`)
            const row = res.data.data
            const expectedIntent = testCase.expected_intent || 'AUTO'
            const expectedStrategy = testCase.expected_strategy || 'AUTO'
            const evidenceLimit = Number(testCase.expected_evidence_count)
            const evidenceCount = Number(row.evidenceCount)
            const passed = (expectedIntent === 'AUTO' || row.intent === expectedIntent) &&
              (expectedStrategy === 'AUTO' || row.selectedStrategy === expectedStrategy) &&
              (evidenceLimit === 0 ? evidenceCount === 0 : evidenceCount > 0 && evidenceCount <= evidenceLimit) && Number(row.modelCalls) > 0
            this.promptCompareResults.push({ ...row, version, caseId: testCase.id, question: testCase.question, passed })
            this.promptCompareProgress++
          }
        }
      } catch (error) { this.promptCompareError = error.response?.data?.msg || error.message || '版本对比失败' }
      finally { this.promptCompareRunning = false }
    },
    async loadPromptVersions() {
      this.promptLoading = true
      this.promptError = ''
      try {
        const res = await request.get('/admin/prompts')
        if (res.data.code !== 200) throw new Error(res.data.msg || '加载 Prompt 版本失败')
        this.promptVersions = Array.isArray(res.data.data) ? res.data.data : []
        if (!this.editingPromptId && !this.viewingPromptId && !this.promptTemplate) {
          const active = this.promptVersions.find(item => item.status === 'published')
          if (active) this.viewPromptVersion(active)
        }
      } catch (error) {
        this.promptError = error.response?.data?.msg || error.message || '加载 Prompt 版本失败'
      } finally {
        this.promptLoading = false
      }
    },
    promptStatusLabel(status) {
      return { published: '已发布', draft: '草稿', inactive: '已停用', gray: '灰度中' }[status] || status
    },
    formatPromptDate(value) {
      const date = new Date(value)
      return Number.isNaN(date.getTime()) ? String(value || '未知') : date.toLocaleString('zh-CN')
    },
    canRollbackPrompt(item) {
      const active = this.promptVersions.find(version => version.status === 'published')
      return Boolean(active && Number(item.version) < Number(active.version))
    },
    startPromptDraft() {
      const active = this.promptVersions.find(item => item.status === 'published')
      this.editingPromptId = null
      this.editingPromptVersion = null
      this.viewingPromptId = null
      this.viewingPromptVersion = null
      this.promptTemplate = active ? active.templateText : ''
      this.promptError = ''
      this.promptSuccess = ''
    },
    startPromptDraftFromViewed() {
      const template = this.promptTemplate
      this.startPromptDraft()
      this.promptTemplate = template
    },
    editPromptDraft(item) {
      this.editingPromptId = item.id
      this.editingPromptVersion = item.version
      this.viewingPromptId = null
      this.viewingPromptVersion = null
      this.promptTemplate = item.templateText || ''
      this.promptError = ''
      this.promptSuccess = ''
    },
    clearPromptDraft() {
      this.editingPromptId = null
      this.editingPromptVersion = null
      this.viewingPromptId = null
      this.viewingPromptVersion = null
      this.promptTemplate = ''
    },
    viewPromptVersion(item) {
      this.editingPromptId = null
      this.editingPromptVersion = null
      this.viewingPromptId = item.id
      this.viewingPromptVersion = item.version
      this.promptTemplate = item.templateText || ''
    },
    async savePromptDraft() {
      if (this.promptBusy || this.promptTemplate.trim().length < 20) return
      this.promptBusy = true
      this.promptError = ''
      try {
        const payload = { template: this.promptTemplate }
        const res = this.editingPromptId
          ? await request.put(`/admin/prompts/${this.editingPromptId}`, payload)
          : await request.post('/admin/prompts', payload)
        if (res.data.code !== 200) throw new Error(res.data.msg || '保存草稿失败')
        this.clearPromptDraft()
        await this.loadPromptVersions()
        if (res.data.data) this.editPromptDraft(res.data.data)
        this.promptSuccess = '草稿已保存；发布前不会影响用户回答。'
      } catch (error) {
        this.promptError = error.response?.data?.msg || error.message || '保存草稿失败'
      } finally {
        this.promptBusy = false
      }
    },
    async changePromptStatus(item, action, successMessage) {
      this.promptBusy = true
      this.promptError = ''
      this.promptSuccess = ''
      try {
        const res = await request.post(`/admin/prompts/${item.id}/${action}`)
        if (res.data.code !== 200) throw new Error(res.data.msg || '操作失败')
        await this.loadPromptVersions()
        if (res.data.data) this.viewPromptVersion(res.data.data)
        this.promptSuccess = successMessage
      } catch (error) {
        this.promptError = error.response?.data?.msg || error.message || '操作失败'
      } finally {
        this.promptBusy = false
      }
    },
    publishPrompt(item) {
      if (this.promptBusy || !confirm(`发布 v${item.version}？新请求会立即使用此版本。`)) return
      this.changePromptStatus(item, 'publish', `v${item.version} 已发布，新请求立即生效。`)
    },
    disablePrompt(item) {
      if (this.promptBusy || !confirm(`停用 v${item.version}？新请求将使用内置兜底规则。`)) return
      this.changePromptStatus(item, 'disable', `v${item.version} 已停用，当前使用内置兜底规则。`)
    },
    restorePrompt(item) {
      if (this.promptBusy) return
      const rollback = this.canRollbackPrompt(item)
      if (!confirm(`${rollback ? '回滚到' : '重新发布'} v${item.version}？新请求会立即使用此版本。`)) return
      this.changePromptStatus(item, rollback ? 'rollback' : 'publish', `v${item.version} 已${rollback ? '回滚' : '重新发布'}。`)
    },
    async deletePromptVersion(item) {
      if (this.promptBusy || item.status === 'published') return
      if (!confirm(`确定删除 v${item.version}？模板正文会被清空且无法恢复，版本号仍保留用于审计。`)) return
      this.promptBusy = true
      this.promptError = ''
      this.promptSuccess = ''
      try {
        const res = await request.delete(`/admin/prompts/${item.id}`)
        if (res.data.code !== 200) throw new Error(res.data.msg || '删除版本失败')
        if (this.editingPromptId === item.id || this.viewingPromptId === item.id) this.clearPromptDraft()
        await this.loadPromptVersions()
        this.promptSuccess = `v${item.version} 已删除，版本号标识仍保留用于审计。`
      } catch (error) {
        this.promptError = error.response?.data?.msg || error.message || '删除版本失败'
      } finally {
        this.promptBusy = false
      }
    },
    resetLlmCostRun() {
      this.llmCostResults = []
      this.llmCostSummary = null
      this.llmCostProgress = 0
      this.llmCostCurrentCase = null
      this.llmCostError = ''
    },
    applyLlmCostPricePreset() {
      const prices = {
        'flash-peak': [3, 9], 'flash-offpeak': [1.5, 4.5],
        'pro-peak': [9, 27], 'pro-offpeak': [4.5, 13.5]
      }
      if (!prices[this.llmCostPricePreset]) return
      const selectedPrice = prices[this.llmCostPricePreset]
      this.llmCostInputPrice = selectedPrice[0]
      this.llmCostOutputPrice = selectedPrice[1]
    },
    async openLlmCostEvaluation() {
      this.currentMenu = 'llmCost'
      await Promise.all([
        this.llmCostCases.length ? Promise.resolve() : this.loadLlmCostCases(),
        this.loadLlmProviderStatus()
      ])
    },
    async loadAdminLlmModels() {
      this.llmModelError = ''
      try {
        const res = await request.get('/admin/llm-models')
        if (res.data.code !== 200) throw new Error(res.data.msg || '模型目录加载失败')
        this.llmAdminModels = res.data.data || []
      } catch (error) { this.llmModelError = error.response?.data?.msg || error.message || '模型目录加载失败' }
    },
    async saveLlmModel() {
      if (!this.llmModelForm.provider || !this.llmModelForm.model) return alert('Provider 和 API 模型名不能为空')
      this.llmModelSaving = true
      try {
        const res = await request.post('/admin/llm-models', this.llmModelForm)
        if (res.data.code !== 200) throw new Error(res.data.msg || '模型保存失败')
        this.llmModelForm = { provider: '', model: '', displayName: '', inputPricePerMillion: 0, outputPricePerMillion: 0, userSelectable: true, default: false }
        await Promise.all([this.loadAdminLlmModels(), this.loadLlmProviderStatus()])
      } catch (error) { alert(error.response?.data?.msg || error.message || '模型保存失败') }
      finally { this.llmModelSaving = false }
    },
    async disableLlmModel(model) {
      if (!confirm(`确定停用“${model.displayName}”吗？`)) return
      try {
        const res = await request.delete(`/admin/llm-models/${model.id}`)
        if (res.data.code !== 200) throw new Error(res.data.msg || '模型停用失败')
        await Promise.all([this.loadAdminLlmModels(), this.loadLlmProviderStatus()])
      } catch (error) { alert(error.response?.data?.msg || error.message || '模型停用失败') }
    },
    async loadLlmCostCases() {
      this.llmCostLoading = true
      this.llmCostError = ''
      try {
        const res = await request.get('/admin/llm-cost-evaluation/cases')
        if (res.data.code !== 200) throw new Error(res.data.msg || '成本测试集加载失败')
        this.llmCostCases = res.data.data || []
      } catch (error) {
        this.llmCostError = error.response?.data?.msg || error.message || '成本测试集加载失败'
      } finally { this.llmCostLoading = false }
    },
    emptyLlmCostCaseForm(executionTarget = null) {
      const target = executionTarget || (this.llmCostScope === 'agent_native' ? 'agent_native' : 'production')
      return {
        category: '', question: '',
        expectedIntent: target === 'agent_native' ? 'AGENT_NATIVE' : 'AUTO',
        expectedStrategy: 'AUTO', executionTarget: target, costBudget: 'standard',
        expectedTools: '', expectedEvidenceCount: target === 'agent_native' ? 0 : 3,
        expectedOutputTokens: 300, expectedModelCall: true
      }
    },
    handleLlmCostExecutionTargetChange() {
      if (this.llmCostCaseForm.executionTarget === 'agent_native') {
        if (this.llmCostCaseForm.expectedIntent === 'AUTO') this.llmCostCaseForm.expectedIntent = 'AGENT_NATIVE'
        this.llmCostCaseForm.expectedEvidenceCount = 0
        this.llmCostCaseForm.expectedModelCall = true
      } else if (this.llmCostCaseForm.expectedIntent === 'AGENT_NATIVE') {
        this.llmCostCaseForm.expectedIntent = 'AUTO'
        this.llmCostCaseForm.expectedEvidenceCount = 3
      }
    },
    openLlmCostCaseDialog(testCase = null) {
      this.editingLlmCostCaseId = testCase?.id || null
      this.llmCostCaseForm = testCase ? {
        category: testCase.category || '', question: testCase.question || '',
        expectedIntent: testCase.expected_intent || 'AUTO',
        expectedStrategy: testCase.expected_strategy || 'AUTO',
        executionTarget: testCase.execution_target || 'production',
        costBudget: testCase.cost_budget || 'standard',
        expectedTools: (testCase.expected_tools || []).join(','),
        expectedEvidenceCount: Number(testCase.expected_evidence_count ?? 3),
        expectedOutputTokens: Number(testCase.expected_output_tokens ?? 300),
        expectedModelCall: Boolean(testCase.expected_model_call)
      } : this.emptyLlmCostCaseForm()
      this.showLlmCostCaseDialog = true
    },
    closeLlmCostCaseDialog() {
      if (this.llmCostCaseSaving) return
      this.showLlmCostCaseDialog = false
      this.editingLlmCostCaseId = null
      this.llmCostCaseForm = this.emptyLlmCostCaseForm()
    },
    async saveLlmCostCase() {
      if (this.llmCostCaseSaving || !this.llmCostCaseForm.question.trim()) return
      const payload = {
        category: this.llmCostCaseForm.category || '未分类', question: this.llmCostCaseForm.question,
        expected_intent: this.llmCostCaseForm.expectedIntent,
        expected_strategy: this.llmCostCaseForm.expectedStrategy,
        execution_target: this.llmCostCaseForm.executionTarget,
        cost_budget: this.llmCostCaseForm.costBudget,
        expected_tools: this.llmCostCaseForm.expectedTools.split(/[,，]/).map(item => item.trim()).filter(Boolean),
        expected_evidence_count: this.llmCostCaseForm.expectedEvidenceCount,
        expected_output_tokens: this.llmCostCaseForm.expectedOutputTokens,
        expected_model_call: this.llmCostCaseForm.expectedModelCall
      }
      this.llmCostCaseSaving = true
      try {
        const res = this.editingLlmCostCaseId
          ? await request.put(`/admin/llm-cost-evaluation/cases/${this.editingLlmCostCaseId}`, payload)
          : await request.post('/admin/llm-cost-evaluation/cases', payload)
        if (res.data.code !== 200) throw new Error(res.data.msg || '保存失败')
        if (this.llmCostScope !== 'all') this.llmCostScope = payload.execution_target
        await this.loadLlmCostCases()
        this.llmCostSummary = null
        this.llmCostResults = []
        this.closeLlmCostCaseDialog()
      } catch (error) { alert(error.response?.data?.msg || error.message || '保存失败') }
      finally { this.llmCostCaseSaving = false }
    },
    async deleteLlmCostCase(testCase) {
      if (this.llmCostRunning || !confirm(`确定删除 ${testCase.id}“${testCase.question}”吗？`)) return
      try {
        const res = await request.delete(`/admin/llm-cost-evaluation/cases/${testCase.id}`)
        if (res.data.code !== 200) throw new Error(res.data.msg || '删除失败')
        await this.loadLlmCostCases()
        this.llmCostSummary = null
        this.llmCostResults = []
      } catch (error) { alert(error.response?.data?.msg || error.message || '删除失败') }
    },
    async runLlmCostEvaluation() {
      if (this.llmCostRunning || !this.llmCostSelectedCases.length) return
      if (this.llmCostRealCall && !confirm(`真实调用会逐题请求 ${this.llmProviderDisplay} 并产生 API 费用，确定继续吗？`)) return
      this.llmCostRunning = true
      this.llmCostError = ''
      this.llmCostProgress = 0
      this.llmCostResults = []
      this.llmCostSummary = null
      try {
        for (const testCase of this.llmCostSelectedCases) {
          this.llmCostCurrentCase = testCase
          const res = await request.post('/admin/llm-cost-evaluation/evaluate', {
            question: testCase.question, history: testCase.history || [],
            modelId: this.llmSelectedModelId,
            expectedOutputTokens: testCase.expected_output_tokens || 300,
            realCall: this.llmCostRealCall,
            inputPricePerMillion: this.llmCostInputPrice,
            outputPricePerMillion: this.llmCostOutputPrice,
            userId: Number(localStorage.getItem('userId')) || null,
            executionTarget: testCase.execution_target || 'production',
            requestedStrategy: testCase.requested_strategy || 'auto',
            costBudget: testCase.cost_budget || 'standard'
          }, { timeout: this.llmCostRealCall ? 60000 : 30000 })
          if (res.data.code !== 200) throw new Error(`${testCase.id}: ${res.data.msg || '成本评测失败'}`)
          const item = res.data.data
          const intentPassed = testCase.expected_intent === 'AUTO' || testCase.expected_intent === item.intent
          const evidenceLimit = Number(testCase.expected_evidence_count)
          const evidenceCount = Number(item.evidenceCount)
          const evidencePassed = evidenceLimit === 0 ? evidenceCount === 0 : evidenceCount > 0 && evidenceCount <= evidenceLimit
          const callPassed = Boolean(testCase.expected_model_call) === (Number(item.modelCalls) > 0)
          const expectedStrategy = testCase.expected_strategy || 'AUTO'
          const strategyPassed = expectedStrategy === 'AUTO' || expectedStrategy === item.selectedStrategy
          const expectedTools = testCase.expected_tools || []
          const actualTools = (item.toolExecutions || []).filter(tool => tool.success).map(tool => tool.tool)
          const toolPassed = !this.llmCostRealCall || expectedTools.every(tool => actualTools.includes(tool))
          this.llmCostResults.push({ ...item, id: testCase.id, category: testCase.category, question: testCase.question, expectedEvidenceLimit: evidenceLimit, expectedTools, toolPassed, passed: intentPassed && evidencePassed && callPassed && strategyPassed && toolPassed })
          this.llmCostProgress++
        }
        this.buildLlmCostSummary()
      } catch (error) { this.llmCostError = error.response?.data?.msg || error.message || '成本评测运行失败' }
      finally { this.llmCostCurrentCase = null; this.llmCostRunning = false }
    },
    buildLlmCostSummary() {
      const total = this.llmCostResults.length
      const sum = key => this.llmCostResults.reduce((value, item) => value + Number(item[key] || 0), 0)
      const modelCalls = sum('modelCalls')
      const latencies = this.llmCostResults.map(item => Number(item.elapsedMs || 0)).sort((a, b) => a - b)
      const p95Index = latencies.length ? Math.min(latencies.length - 1, Math.ceil(latencies.length * 0.95) - 1) : 0
      this.llmCostSummary = {
        evaluationVersion: '2.0', evaluationScope: this.llmCostScope, totalCases: total, modelCalls,
        localBypassRate: total ? this.llmCostResults.filter(item => Number(item.modelCalls || 0) === 0).length / total : 0,
        inputTokens: sum('inputTokens'), outputTokens: sum('outputTokens'), totalTokens: sum('totalTokens'),
        averageTokens: total ? sum('totalTokens') / total : 0,
        averageTokensPerModelCall: modelCalls ? sum('totalTokens') / modelCalls : 0,
        directCases: this.llmCostResults.filter(item => item.selectedStrategy === 'direct').length,
        reactCases: this.llmCostResults.filter(item => item.selectedStrategy === 'react').length,
        toolCalls: sum('toolCalls'), toolRounds: sum('toolRounds'),
        averageLatencyMs: total ? sum('elapsedMs') / total : 0,
        p95LatencyMs: latencies.length ? latencies[p95Index] : 0,
        budgetExceededCases: this.llmCostResults.filter(item => item.budgetExceeded).length,
        estimatedCost: sum('estimatedCost'),
        passedCases: this.llmCostResults.filter(item => item.passed).length,
        passRate: total ? this.llmCostResults.filter(item => item.passed).length / total : 0
      }
    },
    hasLlmTokenPrice() {
      return Number(this.llmCostInputPrice) > 0 || Number(this.llmCostOutputPrice) > 0
    },
    toolExecutionTitle(item) {
      const tools = Array.isArray(item.toolExecutions) ? item.toolExecutions : []
      const toolSummary = tools.map(tool => `${tool.tool}:${tool.success ? 'ok' : (tool.errorCode || 'error')}(${tool.durationMs || 0}ms)`).join(', ')
      return toolSummary || (item.plannedTool ? `模拟计划：${item.plannedTool}` : '') || item.stopReason || item.finishReason || '未调用工具'
    },
    formatLlmCost(value) {
      return this.hasLlmTokenPrice() ? `¥${Number(value || 0).toFixed(6)}` : '未配置价格'
    },
    downloadLlmCostReport() {
      if (!this.llmCostSummary) return
      const report = { schemaVersion: '2.0', generatedAt: new Date().toISOString(), mode: this.llmCostRealCall ? 'real' : 'simulation', scope: this.llmCostScope, provider: this.llmProviderName, model: this.llmModelName, prices: { input: this.llmCostInputPrice, output: this.llmCostOutputPrice }, summary: this.llmCostSummary, cases: this.llmCostResults }
      const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `llm-cost-evaluation-${new Date().toISOString().replace(/[:.]/g, '-')}.json`
      link.click()
      URL.revokeObjectURL(url)
    },
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
    topSongs() { return this.monthlyPlayList.filter(item => Number(item.playCount) > 0).slice(0, 8) },
    totalMonthlyPlays() {
      return this.monthlyPlayList.reduce((total, item) => total + Number(item.playCount || 0), 0)
    },
    playedSongCount() { return this.monthlyPlayList.filter(item => Number(item.playCount) > 0).length },
    topSongPlays() { return Math.max(0, ...this.monthlyPlayList.map(item => Number(item.playCount || 0))) },
    maxDailyPlays() {
      return Math.max(0, ...this.monthlyDailyPlayList.map(item => Number(item.playCount || 0)))
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
.llm-cost-hero { background: linear-gradient(120deg, #242054, #5940a0 58%, #8752d4); }.llm-cost-mode { display: inline-flex !important; align-items: center; gap: 7px; padding: 8px 11px; border: 1px solid rgba(255,255,255,.32); border-radius: 9px; color: #fff !important; background: rgba(255,255,255,.1); white-space: nowrap; }.llm-cost-mode input { accent-color: #a889ee; }.llm-cost-settings { display: flex; align-items: flex-end; gap: 14px; margin-bottom: 16px; padding: 14px 18px; border: 1px solid #e6e0f3; border-radius: 14px; background: #fff; }.llm-cost-settings label { display: grid; gap: 6px; color: #625978; font-size: 11px; }.llm-cost-settings input { width: 150px; padding: 8px 10px; border: 1px solid #ded7ec; border-radius: 8px; }.llm-cost-settings small { flex: 1; color: #928aa1; line-height: 1.5; }.llm-cost-result-table { max-height: 520px; }.llm-cost-result-table table { min-width: 1050px; }.llm-cost-mismatch { background: #fff8f9; }.llm-cost-mismatch td:first-child strong { color: #ca5365; }.llm-cost-metrics .evaluation-metric strong { font-size: 24px; }
.prompt-hero { background: linear-gradient(120deg, #242054, #5940a0 58%, #8752d4); }.prompt-notice, .prompt-success { margin: 0 0 18px; padding: 13px 16px; border: 1px solid #e2d9f3; border-radius: 12px; color: #64587b; background: #fff; font-size: 13px; line-height: 1.6; }.prompt-success { border-color: #bde5d5; color: #277255; background: #f0fbf6; }.prompt-dashboard .evaluation-state { margin-bottom: 18px; }.prompt-layout { display: grid; grid-template-columns: minmax(0,1.1fr) minmax(320px,.9fr); gap: 18px; align-items: start; }.prompt-version-list { overflow-y: auto; max-height: 680px; padding: 15px; }.prompt-version-item { margin-bottom: 12px; padding: 16px; border: 1px solid #e7e1f3; border-radius: 12px; background: #fff; }.prompt-version-item:last-child { margin-bottom: 0; }.prompt-version-current { border-color: #9270d0; background: #faf7ff; }.prompt-version-heading { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }.prompt-version-heading > div { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }.prompt-version-heading strong { color: #3c3159; font-size: 14px; }.prompt-version-heading small { color: #8f869e; font-size: 11px; white-space: nowrap; }.prompt-status { padding: 3px 8px; border-radius: 99px; background: #f0ecf7; color: #736784; font-size: 10px; }.prompt-status-published { color: #1e7956; background: #def5e9; }.prompt-status-draft { color: #7854b5; background: #eee7ff; }.prompt-version-preview { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 3; margin: 12px 0; color: #6f667e; font-size: 12px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }.prompt-version-actions { display: flex; gap: 8px; flex-wrap: wrap; }.prompt-version-actions button { padding: 7px 11px; border: 1px solid #c9b8e9; border-radius: 8px; color: #6545a7; background: #fff; font: inherit; font-size: 11px; cursor: pointer; }.prompt-version-actions button.danger { border-color: #efbec6; color: #bd4f64; }.prompt-version-actions button:disabled { cursor: not-allowed; opacity: .5; }.prompt-editor-body { padding: 18px; }.prompt-editor-body label { display: block; margin-bottom: 9px; color: #4b3f65; font-size: 13px; font-weight: 700; }.prompt-editor-body textarea { box-sizing: border-box; width: 100%; min-height: 360px; padding: 13px; resize: vertical; border: 1px solid #d9d0e9; border-radius: 10px; color: #3d3550; background: #fcfbff; font: inherit; font-size: 12px; line-height: 1.7; }.prompt-editor-body small { display: block; margin-top: 7px; color: #91869e; font-size: 11px; }.prompt-editor-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 16px; }.prompt-editor-actions button { padding: 9px 15px; border-radius: 9px; font: inherit; font-size: 12px; cursor: pointer; }.prompt-editor-actions button:disabled { cursor: not-allowed; opacity: .5; }
.model-catalog-hero { background: linear-gradient(120deg, #242054, #4f3c96 58%, #6f56c9); }.model-catalog-dashboard .evaluation-state { margin-bottom: 18px; }
.prompt-rollout-panel, .prompt-compare-panel, .prompt-metrics-panel { margin-bottom: 18px; }.prompt-rollout-body { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 16px; color: #5b5271; font-size: 13px; }.prompt-rollout-body p { flex-basis: 100%; margin: 0; line-height: 1.6; }.prompt-rollout-body select, .prompt-rollout-body input[type=number] { padding: 7px; border: 1px solid #d9d0e9; border-radius: 8px; background: #fff; }.prompt-rollout-body input[type=number] { width: 58px; }.prompt-rollout-body button { padding: 8px 12px; border: 1px solid #8666c3; border-radius: 8px; color: #fff; background: #7454b4; cursor: pointer; }.prompt-rollout-body button:disabled { opacity: .5; cursor: not-allowed; }.prompt-rollout-body .prompt-promote-btn { border-color: #2d9168; background: #2d9168; }.prompt-answer-review { padding: 0 16px 16px; }.prompt-answer-review details { margin-top: 8px; padding: 9px; border: 1px solid #e7e1f3; border-radius: 8px; }.prompt-answer-review summary { cursor: pointer; }.prompt-answer-review p { white-space: pre-wrap; }.prompt-status-gray { color: #915d1e; background: #fff0d1; }.prompt-metrics-filters { display: flex; gap: 8px; flex-wrap: wrap; }.prompt-metrics-filters select, .prompt-metrics-filters button { padding: 7px 10px; border: 1px solid #d4c7ea; border-radius: 8px; color: #5f4695; background: #fff; }.prompt-metrics-filters button.danger { border-color: #efbec6; color: #bd4f64; }.prompt-metrics-filters button:disabled { cursor: not-allowed; opacity: .5; }.prompt-metrics-privacy, .prompt-decision-note { margin: 0; padding: 13px 16px; color: #6d6380; background: #faf8ff; font-size: 12px; line-height: 1.6; }.prompt-decision-note { border-top: 1px solid #eee8f7; }.prompt-sample-status { display: inline-block; padding: 3px 8px; border-radius: 99px; color: #9b651d; background: #fff1d7; font-size: 11px; }.prompt-sample-status.ready { color: #247454; background: #def5e9; }
.llm-model-catalog { margin-top: 18px; }.llm-model-form { display: grid; grid-template-columns: repeat(3, minmax(180px, 1fr)); gap: 14px; padding: 20px; border-bottom: 1px solid #efecf6; }.llm-model-form label { display: grid; gap: 7px; min-width: 0; color: #625978; font-size: 11px; font-weight: 600; }.llm-model-form label > input:not([type=checkbox]) { box-sizing: border-box; width: 100%; min-width: 0; padding: 10px 12px; border: 1px solid #ded7ec; border-radius: 9px; color: #3d3550; background: #fff; font: inherit; font-weight: 400; }.llm-model-form label > input:not([type=checkbox]):focus { border-color: #8b6bca; outline: 0; box-shadow: 0 0 0 3px rgba(117,80,194,.1); }.llm-model-form .llm-model-toggle { display: flex; align-items: center; gap: 12px; min-height: 58px; padding: 10px 13px; border: 1px solid #e1d9f0; border-radius: 10px; background: #faf8ff; cursor: pointer; }.llm-model-form .llm-model-toggle input { flex: 0 0 auto; width: 18px; height: 18px; margin: 0; accent-color: #7550c2; }.llm-model-toggle span, .llm-model-toggle strong, .llm-model-toggle small { display: block; }.llm-model-toggle strong { color: #45365f; font-size: 12px; }.llm-model-toggle small { margin-top: 3px; color: #8d849d; font-size: 10px; font-weight: 400; line-height: 1.4; }.llm-model-form button { align-self: stretch; min-height: 58px; border: 0; border-radius: 10px; color: #fff; background: #7454b4; font: inherit; font-size: 12px; font-weight: 700; cursor: pointer; }.llm-model-form button:disabled { cursor: not-allowed; opacity: .5; }.llm-model-catalog .evaluation-table button.danger { padding: 5px 10px; border: 1px solid #efbec6; border-radius: 7px; color: #bd4f64; background: #fff; cursor: pointer; }
@media (max-width: 800px) { .statistics-dashboard { padding: 18px; }.stats-toolbar { align-items: flex-start; flex-direction: column; }.stats-kpis { grid-template-columns: 1fr; }.song-bar-chart { overflow-x: auto; }.song-bar-chart .bar-column { min-width: 84px; }.daily-chart-wrap { overflow-x: auto; }.daily-bar-chart { min-width: 600px; } }
@media (max-width: 1100px) { .evaluation-grid { grid-template-columns: 1fr; }.evaluation-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (max-width: 1100px) { .prompt-layout { grid-template-columns: 1fr; } }
@media (max-width: 800px) { .evaluation-dashboard { padding: 15px; }.evaluation-hero { align-items: flex-start; flex-direction: column; }.evaluation-actions { justify-content: flex-start; }.evaluation-metrics { grid-template-columns: 1fr; }.evaluation-form-grid { grid-template-columns: 1fr; }.llm-cost-settings { align-items: stretch; flex-direction: column; }.llm-cost-settings input { width: 100%; }.prompt-version-heading { flex-direction: column; }.prompt-editor-body textarea { min-height: 280px; }.llm-model-form { grid-template-columns: 1fr; } }
</style>

<template>
  <div class="ai-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">Lili 技能中心</span>
            <span class="subtitle">来自 wimoor-ai 真实技能目录（Catalog）</span>
          </div>
          <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-alert v-if="error" type="error" :closable="false" show-icon>{{ error }}</el-alert>

      <div class="stats" v-if="health">
        <el-statistic title="技能数" :value="health.skills" />
        <el-statistic title="工具数" :value="health.tools" />
        <el-statistic title="工作流数" :value="health.workflows" />
        <el-statistic title="版本" :value="health.liliVersion" />
      </div>

      <el-tabs v-model="tab" class="tabs">
        <el-tab-pane label="技能 Skills" name="skills">
          <el-input v-model="keyword" placeholder="搜索技能名称/slug" clearable class="search" />
          <el-table :data="filteredSkills" v-loading="loading" stripe size="small" max-height="520">
            <el-table-column prop="slug" label="Slug" min-width="180" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="category" label="分类" width="150" />
            <el-table-column prop="summary" label="说明" min-width="300" show-overflow-tooltip />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewManifest(row)">Manifest</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="工具 Tools" name="tools">
          <el-table :data="tools" v-loading="loading" stripe size="small" max-height="520">
            <el-table-column prop="name" label="工具名" min-width="220" />
            <el-table-column prop="provider" label="提供方" width="140" />
            <el-table-column prop="description" label="说明" min-width="360" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="工作流 Workflows" name="workflows">
          <el-table :data="workflows" v-loading="loading" stripe size="small" max-height="520">
            <el-table-column prop="name" label="工作流" min-width="220" />
            <el-table-column prop="description" label="说明" min-width="420" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="manifestVisible" :title="'Manifest · ' + currentSlug" width="520px">
      <pre class="manifest">{{ manifest }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { aiApi } from '@/api/sys/tool/aiApi.js'

const loading = ref(false)
const error = ref('')
const health = ref(null)
const skills = ref([])
const tools = ref([])
const workflows = ref([])
const tab = ref('skills')
const keyword = ref('')
const manifestVisible = ref(false)
const manifest = ref('')
const currentSlug = ref('')

const filteredSkills = computed(() => {
  if (!keyword.value) return skills.value
  const k = keyword.value.toLowerCase()
  return skills.value.filter(s =>
    (s.slug || '').toLowerCase().includes(k) || (s.name || '').toLowerCase().includes(k))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [h, s, t, w] = await Promise.all([
      aiApi.health(), aiApi.skills(), aiApi.tools(), aiApi.workflows()
    ])
    health.value = h
    skills.value = Array.isArray(s) ? s : []
    tools.value = Array.isArray(t) ? t : []
    workflows.value = Array.isArray(w) ? w : []
  } catch (e) {
    error.value = '加载失败：' + (e?.message || e)
  } finally {
    loading.value = false
  }
}

async function viewManifest(row) {
  currentSlug.value = row.slug
  try {
    const m = await aiApi.manifest(row.slug)
    manifest.value = JSON.stringify(m, null, 2)
    manifestVisible.value = true
  } catch (e) {
    manifest.value = '加载失败：' + (e?.message || e)
    manifestVisible.value = true
  }
}

onMounted(load)
</script>

<style scoped>
.ai-page { padding: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.title { font-size: 16px; font-weight: 600; margin-right: 12px; }
.subtitle { font-size: 12px; color: #909399; }
.stats { display: flex; gap: 40px; padding: 12px 0 20px; }
.tabs { margin-top: 8px; }
.search { width: 320px; margin-bottom: 12px; }
.manifest { font-size: 12px; max-height: 400px; overflow: auto; background: #f5f7fa; padding: 12px; border-radius: 4px; }
</style>

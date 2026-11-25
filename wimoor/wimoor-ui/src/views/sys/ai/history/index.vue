<template>
  <div class="ai-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">AI 运行历史</span>
            <span class="subtitle">真实持久化 · 租户隔离 · revision 乐观锁</span>
          </div>
          <div>
            <el-input v-model="scope.org" style="width: 130px" placeholder="org" />
            <el-input v-model="scope.ws" style="width: 120px; margin-left: 6px" placeholder="workspace" />
            <el-input v-model="scope.user" style="width: 120px; margin-left: 6px" placeholder="user" />
            <el-button type="primary" style="margin-left: 8px" :loading="loading" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="disabled" type="warning" :closable="false" show-icon>
        持久化未启用：请以后端参数 --ai.persistence=jdbc 启动并配置 db_ai 库。
      </el-alert>

      <el-table :data="conversations" v-loading="loading" stripe size="small">
        <el-table-column prop="id" label="会话 ID" min-width="240" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="revision" label="版本" width="80" />
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewDetail(row)">详情</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" :title="'会话详情 · ' + (current?.id || '')" size="45%">
      <pre class="detail">{{ detail }}</pre>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi } from '@/api/sys/tool/aiApi.js'

const loading = ref(false)
const disabled = ref(false)
const conversations = ref([])
const drawer = ref(false)
const current = ref(null)
const detail = ref('')
const scope = reactive({ org: 'system', ws: 'ws1', user: 'system' })

async function load() {
  loading.value = true
  disabled.value = false
  try {
    const data = await aiApi.listConversations({ org: scope.org, ws: scope.ws, user: scope.user })
    conversations.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (String(e?.message || e).includes('503') || String(e?.response?.status) === '503') {
      disabled.value = true
      conversations.value = []
    } else {
      ElMessage.error('加载失败：' + (e?.message || e))
    }
  } finally {
    loading.value = false
  }
}

async function viewDetail(row) {
  current.value = row
  try {
    const d = await aiApi.getConversation({ org: scope.org, ws: scope.ws, user: scope.user }, row.id)
    detail.value = JSON.stringify(d, null, 2)
  } catch (e) {
    detail.value = '加载失败：' + (e?.message || e)
  }
  drawer.value = true
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确认删除会话 ' + row.id + '？', '提示', { type: 'warning' })
    await aiApi.deleteConversation({ org: scope.org, ws: scope.ws, user: scope.user }, row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message || e))
  }
}

onMounted(load)
</script>

<style scoped>
.ai-page { padding: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.title { font-size: 16px; font-weight: 600; margin-right: 12px; }
.subtitle { font-size: 12px; color: #909399; }
.detail { font-size: 12px; max-height: 70vh; overflow: auto; background: #f5f7fa; padding: 12px; border-radius: 4px; }
</style>

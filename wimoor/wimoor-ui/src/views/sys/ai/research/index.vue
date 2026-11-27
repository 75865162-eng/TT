<template>
  <div class="ai-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">选品研究</span>
            <span class="subtitle">五阶段状态机 · 人工门 · crash-safe 落库</span>
          </div>
          <el-button @click="loadHealth">服务状态</el-button>
        </div>
      </template>

      <el-alert v-if="health" :type="health.status === 'UP' ? 'success' : 'warning'" :closable="false" show-icon>
        AEC-AI {{ health.status }} · skills {{ health.skills }} / tools {{ health.tools }}
      </el-alert>

      <el-steps :active="activeStage" finish-status="success" align-center class="steps">
        <el-step title="找细分" />
        <el-step title="产品研究" />
        <el-step title="评论分析" />
        <el-step title="关税测算" />
        <el-step title="选品评分" />
      </el-steps>

      <el-form :model="form" label-width="120px" class="form">
        <el-form-item label="研究 ID">
          <el-input v-model="form.id" placeholder="research-xxx（留空自动生成）" />
        </el-form-item>
        <el-form-item label="需求描述">
          <el-input v-model="form.requirement" type="textarea" :rows="3" placeholder="例如：分析美国站点 3C 类目低竞争高利润产品" />
        </el-form-item>
        <el-form-item label="目标市场">
          <el-select v-model="form.marketplace" placeholder="选择市场" style="width: 220px">
            <el-option v-for="m in markets" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="组织 / 用户">
          <el-input v-model="form.org" style="width: 160px" placeholder="org" />
          <el-input v-model="form.user" style="width: 160px; margin-left: 8px" placeholder="user" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="createRun">创建研究 Run</el-button>
          <el-button @click="loadRun" :disabled="!form.id">读取 Run</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="result" type="info" :closable="false" show-icon>
        <pre class="result">{{ result }}</pre>
      </el-alert>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { aiApi } from '@/api/sys/tool/aiApi.js'

const markets = ['US', 'UK', 'DE', 'FR', 'CA', 'JP', 'AU']
const health = ref(null)
const result = ref('')
const submitting = ref(false)
const form = reactive({
  id: '', requirement: '', marketplace: 'US', org: 'system', user: 'system'
})

const activeStage = computed(() => {
  if (!result.value) return 0
  try { return Math.min(4, JSON.parse(result.value).stage || 0) } catch { return 0 }
})

async function loadHealth() {
  try { health.value = await aiApi.health() }
  catch (e) { result.value = '服务不可用：' + (e?.message || e) }
}

async function createRun() {
  submitting.value = true
  result.value = ''
  try {
    if (!form.id) form.id = 'research-' + Date.now()
    const payload = {
      id: form.id,
      scope: { organizationId: form.org, workspaceId: 'ws1', ownerId: form.user },
      requirement: form.requirement,
      marketplace: form.marketplace,
      mode: 'live',
      status: 'RUNNING',
      stage: 0,
      revision: 1
    }
    const r = await aiApi.createResearch(form.org, form.user, payload)
    result.value = JSON.stringify(r, null, 2)
  } catch (e) {
    result.value = '创建失败：' + (e?.message || e)
  } finally {
    submitting.value = false
  }
}

async function loadRun() {
  result.value = ''
  try {
    const r = await aiApi.getResearch(form.id)
    result.value = JSON.stringify(r, null, 2)
  } catch (e) {
    result.value = '读取失败：' + (e?.message || e)
  }
}

onMounted(loadHealth)
</script>

<style scoped>
.ai-page { padding: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.title { font-size: 16px; font-weight: 600; margin-right: 12px; }
.subtitle { font-size: 12px; color: #909399; }
.steps { margin: 24px 0; }
.form { max-width: 720px; }
.result { font-size: 12px; max-height: 320px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
</style>

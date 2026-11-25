<template>
  <div class="ai-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div>
            <span class="title">销量统计（Sellfox）</span>
            <span class="subtitle">条条 ERP 独有能力迁入 · 真实指标计算</span>
          </div>
          <el-button type="primary" :loading="loading" @click="load">计算指标</el-button>
        </div>
      </template>

      <el-alert v-if="error" type="error" :closable="false" show-icon>{{ error }}</el-alert>

      <el-form :inline="true" :model="form" class="form">
        <el-form-item label="曝光"><el-input-number v-model="form.impressions" :min="0" /></el-form-item>
        <el-form-item label="点击"><el-input-number v-model="form.clicks" :min="0" /></el-form-item>
        <el-form-item label="花费"><el-input-number v-model="form.spend" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="订单"><el-input-number v-model="form.orders" :min="0" /></el-form-item>
        <el-form-item label="销售额"><el-input-number v-model="form.sales" :min="0" :precision="2" /></el-form-item>
      </el-form>

      <el-row :gutter="16" v-if="metrics">
        <el-col :span="6"><el-statistic title="CTR" :value="metrics.ctr" suffix="%" /></el-col>
        <el-col :span="6"><el-statistic title="CPC" :value="metrics.cpc" /></el-col>
        <el-col :span="6"><el-statistic title="CVR" :value="metrics.cvr" suffix="%" /></el-col>
        <el-col :span="6"><el-statistic title="ACOS" :value="metrics.acos" suffix="%" /></el-col>
        <el-col :span="6"><el-statistic title="ROAS" :value="metrics.roas" /></el-col>
      </el-row>

      <el-divider content-position="left">AI 对话数据源</el-divider>
      <el-tag v-for="s in dataSources" :key="s" class="ds-tag">{{ s }}</el-tag>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { aiApi } from '@/api/sys/tool/aiApi.js'

const loading = ref(false)
const error = ref('')
const form = reactive({ impressions: 10000, clicks: 500, spend: 100, orders: 25, sales: 500 })
const metrics = ref(null)
const dataSources = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const m = await aiApi.adsMetrics({ ...form })
    metrics.value = Object.fromEntries(
      Object.entries(m).map(([k, v]) => [k, isNaN(Number(v)) ? v : Number(v)])
    )
  } catch (e) {
    error.value = '指标计算失败：' + (e?.message || e)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  load()
  try { dataSources.value = await aiApi.dataSources() } catch { /* 只读端点失败不阻断 */ }
})
</script>

<style scoped>
.ai-page { padding: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.title { font-size: 16px; font-weight: 600; margin-right: 12px; }
.subtitle { font-size: 12px; color: #909399; }
.form { margin-bottom: 16px; }
.ds-tag { margin-right: 8px; margin-bottom: 8px; }
</style>

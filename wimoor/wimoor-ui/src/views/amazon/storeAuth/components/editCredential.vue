<template>
	<el-dialog v-model="visible" title="编辑授权凭证" width="560px" destroy-on-close>
		<el-alert type="info" show-icon
			title="仅更新 LWA 凭证与 Refresh Token；留空的项保持原值不变。店铺、区域、Seller ID 不会被修改。"
			style="margin-bottom:16px" />
		<el-form :model="form" label-width="130px">
			<el-form-item label="店铺名称">
				<el-input v-model="form.groupName" readonly></el-input>
			</el-form-item>
			<el-form-item label="区域">
				<el-input v-model="form.regionName" readonly></el-input>
			</el-form-item>
			<el-form-item label="Seller ID">
				<el-input v-model="form.sellerid" readonly></el-input>
			</el-form-item>
			<el-form-item label="LWA Client Id">
				<el-input v-model="form.clientId" placeholder="留空则不修改"></el-input>
			</el-form-item>
			<el-form-item label="LWA Client Secret">
				<el-input v-model="form.clientSecret" placeholder="留空则不修改" show-password></el-input>
			</el-form-item>
			<el-form-item label="Refresh Token">
				<el-input v-model="form.refreshToken" type="textarea" :rows="3" placeholder="留空则不修改"></el-input>
			</el-form-item>
		</el-form>
		<template #footer>
			<span class="dialog-footer">
				<el-button @click="visible=false">取消</el-button>
				<el-button type="primary" :loading="submitting" @click="submit">保存并验证</el-button>
			</span>
		</template>
	</el-dialog>
</template>

<script setup>
	import {ref, reactive} from "vue";
	import {ElMessage} from "element-plus";
	import authApi from '@/api/amazon/auth/authApi.js';

	const visible = ref(false);
	const submitting = ref(false);
	let form = reactive({
		id:'', groupName:'', regionName:'', sellerid:'',
		clientId:'', clientSecret:'', refreshToken:''
	});
	const emit = defineEmits(['done']);

	function show(row, groupName){
		form.id = row.authid;
		form.groupName = groupName || '';
		form.regionName = row.regionname || '';
		form.sellerid = row.sellerid || '';
		form.clientId = '';
		form.clientSecret = '';
		form.refreshToken = '';
		visible.value = true;
	}
	function submit(){
		if(!form.id){
			ElMessage.error('未获取到授权记录ID，无法编辑');
			return;
		}
		if(!form.clientId && !form.clientSecret && !form.refreshToken){
			ElMessage.warning('请至少填写一项要更新的凭证');
			return;
		}
		submitting.value = true;
		authApi.updateCredentials({
			id: form.id,
			clientId: form.clientId,
			clientSecret: form.clientSecret,
			refreshToken: form.refreshToken
		}).then((res)=>{
			submitting.value = false;
			let data = res.data;
			if(data && data.saveWarnMsg){
				ElMessage.warning(data.saveWarnMsg);
			}else{
				ElMessage.success('凭证已更新');
			}
			visible.value = false;
			emit('done');
		}).catch(()=>{ submitting.value = false; });
	}
	defineExpose({ show });
</script>

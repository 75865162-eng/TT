<template>
	<el-dialog
		   v-model="authVisible"
		   title="广告授权"
		   width="600px"
		 >
		  <el-alert   title="请确保当前的电脑和IP是您要授权广告店铺的常用环境，以免店铺关联!" type="error" show-icon />
		  <el-form :inline="true" :model="formInline" class="form-width-fill" label-width="auto">
		     <el-form-item label="店铺名称"   >
		       <el-select v-model="formInline.groupid" placeholder="请选择..." @change="selectStote">
		         <el-option v-for="(item,index) in storelist.list" :label="item.name" :value="item.id" />
		       </el-select>
		     </el-form-item>
			 <el-form-item   label="站点" required>
			   <el-select v-model="formInline.region" placeholder="同区域站点绑定一个,该区域所有站点都会绑定!" @change="selectCountry">
			          <el-option   label="北美"  value="NA" ></el-option>
					  <el-option   label="欧洲"  value="EU" ></el-option>
					  <el-option   label="远东"  value="FE" ></el-option>
			   </el-select>
			 </el-form-item>
			 <el-form-item  label="授权链接" >
			  	        <el-button @click="formInline.showurl=true;selectCountry()" v-if="formInline.showurl==false" size="small" type="primary">查看</el-button>
			 			<el-alert v-if="formInline.showurl"  type="warning" @close="formInline.showurl=false" >
			 						  <template #default>
			 							<div  class="mar-bot"> {{url}}</div>
			 						  </template>  
			 			</el-alert>
			 			<div v-if="formInline.showurl" style="margin-top:5px">
			 				<el-button   type="primary" link  @click.stop="CopyText(url)">复制</el-button>
			 				<div class="font-extraSmall"  >若无法在店铺所在网络登录本网站，可以复制此链接授权</div>
			 			</div>
			 </el-form-item>    
		   </el-form>
		   <el-divider content-position="left">或：使用 Refresh Token 直接配置（无需登录亚马逊）</el-divider>
		   <el-form :model="manualForm" label-width="90px" class="form-width-fill">
		     <el-form-item label="店铺名称">
		       <el-select v-model="manualForm.groupid" placeholder="请选择店铺" style="width:100%">
		         <el-option v-for="(item,index) in storelist.list" :label="item.name" :value="item.id" />
		       </el-select>
		     </el-form-item>
		     <el-form-item label="站点">
		       <el-select v-model="manualForm.region" placeholder="请选择区域" style="width:100%">
		         <el-option label="北美 (NA)" value="NA"></el-option>
		         <el-option label="欧洲 (EU)" value="EU"></el-option>
		         <el-option label="远东 (FE)" value="FE"></el-option>
		       </el-select>
		     </el-form-item>
		     <el-form-item label="Refresh Token">
		       <el-input v-model="manualForm.refreshToken" type="textarea" :rows="3" placeholder="粘贴广告授权 refresh token（Atzr|...）" />
		     </el-form-item>
		   </el-form>
		   <el-alert v-if="manualResult" :title="manualResult" :type="manualOk ? 'success' : 'error'" show-icon />
		   <template #footer>
		     <span class="dialog-footer">
		       <el-button @click="authVisible = false">取消</el-button>
		       <el-button @click="saveRefreshToken" :loading="manualLoading">保存 Refresh Token</el-button>
		       <el-button  type="primary" @click="getamazonUrl"  >登录亚马逊授权</el-button >
		     </span>
		   </template>
		 </el-dialog>
</template>

<script setup>
	import {Help,Plus,MenuUnfold,SettingTwo} from '@icon-park/vue-next';
	import {ref,reactive,onMounted,toRefs,watch} from "vue";
	import {Edit,ArrowDown,Delete} from '@element-plus/icons-vue';
	import { ElDivider,ElMessageBox ,ElMessage } from 'element-plus'
	import authApi from '@/api/amazon/advertisement/auth/authApi.js';
	import marketApi from '@/api/amazon/market/marketApi.js';
	import {dateFormat} from '@/utils/index.js';
	import CopyText from"@/utils/copy_text.js";
	import { useRoute } from "vue-router";
    let props = defineProps({storelist:[]});
    const { storelist} = toRefs(props);
	const emit = defineEmits(['comfirm']);
	const route = useRoute();
	let authVisible =ref(false)
	let market =ref();
	let url=ref();
	var authtype=ref('manuAuthority');
	let formInline = reactive({
		 groupid: '',
		 marketplaceid:'',
		 showurl:false,
		 region:"NA",
	})
	// refresh token 直配模式
	let manualForm = reactive({ groupid: '', region: 'NA', refreshToken: '' })
	let manualLoading = ref(false)
	let manualResult = ref('')
	let manualOk = ref(false)
 
	function selectStote(val){
		formInline.groupid = val;
		handleChange();
	}
	function selectCountry(val){
		formInline.marketplaceid = val;
		handleChange();
	}
	function handleChange(){
		let params={}
		params = formInline;
		if(params.groupid&&params.region){
			authApi.getAuthUrl(params).then((res)=>{
				url.value=res.data;
			});
		}
	}
	function getamazonUrl(){
		let params={}
		params = formInline
		if(params.groupid&&params.region){
			authApi.getAuthUrl(params).then((res)=>{
				ElMessageBox.confirm(
									    '为避免店铺关联，请确认当前设备环境是您授权店铺的常用环境！',
									    {
									      confirmButtonText: '前往授权',
									      cancelButtonText: '取消',
									      type: 'warning',
										  callback:(action)=>{
											 if(action=="confirm"){
												 window.open(res.data)
											 }
										  }
									    }
									  )
			})
		}else{
			ElMessage.error('请选择对应店铺或站点！');
		}
		
	}
	function saveRefreshToken(){
		manualResult.value=''
		if(!manualForm.groupid||!manualForm.region||!manualForm.refreshToken){
			manualOk.value=false
			manualResult.value='请选择店铺、站点并填写 Refresh Token'
			return
		}
		manualLoading.value=true
		authApi.saveByRefreshToken({
			groupid: manualForm.groupid,
			region: manualForm.region,
			refreshToken: manualForm.refreshToken.trim()
		}).then((res)=>{
			manualLoading.value=false
			manualOk.value = res && (res.code === 200 || res.code === 0)
			manualResult.value = '配置已保存：'+(res&&res.msg?res.msg:(res&&res.data||''))
			if(manualOk.value) emit('comfirm')
		}).catch((err)=>{
			manualLoading.value=false
			manualOk.value=false
			manualResult.value='保存失败：'+(err&&err.msg?err.msg:(err&&err.message?err.message:err))
		})
	}
	function show(){
		if(storelist.value&&storelist.value.list&&storelist.value.list.length>0){
			manualForm.groupid = manualForm.groupid || storelist.value.list[0].id
		}
		authVisible.value =true
	}
	watch(props,()=>{
		 if(props.storelist&&props.storelist.list.length>0){
		 	formInline.groupid=props.storelist.list[0].id;
		 	manualForm.groupid=manualForm.groupid||props.storelist.list[0].id;
		 }else{
		 	formInline.groupid="";
		 }
		
	})
	 defineExpose({ show })
</script>

<style>
	.mar-bot{
		word-break:break-all;
		
	}
	
</style>
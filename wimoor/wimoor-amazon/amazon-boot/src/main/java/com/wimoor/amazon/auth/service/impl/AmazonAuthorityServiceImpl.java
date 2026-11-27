package com.wimoor.amazon.auth.service.impl;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import javax.annotation.Resource;

import cn.hutool.extra.spring.SpringUtil;
import com.wimoor.amazon.performance.mapper.AmazonAuthMarketPerformanceMapper;
import com.wimoor.amazon.performance.pojo.entity.AmazonAuthMarketPerformance;
import org.apache.http.HttpException;
import org.apache.http.client.utils.URLEncodedUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.amazon.spapi.SellingPartnerAPIAA.LWAException;
import com.amazon.spapi.api.AuthorizationApi;
import com.amazon.spapi.client.ApiException;
import com.amazon.spapi.model.authorization.AuthorizationCode;
import com.amazon.spapi.model.authorization.GetAuthorizationCodeResponse;
import com.amazonaws.regions.Regions;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wimoor.amazon.auth.mapper.AmazonAuthorityMapper;
import com.wimoor.amazon.auth.pojo.entity.AmazonAuthority;
import com.wimoor.amazon.auth.pojo.entity.AmazonGroup;
import com.wimoor.amazon.auth.pojo.entity.AmazonSellerMarket;
import com.wimoor.amazon.auth.pojo.entity.Marketplace;
import com.wimoor.amazon.auth.pojo.vo.AmazonGroupVO;
import com.wimoor.amazon.auth.pojo.vo.AmazonMarketVO;
import com.wimoor.amazon.auth.pojo.vo.AmazonRegionVO;
import com.wimoor.amazon.auth.service.IAmazonAuthorityService;
import com.wimoor.amazon.auth.service.IAmazonGroupService;
import com.wimoor.amazon.auth.service.IAmazonSellerMarketService;
import com.wimoor.amazon.auth.service.IMarketplaceService;
import com.wimoor.amazon.auth.service.IRunAmazonService;
import com.wimoor.amazon.report.pojo.entity.ReportType;
import com.wimoor.amazon.report.service.IHandlerReportService;
import com.wimoor.amazon.util.AmzDateUtils;
import com.wimoor.common.GeneralUtil;
import com.wimoor.common.HttpClientUtil;
import com.wimoor.common.mvc.BizException;
import com.wimoor.common.user.UserInfo;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
 

/**
 * 用户业务类
 */
@Service
@RequiredArgsConstructor
public class AmazonAuthorityServiceImpl extends ServiceImpl<AmazonAuthorityMapper, AmazonAuthority> implements IAmazonAuthorityService {
    
    final IMarketplaceService iMarketplaceService;
	@Resource
	private ThreadPoolTaskExecutor threadPoolTaskExecutor;
	@Autowired
	AmazonAuthMarketPerformanceMapper amazonAuthMarketPerformanceMapper;
	@Autowired
	ApiBuildService apiBuildService;
	@Autowired
	@Lazy
	IHandlerReportService handlerReportService;
	@Autowired
    IAmazonSellerMarketService iAmazonSellerMarketService;
	
	final IAmazonGroupService iAmazonGroupService;
	 
	public String getUUID() {
		return this.baseMapper.uuid();
	}
	@Cacheable(value = "AmazonAuthorityCache#6000")
	public AmazonAuthority selectByGroupAndMarket(String groupid,String marketplaceid) {
		if(marketplaceid!=null&&marketplaceid.equals("EU")) {
			QueryWrapper<AmazonAuthority> queryWrapper=new QueryWrapper<AmazonAuthority>();
			queryWrapper.eq("groupid", groupid);
			queryWrapper.eq("disable", Boolean.FALSE);
			queryWrapper.eq("region", "EU");
			List<AmazonAuthority> list = this.baseMapper.selectList(queryWrapper);
			if(list!=null&&list.size()>0) {
				return list.get(0);
			}else {
				return null;
			}
		}else {
			return this.baseMapper.selectByGroupAndMarket(groupid, marketplaceid);
		}
	}

	public List<AmazonAuthority> selectByGroupId(String groupid) {
		QueryWrapper<AmazonAuthority> queryWrapper=new QueryWrapper<AmazonAuthority>();
		queryWrapper.eq("groupid", groupid);
		queryWrapper.eq("disable", Boolean.FALSE);
		return this.baseMapper.selectList(queryWrapper);
	}
	
	private String getRefreshTokenByCode(String code) throws HttpException {
		Map<String,String> body=new HashMap<String,String>();
			body.put("grant_type", "authorization_code");
			body.put("code", code);
			body.put("redirect_uri",apiBuildService.getRedirecturl());
			body.put("client_id",apiBuildService.getClientId());
			body.put("client_secret",apiBuildService.getClientSecret());
	        String response = HttpClientUtil.postUrl(ApiBuildService.endpoint, body,null,URLEncodedUtils.CONTENT_TYPE);
	        JSONObject json = GeneralUtil.getJsonObject(response);    
	        String refresh_token = json.getString("refresh_token");
	        return refresh_token;
	}
	
	public Runnable refreshTokenByAuth(IAmazonAuthorityService self,AmazonAuthority auth) {
	   	 Runnable runnable = new Runnable() {
 			public void run() { 
		          	List<Marketplace> marketlist = iMarketplaceService.findbyauth(auth.getId());
		          	auth.setUseApi("getAuthorizationApiGrantless");
		          	AuthorizationApi api = apiBuildService.getAuthorizationApiGrantless(auth);
		          	String sellingPartnerId=auth.getSellerid();
		          	String developerId=marketlist.get(0).getDevAccountNum();
		          	String mwsAuthToken=auth.getMwsauthtoken();
		       		try {
		       			developerId=developerId.replace("-", "");
		       			developerId=developerId.replace("\n", "");
		  				GetAuthorizationCodeResponse codeResult = api.getAuthorizationCode(sellingPartnerId,developerId, mwsAuthToken);
		  				AuthorizationCode authCode = codeResult.getPayload();
		  				String code=authCode.getAuthorizationCode();
		  			    auth.setRefreshToken(getRefreshTokenByCode(code));
		  			    auth.setRefreshTokenTime(new Date());
		  			    self.updateById(auth);
		  			} catch (ApiException e) {
		  				// TODO Auto-generated catch block
		  				auth.setApiRateLimit(null, e);
		  				e.printStackTrace();
		  			} catch (HttpException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (LWAException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
 			}
  		};
       return runnable;
	}
	
	public void getRefreshTokenByRegion(String region) {
        List<AmazonAuthority> authlist = this.baseMapper.selectByRegion(region);
    	List<Runnable> runlist=new ArrayList<Runnable>();
        for(AmazonAuthority auth:authlist) {
        	if(auth.getRefreshToken()!=null) {
       		     continue;
	       	}else {
	       		auth.setRefreshToken(region);
	       	}
        	Runnable runnable = refreshTokenByAuth(this,auth);
        	runlist.add(runnable);
        }
		executThread(runlist,"RefreshTokenByRegion"+region);
	}
	
	public List<String> getAllRegion() {
		List<String> region=new ArrayList<String>();
		region.add(Regions.EU_WEST_1.getName());
		region.add(Regions.US_EAST_1.getName());
		region.add(Regions.US_WEST_2.getName());
		return region;
	}
	
	public 	List<AmazonAuthority> getAllAuth(String region) {
		return this.baseMapper.getAvailableAuthority(region);
	}
	
	public 	List<AmazonAuthority> getAllAuth() {
		return this.baseMapper.getAvailableAuthority(null);
	}
	
	public void executTask(IRunAmazonService apiService) {
	    List<String> regions = getAllRegion() ;
		List<Runnable> runlist=new ArrayList<Runnable>();
		for(String region:regions) { 
			   List<AmazonAuthority> list = getAllAuth(region);
			      for(AmazonAuthority auth:list) { 
			    	  runlist.add(getRunnable(apiService,auth));
			      }
		}
		String type=apiService.toString().split("@")[0];
		executThread(runlist,type);
	}
	
	public  Runnable getRunnable(IRunAmazonService apiService,AmazonAuthority auth) {
		return new Runnable() {
			public void run() { 
				try{
					apiService.runApi(auth);
				}catch(Exception e){
                    e.printStackTrace();
				}
			}
		};
	}
	public  Runnable getRunnable(IRunAmazonService apiService,String region) {
		return new Runnable() {
			public void run() { 
				      List<AmazonAuthority> list = getAllAuth(region);
				      for(AmazonAuthority auth:list) { 
				    	  apiService.runApi(auth);
				      }
			}
		};
	}

	ConcurrentHashMap<String,Integer> runMap=new ConcurrentHashMap<String,Integer>();
	public  void executThread( List<Runnable> runnables, String  type) {
		if(runnables == null){return;}
		int maxTryNumber=3;
		int waitSecond=600;
		ThreadPoolTaskExecutor threadPoolTaskExecutor= SpringUtil.getBean("threadPoolTaskExecutor");
		Map<Future<?>, Long> startTimeMap = new ConcurrentHashMap<>();
		if(!type.equals("normal")){
			int tryNumber=runMap.get(type)!=null?runMap.get(type):0;
			runMap.put(type, tryNumber+1);
			if (tryNumber>1&&tryNumber<maxTryNumber) {
				return;
			}
		}
		List<Future<?>> futures = new ArrayList<Future<?>>();
		for (Runnable runnable : runnables) {
			if(runnable!=null) {
				Future<?> future = threadPoolTaskExecutor.submit(runnable);
				futures.add(future);
				startTimeMap.put(future, System.currentTimeMillis());
			}
		}
		// 监控线程
		Thread monitorThread = new Thread(() -> {
			while (!futures.isEmpty()) {
				Iterator<Future<?>> iterator = futures.iterator();
				while (iterator.hasNext()) {
					Future<?> future = iterator.next();
					Long startTime = startTimeMap.get(future);
					if (startTime != null &&
							System.currentTimeMillis() - startTime > waitSecond* 1000L &&
							!future.isDone()) {

						System.out.println("检测到任务超时，正在取消...");
						boolean cancelled = future.cancel(true);
						if (cancelled) {
							System.out.println("超时任务取消成功");
							iterator.remove();
							startTimeMap.remove(future);
							if(startTimeMap.isEmpty() &&!type.equals("normal")){
								runMap.remove(type);
							}
						}
					} else if (future.isDone()) {
						// 任务正常完成
						iterator.remove();
						startTimeMap.remove(future);
						try {
							future.get(); // 检查是否有异常
							System.out.println("任务正常完成");
						} catch (CancellationException e) {
							System.out.println("任务被取消");
						} catch (Exception e) {
							System.out.println("任务执行异常: " + e.getMessage());
						}finally {
							if(startTimeMap.isEmpty() &&!type.equals("normal")){
								runMap.remove(type);
							}
						}
					}
				}

				try {
					Thread.sleep(1000); // 每秒检查一次
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		});

		monitorThread.start();

	}


	@Override
	public List<AmazonAuthority> selectByMarket(String marketplaceid) {
		// TODO Auto-generated method stub
		return this.baseMapper.selectByMarket(marketplaceid);
	}
	
	@Override
	public List<AmazonAuthority> selectByShopAndMarket(String shopid,String marketplaceid) {
		// TODO Auto-generated method stub
		return this.baseMapper.selectByShopAndMarket(shopid,marketplaceid);
	}
	
	

	@Override
	public AmazonAuthority selectBySellerId(String sellerid) {
		// TODO Auto-generated method stub
		return this.baseMapper.selectBySellerId(sellerid);
	}
	
	@Override
	@CacheEvict(value = { "AmazonAuthorityCache" }, allEntries = true)
	public AmazonAuthority saveAuth(AmazonAuthority auth) {
		// Seller Id 留空时按「店铺组_区域」生成稳定占位值：既能落库，又保证同一店铺组+区域重复保存幂等、不撞 sellerid 唯一键。
		// 占位值不是亚马逊真实 Seller ID，依赖真实 Seller ID 的拉数接口（订单/库存/广告等）会失败，需补填真实值后重新保存。
		if(StrUtil.isBlank(auth.getSellerid())) {
			String region = StrUtil.isBlank(auth.getRegion()) ? "NA" : auth.getRegion().trim();
			String group = StrUtil.isBlank(auth.getGroupid()) ? "0" : auth.getGroupid().trim();
			String placeholder = "AUTO_" + group + "_" + region;
			auth.setSellerid(placeholder);
			auth.setSaveWarnMsg("Seller Id 未填，已用占位值「" + placeholder
					+ "」保存。此授权可用于拉取站点信息；订单/库存/广告等拉数需补填真实 Seller Id 后重新保存。");
		}
		AmazonAuthority oldAuth=null;
		oldAuth=this.selectBySellerId(auth.getSellerid());
		if(StrUtil.isBlank(auth.getClientId())) {
			throw new BizException("Client Id 不能为空");
		}
		if(StrUtil.isBlank(auth.getClientSecret())) {
			throw new BizException("Client Secret 不能为空");
		}
		if(StrUtil.isBlank(auth.getRefreshToken())) {
			throw new BizException("Refresh Token 不能为空");
		}
		auth.setClientId(auth.getClientId().trim());
		auth.setClientSecret(auth.getClientSecret().trim()); 
		auth.setRefreshToken(auth.getRefreshToken().trim()); 
		auth.setSellerid(auth.getSellerid().trim());
		if(auth.getProxyIp()!=null) {
			auth.setProxyIp(auth.getProxyIp().trim());
		}
	    auth.setProxyPort(auth.getProxyPort());
		AmazonGroup group = this.iAmazonGroupService.getById(auth.getGroupid());
		if(group==null) {
			throw new BizException("未找到对应店铺");
		}
		if(oldAuth!=null) {
			auth.setId(oldAuth.getId());
			auth.setRefreshTokenTime(new Date());
			auth.setOpttime(new Date());
			auth.setDisable(false);
			auth.setStatus(null);
			auth.setStatusupdate(null);
			this.updateById(auth);
			AmazonAuthority newauth = this.getById(oldAuth.getId());
			safeRefreshMarketAndReport(auth, newauth, false);
			return auth;
		}else {
			auth.setRefreshTokenTime(new Date());
			auth.setOpttime(new Date());
			auth.setCreatetime(new Date());
			auth.setDisable(false);
			auth.setStatus(null);
			auth.setStatusupdate(null);
			this.save(auth);
			AmazonAuthority newauth = this.getById(auth.getId());
			safeRefreshMarketAndReport(auth, newauth, true);
			return auth;
		}
	}

	/**
	 * 编辑已有授权的凭证：只更新 ClientId/ClientSecret/RefreshToken 三个字段。
	 * 按主键 id 定位，绝不动 sellerid / groupid / region / awsRegion，避免生成重复授权或区域错配。
	 * 留空的字段保持原值（例如只换 Refresh Token 时无需重填 ClientId/Secret）。
	 * 保存后复用同一套容错逻辑重新拉站点，验证新凭证是否可用。
	 */
	@Override
	@CacheEvict(value = { "AmazonAuthorityCache" }, allEntries = true)
	public AmazonAuthority updateCredentials(String id, String clientId, String clientSecret, String refreshToken) {
		if (StrUtil.isBlank(id)) {
			throw new BizException("授权ID不能为空");
		}
		AmazonAuthority auth = this.getById(id);
		if (auth == null) {
			throw new BizException("未找到对应授权记录");
		}
		boolean changed = false;
		if (StrUtil.isNotBlank(clientId)) {
			auth.setClientId(clientId.trim());
			changed = true;
		}
		if (StrUtil.isNotBlank(clientSecret)) {
			auth.setClientSecret(clientSecret.trim());
			changed = true;
		}
		if (StrUtil.isNotBlank(refreshToken)) {
			auth.setRefreshToken(refreshToken.trim());
			changed = true;
		}
		if (!changed) {
			throw new BizException("请至少填写一项要更新的凭证（Client Id / Client Secret / Refresh Token）");
		}
		auth.setRefreshTokenTime(new Date());
		auth.setOpttime(new Date());
		this.updateById(auth);
		AmazonAuthority newauth = this.getById(id);
		safeRefreshMarketAndReport(auth, newauth, false);
		return auth;
	}

	/**
	 * 在「编辑店铺」中保存 SP-API 凭证：按店铺组 + 区域判断。
	 * 已有授权 → 只更新非空的凭证字段（SellerId/店铺/区域不动）；
	 * 无授权 → 按店铺组 + 区域新建授权（SellerId 留空则由 saveAuth 生成占位值），并拉站点验证。
	 * 这样无论此前是否已授权，用户在编辑店铺弹窗填凭证都能生效。
	 */
	@Override
	@CacheEvict(value = { "AmazonAuthorityCache", "authmarketplaceListCache" }, allEntries = true)
	public AmazonAuthority saveGroupCredentials(AmazonAuthority auth) {
		if (auth == null || StrUtil.isBlank(auth.getGroupid())) {
			throw new BizException("店铺不能为空");
		}
		AmazonGroup group = this.iAmazonGroupService.getById(auth.getGroupid());
		if (group == null) {
			throw new BizException("未找到对应店铺");
		}
		if (StrUtil.isBlank(auth.getRegion())) {
			// 前端未给 region 时按 awsRegion 推导，兜底 NA
			if (StrUtil.isNotBlank(auth.getAWSRegion())) {
				auth.setRegion(apiBuildService.getRegion(auth.getAWSRegion()));
			}
			if (StrUtil.isBlank(auth.getRegion())) {
				auth.setRegion("NA");
			}
		}
		String region = auth.getRegion().trim();
		// 不用 selectByGroupAndRegion(内部 selectOne)：历史脏数据可能导致同店铺+区域多条而抛异常。
		// 用 list 过滤无效 sellerid（none/空），优先取真实授权记录，兼容脏数据。
		QueryWrapper<AmazonAuthority> existQ = new QueryWrapper<AmazonAuthority>();
		existQ.eq("groupid", auth.getGroupid());
		existQ.eq("region", region);
		existQ.eq("disable", Boolean.FALSE);
		existQ.isNotNull("sellerid");
		existQ.ne("sellerid", "none");
		existQ.ne("sellerid", "");
		existQ.orderByAsc("id");
		List<AmazonAuthority> existList = this.baseMapper.selectList(existQ);
		AmazonAuthority exist = (existList != null && !existList.isEmpty()) ? existList.get(0) : null;
		if (exist != null) {
			// 已有授权：只更新非空凭证字段
			boolean changed = false;
			if (StrUtil.isNotBlank(auth.getClientId())) { exist.setClientId(auth.getClientId().trim()); changed = true; }
			if (StrUtil.isNotBlank(auth.getClientSecret())) { exist.setClientSecret(auth.getClientSecret().trim()); changed = true; }
			if (StrUtil.isNotBlank(auth.getRefreshToken())) { exist.setRefreshToken(auth.getRefreshToken().trim()); changed = true; }
			if (!changed) {
				throw new BizException("请至少填写一项要更新的凭证（Client Id / Client Secret / Refresh Token）");
			}
			exist.setRefreshTokenTime(new Date());
			exist.setOpttime(new Date());
			this.updateById(exist);
			AmazonAuthority fresh = this.getById(exist.getId());
			safeRefreshMarketAndReport(exist, fresh, false);
			return exist;
		}
		// 无授权：新建（沿用 saveAuth 的占位 SellerId + 拉站点 + 初始报告逻辑）
		return this.saveAuth(auth);
	}

	/**
	 * 授权落库后拉取站点/报告的容错处理：
	 * 保存本身已成功，拉取失败只回传警告，不再抛异常中断保存（避免误判为授权失败）。
	 */
	private void safeRefreshMarketAndReport(AmazonAuthority auth, AmazonAuthority newauth, boolean requestReports) {
		try {
			iAmazonSellerMarketService.refreshMarketByAuth(newauth);
		} catch (Exception e) {
			e.printStackTrace();
			appendWarn(auth, "拉取站点信息失败（" + shortMsg(e.getMessage())
					+ "）。请核对 LWA 凭证 / Refresh Token / 站点区域后重试。");
			return;
		}
		if (requestReports) {
			try {
				Calendar cstart = Calendar.getInstance();
				cstart.add(Calendar.DATE, -30);
				Calendar cend = Calendar.getInstance();
				handlerReportService.requestReport(auth.getId(), ReportType.OrdersByOrderDateReport, cstart, cend, false);
				handlerReportService.requestAuthReport("id", auth.getId(), ReportType.ProductListings, false);
				handlerReportService.requestAuthReport("id", auth.getId(), ReportType.InventoryReport, false);
			} catch (Exception e) {
				e.printStackTrace();
				appendWarn(auth, "发起初始报告失败（" + shortMsg(e.getMessage())
						+ "）。可稍后在报告页手动重新发起。");
			}
		}
	}

	/** 追加警告，不覆盖已有的（如 Seller Id 占位提示） */
	private void appendWarn(AmazonAuthority auth, String msg) {
		String old = auth.getSaveWarnMsg();
		auth.setSaveWarnMsg(StrUtil.isBlank(old) ? ("授权已保存成功；" + msg) : (old + " 另：" + msg));
	}

	private String shortMsg(String msg) {
		if (StrUtil.isBlank(msg)) {
			return "无详细错误";
		}
		return msg.length() > 160 ? msg.substring(0, 160) : msg;
	}
	

	@Override
	@CacheEvict(value = { "AmazonAuthorityCache","authmarketplaceListCache" }, allEntries = true)
	public AmazonAuthority authSeller(String state, String selling_partner_id, String mws_auth_token,
			String spapi_oauth_code) {
		// TODO Auto-generated method stub
		AmazonAuthority auth=new AmazonAuthority();
		auth.setSellerid(selling_partner_id);
		auth.setMwsauthtoken(mws_auth_token);
		String[] groupRegion = state.split("@");
		auth.setGroupid(groupRegion[0]);
		auth.setAWSRegion(groupRegion[1]);
		auth.setRegion(apiBuildService.getRegion(auth.getAWSRegion()));
		AmazonGroup group = this.iAmazonGroupService.getById(auth.getGroupid());
		if(group==null) {
			throw new BizException("未找到对应店铺");
		}
		auth.setShopId(group.getShopid());
		try {
			String refreshcode = this.getRefreshTokenByCode(spapi_oauth_code);
			auth.setRefreshToken(refreshcode);
			AmazonAuthority oldAuth=this.selectBySellerId(selling_partner_id);
			if(oldAuth!=null) {
				oldAuth.setGroupid(auth.getGroupid());
				oldAuth.setAWSRegion(auth.getAWSRegion());
				oldAuth.setMwsauthtoken(auth.getMwsauthtoken());
				oldAuth.setRefreshToken(auth.getRefreshToken());
				oldAuth.setRefreshTokenTime(new Date());
				oldAuth.setShopId(group.getShopid());
				oldAuth.setDisable(false);
				oldAuth.setOpttime(new Date());
				oldAuth.setStatus(null);
				oldAuth.setStatusupdate(null);
				this.updateById(oldAuth);
				iAmazonSellerMarketService.refreshMarketByAuth(auth);
				return oldAuth;
			}else {
				auth.setRefreshTokenTime(new Date());
				auth.setOpttime(new Date());
				auth.setCreatetime(new Date());
				auth.setDisable(false);
				auth.setStatus(null);
				auth.setStatusupdate(null);
				this.save(auth);
				iAmazonSellerMarketService.refreshMarketByAuth(auth);
				Calendar cstart = Calendar.getInstance();
				cstart.add(Calendar.DATE, -30);
				Calendar cend = Calendar.getInstance();
				handlerReportService.requestAuthReport("id",auth.getId(),ReportType.ProductListings,false); 
				handlerReportService.requestReport(auth.getId(),ReportType.OrdersByOrderDateReport,cstart,cend,false); 
				handlerReportService.requestAuthReport("id",auth.getId(),ReportType.InventoryReport,false); 
				return auth;
			}
		} catch (HttpException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			if(e!=null&&e.getMessage()!=null) {
				throw new BizException("授权异常:"+e.getMessage());
			}else {
				throw new BizException("授权异常");
			}
		}
	}
	
	@Override
	public String getAuthUrl(String groupid, String marketplaceid) {
		// TODO Auto-generated method stub
    	Marketplace market = iMarketplaceService.getById(marketplaceid);
    	String result="";
    	if(market.getAwsRegion().equals("eu-west-1")){
    		result="https://sellercentral-europe.amazon.com";
    	}else if(market.getMarket().equals("JP")){
    		result="https://sellercentral-japan.amazon.com";
    	}else {
    		result="https://sellercentral."+market.getPointName().toLowerCase();
    	}
    	result+="/apps/authorize/consent?application_id="+apiBuildService.getAppid()+"&state="+groupid+"@"+market.getAwsRegion();
    	result+="&redirect_uri="+apiBuildService.getRedirecturl();
		return result;
	}

	@Override
	@CacheEvict(value = { "AWSmarketplaceListCache", "ApiCache","AmazonAuthorityCache" }, allEntries = true)
	public boolean updateById(AmazonAuthority entity) {
		// TODO Auto-generated method stub
		return super.updateById(entity);
	}

	@Override
	public AmazonAuthMarketPerformance getPerformance(AmazonAuthority auth, String marketplaceid) {
		QueryWrapper<AmazonAuthMarketPerformance> query= new QueryWrapper<AmazonAuthMarketPerformance>();
		query.eq("amazonauthid", auth.getId());
		query.eq("marketplaceid", marketplaceid);
		AmazonAuthMarketPerformance perf = amazonAuthMarketPerformanceMapper.selectOne(query);
		return perf;
	}
   

	@Override
	public List<AmazonGroupVO> selectBindAuth(UserInfo user) {
		String shopid=user.getCompanyid();
		QueryWrapper<AmazonGroup> queryWrapper=new QueryWrapper<AmazonGroup>();
		queryWrapper.eq("shopid", shopid);
		queryWrapper.eq("isdelete", false);
		queryWrapper.orderByAsc("findex");
		List<AmazonGroup> grouplist=iAmazonGroupService.list(queryWrapper);
		List<AmazonGroupVO> result=new ArrayList<AmazonGroupVO>();
		Map<String, Marketplace> marketMap = iMarketplaceService.findMapByMarketplaceId();
		if(grouplist!=null&&grouplist.size()>0) {
           for(AmazonGroup groupitem:grouplist) {
        	   List<AmazonAuthority> authlist = this.selectByGroupId(groupitem.getId());
        	   List<Map<String,Object>> authAdvlist = this.baseMapper.selectAdvByGroupId(groupitem.getId());
        	   Map<String,AmazonRegionVO> regions=new HashMap<String,AmazonRegionVO>(); 
        	   for(AmazonAuthority authItem:authlist) {
                   String regionName="";
                   String regionCode="";
        		   //
        		   Map<String,AmazonMarketVO> countrys=new HashMap<String,AmazonMarketVO>();
        		   QueryWrapper<AmazonSellerMarket> querymarket =new QueryWrapper<AmazonSellerMarket>();
        		   querymarket.eq("sellerid", authItem.getSellerid());
				   List<AmazonSellerMarket> marketlist =iAmazonSellerMarketService.list(querymarket);
				   for(AmazonSellerMarket marketItem:marketlist) {
					   AmazonMarketVO country = new AmazonMarketVO();
					   Marketplace marketplace = marketMap.get(marketItem.getMarketplaceId());
					   if(marketplace==null)continue;
					   country.setCountrycode(marketplace.getMarket());
					   country.setCountryname(marketplace.getName());
					   regionName=marketplace.getRegionName();
					   country.setDisable(marketItem.getDisable());
					   country.setMarketplaceid(marketItem.getMarketplaceId());
					   countrys.put(marketItem.getMarketplaceId()+authItem.getSellerid(), country);
				   }
        		    if(countrys.size()>0) {
        				AmazonRegionVO region=new AmazonRegionVO();
        				region.setAuthid(authItem.getId());
        				region.setCountrys(countrys);
        				region.setRegion(regionCode);
        				if(authItem.getStatus()==null) {
        					region.setSellerauth("1");
        				}else {
        					region.setSellerauth("0");
        				}
        				region.setRegionname(regionName);
        				region.setSellerid(authItem.getSellerid());
        				region.setTime(authItem.getRefreshTokenTime());
        				region.setAdauth("2");
        				region.setAdvid(null);
        		    	regions.put(authItem.getSellerid(),region);
        		    }
        	   }
         	   for(Map<String,Object> authAdvItem:authAdvlist) {
         		   String key=null;
         		   if(authAdvItem.get("region")==null) {
         			   continue;
         		   }
         		   key=authAdvItem.get("region").toString();
         		   String regionName=null;
         		   if(key.equals("NA")) {
         			   key="us-east-1";
         		   }else if(key.equals("EU")) {
         			   key="eu-west-1";
         		   }else {
         			   key="us-west-2";
         		   }
         		  AmazonRegionVO region=null;
         		
         		   String sellerid=null;
         		   List<Map<String, Object>> advmarketList = this.baseMapper.selectAdvMarketByAdvAuthId(authAdvItem.get("id").toString());
       		       for(Map<String, Object> advcountry:advmarketList) {
       		    	   String marketplaceid=advcountry.get("marketplaceId").toString();
       		    	   String countrykey=advcountry.get("marketplaceId").toString()+advcountry.get("sellerId").toString();
       		    	   sellerid=advcountry.get("sellerId").toString();
       		    	   Marketplace marketplace = marketMap.get(marketplaceid);
						  if(marketplace==null){
							  continue;
						  }
       		    	   regionName=marketplace.getRegionName();
       		    	     AmazonMarketVO country =null;
       		    	     region=regions.get(sellerid);
       		    	    	 if(region!=null) {
    	          			     region.setAdvid(authAdvItem.get("id").toString());
    	          				 region.setAdauth(authAdvItem.get("adauth").toString());
								 region.setAdvtime(AmzDateUtils.getDate(authAdvItem.get("createtime")));
    	          		     }else {
    	          			    region=new AmazonRegionVO();
    	        				region.setRegion(authAdvItem.get("region").toString());
    	        				region.setSellerauth("2");
    	        				region.setRegionname(null);
    	        				region.setSellerid(null);
    	        			    region.setAdvid(authAdvItem.get("id").toString());
    	  				        region.setAdauth(authAdvItem.get("adauth").toString());
    	        				region.setAdvtime(AmzDateUtils.getDate(authAdvItem.get("createtime")));
    	        				regions.put(sellerid, region);
    	          		     }
       		    	   
	       		    	 
       		    	   if(region.getCountrys()!=null) {
       		    		    country =region.getCountrys().get(countrykey);
         		    	  }
       		    	     if(country==null) {
       		    	    	country=new AmazonMarketVO();
  					        country.setCountrycode(marketplace.getMarket());
  					        country.setCountryname(marketplace.getName());
  					        country.setMarketplaceid(countrykey);
  					        country.setSellerid(advcountry.get("sellerId").toString());
  					        if(region.getCountrys()!=null) {
  					        	region.getCountrys().put(countrykey, country);
  					        }else {
  					        	Map<String,AmazonMarketVO> countrys=new HashMap<String,AmazonMarketVO>();
  					        	countrys.put(countrykey, country);
  					        	region.setCountrys(countrys);
  					        }
       		    	     } 
       		       }
       		      if(region!=null&&region.getSellerid()==null) {
       		    	region.setSellerid(sellerid);
       		      }
       		      if(region!=null&&region.getRegionname()==null) {
       		    	  region.setRegionname(regionName);
       		      }
         		  
       	        }
        	   if(regions!=null&&regions.size()>0) {
        		   AmazonGroupVO groupvo=new AmazonGroupVO();
        		   groupvo.setGname(groupitem.getName());
        		   groupvo.setGroupid(groupitem.getId());
        		   groupvo.setRegions(regions);
        		   result.add(groupvo);
        	   }
           }
		 }
		return result;
	}


	@Override
	@CacheEvict(value = { "AmazonAuthorityCache" }, allEntries = true)
	public int deleteByLogic(Map<String, Object> param) {
		int result=0;
		QueryWrapper<AmazonSellerMarket> queryWrapper=new QueryWrapper<AmazonSellerMarket>();
		queryWrapper.eq("sellerid", param.get("sellerid").toString());
		List<AmazonSellerMarket> list = iAmazonSellerMarketService.list(queryWrapper);
		if(list!=null && list.size()>0) {
			for(AmazonSellerMarket item:list) {
				param.put("sellerid", item.getSellerid());
				param.put("marketplaceId", item.getMarketplaceId());
				result+=this.baseMapper.deleteByLogic(param);
			}
		}
		return result;
	}

	@Override
	public AmazonAuthority selectByGroupAndRegion(String groupid, String region) {
		// TODO Auto-generated method stub
		QueryWrapper<AmazonAuthority> queryWrapper=new QueryWrapper<AmazonAuthority>();
		queryWrapper.eq("groupid", groupid);
		queryWrapper.eq("region", region);
		queryWrapper.eq("disable", Boolean.FALSE);
		return this.baseMapper.selectOne(queryWrapper);
	}

	@Override
	@Cacheable(value = "AmazonAuthorityCache#6000")
	public AmazonAuthority getById(Serializable id) {
		// TODO Auto-generated method stub
		return super.getById(id);
	}

	@Override
	public List<AmazonAuthority> selectByshopid(String companyid) {
		// TODO Auto-generated method stub
		return this.baseMapper.selectByshopid(companyid);
	}
	
	
	@Override
	public List<Map<String,Object>> selectAuthMaketplace(String shopid) {
		// TODO Auto-generated method stub
		return this.baseMapper.selectAuthMaketplace(shopid);
	}

	@Override
	public List<String> getAllBossEmalShop() {
		// TODO Auto-generated method stub
		return this.baseMapper.getAllBossEmalShop();
	}

 
	


}
package com.wimoor.common.service.impl;

import java.util.Date;

import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

import com.wimoor.common.result.Result;
import com.wimoor.util.SpringUtil;

import lombok.extern.slf4j.Slf4j;
@Slf4j
public class QuartzTaskFactory extends QuartzJobBean{
 
	@Override
	protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
		try {
			   JobDetail detail = context.getJobDetail();
			   Object jobPath = detail.getJobDataMap().get("jobPath");
			   Object parameter = detail.getJobDataMap().get("parameter");
			   Object description= detail.getJobDataMap().get("description");
			   log.info("\n\n\n"+description+jobPath.toString()+":rundate======>"+new Date()+"===>"+parameter);
			   // 统一走带登录态的调用：后台拉取端点都在网关鉴权之后，裸调必然 403
			   SchedulerApiUtil api = SpringUtil.getBean(SchedulerApiUtil.class);
			   Result<?> result = api.get(jobPath.toString());
			   if(result!=null) {
				   log.info("result-code:"+result.getCode());
			   }
			   log.info(description+":enddate================="+new Date());
		}catch(Exception e){
			e.printStackTrace();
			log.info("出错了:"+e.getMessage());
		} 
	}

}

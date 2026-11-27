package com.wimoor.sys.tool.controller;

import com.wimoor.admin.common.exception.BizException;
import com.wimoor.common.pojo.entity.QuartzJobsVO;
import com.wimoor.common.pojo.entity.QuartzTask;
import com.wimoor.util.SpringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.wimoor.common.result.Result;
import com.wimoor.common.service.SystemSchedulerService;

import java.util.Date;
import java.util.List;


@RestController
@RequestMapping("/api/v1/task")
public class SysTaskController {
   @Autowired
   SystemSchedulerService systemSchedulerService;

    @GetMapping("/listScheduler")
    public Result<List<QuartzJobsVO>> listSchedulerAction() {
        List<QuartzJobsVO> list = systemSchedulerService.listScheduler();
        return Result.success(list);
    }

    @GetMapping("/puaseScheduler")
    public Result<?> puaseSchedulerAction(String jobDetailName, String jobDetailGroup) {
        return Result.success(systemSchedulerService.puaseScheduler(jobDetailName, jobDetailGroup));
    }

    @GetMapping("/resumeScheduler")
    public Result<?> resumeSchedulerAction(String jobDetailName, String jobDetailGroup) {
        return Result.success(systemSchedulerService.resumeScheduler(jobDetailName, jobDetailGroup));
    }

    @GetMapping("/deleteScheduler")
    public Result<?> deleteSchedulerAction(String jobDetailName, String jobDetailGroup) {
        return Result.success(systemSchedulerService.deleteScheduler(jobDetailName, jobDetailGroup));
    }

    @GetMapping("/refresh")
    public Result<?> refresh() {
    	systemSchedulerService.refreshTask();
    	return Result.success();
    }

    @GetMapping("/getTaskList")
    public Result<List<QuartzTask>> getTaskAction() {
        List<QuartzTask> list = systemSchedulerService.getTaskList();
        return Result.success(list);
    }

    @PostMapping("/enableApi")
    public Result<?> enableApiAction(@RequestBody QuartzTask task){
        systemSchedulerService.enableApi(task);
        return Result.success();
    }

    @PostMapping("/disableApi")
    public Result<?> disableApiAction(@RequestBody QuartzTask task){
        systemSchedulerService.disableApi(task);
        return Result.success();
    }


    @PostMapping("/saveQuartzTask")
    public Result<?> saveQuartzTaskAction(@RequestBody QuartzTask task){
        systemSchedulerService.saveQuartzTask(task);
        return Result.success();
    }

    @PostMapping("/addScheduler")
    public Result<?> addSchedulerAction(@RequestBody QuartzTask task){
        systemSchedulerService.addScheduler(task);
        return Result.success();
    }

    /**
     * 执行API操作的控制器方法。
     * 通过 QuartzTask 对象接收任务相关信息，并尝试执行该任务。
     * 主要用于调度系统中触发指定路径的 API 执行，并记录执行信息。
     *
     * @param task 任务信息对象，包含 API 路径、参数和描述等信息。
     * @return 返回执行结果信息。
     */
    @PostMapping("/runApi")
    public Result<?> runApiAction(@RequestBody QuartzTask task){
        String message = ""; // 用于记录任务执行的信息。
        try {
            // 获取任务的路径、参数和描述信息。
            Object jobPath = task.getPath();
            Object parameter = task.getParameter();
            Object description = task.getDescription();
            // 记录任务执行开始相关描述信息。
            message = message + (description + ":rundate=================>" + new Date() + "============>" + parameter);
            // 统一走带登录态的调用：后台拉取端点都在网关鉴权之后，裸调必然 403
            com.wimoor.common.service.impl.SchedulerApiUtil api = SpringUtil.getBean(com.wimoor.common.service.impl.SchedulerApiUtil.class);
            Result<?> result = api.get(jobPath.toString());
            // 如果调用结果不为空，记录结果码到信息中。
            if (result != null) {
                message = message + ("result-code:" + result.getCode());
            }
            // 记录任务执行结束相关描述信息。
            message = message + (description + ":enddate=================" + new Date());
        } catch (Exception e) {
            // 在方法执行过程中出现任何异常，打印异常堆栈并抛出自定义异常。
            e.printStackTrace();
            throw new BizException("出错了:" + e.getMessage());
        }
        // 返回任务执行的信息结果。
        return Result.success(message);
    }

}

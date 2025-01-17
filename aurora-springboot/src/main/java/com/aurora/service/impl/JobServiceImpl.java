package com.aurora.service.impl;

import com.aurora.constant.ScheduleConst;
import com.aurora.dto.JobDTO;
import com.aurora.entity.Job;
import com.aurora.mapper.JobMapper;
import com.aurora.service.JobService;
import com.aurora.utils.BeanCopyUtils;
import com.aurora.utils.CronUtils;
import com.aurora.utils.PageUtils;
import com.aurora.utils.ScheduleUtils;
import com.aurora.vo.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.SneakyThrows;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class JobServiceImpl extends ServiceImpl<JobMapper, Job> implements JobService {

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JobMapper jobMapper;

    @SneakyThrows
    @PostConstruct
    public void init() {
        scheduler.clear();
        List<Job> jobs = jobMapper.selectList(null);
        for (Job job : jobs) {
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
    }

    @SneakyThrows
    @Transactional(rollbackFor = Exception.class)
    public void saveJob(JobVO jobVO) {
        checkCronIsValid(jobVO);
        Job job = BeanCopyUtils.copyObject(jobVO, Job.class);
        int row = jobMapper.insert(job);
        if (row > 0) ScheduleUtils.createScheduleJob(scheduler, job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateJob(JobVO jobVO) {
        checkCronIsValid(jobVO);
        Job temp = jobMapper.selectById(jobVO.getId());
        Job job = BeanCopyUtils.copyObject(jobVO, Job.class);
        int row = jobMapper.updateById(job);
        if (row > 0) updateSchedulerJob(job, temp.getJobGroup());
    }

    @SneakyThrows
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJobs(List<Integer> tagIds) {
        List<Job> jobs = jobMapper.selectList(new LambdaQueryWrapper<Job>().in(Job::getId, tagIds));
        int row = jobMapper.delete(new LambdaQueryWrapper<Job>().in(Job::getId, tagIds));
        if (row > 0) {
            jobs.forEach(item -> {
                try {
                    scheduler.deleteJob(ScheduleUtils.getJobKey(item.getId(), item.getJobGroup()));
                } catch (SchedulerException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Override
    public JobDTO getJobById(Integer jobId) {
        Job job = jobMapper.selectById(jobId);
        JobDTO jobDTO = BeanCopyUtils.copyObject(job, JobDTO.class);
        Date nextExecution = CronUtils.getNextExecution(jobDTO.getCronExpression());
        jobDTO.setNextValidTime(nextExecution);
        return jobDTO;
    }

    @SneakyThrows
    @Override
    public PageResult<JobDTO> listJobs(JobSearchVO jobSearchVO) {
        CompletableFuture<Integer> asyncCount = CompletableFuture.supplyAsync(() -> jobMapper.countJobs(jobSearchVO));
        List<JobDTO> jobDTOs = jobMapper.listJobs(PageUtils.getLimitCurrent(), PageUtils.getSize(), jobSearchVO);
        return new PageResult<>(jobDTOs, asyncCount.get());
    }

    @SneakyThrows
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateJobStatus(JobStatusVO jobStatusVO) {
        Job job = jobMapper.selectById(jobStatusVO.getId());
        if (job.getStatus().equals(jobStatusVO.getStatus())) {
            return;
        }
        Integer status = jobStatusVO.getStatus();
        Integer jobId = job.getId();
        String jobGroup = job.getJobGroup();
        LambdaUpdateWrapper<Job> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Job::getId, jobStatusVO.getId()).set(Job::getStatus, status);
        int row = jobMapper.update(null, updateWrapper);
        if (row > 0) {
            if (ScheduleConst.Status.NORMAL.getValue().equals(status)) {
                scheduler.resumeJob(ScheduleUtils.getJobKey(jobId, jobGroup));
            } else if (ScheduleConst.Status.PAUSE.getValue().equals(status)) {
                scheduler.pauseJob(ScheduleUtils.getJobKey(jobId, jobGroup));
            }
        }
    }

    @SneakyThrows
    @Override
    public void runJob(JobRunVO jobRunVO) {
        Integer jobId = jobRunVO.getId();
        String jobGroup = jobRunVO.getJobGroup();
        scheduler.triggerJob(ScheduleUtils.getJobKey(jobId, jobGroup));
    }

    @Override
    public List<String> listJobGroups() {
        return jobMapper.listJobGroups();
    }

    /**
     * 校验cron表达式的合法性
     */
    private void checkCronIsValid(JobVO jobVO) {
        boolean valid = CronUtils.isValid(jobVO.getCronExpression());
        Assert.isTrue(valid, "Cron表达式无效!");
    }

    /**
     * 更新任务
     */
    @SneakyThrows
    public void updateSchedulerJob(Job job, String jobGroup) {
        Integer jobId = job.getId();
        // 判断是否存在
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
        if (scheduler.checkExists(jobKey)) {
            // 防止创建时存在数据问题 先移除，然后在执行创建操作
            scheduler.deleteJob(jobKey);
        }
        ScheduleUtils.createScheduleJob(scheduler, job);
    }
}

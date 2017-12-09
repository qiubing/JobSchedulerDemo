package com.example.jobschedulerdemo;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * Description:
 * Author: qiubing
 * Date: 2017-12-09 15:07
 */
public class MyJobService extends JobService {
    private static final String TAG = "JobSchedulerDemo";

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        Log.e(TAG,"onStartJob()...jobParameters = " + jobParameters);
        doMyJob();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        Log.e(TAG,"onStopJob()....jobParameters = " + jobParameters);
        stopMyJob();
        return false;
    }

    /***
     * 如果是耗时操作，则需要让任务在子线程中执行
     */
    private void doMyJob(){
        Log.e(TAG,"doMyJob()...");
    }

    private void stopMyJob(){
        Log.e(TAG,"stopMyJob()...");
    }
}

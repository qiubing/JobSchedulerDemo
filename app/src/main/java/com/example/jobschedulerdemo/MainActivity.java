package com.example.jobschedulerdemo;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import java.util.concurrent.TimeUnit;


public class MainActivity extends Activity {
    private static final String TAG = "JobSchedulerDemo";
    private static final int JOB_ID = 1;
    private Button mScheduleBtn;
    private Button mCancelBtn;
    private JobScheduler mJobScheduler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mJobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        mScheduleBtn = (Button) findViewById(R.id.schedule);
        mScheduleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startJobSchedulerService();
            }
        });
        mCancelBtn = (Button) findViewById(R.id.cancel);
        mCancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelJobScheduleService();
            }
        });
    }

    private void startJobSchedulerService(){
        Log.e(TAG,"startJobSchedulerService()...");
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,new ComponentName(this,MyJobService.class));//指定那个JobService执行操作
        builder.setMinimumLatency(TimeUnit.MILLISECONDS.toMillis(10))//执行的最小延迟时间
                .setOverrideDeadline(TimeUnit.MILLISECONDS.toMillis(15))//执行的最长等待时间，如果执行条件不满足也执行
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) //指定网络状态
                .setBackoffCriteria(TimeUnit.MINUTES.toMillis(10),JobInfo.BACKOFF_POLICY_LINEAR)// 指定线性重试方案
                .setRequiresCharging(false);// 指定未充电状态
        mJobScheduler.schedule(builder.build());
    }

    private void cancelJobScheduleService(){
        Log.e(TAG,"cancelJobScheduleService()...");
        mJobScheduler.cancel(JOB_ID);
    }
}

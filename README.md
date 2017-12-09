# JobSchedulerDemo

### 1.概述

JobScheduler主要用于在未来某个时间下满足一定条件时触发执行某项任务的情况，那么可以创建一个JobService的子类，重写其onStartJob()方法来实现这个功能。

JobScheduler提供了接口来调度各种类型的工作，在应用进程中执行。

JobInfo里面包含了很多关于工作的信息，可以将JobInfo传递给JobScheduler的schedule()方法。当声明的条件满足时，系统将会在应用进程中执行该工作。

当在创建JobInfo时，可以指定使用哪个JobService来执行工作。

Framework会知道你什么时候接收回调，并且尽可能尝试批量和延迟执行这些工作。典型的，如果你没有特别声明一个工作截止期限，那么工作将可能在任何时候被执行，这个依赖于JobScheduler内部队列的当前状态。然而，工作也可能被延迟到下一次设备连接到电源时执行。

JobScheduler不能直接初始化，而应该通过Context.getSystemService(Context.JOB\_SCHEDULER\_SERVICE）方法获取。

JobScheduler的定义如下：

	public abstract class JobScheduler {
		/*
		* schedule(JobInfo)的返回值；
		* 当输入参数无效时，则返回RESULT_FAILURE。这种情况会出现在工作执行时间太短了，或者系统不能解析JobService服务。
		*/
		public static final int RESULT_FAILURE = 0;
		/*
		* schedule(JobInfo)的返回值；
		* 如果该工作被成功执行后，返回RESULT_SUCCESS。
		*/
		public static final int RESULT_SUCCESS = 1;

		public abstract int schedule(JobInfo job);

		public abstract int scheduleAsPackage(JobInfo job, String packageName, int userId, String tag);
		
		public abstract void cancel(int jobId);

		public abstract void cancelAll();

		public abstract @NonNull List<JobInfo> getAllPendingJobs();

		public abstract @Nullable JobInfo getPendingJob(int jobId);
	}

JobScheduler是一个抽象类，具体的实现类是JobSchedulerImpl类。

### 2.JobScheduler服务的初始化

JobScheduler服务的启动是从SystemServer的startOtherServices()方法开始的。

	private void startOtherServices() {
		......
		mSystemServiceManager.startService(JobSchedulerService.class);
		......
	}

JobSchedulerService服务继承自SystemService服务，在被启动的时候，会执行onStart()方法。

**2.1 SystemServiceManager.startService()**

	public <T extends SystemService> T startService(Class<T> serviceClass) {
		try {
            final String name = serviceClass.getName();
			
            // Create the service.
            ......
            final T service;
            try {
                Constructor<T> constructor = serviceClass.getConstructor(Context.class);
                service = constructor.newInstance(mContext);//获取构造器
            } catch (InstantiationException ex) {
                ......
            }

            // Register it.
            mServices.add(service);

            // Start it.
            try {
                service.onStart();//调用服务的onStart()方法
            } catch (RuntimeException ex) {
                throw new RuntimeException("Failed to start service " + name
                        + ": onStart threw an exception", ex);
            }
            return service;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
	}
可以看到，SystemServiceManager的startService()方法的主要作用是创建一个服务对象实例，并调用服务对象实例的onStart()方法。

**2.2 JobSchedulerService.onStart()**

	public void onStart() {
        publishLocalService(JobSchedulerInternal.class, new LocalService());//将JobSchedulerService里的LocalService发布到本地服务中，只能被system进程访问该服务
        publishBinderService(Context.JOB_SCHEDULER_SERVICE, mJobSchedulerStub);//发布jobscheduler服务，这样其他服务和应用可以访问该服务
    }
在onStart()方法中，主要是完成以下两件事情：

1. 将JobSchedulerService里的LocalService发布到本地服务中，该服务只能被system进程访问；
2. 将jobscheduler服务发布到SystemManager中，这样其他服务和应用可以访问该服务。
	
LocalService对应的实现如下：

	final class LocalService implements JobSchedulerInternal {

        /**
         * Returns a list of all pending jobs. A running job is not considered pending. Periodic
         * jobs are always considered pending.
         */
        @Override
        public List<JobInfo> getSystemScheduledPendingJobs() {
            synchronized (mLock) {
                final List<JobInfo> pendingJobs = new ArrayList<JobInfo>();
                mJobs.forEachJob(Process.SYSTEM_UID, new JobStatusFunctor() {
                    @Override
                    public void process(JobStatus job) {
                        if (job.getJob().isPeriodic() || !isCurrentlyActiveLocked(job)) {
                            pendingJobs.add(job.getJob());
                        }
                    }
                });
                return pendingJobs;
            }
        }
    }

LocalService实现了JobSchedulerInternal接口，主要是返回所有待处理的工作。该服务是添加到本地服务列表中的，只能被system进程访问。

jobscheduler服务对应的实现是mJobSchedulerStub，它是在构建JobSchedulerService服务时被初始化的。
	

**2.3 JobSchedulerService()**

	public JobSchedulerService(Context context) {
        super(context);
        mHandler = new JobHandler(context.getMainLooper());//构建一个主线程的Handler
        mConstants = new Constants(mHandler);
        mJobSchedulerStub = new JobSchedulerStub();//初始化JobSchedulerStub
        mJobs = JobStore.initAndGet(this);//初始化JobStore，见2.4

        // Create the controllers.
        mControllers = new ArrayList<StateController>();
        mControllers.add(ConnectivityController.get(this));//创建连接状态Controller
        mControllers.add(TimeController.get(this));//创建时间Controller
        mControllers.add(IdleController.get(this));//创建空闲Controller
        mControllers.add(BatteryController.get(this));//创建电池状态Controller
        mControllers.add(AppIdleController.get(this));//创建应用空闲Controller
        mControllers.add(ContentObserverController.get(this));
        mControllers.add(DeviceIdleJobsController.get(this));//创建设备空闲Controller
    }

JobSchedulerStub继承了IJobScheduler.Stub类，作为实现接口IJobScheduler的binder服务端。

**2.4 JobStore.initAndGet()**

	static JobStore initAndGet(JobSchedulerService jobManagerService) {
		//单例模式
        synchronized (sSingletonLock) {
            if (sSingleton == null) {
                sSingleton = new JobStore(jobManagerService.getContext(),
                        jobManagerService.getLock(), Environment.getDataDirectory());//见2.5
            }
            return sSingleton;
        }
    }

JobStore的构造采取的是单例模式，全局只会创建一个对象实例。

**2.5 JobStore()**

	private JobStore(Context context, Object lock, File dataDir) {
        mLock = lock;
        mContext = context;
        mDirtyOperations = 0;

        File systemDir = new File(dataDir, "system");//system目录
        File jobDir = new File(systemDir, "job");//job目录
        jobDir.mkdirs();
        mJobsFile = new AtomicFile(new File(jobDir, "jobs.xml"));// 创建一个jobs.xml文件

        mJobSet = new JobSet();//创建一个JobSet对象

        readJobMapFromDisk(mJobSet);//见2.6
    }
在构造JobStore的时候，会创建一个jobs.xml文件，并创建一个JobSet对象，里面保存的是一个JobStatus的集合，最后将磁盘中的数据读取到JobSet对象中。这里的mJobsFile就是/data/system/job/jobs.xml。

**2.6 JobStore.readJobMapFromDisk()**

	public void readJobMapFromDisk(JobSet jobSet) {
        new ReadJobMapFromDiskRunnable(jobSet).run();
    }

	@Override
    public void run() {
        try {
            List<JobStatus> jobs;
            FileInputStream fis = mJobsFile.openRead();//打开JobsFile
            synchronized (mLock) {
                jobs = readJobMapImpl(fis);//借助XmlPullParser，从输入文件流中读取Job状态信息
                if (jobs != null) {
                    for (int i=0; i<jobs.size(); i++) {
                        this.jobSet.add(jobs.get(i));//将JobStatus添加到JobSet中
                    }
                }
            }
            fis.close();
        } catch (FileNotFoundException e) {
           ......
        }
    }

readJobMapFromDisk方法主要是将磁盘文件中的Job状态信息读取到内存中，并通过XmlPullParser解析xml文件信息，将Job相关信息逐步封装成JobInfo——>JobStatus——>JobStore，最后保存在JobSchedulerService服务中。

在JobSchedulerService中保存了与工作相关的JobStore，然后JobStore又保存了JobSet，在JobSet中保存了一个JobStatus的集合，在每个JobStatus中都有一个JobInfo，在JobInfo里面保存了与工作相关的信息，例如jobId,service。他们之间的类图关系如下：

![JobInfo类图](http://orbohk5us.bkt.clouddn.com/17-12-5/58323760.jpg)


在创建JobSchedulerService的时候，初始化了7个StateController，这些Controller分别控制不同触发条件。

	public abstract class StateController {
    	protected final Context mContext;
    	protected final Object mLock;
    	protected final StateChangedListener mStateChangedListener;

    	public StateController(StateChangedListener stateChangedListener, Context context,
            Object lock) {
        	mStateChangedListener = stateChangedListener;
        	mContext = context;
        	mLock = lock;
    	}
		......
	}

以ConnectivityController为例，来描述Controller的初始化过程。

**2.7 ConnectivityController.get()**

	public static ConnectivityController get(JobSchedulerService jms) {
        synchronized (sCreationLock) {
			// 单例模式
            if (mSingleton == null) {
                mSingleton = new ConnectivityController(jms, jms.getContext(), jms.getLock());
            }
            return mSingleton;
        }
    }

	private ConnectivityController(StateChangedListener stateChangedListener, Context context,
            Object lock) {
        super(stateChangedListener, context, lock);

        mConnManager = mContext.getSystemService(ConnectivityManager.class);//获取ConnectivityManager服务
        mNetPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);//获取NetPolicyManager服务

        final IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiverAsUser(mConnectivityReceiver, UserHandle.SYSTEM, intentFilter, null, null);//动态注册广播

        mNetPolicyManager.registerListener(mNetPolicyListener);//注册网络状态监听
    }

由于JobSchedulerService实现了StateChangedListener接口，因此当ConnectivityController状态发生改变了，会回调StateChangedListener接口中的方法。StateChangedListener接口中定义了如下的方法：

	public interface StateChangedListener {
		public void onControllerStateChanged();// Controller状态发生了变化，通知JobSchedulerService服务检查任务的状态
		public void onRunJobNow(JobStatus jobStatus);// Controller通知JobSchedulerService，立即执行任务
		public void onDeviceIdleStateChanged(boolean deviceIdle);
	}

![StateController的类图](http://orbohk5us.bkt.clouddn.com/17-12-5/82076902.jpg)

可以看到，这些Controller都继承自StateController类，并且在StateController里面保存了一个StateChangedListener接口，JobSchedulerService实现了StateChangedListener接口，当Controller中的状态发生了变化时，会通知到JobSchedulerService服务。

至此，JobScheduler服务的初始化流程完成了，主要有以下几个步骤：

1. 在SystemServer的startOtherService()过程中，启动JobSchedulerService服务；
2. 在JobSchedulerService的onStart()方法中，将JobSchedulerService里的LocalService发布到本地服务中，该服务只能被system进程访问该服务；将jobscheduler服务发布到ServiceManager中，这样可以被其他服务和应用访问。
3. jobscheduler服务对应的实现是mJobSchedulerStub，它是在构建JobSchedulerService服务时被初始化的。
4. 从磁盘文件/data/system/job/jobs.xml中读取工作相关数据来构建JobStore。
5. 针对不同的条件创建不同的StateController，这些Controller分别控制不同的触发条件。

### 3.JobScheduler的Schedule()过程

一个JobScheduler的典型用法如下：

	JobScheduler scheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);  
 	ComponentName jobService = new ComponentName(this, MyJobService.class);

 	JobInfo jobInfo = new JobInfo.Builder(123, jobService) //任务Id等于123
         .setMinimumLatency(5000)// 任务最少延迟时间  
         .setOverrideDeadline(60000)// 任务deadline，当到期没达到指定条件也会开始执行  
         .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)// 网络条件，默认值NETWORK_TYPE_NONE
         .setRequiresCharging(true)// 是否充电  
         .setRequiresDeviceIdle(false)// 设备是否空闲
         .setPersisted(true) //设备重启后是否继续执行
         .setBackoffCriteria(3000，JobInfo.BACKOFF_POLICY_LINEAR) //设置退避/重试策略
         .build();  
 	scheduler.schedule(jobInfo);

最终都是需要调用schedule()方法来完成调度工作。接下来就从JobScheduler的schedule()方法开始分析：


**3.1 JobSchedulerImpl.schedule()**

因为JobScheduler是一个抽象类，实际的实现类是JobSchedulerImpl。

	@Override
    public int schedule(JobInfo job) {
        try {
            return mBinder.schedule(job);
        } catch (RemoteException e) {
            return JobScheduler.RESULT_FAILURE;
        }
    }
这里mBinder是IJobScheduler类型，是在构造JobSchedulerImpl时被赋值的。

	JobSchedulerImpl(IJobScheduler binder) {
        mBinder = binder;
    }

我们可以通过Context.getSystemService(Context.JOB\_SCHEDULER\_SERVICE)方法获取JobScheduler服务，而JobScheduler服务的注册过程是通过SystemServiceRegistry类的静态代码块来完成的。

	 registerService(Context.JOB_SCHEDULER_SERVICE, JobScheduler.class,
                new StaticServiceFetcher<JobScheduler>() {
            @Override
            public JobScheduler createService() {
                IBinder b = ServiceManager.getService(Context.JOB_SCHEDULER_SERVICE);
                return new JobSchedulerImpl(IJobScheduler.Stub.asInterface(b));//可以看到binder对象为IJobScheduler
            }});

在前面介绍JobScheduler初始化时，在2.2小结中，可以看到通过ServiceManager.getService(Context.JOB\_SCHEDULER\_SERVICE)返回的是mJobSchedulerStub，mJobSchedulerStub的初始化在构建JobSchedulerService时被初始化的。因此最终调用的是JobSchedulerStub的schedule()方法。

**3.2 JobSchedulerStub.schedule()**

	@Override
    public int schedule(JobInfo job) throws RemoteException {
    	final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();

        enforceValidJobRequest(uid, job);//验证Job请求是有效的，主要是验证是否有BIND_JOB_SERVICE权限，以及调用者是否正确。
        if (job.isPersisted()) {
            if (!canPersistJobs(pid, uid)) {
                throw new IllegalArgumentException("Error: requested job be persisted without"
                        + " holding RECEIVE_BOOT_COMPLETED permission.");
            }
        }

        if ((job.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.CONNECTIVITY_INTERNAL, TAG);
        }

        long ident = Binder.clearCallingIdentity();
        try {
            return JobSchedulerService.this.schedule(job, uid);//见3.3
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
JobSchedulerStub是JobSchedulerService类的一个内部类，经过一些参数检查，以及转换调用者身份后，最终调用JobSchedulerService的schedule()方法。

**3.3 JobSchedulerService.schedule()**

	public int schedule(JobInfo job, int uId) {
        return scheduleAsPackage(job, uId, null, -1, null);//见3.4
    }

这个方法是客户端调度已经提供工作的入口点。如果工作已经被调度了，则取消该工作，并用最新的工作替换。

**3.4 JobSchedulerService.scheduleAsPackage()**

	public int scheduleAsPackage(JobInfo job, int uId, String packageName, int userId,
            String tag) {
        JobStatus jobStatus = JobStatus.createFromJobInfo(job, uId, packageName, userId, tag);//创建一个新的调度工作,见3.5
        try {
            if (ActivityManagerNative.getDefault().getAppStartMode(uId,
                    job.getService().getPackageName()) == ActivityManager.APP_START_MODE_DISABLED) {//判断是否允许启动该Job
                Slog.w(TAG, "Not scheduling job " + uId + ":" + job.toString()
                        + " -- package not allowed to start");
                return JobScheduler.RESULT_FAILURE;
            }
        } catch (RemoteException e) {
        }

        JobStatus toCancel;
        synchronized (mLock) {
            // Jobs on behalf of others don't apply to the per-app job cap
            if (ENFORCE_MAX_JOBS && packageName == null) {
                if (mJobs.countJobsForUid(uId) > MAX_JOBS_PER_APP) {//判断是否有很多工作在uid上
                    Slog.w(TAG, "Too many jobs for uid " + uId);
                    throw new IllegalStateException("Apps may not schedule more than "
                                + MAX_JOBS_PER_APP + " distinct jobs");
                }
            }

            toCancel = mJobs.getJobByUidAndJobId(uId, job.getId());//查找与uid和jobId匹配的job
            if (toCancel != null) {
                cancelJobImpl(toCancel, jobStatus);//如果job已经被调度了，则取消Job，见3.6
            }
            startTrackingJob(jobStatus, toCancel);//将JobStatus插入到JobStore中，并通知所有的StateController，见3.7
        }
        mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();//发送一个检查job的消息
        return JobScheduler.RESULT_SUCCESS;
    }

可以看到，在scheduleAsPackage()方法中，主要完成了以下几件事情：

1. 根据JobInfo以及uid，创建一个新的JobStatus，代表一个新的调度工作；
2. 判断系统是否允许该uid启动该job；
3. 判断该uid调度的工作是否超出了上限，最大是100；
4. 根据uid和jobId在JobStatus中查看是否存在相同uid和jobId的job，如果存在这样的job，则取消该job；
5. 将一个新的jobStatus插入到JobStore中，并通知所有的StateController；
6. 发送一个MSG\_CHECK\_JOB消息，执行检查工作；


**3.5 JobStatus.createFromJobInfo()**

	public static JobStatus createFromJobInfo(JobInfo job, int callingUid, String sourcePackageName,
            int sourceUserId, String tag) {
        final long elapsedNow = SystemClock.elapsedRealtime();
        final long earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis;
        if (job.isPeriodic()) {//是否定期执行
            latestRunTimeElapsedMillis = elapsedNow + job.getIntervalMillis();//下一次执行时间
            earliestRunTimeElapsedMillis = latestRunTimeElapsedMillis - job.getFlexMillis();
        } else {
            earliestRunTimeElapsedMillis = job.hasEarlyConstraint() ?
                    elapsedNow + job.getMinLatencyMillis() : NO_EARLIEST_RUNTIME;
            latestRunTimeElapsedMillis = job.hasLateConstraint() ?
                    elapsedNow + job.getMaxExecutionDelayMillis() : NO_LATEST_RUNTIME;
        }
        return new JobStatus(job, callingUid, sourcePackageName, sourceUserId, tag, 0,
                earliestRunTimeElapsedMillis, latestRunTimeElapsedMillis);//创建一个JobStatus对象
    }
根据job是否定期执行，来决定job最早执行时间以及最晚执行时间，最后创建一个JobStatus对象。


**3.6 JobSchedulerService.cancelJobImpl()**

	private void cancelJobImpl(JobStatus cancelled, JobStatus incomingJob) {
        stopTrackingJob(cancelled, incomingJob, true /* writeBack */);//移除一个JobSet集合中移除JobStatus
        synchronized (mLock) {
            // Remove from pending queue.
            if (mPendingJobs.remove(cancelled)) {//从待处理队列中移除该job
                mJobPackageTracker.noteNonpending(cancelled);
            }
            // Cancel if running.
			// 如果job在执行，则取消job执行
            stopJobOnServiceContextLocked(cancelled, JobParameters.REASON_CANCELED);
            reportActive();//更新Active状态，如果待处理队列中的jobs数量不为空，或者当前有任务在执行，则更新active为true。
        }
    }
当需要取消一个任务时，首先从JobSet集合中移除JobStatus，然后从待处理队列中移除该job。如果job在执行，则取消job的执行。最后更新active状态，如果待处理队列中的jobs数量不为空，或者当前有任务在执行，则更新active为true。


**3.7 JobSchedulerService.startTrackingJob()**

	private void startTrackingJob(JobStatus jobStatus, JobStatus lastJob) {
        synchronized (mLock) {
            final boolean update = mJobs.add(jobStatus);//将JobStatus添加到JobSet集合中
            if (mReadyToRock) {//是否允许启动第三方应用，当第三方应用可用时，该mReadyToRock为true
                for (int i = 0; i < mControllers.size(); i++) {
                    StateController controller = mControllers.get(i);
                    if (update) {//如果是替换已经存在的JobStatus，则取消之前的JobStatus
                        controller.maybeStopTrackingJobLocked(jobStatus, null, true);
                    }
                    controller.maybeStartTrackingJobLocked(jobStatus, lastJob);//通过StateController开始追踪新的JobStatus
                }
            }
        }
    }
在startTrackingJob()方法中，主要是将JobStatus添加到JobSet集合中。如果此时第三方应用可用时，则通知StateController开始追踪新的JobStatus，并且取消之前已经存在的JobStatus。

第三方应用是否可用主要是根据mReadyToRock变量来判断的，具体是在JobSchedulerService的onBootPhase()方法中，被赋值初始化的。

	 @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {//1.在此阶段，服务只能调用一些系统服务，例如PowManager或PackageManager
            mConstants.start(getContext().getContentResolver());//监听job_scheduler_constants内容是否发生变化
            // Register br for package removals and user removals.
            // 注册监听安装包和用户移除
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
            filter.addDataScheme("package");
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, filter, null, null);
            final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
            getContext().registerReceiverAsUser(
                    mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
            mPowerManager = (PowerManager)getContext().getSystemService(Context.POWER_SERVICE);
            try {
                ActivityManagerNative.getDefault().registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE
                        | ActivityManager.UID_OBSERVER_IDLE);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {// 2.在此阶段，服务可以调用第三方服务；
            synchronized (mLock) {
                // Let's go!
                mReadyToRock = true;//将该变量置为true，表示第三方应用可用了
                mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                        BatteryStats.SERVICE_NAME));
                mLocalDeviceIdleController
                        = LocalServices.getService(DeviceIdleController.LocalService.class);
                // Create the "runners".
                for (int i = 0; i < MAX_JOB_CONTEXTS_COUNT; i++) {//MAX_JOB_CONTEXTS_COUNT为16
                    mActiveServices.add(
                            new JobServiceContext(this, mBatteryStats, mJobPackageTracker,
                                    getContext().getMainLooper()));//创建JobServiceContext对象
                }
                // Attach jobs to their controllers.
				// 让所有的job都执行process()方法
                mJobs.forEachJob(new JobStatusFunctor() {
                    @Override
                    public void process(JobStatus job) {
                        for (int controller = 0; controller < mControllers.size(); controller++) {
                            final StateController sc = mControllers.get(controller);
                            sc.maybeStartTrackingJobLocked(job, null);
                        }
                    }
                });
                // GO GO GO!
                mHandler.obtainMessage(MSG_CHECK_JOB).sendToTarget();//开始检查job是否可以执行
            }
        }
    }

可以看到，当第三方应用可以被调用时，则mReadyToRock会被置为true，然后创建执行job的runners，最后检查job是否可以执行。

回调3.4方法中，最后发送的是一个MSG\_CHECK\_JOB消息，该消息是在SystemServer的主线程中执行的。

**3.8 JobHandler.hanleMessage()**

	@Override
    public void handleMessage(Message message) {
		synchronized (mLock) {
        	if (!mReadyToRock) {
                return;
            }
        }

		switch (message.what) {

			case MSG_CHECK_JOB:
            	synchronized (mLock) {
                	if (mReportedActive) {
                    	// if jobs are currently being run, queue all ready jobs for execution.
                    	queueReadyJobsForExecutionLockedH();//见3.9
                    } else {
                        // Check the list of jobs and run some of them if we feel inclined.
                        maybeQueueReadyJobsForExecutionLockedH();//见3.10
                    }
                }
                break;
		}

		maybeRunPendingJobsH();//执行待处理的job，见3.11
        // Don't remove JOB_EXPIRED in case one came along while processing the queue.
        removeMessages(MSG_CHECK_JOB);
	}

在处理MSG\_CHECK\_JOB消息的过程中，主要是根据当前应用是否还有job在执行，即根据mReportedActive变量值来决定执行什么方法。如果mReportedActive为true，则说明当前还有job在执行，则将所有准备好执行的job放入队列中。如果mReportedActive为false，则说明当前没有job在执行，则从Job列表中，选取一个合适的job执行。

**3.9 JobHandler.queueReadyJobsForExecutionLockedH()**

	private void queueReadyJobsForExecutionLockedH() {
    	noteJobsNonpending(mPendingJobs);
        mPendingJobs.clear();//清除待处理的job
        mJobs.forEachJob(mReadyQueueFunctor);//让所有的job调用mReadyQueueFunctor的process()方法
        mReadyQueueFunctor.postProcess();//调用mReadyQueueFunctor的postProcess()方法，将所有准备好执行的job加入到pending队列中
    }

JobStore的forEachJob()方法的主要作用是让所有的job都调用参数里面的JobStatusFunctor的process()方法。mReadyQueueFunctor实现了JobStatusFunctor接口，定义而来process()和postProcess()方法：

	@Override
    public void process(JobStatus job) {
    	if (isReadyToBeExecutedLocked(job)) {//判断job是否准备好执行了
                    
        	if (newReadyJobs == null) {
            	newReadyJobs = new ArrayList<JobStatus>();
            }
            newReadyJobs.add(job);//将Job添加到新的准备列表中
     	} else if (areJobConstraintsNotSatisfiedLocked(job)) {//如果job不满足执行条件
        	stopJobOnServiceContextLocked(job,
            JobParameters.REASON_CONSTRAINTS_NOT_SATISFIED);//停止job执行，原因是不满足条件
        }
    }

	public void postProcess() {
    	if (newReadyJobs != null) {
        	noteJobsPending(newReadyJobs);
            mPendingJobs.addAll(newReadyJobs);//将所有新准备的job添加到待处理的队列中
        }
           	newReadyJobs = null;
    }

可以看到通过ReadyQueueFunctor的process()和postProcess()方法，把准备执行的job添加到待处理的队列中，把不满足执行条件的job停止掉。

**3.10 JobHandler.maybeQueueReadyJobsForExecutionLockedH()**

	private void maybeQueueReadyJobsForExecutionLockedH() {
    	noteJobsNonpending(mPendingJobs);
        mPendingJobs.clear();//清除待处理的队列
        mJobs.forEachJob(mMaybeQueueFunctor);//让所有的job执行mMaybeQueueFunctor的process()方法
        mMaybeQueueFunctor.postProcess();
    }
和queueReadyJobsForExecutionLockedH()方法类似，主要是调用mMaybeQueueFunctor的process()方法和postProcess()方法来选出待执行的任务。

	public void process(JobStatus job) {
    	if (isReadyToBeExecutedLocked(job)) {//判断job是否可以执行
        	try {
            	if (ActivityManagerNative.getDefault().getAppStartMode(job.getUid(),
                	job.getJob().getService().getPackageName())
                    	== ActivityManager.APP_START_MODE_DISABLED) {//判断是否允许启动
                	Slog.w(TAG, "Aborting job " + job.getUid() + ":"
                    	+ job.getJob().toString() + " -- package not allowed to start");
                	mHandler.obtainMessage(MSG_STOP_JOB, job).sendToTarget();
                   	return;
                 }
           	} catch (RemoteException e) {
            }
			// 根据Job的状态，更新一些控制变量值
           	if (job.getNumFailures() > 0) {
            	backoffCount++;
            }
          	if (job.hasIdleConstraint()) {
            	idleCount++;
            }
           	if (job.hasConnectivityConstraint() || job.hasUnmeteredConstraint()
                	|| job.hasNotRoamingConstraint()) {
            	connectivityCount++;
           	}
            if (job.hasChargingConstraint()) {
            	chargingCount++;
            }
            if (job.hasContentTriggerConstraint()) {
            	contentCount++;
           	}
           	if (runnableJobs == null) {
           		runnableJobs = new ArrayList<>();
            }
          	runnableJobs.add(job);//将job添加到可运行的队列中
       	} else if (areJobConstraintsNotSatisfiedLocked(job)) {//job不满足条件，停止执行
        	stopJobOnServiceContextLocked(job,JobParameters.REASON_CONSTRAINTS_NOT_SATISFIED);
        }
	}


	public void postProcess() {
    	if (backoffCount > 0 ||
        	idleCount >= mConstants.MIN_IDLE_COUNT ||
            connectivityCount >= mConstants.MIN_CONNECTIVITY_COUNT ||
            chargingCount >= mConstants.MIN_CHARGING_COUNT ||
            contentCount >= mConstants.MIN_CONTENT_COUNT ||
            (runnableJobs != null && runnableJobs.size() >= mConstants.MIN_READY_JOBS_COUNT)) {
                    
            noteJobsPending(runnableJobs);//标注job正在等待处理
          	mPendingJobs.addAll(runnableJobs);//将job添加到待处理队列中
        } else {
                    
        }

        // Be ready for next time
        reset();//恢复设置
    }

	private void reset() {
    	chargingCount = 0;
       	idleCount =  0;
        backoffCount = 0;
        connectivityCount = 0;
       	contentCount = 0;
        runnableJobs = null;
    }

可以看到，在MaybeReadyJobQueueFunctor中，如果job已经准备好执行了，则将job添加到runnableJobs队列中，并根据job的状态来更新一些控制变量；如果job不满足执行条件，则停止job执行；最后，根据更新后的控制变量，决定是否需要将runnableJobs队列中的job添加到待处理job队列中。

**3.11 JobHandler.maybeRunPendingJobsH()**

	private void maybeRunPendingJobsH() {
    	synchronized (mLock) {
        	assignJobsToContextsLocked();//见3.12
            reportActive();
        }
    }
在待处理的工作队列中，选择一个job来执行。

**3.12 JobSchedulerService.assignJobsToContextsLocked()**

	private void assignJobsToContextsLocked() {

        int memLevel;
        try {
            memLevel = ActivityManagerNative.getDefault().getMemoryTrimLevel();// 获取内存状态等级
        } catch (RemoteException e) {
            memLevel = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        }
        switch (memLevel) {
            case ProcessStats.ADJ_MEM_FACTOR_MODERATE:
                mMaxActiveJobs = mConstants.BG_MODERATE_JOB_COUNT;//允许最大的后台job数量
                break;
            case ProcessStats.ADJ_MEM_FACTOR_LOW:
                mMaxActiveJobs = mConstants.BG_LOW_JOB_COUNT;//在低内存中，允许的最大的后台job数量为1
                break;
            case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                mMaxActiveJobs = mConstants.BG_CRITICAL_JOB_COUNT;//允许最大的后台job数量为1
                break;
            default:
                mMaxActiveJobs = mConstants.BG_NORMAL_JOB_COUNT;//默认情况下，在正常的内存状态下，允许的最大后台job数量为6
                break;
        }

        JobStatus[] contextIdToJobMap = mTmpAssignContextIdToJobMap;
        boolean[] act = mTmpAssignAct;
        int[] preferredUidForContext = mTmpAssignPreferredUidForContext;
        int numActive = 0;
        int numForeground = 0;
		// 遍历所有活动的JobServiceContext，并判断是否有运行的JobStatus
        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            final JobServiceContext js = mActiveServices.get(i);
            final JobStatus status = js.getRunningJob();
            if ((contextIdToJobMap[i] = status) != null) {
                numActive++;
                if (status.lastEvaluatedPriority >= JobInfo.PRIORITY_TOP_APP) {
                    numForeground++;
                }
            }
            act[i] = false;
            preferredUidForContext[i] = js.getPreferredUid();
        }
        
		// 遍历所有待处理的工作
        for (int i=0; i<mPendingJobs.size(); i++) {
            JobStatus nextPending = mPendingJobs.get(i);

            // If job is already running, go to next job.
            int jobRunningContext = findJobContextIdFromMap(nextPending, contextIdToJobMap);
            if (jobRunningContext != -1) {
                continue;
            }

            final int priority = evaluateJobPriorityLocked(nextPending);//评估待处理job的优先级
            nextPending.lastEvaluatedPriority = priority;

            // Find a context for nextPending. The context should be available OR
            // it should have lowest priority among all running jobs
            // (sharing the same Uid as nextPending)
            int minPriority = Integer.MAX_VALUE;
            int minPriorityContextId = -1;
            for (int j=0; j<MAX_JOB_CONTEXTS_COUNT; j++) {
                JobStatus job = contextIdToJobMap[j];
                int preferredUid = preferredUidForContext[j];
                if (job == null) {
                    if ((numActive < mMaxActiveJobs ||
                            (priority >= JobInfo.PRIORITY_TOP_APP &&
                                    numForeground < mConstants.FG_JOB_COUNT)) &&
                            (preferredUid == nextPending.getUid() ||
                                    preferredUid == JobServiceContext.NO_PREFERRED_UID)) {
                        // This slot is free, and we haven't yet hit the limit on
                        // concurrent jobs...  we can just throw the job in to here.
                        minPriorityContextId = j;
                        break;
                    }
                    // No job on this context, but nextPending can't run here because
                    // the context has a preferred Uid or we have reached the limit on
                    // concurrent jobs.
                    continue;
                }
                if (job.getUid() != nextPending.getUid()) {
                    continue;
                }
                if (evaluateJobPriorityLocked(job) >= nextPending.lastEvaluatedPriority) {
                    continue;
                }
                if (minPriority > nextPending.lastEvaluatedPriority) {
                    minPriority = nextPending.lastEvaluatedPriority;
                    minPriorityContextId = j;
                }
            }
			// 找到了需要执行的job
            if (minPriorityContextId != -1) {
                contextIdToJobMap[minPriorityContextId] = nextPending;
                act[minPriorityContextId] = true;
                numActive++;
                if (priority >= JobInfo.PRIORITY_TOP_APP) {
                    numForeground++;
                }
            }
        }
        
        mJobPackageTracker.noteConcurrency(numActive, numForeground);//记录活跃的和前台的job数量
        for (int i=0; i<MAX_JOB_CONTEXTS_COUNT; i++) {
            boolean preservePreferredUid = false;
            if (act[i]) {//需要执行
                JobStatus js = mActiveServices.get(i).getRunningJob();//获取正在执行的job
                if (js != null) {
                    // preferredUid will be set to uid of currently running job.
                    mActiveServices.get(i).preemptExecutingJob();//强占执行
                    preservePreferredUid = true;
                } else {
                    final JobStatus pendingJob = contextIdToJobMap[i];//待处理的job
                    for (int ic=0; ic<mControllers.size(); ic++) {
                        mControllers.get(ic).prepareForExecutionLocked(pendingJob);//通知所有的StateController准备执行待处理的job
                    }
                    if (!mActiveServices.get(i).executeRunnableJob(pendingJob)) {//执行待处理的job，见3.13
                        Slog.d(TAG, "Error executing " + pendingJob);
                    }
                    if (mPendingJobs.remove(pendingJob)) {//从待处理队列中移除该job
                        mJobPackageTracker.noteNonpending(pendingJob);
                    }
                }
            }
            if (!preservePreferredUid) {
                mActiveServices.get(i).clearPreferredUid();
            }
        }
    }

在该方法中，主要是从待处理工作的队列中获取job，然后让他们运行在可用的JobServiceContext上。如果没有可用的JobServiceContext可用，则让高优先级的job优先于低优先级的job执行。

**3.13 JobServiceContext.executeRunnableJob()**

	boolean executeRunnableJob(JobStatus job) {
        synchronized (mLock) {
            if (!mAvailable) {
                Slog.e(TAG, "Starting new runnable but context is unavailable > Error.");
                return false;
            }

            mPreferredUid = NO_PREFERRED_UID;

            mRunningJob = job;//保存正在执行的job
            final boolean isDeadlineExpired =
                    job.hasDeadlineConstraint() &&
                            (job.getLatestRunTimeElapsed() < SystemClock.elapsedRealtime());//判断工作是否超时了
            Uri[] triggeredUris = null;
            if (job.changedUris != null) {
                triggeredUris = new Uri[job.changedUris.size()];
                job.changedUris.toArray(triggeredUris);
            }
            String[] triggeredAuthorities = null;
            if (job.changedAuthorities != null) {
                triggeredAuthorities = new String[job.changedAuthorities.size()];
                job.changedAuthorities.toArray(triggeredAuthorities);
            }
            mParams = new JobParameters(this, job.getJobId(), job.getExtras(), isDeadlineExpired,
                    triggeredUris, triggeredAuthorities);//构建job执行的参数
            mExecutionStartTimeElapsed = SystemClock.elapsedRealtime();//记录执行的起始时间

            mVerb = VERB_BINDING;//记录job初始状态
            scheduleOpTimeOut();//设置job执行超时时间
            final Intent intent = new Intent().setComponent(job.getServiceComponent());
            boolean binding = mContext.bindServiceAsUser(intent, this,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND,
                    new UserHandle(job.getUserId()));//启动需要执行Job的service，见3.14

            if (!binding) {//如果服务不可用，则恢复状态设置
                if (DEBUG) {
                    Slog.d(TAG, job.getServiceComponent().getShortClassName() + " unavailable.");
                }
                mRunningJob = null;
                mParams = null;
                mExecutionStartTimeElapsed = 0L;
                mVerb = VERB_FINISHED;
                removeOpTimeOut();
                return false;
            }
            try {
                mBatteryStats.noteJobStart(job.getBatteryName(), job.getSourceUid());
            } catch (RemoteException e) {
                // Whatever.
            }
            mJobPackageTracker.noteActive(job);//记录Job处于工作状态
            mAvailable = false;
            return true;
        }
    }

在将job放在JobServiceContext上执行之前，首先需要检查该JobServiceContext是否可用，然后判断job是否超时了。如果没有超时，则构建job执行的参数，记录job执行的初始状态。最后，绑定到job中指定的service服务,绑定成功后会执行JobServiceContext的onServiceConnected()方法


**3.14 JobServiceContext.onServiceConnected()**

	public void onServiceConnected(ComponentName name, IBinder service) {
        JobStatus runningJob;
        synchronized (mLock) {
            runningJob = mRunningJob;
        }
        if (runningJob == null || !name.equals(runningJob.getServiceComponent())) {
            mCallbackHandler.obtainMessage(MSG_SHUTDOWN_EXECUTION).sendToTarget();
            return;
        }
        this.service = IJobService.Stub.asInterface(service);//获取远程JobService的代理端
        final PowerManager pm =
                (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                runningJob.getTag());
        wl.setWorkSource(new WorkSource(runningJob.getSourceUid()));
        wl.setReferenceCounted(false);
        wl.acquire();
        synchronized (mLock) {
            // We use a new wakelock instance per job.  In rare cases there is a race between
            // teardown following job completion/cancellation and new job service spin-up
            // such that if we simply assign mWakeLock to be the new instance, we orphan
            // the currently-live lock instead of cleanly replacing it.  Watch for this and
            // explicitly fast-forward the release if we're in that situation.
            if (mWakeLock != null) {
                Slog.w(TAG, "Bound new job " + runningJob + " but live wakelock " + mWakeLock
                        + " tag=" + mWakeLock.getTag());
                mWakeLock.release();
            }
            mWakeLock = wl;
        }
        mCallbackHandler.obtainMessage(MSG_SERVICE_BOUND).sendToTarget();//发送MSG_SERVICE_BOUND消息，见3.15
    }

在JobServiceContext中的service保存的是远程的IJobService的代理端JobService，mCallbackHandler是运行在SystemServer的主线程中。

**3.15 JobServiceHandler.handleMessage()**

	public void handleMessage(Message message) {
		switch (message.what) {
			case MSG_SERVICE_BOUND:
                removeOpTimeOut();//移除超时设置
                handleServiceBoundH();//见3.16
            	break;
		}
	}

**3.16 JobServiceHandler.handleServiceBoundH()**

	private void handleServiceBoundH() {
            
    	if (mVerb != VERB_BINDING) {
        	Slog.e(TAG, "Sending onStartJob for a job that isn't pending. "
                   + VERB_STRINGS[mVerb]);
            closeAndCleanupJobH(false /* reschedule */);
            return;
        }
       	if (mCancelled.get()) {
        	closeAndCleanupJobH(true /* reschedule */);
            return;
        }
        try {
        	mVerb = VERB_STARTING;//将job的状态更新为Starting
            scheduleOpTimeOut();//设置超时处理
            service.startJob(mParams);//调用JobService的startJob方法，见3.17
        } catch (Exception e) {
        	// We catch 'Exception' because client-app malice or bugs might induce a wide
            // range of possible exception-throw outcomes from startJob() and its handling
           	// of the client's ParcelableBundle extras.
           	Slog.e(TAG, "Error sending onStart message to '" + mRunningJob.getServiceComponent().getShortClassName() + "' ", e);
      	}
  	}

**3.17 JobService.startJob()**

	static final class JobInterface extends IJobService.Stub {
        final WeakReference<JobService> mService;

        JobInterface(JobService service) {
            mService = new WeakReference<>(service);
        }
		public void startJob(JobParameters jobParams) throws RemoteException {
    		JobService service = mService.get();
        	if (service != null) {
				service.ensureHandler();//确保Handler在主线程中执行
            	Message m = Message.obtain(service.mHandler, MSG_EXECUTE_JOB, jobParams);//发送MSG_EXECUTE_JOB消息，见3.18
            	m.sendToTarget();
        	}
		}
		
		public void stopJob(JobParameters jobParams) throws RemoteException {
            JobService service = mService.get();
            if (service != null) {
                service.ensureHandler();
                Message m = Message.obtain(service.mHandler, MSG_STOP_JOB, jobParams);
                m.sendToTarget();
            }

        }
	}

由于JobService是运行在app端所在的进程，因此此处的mHandler便是指app进程的主线程。

**3.18 JobHandler.handleMessage()**

	 public void handleMessage(Message msg) {
     	final JobParameters params = (JobParameters) msg.obj;
        switch (msg.what) {
        	case MSG_EXECUTE_JOB:
            	try {
                	boolean workOngoing = JobService.this.onStartJob(params);//调用JobService的onStartJob()方法，见3.19
                   	ackStartMessage(params, workOngoing);//返回给JobServiceContext一个确认信息，见3.20
                } catch (Exception e) {
                	Log.e(TAG, "Error while executing job: " + params.getJobId());
                	throw new RuntimeException(e);
              	}
     		break;
		}
	}
JobService的onStartJob()方法是一个抽象方法，由子类来实现该方法来执行具体的工作。onStartJob()方法默认是执行在主线程中的，因此如果是耗时操作，应该在该方法中创建一个子线程来执行工作。

	/*
	* params代表需要执行job的参数
	* 返回值为true，则表示需要处理该工作，如果返回值为false，则说明不需要处理该工作
	*/
	public abstract boolean onStartJob(JobParameters params);

**3.19 JobHandler.ackStartMessage()**

	private void ackStartMessage(JobParameters params, boolean workOngoing) {
    	final IJobCallback callback = params.getCallback();//返回JobServiceContext
        final int jobId = params.getJobId();
        if (callback != null) {
        	try {
				callback.acknowledgeStartMessage(jobId, workOngoing);//通知JobServiceContext，服务已经启动了
			} catch(RemoteException e) {
			Log.e(TAG, "System unreachable for starting job.");
            }
		} else {
			if (Log.isLoggable(TAG, Log.DEBUG)) {
				Log.d(TAG, "Attempting to ack a job that has already been processed.");
            }
        }
	}


### 4.小结

JobScheduler的执行流程大致如下：

![JobScheduler的执行流程](http://orbohk5us.bkt.clouddn.com/17-12-9/53631665.jpg)

在上面的流程中，涉及到两次跨进程的调用，第一次是从App进程进入SystemServer进程的JobSchedulerStub，调用JobSchedulerService的schedule()方法。第二次是通过bindService方式启动JobService，然后在onServiceConnected()方法中，调用JobService的onStartJob()方法，让应用执行自定义的工作方法。

由于JobService服务是运行在app进程中，并且onStartJob()方法是运行在主线程中，因此如果是执行耗时操作，应该让耗时操作在子线程中执行。

[JobScheduler使用Demo](https://github.com/qiubing/JobSchedulerDemo)


















	












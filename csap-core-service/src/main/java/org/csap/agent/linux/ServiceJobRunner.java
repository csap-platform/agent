package org.csap.agent.linux;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.csap.agent.CSAP;
import org.csap.agent.misc.CsapEventClient;
import org.csap.agent.model.Application;
import org.csap.agent.model.ServiceBaseParser;
import org.csap.agent.model.ServiceInstance;
import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.javasimon.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 
 * 
 * Applications -> ReleasePackages -> Services -> Jobs
 * 
 * JobRunner looks for services on host with jobs scheduled at defined intervals.
 * - jobs are pushed onto another thread pool so as to not block timer executions
 * - max time jobs are allowed to run is controlled by service instance timeout
 * - jobs can be invoked on demand from UI for jobs with and without scheduled invocation.
 * 
 * @author Peter Nightingale
 *
 */

public class ServiceJobRunner {

	final private Logger logger = LoggerFactory.getLogger( getClass() );

	Application csapApplication;

	// wakes up and checks for jobs that are scheduled to be run
	private ScheduledExecutorService jobTimerService ;

	// jobs invoked on separate thread pool
	private ExecutorService jobRunnerService;
	private static final int MAX_JOBS_QUEUED = 60;
	private static final int MAX_JOBS_CONCURRENT = 2;
	volatile BlockingQueue<Runnable> jobRunnerQueue;
	public static final String SERVICE_JOB_ID = "service.jobs.";

	
	
	public ServiceJobRunner( Application csapApplication ) {

		this.csapApplication = csapApplication;

		long initialDelay = 5;
		long interval = 60;

		TimeUnit logRotationTimeUnit = TimeUnit.MINUTES;

		if ( Application.isRunningOnDesktop() ) {
			logger.warn( "Setting DESKTOP to seconds" );
			logRotationTimeUnit = TimeUnit.SECONDS;
		}
		
		

		logger.warn(
			"Creating job schedule thread, invoked: {} {}.",
			interval, logRotationTimeUnit );
		
		BasicThreadFactory schedFactory = new BasicThreadFactory.Builder()
				.namingPattern( "CsapLogRotation-%d" )
				.daemon( true )
				.priority( Thread.NORM_PRIORITY )
				.build();
		
		jobTimerService = Executors
				.newScheduledThreadPool( 1, schedFactory );

		ScheduledFuture<?> jobHandle = jobTimerService
			.scheduleAtFixedRate(
				() -> findAndRunActiveJobs(),
				initialDelay,
				interval,
				logRotationTimeUnit );


		logger.warn(
			"Creating job runner thread pool: {} threads.  Maximum jobs queued: {}",
			MAX_JOBS_CONCURRENT, MAX_JOBS_QUEUED );
		
		BasicThreadFactory jobRunnerThreadFactory = new BasicThreadFactory.Builder()
			.namingPattern( "CsapServiceJobRunner-%d" )
			.daemon( true )
			.priority( Thread.NORM_PRIORITY + 1 )
			.build();
		//
		jobRunnerQueue = new ArrayBlockingQueue<>( MAX_JOBS_QUEUED );

		jobRunnerService = new ThreadPoolExecutor( 
			MAX_JOBS_CONCURRENT, MAX_JOBS_CONCURRENT,
			30, TimeUnit.SECONDS,
			jobRunnerQueue,
			jobRunnerThreadFactory );
	}

	// @Scheduled(initialDelay = 10 * CSAP.ONE_SECOND_MS, fixedRate = 60 * CSAP.ONE_SECOND_MS)
	public void findAndRunActiveJobs () {

		Stopwatch allStopWatch = SimonManager.getStopwatch( SERVICE_JOB_ID + "all.script.checkForActive" );
		Split allServicesSplit = allStopWatch.start();

		try {
			csapApplication.getActiveModel()
				.getServicesOnHost( Application.getHOST_NAME() )
				.filter( ServiceInstance::hasJobs )
				.flatMap( this::jobEntries )
				.forEach( jobEntry -> {
					jobRunnerService.submit( () -> runJob( jobEntry ) );
				} );
		} catch (Exception e) {
			logger.error( "Failed to schedule job", CSAP.getCsapFilteredStackTrace( e ) );
		}

		allServicesSplit.stop();
	}

	/**
	 * 
	 */
	public void shutdown () {

		logger.warn( "Shutting down all jobs" );
		try {
			jobTimerService.shutdown();
			jobRunnerService.shutdown();
		} catch (Exception e) {
			logger.error( "Shutting down error {}", CSAP.getCsapFilteredStackTrace( e ) );
		}

	}

	ObjectMapper jacksonMapper = new ObjectMapper();

	int lastDay = -1;
	static long NANOS_IN_SECOND = 1000 * 1000000;

	private Stream<Map.Entry<ServiceBaseParser.ServiceJob, ServiceInstance>> jobEntries ( ServiceInstance instance ) {
		HashMap<ServiceBaseParser.ServiceJob, ServiceInstance> map = new HashMap<>();

		instance.getJobs().forEach( job -> {

			if ( job.isTimeToRun() ) {
				map.put( job, instance );
			} else {
				logger.debug( "Skipping {} ", job );
			}

		} );

		logger.debug( "Jobs: {} ", map );

		return map.entrySet().stream();
	}

	OsCommandRunner cleanOsCommandRunner = new OsCommandRunner( 60, 1, ServiceJobRunner.class.getName() );

	
	/**
	 * 
	 * Manually triggering a job from UI
	 * 
	 */
	public String runJobUsingDescription ( ServiceInstance serviceInstance, String description ) {

		String jobResult = "Did not find matching job: " + description;
		if ( description.equals( "Log Rotation" ) ) {
			jobResult = "Log Rotation: " + csapApplication.getOsManager().getLogRoller().rotate( serviceInstance );

		} else {
			Optional<ServiceBaseParser.ServiceJob> matchedJob = serviceInstance.getJobs().stream()
				.filter( job -> job.isMatchingJob( description ) )
				.findFirst();

			if ( matchedJob.isPresent() ) {
				HashMap<ServiceBaseParser.ServiceJob, ServiceInstance> map = new HashMap<>();
				map.put( matchedJob.get(), serviceInstance );
				jobResult = runJob( map.entrySet().iterator().next() );
			}

		}

		return jobResult;
	}


	private String runJob ( Map.Entry<ServiceBaseParser.ServiceJob, ServiceInstance> jobEntry ) {

		Split allTimer = SimonManager.getStopwatch( SERVICE_JOB_ID + "all.script.run" ).start();
		ServiceInstance serviceInstance = jobEntry.getValue();
		ServiceBaseParser.ServiceJob job = jobEntry.getKey();
		String jobResult = "not able to run";
		try {

			if ( job.isDiskCleanJob() ) {

				// find $scriptFolder -maxdepth $dirDepth -mtime $numDays -type
				// f | xargs \rm -rf
				String numDays = "+" + job.getOlderThenDays(); // find requires
																// plus prefix

				String pruneCommand = "";
				if ( job.isPruneEmptyFolders() ) {
					pruneCommand = "find " + job.getPath() + " -type d -empty | xargs \\rm -rf";
				}
				String[] diskCleanLines = {
						"#!/bin/bash",
						// "set -x",
						"numberFound=`find " + job.getPath()
								+ " -maxdepth " + job.getMaxDepth()
								+ " -mtime " + numDays
								+ " -type f  | wc -l  `",
						"echo numberFound: $numberFound",
						"find " + job.getPath()
								+ " -maxdepth " + job.getMaxDepth()
								+ " -mtime " + numDays
								+ " -type f  | xargs \\rm -rf  ",
						pruneCommand,
						"" };

				Split diskTimer = SimonManager
					.getStopwatch( SERVICE_JOB_ID + "script." + serviceInstance.getServiceName() + ".diskClean" ).start();
				jobResult = cleanOsCommandRunner
					.runUsingDefaultUser( "cleanServiceDisks" + serviceInstance.getServiceName_Port(), diskCleanLines );
				diskTimer.stop();

				jobResult = "\n Clean script: " + Arrays.asList( diskCleanLines ) + "\n\n Result:\n" + jobResult;

				logger.debug( "Results from {}, \n {}", Arrays.asList( diskCleanLines ), jobResult );

			} else {

				jobResult = csapApplication
					.getOsManager()
					.getServiceManager()
					.runServiceJob( serviceInstance, job.getDescription() );
			}

			csapApplication.getEventClient()
				.generateEvent( CsapEventClient.CSAP_SVC_CATEGORY + "/" + serviceInstance.getServiceName(),
					csapApplication.lifeCycleSettings().getAgentUser(), "job launched: " + job.getDescription(), jobResult );

		} catch (Exception e) {
			logger.error( "Failed running jobs for {} job: {}, {}",
				serviceInstance.getServiceName(),
				job,
				CSAP.getCsapFilteredStackTrace( e ) );
		}

		allTimer.stop();
		Stopwatch serviceStop = SimonManager
			.getStopwatch( SERVICE_JOB_ID + "script." + serviceInstance.getServiceName() );
		serviceStop.addSplit( allTimer );

		return jobResult;
	}

	public static final String SIMON_ID = "java.script.scheduled.";

}

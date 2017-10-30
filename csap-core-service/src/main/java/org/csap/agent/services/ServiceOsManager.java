package org.csap.agent.services;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.csap.agent.CSAP;
import org.csap.agent.input.http.ui.rest.HostRequests;
import org.csap.agent.input.http.ui.rest.ServiceRequests;
import org.csap.agent.input.http.ui.rest.SsoRequestFactory;
import org.csap.agent.linux.OsCommandRunner;
import org.csap.agent.linux.OutputFileMgr;
import org.csap.agent.linux.TransferManager;
import org.csap.agent.misc.CsapEventClient;
import org.csap.agent.model.Application;
import org.csap.agent.model.HttpdIntegration;
import org.csap.agent.model.ReleasePackage;
import org.csap.agent.model.ServiceAlertsEnum;
import org.csap.agent.model.ServiceAttributes;
import org.csap.agent.model.ServiceInstance;
import org.csap.agent.model.Application.FileToken;
import org.csap.integations.CsapEncryptableProperties;
import org.csap.integations.CsapPerformance;
import org.csap.security.SpringAuthCachingFilter;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.javasimon.utils.SimonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.WebUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class ServiceOsManager {

	private static final String PACKAGE_SYNC = "package-transfer-id";

	public static final String BUILD_SUCCESS = "BUILD__SUCCESS";

	final Logger logger = LoggerFactory.getLogger( ServiceOsManager.class );

	CsapEventClient csapEventClient;

	@Inject
	public ServiceOsManager( CsapEventClient csapEventClient ) {
		this.csapEventClient = csapEventClient;
	}

	public static final String CS_AGENT = "CsAgent";

	public final static String START_CLEAN = "startClean";
	public final static String START_NO_DEPLOY_PARAM = "noDeploy";
	public final static String SKIP_HEADERS = "skipHeaders";
	public static final String MAVEN_DEFAULT_BUILD = "default";

	// service deployment ops
	static public String START_FILE = "csap-start.sh";
	static public String KILL_FILE = "csap-kill.sh";
	static public String REBUILD_FILE = "csap-deploy.sh";
	static public String STOP_FILE = "csap-stop.sh";

	// deploy log file names
	static public String START_OP = "_start";
	static public String KILL_OP = "_kill";
	static public String DEPLOY_OP = "_deploy";

	static private String JOB_RUNNER = "csap-job-run.sh";

	private static final String SCRIPT_PARAM_LIFECYCLE = "-l";

	OsCommandRunner osCommandRunner = new OsCommandRunner( 60, 2, "SvcMgr" );

	@Autowired ( required = false )
	CsapPerformance csapPerformance;

	@Inject
	private OsManager osManager;
	// Spring Injected
	@Inject
	private Application csapApp;

	@Inject
	SourceControlManager sourceControlManager;

	public String runScript (	String userName, String scriptName, String svcName,
								List<String> paramsInput,
								HttpSession session, BufferedWriter outputWriter ) {
		ServiceInstance serviceInstance = csapApp.getServiceInstanceCurrentHost( svcName );

		if ( serviceInstance == null ) {
			return "Error: Instance: " + svcName + " was not found on VM:"
					+ Application.getHOST_NAME();
		}
		String results = "";

		if ( serviceInstance.isDockerContainer() ) {
			// hook for stop RPC from UI - need to move to async
			synchronizeServiceState( KILL_FILE, serviceInstance );
			boolean isKill = true;
			if ( scriptName.equals( STOP_FILE ) ) {
				isKill = false;
			}
			ObjectNode dockerObj = dockerHelper.containerStop( null, serviceInstance.getDockerContainerPath(), isKill, 3 );
			results = jsonFormat( dockerObj );

		} else {

			results = runShellCommand( userName, scriptName, serviceInstance,
				paramsInput, null, null, null,
				session, outputWriter );

		}

		return results;
	}

	public String runServiceJob ( ServiceInstance serviceInstance, String jobDescription )
			throws Exception {

		OutputFileMgr outputFm = new OutputFileMgr(
			serviceInstance.getLogWorkingDirectory(),
			"/" + CSAP.alphaNumericOnly( jobDescription ) + "_launch" );

		logger.info( "{} running job, output at: {}",
			serviceInstance.getServiceName(),
			outputFm.getOutputFile().getAbsolutePath() );

		ArrayList<String> jobParameters = new ArrayList<>();
		jobParameters.add( jobDescription );

		String results = runShellCommand( Application.AGENT_ID, ServiceOsManager.JOB_RUNNER, serviceInstance,
			jobParameters, null, null, null,
			null, outputFm.getBufferedWriter() );

		return results;
	}

	@Inject
	CsapEncryptableProperties csapEncryptableProperties;

	private String runShellCommand (
										String userName, String scriptName,
										ServiceInstance serviceInstance,
										List<String> paramsInput, Map<String, String> environmentVariablesInput,
										String commandArgumentsOverride, String runtime,
										HttpSession session, BufferedWriter outputWriter ) {

		logger.debug( "{} runtime: {}, Invoking: {}, commandArgumentsOverride: {} , params: {}",
			serviceInstance.getServiceName_Port(), runtime, scriptName, commandArgumentsOverride, paramsInput );

		osManager.resetAllCaches(); // reset du command timer so that next
		// query will
		// refresh

		// params = params.replaceAll("&", "\\\\&") ;
		// logger.info("Run Script: " + scriptName + " Service: " + svcName +
		// " moded params " + params );
		String result = "";

		File workingDir = csapApp.getStagingFolder();
		File scriptPath = new File( workingDir, "/bin/" + scriptName );

		// String userName = getUserIdFromContext();
		List<String> parmList = new ArrayList<String>();
		parmList.add( "bash" );
		// parmList.add("-c") ;
		parmList.add( scriptPath.getAbsolutePath() );

		if ( serviceInstance.isOs() ) {
			return "Skipping " + scriptName + ", instance is a OS wrapper: " + serviceInstance.getServiceName_Port();
		}

		synchronizeServiceState( scriptName, serviceInstance );

		parmList.add( "-jmxAuth" );
		parmList.add( csapApp.getJmxAuth() );

		// parmList.add( "-loadBalanceUrl" );
		// parmList.add( csapApp.getCurrentLifecycleMetaData().getLbUrl() );
		String programParameters = serviceInstance.getParameters();

		if ( commandArgumentsOverride != null
				&& !commandArgumentsOverride.startsWith( "default" ) ) {
			programParameters = commandArgumentsOverride;
		}
		if ( serviceInstance.isSpringBoot() && serviceInstance.isApacheWebIntegration() ) {
			programParameters += " -Dserver.context-path=/" + serviceInstance.getContext();
		}
		programParameters = serviceInstance.replaceParserVariables( programParameters );

		String scriptServerType = serviceInstance.getServerType();
		if ( runtime != null && !runtime.startsWith( "default" ) ) {
			scriptServerType = runtime;
		}

		parmList.add( "-csapDeployOp" );
		parmList.add( "-serviceName" );
		parmList.add( serviceInstance.getServiceName() );
		parmList.add( "-threads" );
		parmList.add( serviceInstance.getServletThreadCount() );
		parmList.add( "-accept" );
		parmList.add( serviceInstance.getServletAccept() );
		parmList.add( "-maxConn" );
		parmList.add( serviceInstance.getServletMaxConnections() );
		parmList.add( "-timeOut" );
		parmList.add( serviceInstance.getServletTimeoutMs() );
		// not needed, doing a fs check instead
		// parmList.add("-vdcImage");
		// parmList.add(serviceInstance.getPartitionType());
		parmList.add( "-ver" );
		parmList.add( serviceInstance.getPlatformVersion() );
		parmList.add( "-serviceEnv" );
		parmList.add( serviceInstance.getLifecycle() );
		parmList.add( "-clusterScmPath" );
		parmList.add( csapApp.getRootModelBuildLocation() );
		// parmList.add( "-port" );
		// parmList.add( serviceInstance.getPort() );
		parmList.add( "-repo" );
		parmList.add( serviceInstance.getMavenRepo() );

		// might be null for some containers
		if ( serviceInstance.getContext().length() > 0 ) {
			parmList.add( "-context" );
			parmList.add( serviceInstance.getContext() );
		}
		parmList.add( "-serverType" );
		parmList.add( scriptServerType );
		// parmList.add( "-javaOpts" );
		// parmList.add( programParameters.replaceAll( " ", "__" ) ); // hack
		// for passing parameters. switch to env
		parmList.add( "-osProcessPriority" );
		parmList.add( serviceInstance.getOsProcessPriority() );
		parmList.add( "-ajpSecret" );
		parmList.add( csapApp.getTomcatAjpKey() );

		if ( serviceInstance.getCompression().length() != 0 ) {
			parmList.add( "-compress" );
			parmList.add( serviceInstance.getCompression() );
		}

		if ( serviceInstance.getCompressableMimeType().length() != 0 ) {
			parmList.add( "-mimeType" );
			parmList.add( serviceInstance.getCompressableMimeType() );
		}

		if ( serviceInstance.getCookieDomain().length() != 0 ) {
			parmList.add( "-cookieDomain" );
			parmList.add( serviceInstance.getCookieDomain() );
		}

		if ( serviceInstance.getCookieName().length() != 0 ) {
			parmList.add( "-cookieName" );
			parmList.add( serviceInstance.getCookieName() );
		}

		if ( serviceInstance.getCookiePath().length() != 0 ) {
			parmList.add( "-cookiePath" );
			// tweak for linux sed command
			parmList.add( serviceInstance.getCookiePath().replaceAll( "/", "\\\\/" ) );
		}

		if ( serviceInstance.getMetaData().contains( HttpdIntegration.SKIP_INTERNAL_HTTP_TAG ) ) {
			parmList.add( "-" + HttpdIntegration.SKIP_INTERNAL_HTTP_TAG );
		}

		if ( serviceInstance.isSecure() ) {
			parmList.add( "-secure" );
		}

		if ( serviceInstance.isNio() ) {
			parmList.add( "-nio" );
		}

		if ( serviceInstance.getMetaData().contains( Application.SKIP_TOMCAT_JAR_SCAN ) ) {
			parmList.add( "-" + Application.SKIP_TOMCAT_JAR_SCAN );
		}

		Map<String, String> serviceEnvironmentVariables = new HashMap<>();
		serviceEnvironmentVariables.put( "csapParams", programParameters );

		if ( environmentVariablesInput != null ) {
			serviceEnvironmentVariables.putAll( environmentVariablesInput );
		}
		if ( scriptName.equals( JOB_RUNNER ) ) {
			configureServiceJob( paramsInput, serviceInstance, serviceEnvironmentVariables );
		} else {
			// add all the params
			parmList.addAll( paramsInput );
		}

		parmList.add( SCRIPT_PARAM_LIFECYCLE );
		parmList.add( csapApp.getCurrentLifeCycle() );

		if ( !scriptPath.exists() && !Application.isRunningOnDesktop() ) {
			result = "Failed to find path: " + scriptPath + " for :\n" + parmList.toString();
			logger.error( result );
			csapEventClient.publishEvent( CsapEventClient.CSAP_SVC_CATEGORY + "/" + serviceInstance.getServiceName(),
				result, "No more\nDetails",
				new NoSuchElementException( scriptPath.getAbsolutePath() ) );
			return result;
		}
		if ( !scriptPath.canExecute() && !Application.isRunningOnDesktop() ) {
			result = "Not able to execute, check permissions: " + scriptPath.getAbsolutePath();
			logger.error( result );
		}

		try {
			addCsapModelEnvVariables( serviceInstance, serviceEnvironmentVariables );
			addServiceEnvVariables( serviceInstance, serviceEnvironmentVariables );
		} catch (Exception e) {
			logger.error( "{} Failed to set environment variables for service {}",
				serviceInstance.getServiceName(), CSAP.getCsapFilteredStackTrace( e ) );
		}

		StringBuilder description = new StringBuilder();
		description.append( "\n\n Script:\t\t\t" + scriptName );
		description.append( "\n\n Timeout:\t\t\t" + serviceInstance.getDeployTimeOutSeconds() );
		description.append( "\n\n workingDir:\t\t " + workingDir );
		description.append( "\n\n Command:\t\t" + parmList.toString() );
		if ( serviceEnvironmentVariables.containsKey( ENVIRONMENT_CUSTOM_VALS ) ) {
			description.append( "\n Custom Attributes:\t\t" + serviceEnvironmentVariables.get( ENVIRONMENT_CUSTOM_VALS ) );
		}
		description.append( "\n" );
		csapEventClient.generateEvent( CsapEventClient.CSAP_SVC_CATEGORY + "/" + serviceInstance.getServiceName(),
			userName, scriptName, description.toString() );

		Split timer = SimonManager.getStopwatch( "java.ServiceManager." + serviceInstance.getServiceName() ).start();
		result = osCommandRunner.executeString( parmList, serviceEnvironmentVariables, workingDir, null, session,
			serviceInstance.getDeployTimeOutSeconds(), 1, outputWriter );
		timer.stop();

		logger.debug( "{} results from {} are {}", serviceInstance.getServiceName(), scriptName, result );

		if ( result.length() > 600000 ) {
			int origLength = result.length();
			result = "\n\n ======= Only last 600k of command output shown as it was " + origLength
					+ " characters. View full output in runtime folder."
					+ result.substring( result.length() - 600000 );
		}

		csapApp.setLastOp( System.currentTimeMillis() + "::" + userName + " invoked: "
				+ scriptName + " on: " + serviceInstance.getServiceName_Port() + " time: " + new Date() );

		// finally refresh processes and versions
		serviceInstance.setFileSystemScanRequired( true );
		csapApp.updateCache( true );

		// refresh in memory state of OS
		osManager.resetAllCaches();
		osManager.scheduleDiskUsageCollection();
		osManager.checkForProcessStatusUpdate();
		return result;
	}

	private void addCsapModelEnvVariables (
											ServiceInstance serviceInstance,
											Map<String, String> serviceEnvironmentVariables ) {

		ObjectNode notifications = serviceInstance.getAttributeAsObject( ServiceAttributes.notifications );

		if ( notifications != null ) {
			if ( notifications.has( "csapAddresses" ) ) {

				String emailAddresses = notifications.get( "csapAddresses" ).asText();

				if ( emailAddresses.contains( "someUser" ) ) {
					
					logger.warn( "Default 'someUser' found in emailAddress list, not enabling notifications: {}", emailAddresses );

				} else {

					serviceEnvironmentVariables.put( "csapAddresses", emailAddresses );
					if ( notifications.has( "csapFrequency" ) ) {
						serviceEnvironmentVariables.put( "csapFrequency", notifications.get( "csapFrequency" ).asText() );
					}
					if ( notifications.has( "csapTimeUnit" ) ) {
						serviceEnvironmentVariables.put( "csapTimeUnit", notifications.get( "csapTimeUnit" ).asText() );
					}
					if ( notifications.has( "csapMaxBacklog" ) ) {
						serviceEnvironmentVariables.put( "csapMaxBacklog", notifications.get( "csapMaxBacklog" ).asText() );
					}
				}
			}
		}

		if ( !serviceEnvironmentVariables.containsKey( "csapAddresses" ) ) {
			
			logger.warn( "Notifications are disabled. Update Application.json as needed" );
			serviceEnvironmentVariables.put( "csapAddresses", "disabled" );
			
		}

		// Clustering infor
		try {
			ReleasePackage activeModel = csapApp.getActiveModel();

			List<String> serviceHostList = activeModel.findOtherHostsForService( serviceInstance.getServiceName() );
			StringBuilder hostsForBash = new StringBuilder();
			serviceHostList.stream().forEach( ( host ) -> {

				if ( hostsForBash.length() > 0 ) {
					hostsForBash.append( " " );
				}
				hostsForBash.append( host );
			} );
			serviceEnvironmentVariables.put( "csapPeers", hostsForBash.toString() );

		} catch (Exception e) {
			logger.error( "Failed to set peers for service {}", serviceInstance.getServiceName(), e );
		}

		// String version = serviceInstance.getMavenVersion();
		String version = serviceInstance.getScmVersion();
		// logger.info("version is: {}" , version);
		if ( version.length() != 0 && version.contains( " " ) ) {
			version = version.substring( 0, version.indexOf( " " ) );
		}

		// logger.info("version is: {}" , version);
		if ( version.length() == 0 ) {
			version = serviceInstance.getMavenVersion();
		}

		// logger.info("version is: {}" , version);
		serviceEnvironmentVariables.put( "csapVersion", version );

		serviceEnvironmentVariables.put( "csapServer", serviceInstance.getServerQualifedType() );
		if ( serviceInstance.isTomcatPackaging() ) {
			serviceEnvironmentVariables.put( "csapTomcat", "true" );
		}
		if ( serviceInstance.isConfigureAsSingleVmPartition() ) {
			serviceEnvironmentVariables.put( "csapHttpPerHost", "true" );
		}

		serviceEnvironmentVariables.put( "csapName", serviceInstance.getServiceName() );
		serviceEnvironmentVariables.put( "csapPids", serviceInstance.getPidsAsString() );
		serviceEnvironmentVariables.put( "csapAjp", csapApp.getTomcatAjpKey() );
		serviceEnvironmentVariables.put( "csapWorkingDir",
			csapApp.getWorkingDirectory(
				serviceInstance.getServiceName_Port() )
				.getAbsolutePath() );
		serviceEnvironmentVariables.put( "csapLogDir",
			csapApp.getLogDir( serviceInstance.getServiceName_Port() )
				.getAbsolutePath() );
		serviceEnvironmentVariables.put( "csapHttpPort", serviceInstance.getPort() );
		serviceEnvironmentVariables.put( "csapJmxPort", serviceInstance.getJmxPort() );
		serviceEnvironmentVariables.put( "csapServiceLife", serviceInstance.getLifecycle() );

		// add infra settings

		serviceEnvironmentVariables.put( "hostUrlPattern", csapApp.getAgentHostUrlPattern( true ) );
		if ( csapApp.isCompanyVariableConfigured( "spring.mail.host" ) ) {
			serviceEnvironmentVariables.put( "mailServer", csapApp.getCompanyConfiguration( "spring.mail.host", "" ) );
		}
		if ( csapApp.isCompanyVariableConfigured( "spring.mail.port" ) ) {
			serviceEnvironmentVariables.put( "mailPort", csapApp.getCompanyConfiguration( "spring.mail.port", "" ) );
		}
		if ( csapApp.isCompanyVariableConfigured( "my-service-configuration.docker.template-repository" ) ) {
			serviceEnvironmentVariables.put( "csapDockerRepository",
				csapApp.getCompanyConfiguration( "my-service-configuration.docker.template-repository", "" ) );
		}

	}

	private void configureServiceJob ( List<String> paramsInput, ServiceInstance serviceInstance, Map<String, String> envVarMap ) {
		try {
			if ( paramsInput.size() == 1 ) {

				String targetJob = paramsInput.get( 0 );

				serviceInstance.getJobs().forEach( job -> {

					if ( job.getDescription().equals( targetJob ) ) {

						try {
							String outputFileName = CSAP.alphaNumericOnly( job.getDescription() )
									+ "_output.txt";

							String jobPath = job.getScript();
							envVarMap.put( "csapJob", jobPath );
							envVarMap.put( "outputFile", outputFileName );
							logger.info( "Invoking job: {}, output: {}", jobPath, outputFileName );
							// variableNameList.add( "csapJob" );
						} catch (Exception ex) {
							logger.error( "{} Failed to parse: {}", serviceInstance.getServiceName_Port(), serviceInstance.getJobs(), ex );
						}
					}

				} );
			}
		} catch (Exception e) {
			logger.error( "Failed to configure job", e );
		}
	}

	public static final String ENVIRONMENT_CUSTOM_VALS = "customAttributes";

	/**
	 * Stateful filesystem related activity.
	 *
	 * @param scriptName
	 * @param serviceInstance
	 */
	private void synchronizeServiceState ( String scriptName, ServiceInstance serviceInstance ) {

		logger.debug( "{} , script: {}, autoKill: {}", serviceInstance.getServiceName(), scriptName,
			serviceInstance.isAutoKillInProgress() );
		if ( serviceInstance.isAutoKillInProgress() ) {
			// reset the flag in case admin trys to kill;
			serviceInstance.setAutoKillInProgress( false );
			return;
		}
		try {
			if ( scriptName.equals( KILL_FILE ) || scriptName.equals( STOP_FILE ) ) {
				// Make sure everything is update to date - include checking the
				// version in start file
				csapApp.updateCache( true );
				File stopFile = csapApp.getWorkingDirectory( serviceInstance.getStoppedFileName() );
				if ( !stopFile.exists() ) {
					stopFile.createNewFile();
				}
			} else if ( scriptName.equals( START_FILE ) ) {
				// Make sure everything is update to date - include checking the
				// version in start file
				csapApp.updateCache( true );
				File stopFile = csapApp.getWorkingDirectory( serviceInstance.getStoppedFileName() );
				if ( stopFile.exists() ) {
					stopFile.delete();
				}
				JsonNode serviceFiles = serviceInstance.getFiles();
				if ( serviceFiles != null ) {
					serviceFiles.forEach( propFile -> {
						try {
							if ( propFile.has( ServiceAttributes.FileAttributes.external.json )
									&& !propFile.get( ServiceAttributes.FileAttributes.external.json ).asBoolean() ) {
								File targetFile = new File(
									csapApp.getResourcesFolder( serviceInstance.getServiceName() ).getCanonicalFile(),
									Application.getCurrentLifeCycle() + "/"
											+ propFile.get( ServiceAttributes.FileAttributes.name.json ).asText() );

								if ( !targetFile.getParentFile().exists() ) {
									logger.info( "Creating parent folders: {} ", targetFile.getParentFile().getAbsolutePath() );
									targetFile.getParentFile().mkdirs();
								}
								logger.info( "Creating: {}", targetFile.getCanonicalPath() );
								ArrayList<String> lines = jacksonMapper.readValue(
									propFile.path( ServiceAttributes.FileAttributes.content.json ).traverse(),
									new TypeReference<ArrayList<String>>() {
									} );

								Files.write( targetFile.toPath(), lines, Charset.forName( "UTF-8" ) );
							}
						} catch (IOException e) {
							logger.error( "{} Failed creating property files : {}",
								serviceInstance.getServiceName(), CSAP.getCsapFilteredStackTrace( e ) );
						}

					} );
				}
			}
		} catch (Exception e) {
			logger.error( "Failed on user stop file maintenance", e );
		}

	}

	private void addServiceEnvVariables ( ServiceInstance serviceInstance, Map<String, String> environmentVariables ) {

		Set<String> auditNameList = new HashSet<>();
		serviceInstance.environmentVariableNames()
			.forEach( variableName -> {

				addVariableToEnvVars(
					environmentVariables,
					serviceInstance.getAttributeAsObject( ServiceAttributes.environmentVariables ),
					serviceInstance, auditNameList, variableName );

			} );

		// Check for lifecycle over rides
		serviceInstance.environmentLifeVariableNames()
			.forEach( variableName -> {

				addVariableToEnvVars(
					environmentVariables,
					serviceInstance.getLifeEnvironmentVariables(),
					serviceInstance, auditNameList, variableName );

			} );

		logger.info( "{} Environment Variables: {}", serviceInstance.getServiceName(), environmentVariables );
		environmentVariables.put( ENVIRONMENT_CUSTOM_VALS, auditNameList.toString() );

		// Need for decryption of property files
		environmentVariables.put( CsapEncryptableProperties.ENV_VARIABLE, csapEncryptableProperties.getToken() );
		environmentVariables.put( CsapEncryptableProperties.ALGORITHM_ENV_VARIABLE, csapEncryptableProperties.getAlgorithm() );

		// Standard for metadata
		environmentVariables.put( "csapPackage", csapApp.getActiveModelName() );
		environmentVariables.put( "csapLife", Application.getCurrentLifeCycle() );
		environmentVariables.put( "csapLbUrl", csapApp.lifeCycleSettings().getLbUrl() );

		return;
	}

	private void addVariableToEnvVars (
										Map<String, String> environmentVariables,
										ObjectNode variableContainer,
										ServiceInstance serviceInstance,
										Set<String> auditNameList,
										String variableName ) {

		try {
			auditNameList.add( variableName );

			logger.debug( "{} variableName: {}", serviceInstance.getServiceName(), variableName );

			JsonNode attributeNode = variableContainer.get( variableName );

			String value = attributeNode.asText();
			boolean isDecodeNeeded = false;
			if ( value.contains( DO_DECODE ) ) {
				isDecodeNeeded = true;
				value = value.substring( DO_DECODE.length() );
			}

			// redis wrapper hooks value above is used to
			// identify
			// the cluster
			if ( variableName.equals( "redisMaster" ) ) {
				String redisServiceName = value.substring( SERVICE_REF.length() );
				value = getRedisSentinels( redisServiceName, 26379 );
				// 1st redis host is assumed to be the master
				value = value.substring( 0, value.indexOf( ":" ) );
			} else if ( variableName.equals( "redisSentinels" ) ) {
				String redisServiceName = value.substring( SERVICE_REF.length() );
				value = getRedisSentinels( redisServiceName, 26379 );
			} else if ( value.contains( SERVICE_REF ) ) {
				String serviceHosts = value.substring( SERVICE_REF.length() );
				value = getServiceReferenceHosts( serviceHosts );
			}

			if ( value.contains( LIFE_SETTINGS_REF ) ) {

				String searchKey = value.substring( LIFE_SETTINGS_REF.length() );
				ObjectNode lifeCycleRefs = csapApp.lifeCycleSettings().getReferences();

				if ( lifeCycleRefs == null ) {
					value = "MissingReference";
					logger.warn( "attribute: {} , No references found in settings {}", variableName,
						Application.getCurrentLifeCycle() );
				} else {
					int numMatches = lifeCycleRefs.findValues( searchKey ).size();
					if ( numMatches != 1 ) {
						value = "MissingReference";
						logger.warn( "attribute: {} , searchKey: {}, Unexpected match count: {}",
							variableName, searchKey, numMatches );
					} else {
						value = lifeCycleRefs.findValue( searchKey ).asText();
					}
				}
			}

			if ( value.contains( PERFORMANCE_APPLICATION ) ) {
				String searchKey = value.substring( PERFORMANCE_APPLICATION.length() );
				value = osManager.getLastCollected( serviceInstance, searchKey );
				// serviceInstance.
			}

			logger.debug( "{} Adding customAttributeName: {} , {}", serviceInstance.getServiceName(),
				variableName, value );

			if ( isDecodeNeeded ) {
				value = csapApp.decode( value, "Environment Variable: " + variableName );
			} else if ( value.startsWith( "$" ) ) {
				value = serviceInstance.replaceParserVariables( value );
			}
			environmentVariables.put( variableName, value );

		} catch (Exception e) {
			logger.warn( "Failed building custom list: {}", variableName, e );
		}
	}

	private static String WORKING_DIR = "$workingDir";
	private static String SERVICE_REF = "$serviceRef:";
	private static String DO_DECODE = "doDecode:";
	private static String LIFE_SETTINGS_REF = "$lifeCycleRef:";
	private static String PERFORMANCE_APPLICATION = "$application:";

	// ha endpoints for redis
	private String getRedisSentinels ( String redisName, int sentinelPort ) {

		StringBuffer sentinalUrls = new StringBuffer();
		csapApp
			.getRootModel().getAllPackagesModel()
			.getServiceInstances( redisName )
			.map( serviceInstance -> serviceInstance.getHostName() )
			.forEach( host -> {
				if ( sentinalUrls.length() > 0 ) {
					sentinalUrls.append( "," );
				}
				sentinalUrls.append( host + ":" + sentinelPort );
			} );

		return sentinalUrls.toString();
	}

	private String getServiceReferenceHosts ( String serviceName ) {

		StringBuffer serviceHosts = new StringBuffer();
		csapApp
			.getRootModel().getAllPackagesModel()
			.getServiceInstances( serviceName )
			.map( serviceInstance -> serviceInstance.getHostName() )
			.forEach( host -> {
				if ( serviceHosts.length() > 0 ) {
					serviceHosts.append( " " );
				}
				serviceHosts.append( host );
			} );

		if ( serviceHosts.length() == 0 ) {
			logger.warn( "Did not find any instances of service: {}", serviceName );
			serviceHosts.append( "noHostsFoundFor_" + serviceName );
		}

		return serviceHosts.toString();
	}

	private ObjectMapper jacksonMapper = new ObjectMapper();
	private ArrayList<String> killList = new ArrayList<String>();

	public String submitKillJob ( String userid, String serviceNamePort, ArrayList<String> params ) {

		String killItem = "User: " + userid + " Service: " + serviceNamePort;
		getKillList().add( killItem );

		try {
			OutputFileMgr outputFm = new OutputFileMgr(
				csapApp.getProcessingDir(), "/"
						+ serviceNamePort + KILL_OP );
			outputFm.print( "\n Request(s) Queued:\n" + getKillList() );

			outputFm.close();
		} catch (IOException e) {
			logger.error( "Failed closing log file", e );
		}

		String serviceName = serviceNamePort;
		if ( serviceName.indexOf( "_" ) != -1 ) {
			serviceName = serviceName.substring( 0, serviceName.indexOf( "_" ) );
		}

		// logger.info("Generating Event: " + CsapEventClient.CSAP_SVC_CATEGORY
		// + "/" + serviceName);

		serviceJobExecutor.submit( () -> killJobRunnable( userid, serviceNamePort, params ) );

		csapEventClient.generateEvent( CsapEventClient.CSAP_SVC_CATEGORY + "/" + serviceName, userid, "Kill Queued",
			" Command: \n" + params );

		return killItem;
	}

	public void killJobRunnable ( String userid, String serviceNamePort, ArrayList<String> params ) {
		try {

			OutputFileMgr outputFm = new OutputFileMgr( csapApp.getProcessingDir(),
				"/" + serviceNamePort + KILL_OP );

			ServiceInstance serviceInstance = csapApp.getServiceInstanceCurrentHost( serviceNamePort );
			// we dump start output immediately to catch any error
			// conditions in logs
			if ( serviceInstance != null && serviceInstance.isTomcatJarsPresent() ) {
				logger.debug( "Pushing logs Immediately" );
				outputFm.setForceImmediate( true );
			}

			outputFm.print( "Userid: " + userid + " requested." );
			logger.info( "===================> {} Invoked kill script for {}: params: {}. \n\t Results are stored in: {} ",
				userid, serviceNamePort, params, outputFm.getOutputFile().getCanonicalPath() );

			// Before killing, lets publish alls stats so gaps do not appear
			if ( serviceNamePort.startsWith( CSAP.AGENT_CONTEXT ) ) {
				try {
					outputFm.printImmediate(
						"Shutting down agent collection threads and flushing events. This can take 30-60 seconds to complete...." );
					logger.info( "Shutting down agent prior to issuing kill" );
					csapApp.shutdown();
				} catch (Exception e) {
					logger.info( "Failed shutting down manager services", e );
				}
			}

			if ( serviceInstance.isDockerContainer() ) {

				killServiceUsingDocker( serviceInstance, outputFm, params );

			} else {

				if ( serviceInstance.isRunInDockerContainer() ) {
					// do not remove container - so null out params
					killServiceUsingDocker( serviceInstance, outputFm, null );
					String[] chmodLines = {
							"#!/bin/bash",
							"echo detected docker container, updating files owned by root to 777 in "
									+ csapApp.getWorkingDirectory( serviceNamePort ),
							"find " + csapApp.getWorkingDirectory( serviceNamePort ) + " -user root | xargs chmod 777",
							"" };
					String chmodResponse = osCommandRunner.runUsingRootUser( "dockerFileCleanup", chmodLines );
					outputFm.print( chmodResponse );
				}

				runShellCommand( userid, KILL_FILE, serviceInstance, params,
					buildDockerEnvVariables( serviceInstance ),
					null, null, null,
					outputFm.getBufferedWriter() );

			}

			outputFm.opCompleted();
		} catch (Exception e) {
			logger.error( "Failed to complete kill {}", CSAP.getCsapFilteredStackTrace( e ) );
		} finally {
			getKillList().remove( 0 );
		}

		logger.info( "===================> Kill Completed for service: " + serviceNamePort );
		;
	}

	public String submitStartJob (	String userid, String serviceNamePort, ArrayList<String> params,
									String commandArguments, String runtime, String deployId ) {

		String startItem = "User: " + userid + " Service: " + serviceNamePort;
		getStartList().add( startItem );

		try {
			OutputFileMgr outputFm = new OutputFileMgr(
				csapApp.getProcessingDir(), "/"
						+ serviceNamePort + START_OP );
			outputFm.print( "\n Request(s) Queued:\n" + getStartList() );

			outputFm.close();
		} catch (IOException e) {
			logger.error( "Failed closing log file", e );
		}

		String serviceName = serviceNamePort;
		if ( serviceName.indexOf( "_" ) != -1 ) {
			serviceName = serviceName.substring( 0, serviceName.indexOf( "_" ) );
		}

		// logger.info("Generating Event: " + CsapEventClient.CSAP_SVC_CATEGORY
		// + "/" + serviceName);
		csapEventClient.generateEvent( CsapEventClient.CSAP_SVC_CATEGORY + "/" + serviceName, userid, "Start Queued",
			" Command: \n" + params );
		serviceJobExecutor.submit( () -> startServiceRunnable( userid,
			serviceNamePort, params, commandArguments, runtime, deployId ) );

		return startItem;
	}

	BasicThreadFactory factory = new BasicThreadFactory.Builder()
		.namingPattern( "CsapServiceJobThread-%d" )
		.daemon( true )
		.priority( Thread.MAX_PRIORITY )
		.build();

	final BlockingQueue<Runnable> servicesOpsQueue = new ArrayBlockingQueue<>( 1000 );

	public int getOpsQueued () {

		int jobsActive = servicesOpsQueue.size() + serviceJobExecutor.getActiveCount();

		return jobsActive;
	}

	public ObjectNode getJobStatus () {
		ObjectNode status = jacksonMapper.createObjectNode();

		status.put( "tasksRemaining", getOpsQueued() );

		status.set( "killTasks", jacksonMapper.convertValue(
			getKillList(),
			ArrayNode.class ) );

		status.set( "startTasks", jacksonMapper.convertValue(
			getStartList(),
			ArrayNode.class ) );

		status.set( "deployTasks", jacksonMapper.convertValue(
			getBuildList(),
			ArrayNode.class ) );

		return status;
	}

	// private ExecutorService serviceJobExecutor = new ThreadPoolExecutor( 1,
	// 1,
	private ThreadPoolExecutor serviceJobExecutor = new ThreadPoolExecutor( 1, 1,
		0L, TimeUnit.MILLISECONDS,
		servicesOpsQueue, factory );
	// private ScheduledExecutorService serviceDeployerExecutor =
	// Executors.newScheduledThreadPool(1);

	// Starts are stateful
	// private Lock startLock = new ReentrantLock();
	private ArrayList<String> startList = new ArrayList<String>();

	public ArrayList<String> getStartList () {
		return startList;
	}

	public void setStartList ( ArrayList<String> startList ) {
		this.startList = startList;
	}

	public void startServiceRunnable (	String userid, String serviceNamePort, ArrayList<String> params,
										String commandArguments, String runtime, String deployId ) {
		OutputFileMgr outputFm = null;
		try {

			outputFm = new OutputFileMgr( csapApp.getProcessingDir(),
				"/" + serviceNamePort + START_OP );

			logger.info( "===================> {} Invoking start script for {}: params: {}. \n\t Results are stored in: {} ",
				userid, serviceNamePort, params, outputFm.getOutputFile().getCanonicalPath() );

			ServiceInstance serviceInstance = csapApp.getServiceInstanceCurrentHost( serviceNamePort );
			// we dump start output immediately to catch any error
			// conditions in logs
			if ( serviceInstance != null && serviceInstance.isTomcatJarsPresent() ) {
				logger.debug( "Pushing logs Immediately" );
				outputFm.setForceImmediate( true );
			}

			outputFm.print( "Userid: " + userid + " requested." );

			boolean skipStart = false;
			if ( deployId != null ) {

				File syncLocation = getSyncLocation();
				syncLocation.mkdirs();
				// more defensive - nio handles large director
				try (Stream<Path> pathStream = Files.list( syncLocation.toPath() )) {
					Optional<Path> deploymentCheck = pathStream
						.filter( path -> path.getFileName().toString().endsWith( deployId ) )
						.findFirst();

					if ( !deploymentCheck.isPresent() ) {
						skipStart = true;
					} else {
						List<String> lines = Files.readAllLines( deploymentCheck.get() );
						if ( lines.isEmpty() || lines.get( 0 ).toLowerCase().contains( "fail" ) ) {
							skipStart = true;
						}
					}
				}
			}

			if ( !skipStart ) {
				if ( serviceInstance.isDockerContainer() ) {

					startServiceUsingDocker( serviceInstance, outputFm, commandArguments );

				} else {

					runShellCommand(
						userid, START_FILE, serviceInstance, params,
						buildDockerEnvVariables( serviceInstance ),
						commandArguments, runtime, null,
						outputFm.getBufferedWriter() );

					if ( serviceInstance.isRunInDockerContainer() ) {
						startServiceUsingDocker( serviceInstance, outputFm, commandArguments );
					}

				}
			} else {
				outputFm.print( "Warning: skipping start due to deployment failure. Reference id: " + deployId );
				logger.warn( "Warning: skipping start of {} due to deployment failure. Reference id: {}", serviceInstance.getServiceName(),
					deployId );
			}

		} catch (Exception e) {
			String message = "Failed to start service: " +
					CSAP.getCsapFilteredStackTrace( e );
			logger.error( message );
			outputFm.print( message );

		} finally {
			getStartList().remove( 0 );
			if ( outputFm != null ) {
				outputFm.opCompleted();
			}
		}

		logger.info( "===================> Startup Completed for service: " + serviceNamePort );

	}

	private Map<String, String> buildDockerEnvVariables ( ServiceInstance serviceInstance ) {
		Map<String, String> startEnvironmentVariables = new HashMap<>();

		if ( serviceInstance.isRunInDockerContainer() ) {
			startEnvironmentVariables.put( "csapDockerTarget", "true" );
		} else {
			startEnvironmentVariables.put( "csapDockerTarget", "false" );
		}
		return startEnvironmentVariables;
	}

	public File getReImageFile () {
		return new File( csapApp.getStagingFolder(), "reImageIndicator" );
	}

	@Inject
	private StandardPBEStringEncryptor encryptor;

	private ArrayList<String> buildList = new ArrayList<String>();

	public ArrayList<String> getBuildList () {
		return buildList;
	}

	public void setBuildList ( ArrayList<String> buildList ) {
		this.buildList = buildList;
	}

	public String submitDeployJob (	String requestedByUserid, ServiceInstance instance,
									MultiValueMap<String, String> rebuildVariables, boolean isPerformStart,
									boolean isForceDeploy ) {

		String buildItem = "User: " + requestedByUserid + " service: " + instance.getServiceName_Port();

		if ( getBuildList().size() == 0 ||
				(getBuildList().size() > 0 && !getBuildList().contains( buildItem )) ) {
			// update deploy file to reflect new schedule in progress.
			try {
				OutputFileMgr outputFm = new OutputFileMgr(
					csapApp.getProcessingDir(), "/"
							+ instance.getServiceName_Port() + DEPLOY_OP );
				outputFm.print( "*** Scheduling Deployment" );
				outputFm.close();
				// do not to opComplete as UI polling will end
			} catch (IOException e) {
				// Emptying
			}
		}

		if ( !instance.getServiceName().equals( INIT_COMPLETE ) ) {
			buildItem = "User: " + requestedByUserid + " Service: " + instance.getServiceName();
			getBuildList().add( buildItem );

			String userName = Application.SYS_USER;
			if ( rebuildVariables != null && rebuildVariables.containsKey( "scmUserid" ) ) {
				userName = rebuildVariables.getFirst( "scmUserid" );
			}

			csapEventClient.generateEvent( CsapEventClient.CSAP_SVC_CATEGORY + "/" + instance.getServiceName(),
				userName, "Deployment Queued",
				"Timeout configured in cluster.js: "
						+ instance.getDeployTimeOutSeconds() + ", Command: \n" + rebuildVariables );
		}

		serviceJobExecutor.submit( () -> deployServiceRunnable( requestedByUserid,
			instance, rebuildVariables, isPerformStart, isForceDeploy ) );

		return buildItem;
	}

	public void deployServiceRunnable (	String requestedByUserid, ServiceInstance instance,
										MultiValueMap<String, String> rebuildVariables, boolean isPerformStart,
										boolean isForceDeploy ) {
		try {

			//
			if ( instance.getServiceName().equals( INIT_COMPLETE ) ) {
				// hook so UI can switch agent out of bootstrap status
				csapApp.setBootstrapComplete();
				getReImageFile().delete();
				csapEventClient.publishEvent( CsapEventClient.CSAP_SYSTEM_CATEGORY + "/initComplete",
					"System Ready", null, csapApp.statusForAdminOrAgent( ServiceAlertsEnum.ALERT_LEVEL ) );

			} else {
				deployAndOptionalStart( requestedByUserid, instance, rebuildVariables, isPerformStart, isForceDeploy );

			}
		} catch (Exception e) {
			logger.error( "Failed deployment {}",
				CSAP.getCsapFilteredStackTrace( e ) );
		} finally {
			csapApp.updateCache( true );
		}

		buildList.remove( 0 );

	}

	public static String filterField ( String input, String filter ) {
		return input.replaceAll( "\\b" + filter + "[^\\s]*",
			filter + "[*MASKED*]," );
	}

	private void deployAndOptionalStart (
											String requestedByUserid, ServiceInstance serviceInstance,
											MultiValueMap<String, String> rebuildVariables,
											boolean isPerformStart, boolean isForceDeploy )
			throws IOException, Exception {

		logger.info( "{} deployment Parameters: \n {}",
			serviceInstance.getServiceName_Port(),
			filterField( rebuildVariables.toString(), "scmPass" ) );

		csapApp.setAgentStatus( serviceInstance.getServiceName_Port() );

		// rebuildServer("CsAgentAutoDeploy", "dummyPass",
		// "dummBranch", serviceName + "_" + servicePort,
		// javaOpts, "", null, null, MAVEN_DEFAULT_BUILD
		// + ":dummyStringToSkipSvn", null, null);
		if ( getReImageFile().exists() && serviceInstance.getServerType().equals( "wrapper" )
				&& Application.isRunningAsRoot() ) {

			if ( serviceInstance.getUser() != null && !serviceInstance.getUser().equals( csapApp.getAgentRunUser() ) ) {

				File propFile = new File( "/home/" + serviceInstance.getUser() );

				serviceInstance.setCpuClean();
				logger.info( "Doing a clean of " + propFile.getAbsolutePath() );

				List<String> parmList = Arrays.asList( "bash", "-c", "sudo rm -rf "
						+ propFile.getAbsolutePath() + "/*" );
				// osCommandRunner.executeString(parmList);
				osCommandRunner.executeString( parmList,
					csapApp.getStagingFolder(), null, null, 600, 1, null );
				serviceInstance.setCpuAuto();
			}
		}
		File warFolder = csapApp.getDeploymentStorageFolder();
		File deployFile = new File( warFolder, serviceInstance.getDeployFileName() );

		// We always use existing artifact if it exists.
		if ( (!deployFile.exists()) || isForceDeploy ) {
			// we need to rebuild
			logger.info( "Deploying Service: {} \t Force Deploy: {} path: {}",
				serviceInstance.getServiceName_Port(), isForceDeploy, deployFile.getAbsolutePath() );

			// This adds default parameter values to handle restart scenario
			// where no params are passed
			// They are retrieved via the getFirst method
			rebuildVariables.add( "scmUserid", Application.SYS_USER );
			rebuildVariables.add( "scmPass", encryptor.encrypt( "dummyPass" ) );
			rebuildVariables.add( "scmBranch", "dummBranch" );
			rebuildVariables.add( "mavenDeployArtifact", MAVEN_DEFAULT_BUILD + ":dummyStringToSkipSvn" );
			rebuildVariables.add( "scmCommand", null );
			rebuildVariables.add( "targetScpHosts", "" );
			rebuildVariables.add( "hotDeploy", null );

			OutputFileMgr outputFm = new OutputFileMgr(
				csapApp.getProcessingDir(), "/"
						+ serviceInstance.getServiceName_Port() + DEPLOY_OP );
			// outputFm.printImmediate("Building: " +
			// rebuildVariables);
			try {

				deployService(
					serviceInstance,
					rebuildVariables.getFirst( "primaryHost" ),
					rebuildVariables.getFirst( "deployId" ),
					requestedByUserid,
					rebuildVariables.getFirst( "scmUserid" ),
					rebuildVariables.getFirst( "scmPass" ),
					rebuildVariables.getFirst( "scmBranch" ),
					rebuildVariables.getFirst( "mavenDeployArtifact" ),
					rebuildVariables.getFirst( "scmCommand" ),
					rebuildVariables.getFirst( "targetScpHosts" ),
					rebuildVariables.getFirst( "hotDeploy" ),
					rebuildVariables.getFirst( "javaOpts" ),
					rebuildVariables.getFirst( "runtime" ),
					outputFm );

			} catch (Exception e) {
				logger.warn( "Failed to deploy: {}", CSAP.getCsapFilteredStackTrace( e ) );
			} finally {
				outputFm.opCompleted();
			}

			// syncBuildForScmSession(scmUserid, scmPass, scmBranch,
			// svcName, mavenDeployArtifact, scmCommand,
			// targetScpHosts, hotDeploy, response, session)
		}

		// UI starts are triggered client side, so this is usually
		// invoked only from localhost during startup
		if ( isPerformStart ) {
			logger.info( serviceInstance.getServiceName_Port() + ": Found war or zip in "
					+ warFolder.getAbsolutePath() + ", Issueing a start" );
			ArrayList<String> params = new ArrayList<String>();

			// typically a restart of VM.
			// params.add("-cleanType");
			// params.add("clean");
			// params.add("-hotDeploy");
			// params.add("-skipDeployment");
			OutputFileMgr outputFm = new OutputFileMgr(
				csapApp.getProcessingDir(), "/"
						+ serviceInstance.getServiceName_Port() + START_OP );
			try {

				if ( serviceInstance.isDockerContainer() ) {
					startServiceUsingDocker( serviceInstance, outputFm, null );
				} else {
					// String results = runScript( requestedByUserid,
					// START_FILE,
					// serviceInstance.getServiceName_Port(),
					// params, null, outputFm.getBufferedWriter() );
					runShellCommand(
						requestedByUserid, START_FILE, serviceInstance, params,
						buildDockerEnvVariables( serviceInstance ),
						null, null, null,
						outputFm.getBufferedWriter() );

					if ( serviceInstance.isRunInDockerContainer() ) {
						startServiceUsingDocker( serviceInstance, outputFm, null );
					}
				}
			} finally {
				outputFm.opCompleted();
			}
		}

		csapEventClient.generateEvent( CsapEventClient.CSAP_SYSTEM_CATEGORY + "/deploy",
			Application.SYS_USER, "Deployment Completed For Service "
					+ serviceInstance.getServiceName_Port(),
			"" );

		serviceInstance.setCpuReset();
	}

	private File getSyncLocation ()
			throws IOException {
		return new File( csapApp.getCsapSavedFolder(), PACKAGE_SYNC ).getCanonicalFile();
	}

	private String jsonFormat ( ObjectNode json ) {
		String result = json.toString();
		try {
			result = jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString( json );
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return result;
	}

	@Inject
	DockerHelper dockerHelper;

	private String deployDockerPrimary (	ServiceInstance serviceInstance,
											OutputFileMgr outputFileMgr,
											String imageNameOverride,
											String scmUserid ) {

		String imageName = serviceInstance.getDockerSettings().get( "image" ).asText();

		if ( imageNameOverride != null &&
				!imageNameOverride.startsWith( MAVEN_DEFAULT_BUILD ) &&
				imageNameOverride.trim().length() > 0 ) {
			imageName = imageNameOverride;
		}

		logger.info( "Starting deploy of {} using: {} ",
			serviceInstance.getServiceName_Port(), imageName );

		outputFileMgr.printImmediate( "\n\n *** starting docker pull of image: " + imageName );

		ObjectNode results = dockerHelper.imagePull(
			imageName,
			outputFileMgr,
			serviceInstance.getDeployTimeOutSeconds() );

		logger.info( "pull results: {}", results );
		outputFileMgr.printImmediate( jsonFormat( results ) );

		if ( !results.get( DockerJson.error.key ).asBoolean() ) {

			File deployFile = csapApp.getServiceDeployFile( serviceInstance );

			outputFileMgr.printImmediate( "\n storing image: " + deployFile.toURI() + " ..." );

			boolean success = dockerHelper.imageSave( imageName, deployFile );
			if ( success ) {
				outputFileMgr.printImmediate( "\n docker image saved to deployment folder: " + deployFile.toURI() );
				outputFileMgr.print( BUILD_SUCCESS );
			} else {
				outputFileMgr.printImmediate( "\n ERROR: unable to save docker image to: " + deployFile.toURI() );
			}

			File versionFile = csapApp.getDeployVersionFile( serviceInstance );
			createVersionFile( versionFile, null, scmUserid, "Docker Pull" );
			return BUILD_SUCCESS;
		}

		// @Todo need to add enforceable timeout handling
		// pull using OS ?

		return "Docker Pull did not complete successfully";

	}

	private void startServiceUsingDocker (	ServiceInstance serviceInstance,
											OutputFileMgr outputFileMgr,
											String commandArguments )
			throws Exception {
		//
		logger.info( "Starting docker service: {}, type: {}, using: {} ",
			serviceInstance.getServiceName_Port(), serviceInstance.getServerType(), commandArguments );

		synchronizeServiceState( START_FILE, serviceInstance );

		ObjectNode dockerDefinition = serviceInstance.getDockerSettings();

		// Boot in Docker support
		if ( commandArguments != null && commandArguments.length() > 0 ) {

			if ( serviceInstance.isRunInDockerContainer() ) {
				// clone
				dockerDefinition = (ObjectNode) jacksonMapper.readTree( dockerDefinition.toString() );

				// update commands with command line overrides
				JsonNode commandArray = dockerDefinition.get( DockerJson.command.key );
				ArrayNode updatedCommands = buildRuntimeParameters( serviceInstance, commandArguments, commandArray );

				if ( updatedCommands.size() > 0 ) {
					dockerDefinition.set( DockerJson.command.key, updatedCommands );
				}

				// update entry with command line overrides
				JsonNode entryArray = dockerDefinition.get( DockerJson.entryPoint.key );
				ArrayNode updatedEntry = buildRuntimeParameters( serviceInstance, commandArguments, entryArray );

				if ( updatedEntry.size() > 0 ) {
					dockerDefinition.set( DockerJson.entryPoint.key, updatedEntry );
				}

				ArrayNode runtimeEnvVars = (ArrayNode) dockerDefinition.get( DockerJson.environmentVariables.key );

				// add environment variables from service
				Map<String, String> serviceEnvVars = new HashMap<>();
				addCsapModelEnvVariables( serviceInstance, serviceEnvVars );
				addServiceEnvVariables( serviceInstance, serviceEnvVars );
				serviceEnvVars
					.entrySet()
					.stream()
					.forEach( variable -> {
						runtimeEnvVars.add( variable.getKey() + "=" + variable.getValue() );
					} );

			} else {
				dockerDefinition = (ObjectNode) jacksonMapper.readTree( commandArguments );
			}
		}

		// kill any running instance
		if ( dockerHelper.findContainerByName( serviceInstance.getDockerContainerPath() ).isPresent() ) {

			outputFileMgr.printImmediate( "\n *** Found existing container,  issueing remove..." );
			killServiceUsingDocker( serviceInstance, outputFileMgr, null );

		}

		String targetImage = dockerDefinition.get( DockerJson.imageName.key ).asText();
		if ( serviceInstance.isRunInDockerContainer() ) {

			// && ! dockerHelper.findImageByName( targetImage ).isPresent()
			logger.warn( "Running in docker container, checking for specified image: {}", targetImage );
			ObjectNode results = dockerHelper.imagePull(
				targetImage,
				outputFileMgr,
				serviceInstance.getDeployTimeOutSeconds() );

			logger.info( "pull results: {}", results );
		}
		outputFileMgr.printImmediate( "\n *** Creating Container..." );
		ObjectNode results = dockerHelper.containerCreateAndStart( serviceInstance, dockerDefinition );

		if ( results.has( DockerJson.errorReason.key ) ) {
			outputFileMgr.print( results.get( DockerJson.errorReason.key ).asText() );
		} else {

			outputFileMgr.print( jsonFormat( results ) );
		}

	}

	private ArrayNode buildRuntimeParameters ( ServiceInstance serviceInstance, String commandArguments, JsonNode commandArray ) {
		ArrayNode updatedCommands = jacksonMapper.createArrayNode();
		if ( commandArray != null ) {

			CSAP.jsonStream( commandArray )
				.map( JsonNode::asText )
				.map( String::trim )
				.forEach( command -> {
					if ( command.equals( CSAP.SERVICE_PARAMETERS ) ) {
						// this is itemized parameters scenarios
						String[] serviceParams = commandArguments.split( " " );

						for ( String param : serviceParams ) {
							String trimmedParam = serviceInstance.replaceParserVariables( param );
							if ( trimmedParam.length() > 0 ) {
								updatedCommands.add( trimmedParam );
							}
						}

					} else {
						String commandItem = serviceInstance.replaceParserVariables( command );
						updatedCommands.add( commandItem );
					}
				} );
		}
		return updatedCommands;
	}

	private void killServiceUsingDocker (	ServiceInstance serviceInstance,
											OutputFileMgr outputFileMgr,
											ArrayList<String> params )
			throws Exception {
		//
		logger.info( "Killing docker service: {}, using: {} ",
			serviceInstance.getServiceName_Port(), params );

		synchronizeServiceState( KILL_FILE, serviceInstance );

		ObjectNode results = dockerHelper
			.containerRemove( null,
				serviceInstance.getDockerContainerPath(),
				true, true );

		if ( results.has( DockerJson.errorReason.key ) ) {
			outputFileMgr.print( results.get( DockerJson.errorReason.key ).asText() );
		} else {
			outputFileMgr.print( jsonFormat( results ) );
		}

		if ( params != null && params.contains( "clean" ) ) {

			ObjectNode removeResults = dockerHelper
				.imageRemove( null,
					serviceInstance.getDockerImageName() );

			if ( removeResults.has( DockerJson.errorReason.key ) ) {
				outputFileMgr.print( removeResults.get( DockerJson.errorReason.key ).asText() );
			} else {
				outputFileMgr.print( jsonFormat( removeResults ) );
			}
		}

	}

	public File createVersionFile (
									File versionFile, String version,
									String userid, String description ) {

		String foundTime = LocalDateTime.now().format( DateTimeFormatter.ofPattern( "HH:mm:ss MMM d" ) );
		if ( version == null ) {
			version = LocalDateTime.now().format( DateTimeFormatter.ofPattern( "MMM.d-HH.mm" ) );
		}
		List<String> lines = Arrays.asList(
			"Deployment Notes",
			"<version>" + version + "</version>",
			description + " by: " + userid + " at " + foundTime );

		try {
			Files.write( versionFile.toPath(), lines, Charset.forName( "UTF-8" ) );
		} catch (IOException ex) {
			logger.error( "Failed creating version file", ex );
		}

		return versionFile;
	}

	// only a single thread doing deploys else need synchronized
	public boolean deployService (	ServiceInstance serviceInstance, String primaryHost, String deployId,
									String requestedByUserid, String scmUserid, String scmPass,
									String scmBranch,
									String deploymentArtifact, String scmCommand, String targetScpHosts,
									String hotDeploy, String javaOpts, String runtime, OutputFileMgr outputFileMgr )
			throws Exception {

		logger.info( "{}  user: {} requested build: scmUserid: {} , scmBranch {}, deploymentArtifact: {}, scmCommand: {}, "
				+ "\n javaOpts: {}, runtime: {}, hotDeploy: {}, primaryHost:{}, deployRequestId: {}"
				+ "\n targetScpHosts: targetScpHosts: {}",
			serviceInstance.getServiceName_Port(), requestedByUserid, scmUserid, scmBranch, deploymentArtifact, scmCommand, javaOpts,
			runtime,
			hotDeploy, primaryHost, deployId,
			targetScpHosts );

		File svcDirOnHost = csapApp.getStagingFolder();
		File workingDir = new File( svcDirOnHost.getAbsolutePath() );

		//
		// Step 1 - Check out the code if a source build is requested: either
		// cvs or svn
		//

		boolean isSourceCodeOk = true;
		if ( deploymentArtifact == null || deploymentArtifact.length() == 0 ) {
			// only login on source build

			logger.debug( "scm: {}", serviceInstance.getScm() );

			if ( primaryHost == null || primaryHost.equals( (Application.getHOST_NAME()) ) ) {
				isSourceCodeOk = checkoutFromSourceControl( serviceInstance, scmUserid, scmPass, scmBranch, outputFileMgr, workingDir );
			} else {
				logger.info( "Skipping source checkout on secondary node" );
				isSourceCodeOk = true;
			}

		}

		//
		// Step 2: Get build params for rebuild script
		//
		ArrayList<String> params = buildDeployParameters(
			serviceInstance, scmUserid, scmBranch,
			deploymentArtifact, scmCommand, hotDeploy );

		outputFileMgr.printImmediate( "\n\n ============= Deployment on host "
				+ Application.getHOST_NAME() + "  =====================\n\n" );

		//

		Map<String, String> deployEnvironmentVariables = new HashMap<>();
		String waitForPrimaryFile = "none";
		File deployCompleteFile = new File(
			getSyncLocation(),
			serviceInstance.getServiceName() + "." + primaryHost + "." + deployId );

		boolean isDockerPrimary = false;

		if ( serviceInstance.isDockerContainer() && !csapApp.isJvmInManagerMode() ) {
			isDockerPrimary = true;
		} else if ( serviceInstance.isDockerContainer() &&
				(primaryHost != null && primaryHost.equals( Application.getHOST_NAME() )) ) {
			isDockerPrimary = true;
		}
		if ( primaryHost != null ) {
			// synchronize state using file system. Corner cases can occur - but
			// are highly unusual

			if ( !primaryHost.equals( (Application.getHOST_NAME()) ) ) {

				// in case file has been left on FS by aborted requests
				// It is possible that sync occurs quicker then requests - but
				// also highly unlikely
				waitForPrimaryFile = deployCompleteFile.getCanonicalPath();

			} else {

				logger.info( "Creating: {}", deployCompleteFile );
				deployCompleteFile.getParentFile().mkdirs();
				deployCompleteFile.createNewFile();
			}
			deployEnvironmentVariables.put( "waitForPrimaryFile", waitForPrimaryFile );
		}
		deployEnvironmentVariables.put( "buildSubDir", "/" );
		if ( serviceInstance.getScmBuildLocation().length() > 0 ) {
			deployEnvironmentVariables.put( "buildSubDir", serviceInstance.getScmBuildLocation() );
		}

		//
		// Step 3: Trigger the deployment
		//
		String buildResults = "";
		if ( isSourceCodeOk ) {
			if ( isDockerPrimary ) {
				buildResults = deployDockerPrimary(
					serviceInstance,
					outputFileMgr,
					deploymentArtifact,
					scmUserid );

			} else {

				buildResults = runShellCommand( requestedByUserid, REBUILD_FILE, serviceInstance, params,
					deployEnvironmentVariables, javaOpts, runtime,
					null,
					outputFileMgr.getBufferedWriter() );

			}
		}

		if ( primaryHost != null &&
				(!primaryHost.equals( (Application.getHOST_NAME()) )) ) {
			logger.info( "Completed deployment using sync from: {}", primaryHost );

			if ( serviceInstance.isDockerContainer() ) {
				File deployFile = csapApp.getServiceDeployFile( serviceInstance );

				outputFileMgr.printImmediate( "\n\n *** Loading docker image: " + deployFile.toURI() );

				boolean success = dockerHelper.imageLoad( deployFile );
				if ( success ) {
					outputFileMgr.printImmediate( "\n docker image loaded: " + deployFile.toURI() );
					logger.info( "\n docker image loaded: {}", deployFile.toURI() );
				} else {
					outputFileMgr.printImmediate( "\n ERROR: unable to load docker image: " + deployFile.toURI() );
					logger.info( "\n unable to load docker image: {}", deployFile.toURI() );
				}

			}

			return isSourceCodeOk;
		}
		// Test data for desktop
		if ( Application.isRunningOnDesktop() && !isDockerPrimary ) {
			URL psUrl = getClass().getResource( "/linux/buildResults.txt" );
			logger.warn( "Application.isRunningOnDesktop()" );
			outputFileMgr.printImmediate( "\n\n *** Desktop test\n\n" );
			outputFileMgr.printImmediate( Application.getContents( new File( psUrl.getFile() ) ) );
			// resultsFromBuild.append("Application.isRunningOnDesktop():
			// "
			// + Application.getContents(new File(psUrl.getFile())));
		}

		// need to add in scp commands here
		String last50FromRebuild = buildResults;
		if ( buildResults.length() > 100 ) {
			last50FromRebuild = buildResults.substring( buildResults.length() - 50 );
		}

		logger.info( "\n *** last50FromRebuild: " + last50FromRebuild );

		// addOtherHostsParam(svcName, params);
		if ( targetScpHosts != null && targetScpHosts.trim().length() > 0 ) {

			boolean isBuildSuccessful = last50FromRebuild.contains( BUILD_SUCCESS );

			pushFilesToOtherHosts(
				requestedByUserid, targetScpHosts, serviceInstance,
				outputFileMgr.getBufferedWriter(),
				isBuildSuccessful,
				deployCompleteFile );

		}

		return isSourceCodeOk;
	}

	private ArrayList<String> buildDeployParameters (	ServiceInstance serviceInstance, String scmUserid, String scmBranch,
														String mavenDeployArtifact, String scmCommand, String hotDeploy ) {
		ArrayList<String> params = new ArrayList<String>();
		params.add( "-scmUser" );
		params.add( scmUserid );
		params.add( "-scmBranch" );
		params.add( scmBranch );

		if ( hotDeploy != null ) {
			params.add( "-hotDeploy" );
		}

		if ( mavenDeployArtifact != null ) {
			params.add( "-mavenCommand" );
			if ( mavenDeployArtifact.startsWith( MAVEN_DEFAULT_BUILD ) ) {
				// Hook for deployments of multiple artifacts

				if ( serviceInstance != null ) {
					params.add( serviceInstance.getMavenId() );
				} else {
					params.add( "couldNotFind" + serviceInstance.getServiceName_Port() );
				}
			} else {
				// Hook for deployScripts not like spaces
				params.add( mavenDeployArtifact.replaceAll( " ", "__" ) );
			}
		} else {
			// Source build Option
			params.add( "-mavenCommand" );
			// Hook for deployScripts not like spaces
			params.add( scmCommand.replaceAll( " ", "__" ) );
		}

		if ( serviceInstance.getMavenSecondary() != null ) {
			params.add( "-secondary" );
			params.add( serviceInstance.getMavenSecondary() );
		}
		return params;
	}

	private boolean checkoutFromSourceControl (
												ServiceInstance instanceConfig, String scmUserid, String scmPass, String scmBranch,
												OutputFileMgr outputFileMgr, File workingDir ) {

		try {

			sourceControlManager.checkOutFolder(
				scmUserid, scmPass, scmBranch, instanceConfig.getServiceName_Port(),
				instanceConfig, outputFileMgr.getBufferedWriter() );

		} catch (Exception e) {
			logger.error( "Failed to do source checkout: {}, {} ",
				instanceConfig.getScmLocation(),
				CSAP.getCsapFilteredStackTrace( e ) );

			outputFileMgr
				.printImmediate( CSAP.CONFIG_PARSE_ERROR
						+ "GIT Failure: Verify password and target is correct, and that url exists\n"
						+ instanceConfig.getScmLocation() + "\n Exception: " + e );

			if ( e.toString().indexOf( "is already a working copy for a different URL" ) != -1 ) {
				File svnCheckoutFolder = new File( Application.BUILD_DIR + instanceConfig.getServiceName_Port() );
				outputFileMgr
					.printImmediate( "Blowing away previous build folder, try again:"
							+ svnCheckoutFolder );
				FileUtils.deleteQuietly( svnCheckoutFolder );
			}
			return false;
		}
		return true;
	}

	/**
	 *
	 * Push files to other hosts if needed.
	 *
	 *
	 *
	 * @param svcName
	 * @param targetScpHosts
	 * @param response
	 * @param session
	 * @param resultsFromBuild
	 * @param serviceInstance
	 */
	private void pushFilesToOtherHosts (	String userid, String targetScpHosts,
											ServiceInstance serviceInstance,
											BufferedWriter outputWriter,
											boolean isBuildSuccessful, File deployCompleteFile )
			throws IOException {

		String[] hostsArray = targetScpHosts.trim().split( " " );

		List<String> hostList = Arrays.asList( hostsArray );
		hostList.remove( Application.getHOST_NAME() );
		if ( hostList.size() == 0 ) {
			logger.info( "No other hosts specified" );
			return;
		}
		// StringBuffer buf = new StringBuffer("");

		// Trigger reload on otherhosts...
		// for (String host : hostsArray) {

		// // do not need current host
		// if (host.equals(Application.getHOST_NAME()))
		// continue;
		// in a multi-service deploy, only push if host contains
		// service
		if ( serviceInstance == null ) {
			logger.error( "Warning: {} was not found in Application.json definition", serviceInstance.getServiceName() );
			return;
		}

		File deployFile = csapApp.getServiceDeployFile( serviceInstance );
		File deployVersionFile = csapApp.getDeployVersionFile( serviceInstance );

		logger.debug( "Checking for deployment Files: {}", deployFile.getAbsolutePath() );

		if ( isBuildSuccessful ) {
			if ( deployFile.exists() ) {

				TransferManager transferManager = new TransferManager( csapApp, 120, outputWriter );

				transferManager.httpCopyViaCsAgent( userid, deployFile,
					Application.CSAP_PACKAGES_TOKEN, hostList );

				transferManager.httpCopyViaCsAgent( userid, deployVersionFile,
					Application.CSAP_PACKAGES_TOKEN, hostList );

				File secondaryFolder = new File( csapApp.getDeploymentStorageFolder(),
					serviceInstance.getServiceName() + ".secondary" );

				if ( secondaryFolder.exists() && secondaryFolder.isDirectory() ) {

					syncToOtherHosts( userid, hostList, secondaryFolder.getAbsolutePath(),
						Application.CSAP_PACKAGES_TOKEN + serviceInstance.getServiceName() + ".secondary",
						csapApp.getAgentRunUser(),
						userid, false, outputWriter );

				}
				logger.debug( "sending complete file to remote hosts" );

				String transResults = transferManager.waitForComplete();

				logger.info( "Transfer results have been added $PROCESSING/{}.deploy.log", serviceInstance.getServiceName() );
				if ( transResults.contains( CSAP.CONFIG_PARSE_ERROR ) ) {
					logger.warn( "Found 1 or more errors in transfer results" );
				}

				outputWriter.write( transResults );
			} else {
				logger.warn( "Did not find deployment file: {}", deployFile.getAbsolutePath() );
			}

			// CsAgent platform update Step - only done AFTER the above has
			// complete. Bin contains
			// command line scripts and must only be done
			// when no other activity is occuring.
			// if ( serviceInstance.getServiceName().equals( CS_AGENT ) ) {
			// logger.warn( "STAGING/bin being Deployed to all VMs: " + hostList
			// );
			//
			// TransferManager stagingBinManager = new TransferManager( csapApp,
			// 30, outputWriter );
			//
			// stagingBinManager.httpCopyViaCsAgent(
			// userid,
			// csapApp.getStagingFile( "/bin" ),
			// Application.FileToken.STAGING.value + "/bin",
			// hostList );
			//
			// // blocking for response on a http thread. Generally - this
			// // should
			// // be less then a couple of minutes
			// String transResults = stagingBinManager.waitForComplete();
			//
			// logger.info( "CsAgent Binaries transfered" );
			//
			// if ( transResults.contains( CSAP.CONFIG_PARSE_ERROR ) ) {
			// logger.warn( "Found 1 or more errors in transfer results" );
			// outputWriter.write( "\n ===== WARNING: one or more transfers of
			// STAGING/bin failed =====\n" );
			// } else {
			// outputWriter.write( "\n ===== STAGING/bin updated: " + hostList +
			// " =====\n" );
			// }
			// // outputWriter.write( transResults );
			// }
		}

		// Final step - send file to sync deploy complete
		List<String> lines = Arrays.asList( "source: " + Application.getHOST_NAME() + " deploy passed" );
		if ( !isBuildSuccessful ) {
			lines = Arrays.asList( "source: " + Application.getHOST_NAME() + " deploy failed" );
		}
		try {
			Files.write( deployCompleteFile.toPath(), lines, Charset.forName( "UTF-8" ) );
		} catch (IOException ex) {
			logger.error( "Failed creating version file", ex );
		}

		TransferManager deployCompleteManager = new TransferManager( csapApp, 30, null );
		deployCompleteManager.httpCopyViaCsAgent( userid,
			deployCompleteFile,
			Application.CSAP_SAVED_TOKEN + PACKAGE_SYNC, hostList );

		String transResults = deployCompleteManager.waitForComplete();

		logger.info( "Deployment sync completed", transResults );
		if ( transResults.contains( CSAP.CONFIG_PARSE_ERROR ) ) {
			logger.warn( "Found 1 or more errors in transfer results" );
			outputWriter.write( "\n WARNING:  STAGING/bin updated: " + hostList + " =====\n" );
		}
		;

		// avoid writing sync stats so errors are more obvious
		// outputWriter.write( transResults );

	}

	public String syncToOtherHosts (
										String userid, List<String> hostList, String locationToZip,
										String extractDir, String chownUserid, String auditUser,
										boolean isHtml, BufferedWriter outputWriter )
			throws IOException {

		logger.debug( "auditUser: {}, locationToZip: {}, extractDir: {}, chownUserid: {}, hostList: {}",
			auditUser, locationToZip, extractDir, chownUserid, hostList );

		if ( hostList != null && hostList.contains( Application.getHOST_NAME() ) ) {

			logger.debug( "Removing : {}", Application.getHOST_NAME() );
			// always remove current host
			hostList.remove( Application.getHOST_NAME() );
		}

		if ( hostList == null || hostList.size() == 0 ) {
			return "No Additional Synchronization required";
		}

		TransferManager transferManager = new TransferManager( csapApp, 120, outputWriter );

		File zipLocation = new File( locationToZip );

		String result = "Specified Location does not exist: " + locationToZip + " on host: "
				+ Application.getHOST_NAME();
		if ( zipLocation.exists() ) {

			// logger.info("******* extractDir: "+ extractDir + " full path: " +
			// targetFolder.getAbsolutePath());
			// targetFolder.getAbsolutePath()
			transferManager.httpCopyViaCsAgent( userid, zipLocation,
				extractDir, hostList, chownUserid );

			if ( isHtml ) {
				result = transferManager.waitForComplete( "<pre class=\"result\">", "</pre>" );
			} else {
				result = transferManager.waitForComplete();
			}
		}

		logger.debug( "Result: {}", result );

		return result;
	}

	private boolean isInit = false;

	@EventListener ( { ContextRefreshedEvent.class } )
	@Order ( Application.CSAP_SERVICE_STATE_LOAD_ORDER )
	public void onSpringContextRefreshedEvent () {
		// public void onSpringContextRefreshedEvent(ContextRefreshedEvent
		// event) {

		if ( Application.isJvmInManagerMode() ) {
			logger.debug( "Skipping init scince we are in mgr mode" );
			csapApp.setBootstrapComplete();
			return;
		}

		if ( isInit ) {
			logger.warn( "CsAgent already initialized, but received a second ContextRefreshedEvent from Spring" );
			return;
		}

		File workingDir = csapApp.getStagingFolder();
		File rebuildPath = new File( workingDir, "/bin/" + REBUILD_FILE );

		if ( !rebuildPath.exists() ) {
			logger.warn( "did not find: {}. Ensure latest csap-package-linx is deployed. Switching names to legacy",
				rebuildPath.getAbsolutePath() );
			REBUILD_FILE = "rebuildAndDeploySvc.sh";
			KILL_FILE = "killInstance.sh";
			START_FILE = "startInstance.sh";
			JOB_RUNNER = "jobRunner.sh";
		} else {
			logger.info( "Found csap-package-linx : {}",
				rebuildPath.getAbsolutePath() );
		}

		isInit = true;

		if ( Application.isStatefulRestartNeeded() ) {

			logger.warn(
				"Found -Dorg.csap.needStatefulRestart=yes, triggering a restart so that cluster params can be loaded" );
			ArrayList<String> params = new ArrayList<String>();
			params.add( "-cleanType" );
			params.add( "no" );

			String results;
			try {
				results = runScript( Application.SYS_USER, KILL_FILE, "CsAgent_8011",
					params, null, null );
				logger.warn( "Results from restart command:" + results );
			} catch (Exception e) {
				logger.error( "Failed to issue restart command", e );
			}
		} else {

			initializeServiceState();

		}

	}

	/**
	 *
	 *
	 */
	private void initializeServiceState () {

		StringBuilder initMessage = new StringBuilder( "\n\n" );

		initMessage
			.append( "\n=============== Service Status Synchronization =========================" );

		initMessage.append( "\n\n Hosts:\n\t" );

		int hostCount = 0;
		for ( String host : csapApp.getActiveModel().getHostsCurrentLc() ) {
			initMessage.append( StringUtils.rightPad( host, 17 ) );
			if ( ++hostCount % 7 == 0 )
				initMessage.append( "\n\t" );
		}

		initMessage.append( "\n\n Services:" );
		// TreeMap maintains sorted keys for us
		TreeMap<String, ServiceInstance> restartMap = new TreeMap<String, ServiceInstance>();
		for ( ServiceInstance instance : csapApp.getServicesOnHost() ) {

			if ( instance.isAutoStart() ) {
				restartMap.put( instance.getAutoStart(), instance );
			} else {

				initMessage.append( "\n\t" + instance.paddedId() );
				initMessage.append( "\t\t *Skipped: auto start not enabled" );
			}
		}

		// refresh instances with updated service stats.
		osManager.checkForProcessStatusUpdate();

		// possible race condition on initial lod
		int attempts = 0;
		while (attempts < 5) {
			try {
				Thread.sleep( 2000 );
			} catch (Exception e) {
				logger.info( "Wait for ps to complete", CSAP.getCsapFilteredStackTrace( e ) );
			}
			if ( osManager.isInitalPsComplete() )
				break;
		}

		for ( String orderedKey : restartMap.keySet() ) {
			ServiceInstance instance = restartMap.get( orderedKey );

			// Never trigger restarts of CsAgent
			if ( instance.getServiceName().equals( CS_AGENT ) || instance.isOs() ) {
				continue;
			}

			initMessage.append( "\n\t" + instance.paddedId() );

			if ( instance.isScript() ) {
				// special case for scripts - we will only trigger if they
				// do not exist in runtime dir
				// Scripts are meant to only be invoked 1 time. If someone
				// cleans the folder - script will need to
				// check fs if they cannot be run twice.
				File scriptWorkingDirectory = new File( csapApp.getProcessingDir(), instance.getServiceName_Port() );

				logger.debug( "Checking: {}", scriptWorkingDirectory.getAbsolutePath() );

				// initMessage.append( "\n Script: " +
				// checkFile.getAbsolutePath() );

				if ( scriptWorkingDirectory.exists() ) {
					logger.debug(
						"Found autostart on a instance with metaData isScript, but since it exists, autoStart is ignored" );

					initMessage.append( "\t\t *Skipped: script already deployed" );
					continue;
				}

			}

			File stopFile = csapApp.getWorkingDirectory( instance.getStoppedFileName() );

			boolean needToStartSevice = !instance.isRunning()
					&& !Application.isRunningOnDesktop()
					&& !stopFile.exists();

			if ( needToStartSevice ) {

				logger.debug( "{} - Scheduling auto deploy on startup", instance.getServiceName() );

				instance.setCpuAuto();
				if ( getReImageFile().exists() && instance.getServerType().equals( "wrapper" )
						&& instance.getUser() != null && !instance.getUser().equals( csapApp.getAgentRunUser() ) ) {
					instance.setCpuClean();
				}

				MultiValueMap<String, String> rebuildVariables = new LinkedMultiValueMap<String, String>();

				rebuildVariables.add( "scmUserid", Application.SYS_USER );
				rebuildVariables.add( "scmPass", "dummyPass" );
				rebuildVariables.add( "scmBranch", "dummBranch" );
				rebuildVariables.add( CSAP.SERVICE_PORT_PARAM, instance.getServiceName_Port() );
				rebuildVariables.add( "mavenDeployArtifact", MAVEN_DEFAULT_BUILD
						+ ":dummyStringToSkipSvn" );
				rebuildVariables.add( "scmCommand", null );
				rebuildVariables.add( "targetScpHosts", "" );
				rebuildVariables.add( "hotDeploy", null );

				initMessage.append( "\t\t *Adding to Deployment Queue" );
				submitDeployJob(
					Application.SYS_USER,
					instance, rebuildVariables,
					true, false );
			} else {

				logger.debug( "{} is already running or manually stopped, skipping autodeploy",
					instance.getServiceName() );

				if ( stopFile.exists() ) {
					initMessage.append( "\t\t *Skipped: service stopped by operator" );
				} else {
					initMessage.append( "\t\t *Skipped: service already running" );
				}
			}

		}

		ServiceInstance initCompleteInstance = new ServiceInstance();
		initCompleteInstance.setServiceName( INIT_COMPLETE );
		// Hook to trigger init complete
		submitDeployJob( Application.SYS_USER, initCompleteInstance, null, true, false );

		initMessage.append( "\n=============================================================\n\n\n" );
		logger.warn( initMessage.toString() );

		csapEventClient.publishEvent( CsapEventClient.CSAP_SYSTEM_CATEGORY + "/initializeServiceState",
			"Service Synchronization", initMessage.toString() );
	}

	private static String INIT_COMPLETE = "InitComplete";

	// @Deprecated
	// public ObjectNode remoteAdminUsingUserCredentials (
	// String[] hosts, String commandUrl,
	// MultiValueMap<String, String> urlVariables,
	// HttpServletRequest request ) {
	//
	// return remoteAdminExecute( Arrays.asList( hosts ), commandUrl,
	// urlVariables, extractSsoCookie( request ) );
	//
	// }

	public ObjectNode remoteAgentsUsingUserCredentials (
															List<String> hosts, String commandUrl,
															MultiValueMap<String, String> urlVariables,
															HttpServletRequest request ) {

		return remoteHttpQuery( hosts, commandUrl, urlVariables, extractSsoCookie( request ) );

	}

	public final static String STATELESS = "NO_COOKIE";

	public ObjectNode remoteAgentsStateless (	List<String> hosts,
												String commandUrl,
												MultiValueMap<String, String> urlVariables ) {
		return remoteHttpQuery( hosts, commandUrl, urlVariables, STATELESS );
	}

	public ObjectNode remoteAgentsApiDefaultUser (	List<String> hosts, String commandUrl,
													MultiValueMap<String, String> urlVariables ) {

		urlVariables.set( SpringAuthCachingFilter.USERID, csapApp.lifeCycleSettings().getAgentUser() );
		urlVariables.set( SpringAuthCachingFilter.PASSWORD, csapApp.lifeCycleSettings().getAgentPass() );

		return remoteHttpQuery( hosts, commandUrl, urlVariables, STATELESS );
	}

	public ObjectNode remoteAgentsApi (	String apiUser, String apiPass, List<String> hosts, String commandUrl,
										MultiValueMap<String, String> urlVariables ) {

		urlVariables.set( SpringAuthCachingFilter.USERID, apiUser );
		urlVariables.set( SpringAuthCachingFilter.PASSWORD, apiPass );

		return remoteHttpQuery( hosts, commandUrl, urlVariables, STATELESS );
	}

	private ObjectNode remoteHttpQuery (	List<String> hosts, String commandUrl,
											MultiValueMap<String, String> urlVariables,
											String ssoCookieStringForHeader ) {
		ObjectNode resultsJson = jacksonMapper.createObjectNode();
		// API calls do not need sso token.

		logger.debug( "ssoCookieStringForHeader: {}", ssoCookieStringForHeader );

		if ( hosts == null || hosts.size() == 0 ) {
			resultsJson.put( "error", "One or more hosts required" );
			return resultsJson;
		}

		// hook to prune null params and get max timeout
		int maxTimeoutInMs = 25000;
		ArrayList<String> keysToPrune = new ArrayList<String>();
		for ( String key : urlVariables.keySet() ) {

			if ( urlVariables.get( key ).get( 0 ) == null ) {
				keysToPrune.add( key );
				continue;
			}

			if ( key.equals( CSAP.SERVICE_PORT_PARAM ) ) {
				for ( String serviceName_port : urlVariables.get( key ) ) {

					// Use the longest time configured
					int serviceMaxMs = csapApp.getMaxDeploySecondsForService( serviceName_port ) * 1000;

					if ( serviceMaxMs > maxTimeoutInMs ) {
						maxTimeoutInMs = serviceMaxMs;
					}
				}

			}
		}

		for ( String key : keysToPrune ) {
			urlVariables.remove( key );
		}

		RestTemplate restTemplate = csapApp.getAgentPooledConnection( 1, 1 );
		String connectionType = "pooled";
		if ( !ssoCookieStringForHeader.equals( STATELESS ) ) {
			connectionType = "transient";
			SsoRequestFactory simpleClientRequestFactory = new SsoRequestFactory(
				ssoCookieStringForHeader, maxTimeoutInMs );

			restTemplate = new RestTemplate( simpleClientRequestFactory );
		}
		Split totalTimer = SimonManager.getStopwatch( "agent.remote.admin.total" ).start();
		for ( String host : hosts ) {

			if ( Application.isRunningOnDesktop() && host.equals( "localhost" ) ) {
				resultsJson.put( host, "Skipping host because desktop detected" );
				continue;
			}
			String url = csapApp.getAgentUrl( host, commandUrl, true );
			Split transferTimer = SimonManager.getStopwatch( "agent.remote.admin.host" ).start();
			try {
				urlVariables.add( CSAP.HOST_PARAM, host );

				logger.debug( "Executing remote admin command: {}, params: {} ", url, urlVariables );

				ObjectNode restResult = restTemplate.postForObject( url, urlVariables, ObjectNode.class );
				resultsJson.set( host, restResult );

				if ( restResult == null ) {
					logger.warn( "{} : Null response from command: {} ,  Time taken: {}, \n urlVariables: {} \n restResult: {}",
						host, url, SimonUtils.presentNanoTime( transferTimer.runningFor() ), urlVariables, restResult );
				}

				logger.debug( "{} : command: {} ,  Time taken: {}, \n urlVariables: {} \n restResult: {}",
					host, url, SimonUtils.presentNanoTime( transferTimer.runningFor() ), urlVariables, restResult );

			} catch (Exception e) {

				logger.warn( "Exception on url: {}, connection: {},  variables: {}, {}", url, connectionType, urlVariables,
					CSAP.getCsapFilteredStackTrace( e ) );
				logger.debug( "Failed remote connection", e );

				logger.info( "converters: {}", restTemplate.getMessageConverters() );
				;
				resultsJson.put( host, CSAP.CONFIG_PARSE_ERROR
						+ " Got a connection exception on request: " + url + ", Message: "
						+ e.getMessage()
						+ "\n\nIf caused by timeout, consider extending deploy timeout in Capability Definition" );
			}

			transferTimer.stop();
		}
		totalTimer.stop();
		if ( !commandUrl.contains( ServiceRequests.DEPLOY_PROGRESS_URL ) ) {
			logger.info( "********** Completed: {} ,  on host(s): {}, time: {}, connectionType: {} ",
				commandUrl,
				hosts,
				SimonUtils.presentNanoTime( totalTimer.runningFor() ),
				connectionType );
		} else {
			logger.debug( "********** Completed: {} ,  on host(s): {}, time: {}, connectionType: {} ",
				commandUrl,
				hosts,
				SimonUtils.presentNanoTime( totalTimer.runningFor() ),
				connectionType );
		}

		return resultsJson;

	}

	public String extractSsoCookie ( HttpServletRequest request ) {
		String ssoCookieStringForHeader = HostRequests.getSSO_COOKIE_NAME() + "=NotUsed";

		if ( request != null ) {
			ssoCookieStringForHeader = HostRequests.getSSO_COOKIE_NAME() + "="
					+ WebUtils.getCookie( request, HostRequests.getSSO_COOKIE_NAME() ).getValue();
		}

		return ssoCookieStringForHeader;
	}

	public ArrayList<String> getKillList () {
		return killList;
	}

	public void setKillList ( ArrayList<String> killList ) {
		this.killList = killList;
	}
}

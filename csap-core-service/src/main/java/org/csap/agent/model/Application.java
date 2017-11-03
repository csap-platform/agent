package org.csap.agent.model;

import static org.csap.agent.services.SourceControlManager.CONFIG_SUFFIX_FOR_UPDATE;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.csap.agent.CsapCoreService;
import org.csap.agent.CSAP;
import org.csap.agent.linux.HostAlertProcessor;
import org.csap.agent.linux.HostInfo;
import org.csap.agent.linux.HostStatusManager;
import org.csap.agent.linux.OsCommandRunner;
import org.csap.agent.linux.OutputFileMgr;
import org.csap.agent.misc.CsapEventClient;
import org.csap.agent.services.DockerHelper;
import org.csap.agent.services.OsManager;
import org.csap.agent.stats.MetricCategory;
import org.csap.agent.stats.MetricsPublisher;
import org.csap.agent.stats.OsProcessCollector;
import org.csap.agent.stats.OsSharedResourcesCollector;
import org.csap.agent.stats.ServiceCollector;
import org.csap.helpers.CsapRestTemplateFactory;
import org.csap.integations.CsapInformation;
import org.csap.integations.CsapSecurityRestFilter;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;
import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 *
 * Central function is to parse and load the capability definition file. The
 * file is a JSON document stored on $STAGING/conf, which defines the primary
 * elements:
 * <ul>
 * <li>lifecycles - contain clusters, versions, and hosts</li>
 * <li>jvms - list of war files and associated metadata: eg. jvm heap sizes,
 * maven artifact name, etc</li>
 * <li>wrappers - list of non JEE processes and associated metadata: eg. home
 * directory, startup parameters, etc</li>
 * </ul>
 *
 * @see ServiceInstance
 *
 * @see LifeCycleSettings
 *
 * @see <a href="doc-files/csagent.jpg"> Click to enlarge
 *      <IMG width=300 SRC="doc-files/csagent.jpg"></a>
 *
 * @see <a href="doc-files/spring.jpg" > Click to enlarge
 *      <IMG width=300 SRC="doc-files/modelDocs.jpg"></a>
 *
 * @author someDeveloper
 *
 */
@Service
public class Application {

	private static final String SCRIPTS_RUN = "scripts-run";

	final static Logger logger = LoggerFactory.getLogger( Application.class );

	/**
	 * 
	 * Main modes of operation: - Normal: STAGING and PROCESSING env variables
	 * present - Desktop: STAGING not set, definition files loaded using yaml
	 * settings - Junit Spring: Spring context loaded - Junit POJO: no Spring
	 * context
	 * 
	 * 
	 */
	public Application( OsManager osManager ) {

		this();
		logger.info( "Unit testing with osManager" );
		_osManager = osManager;

	}

	public Application( ) {

		logger.info(
			"\n*** StartUp Environment Variables :\n\t staging: {}, processing: {}, csapName: {}, csapHttpPort: {}, csapJavaOpts+: {} ",
			CsapCoreService.getStaging(),
			CsapCoreService.getProcessing(),
			System.getenv( "csapName" ),
			System.getProperty( "csapHttpPort" ),
			System.getProperty( "csapJavaOpts" ) );

		STAGING = CsapCoreService.getStaging();
		PROCESSING = CsapCoreService.getProcessing();

	}

	static private boolean developerMode = false;

	private static String STAGING;
	private static String PROCESSING;

	@Value ( "${user.home:/dummy}" )
	private String agentRunHome = "willBeReplacedOnSpringInit";

	@Value ( "${user.name:dummy}" )
	private String agentRunUser = "willBeReplacedOnSpringInit";

	// @PostConstruct
	@EventListener ( { ContextRefreshedEvent.class } )
	@Order ( CSAP_MODEL_LOAD_ORDER )
	public void initialize () {

		CSAP_ROOT_DEFINITION_FILE = new File( STAGING + "/conf/" + CSAP_DEFAULT_DEFINITION_NAME );

		if ( isDesktopProfileActive() || isJunit() ) {
			developerMode = true;
		}

		if ( isAdminProfileActive() || System.getProperty( "mgrUi" ) != null ) {
			logger.warn( "Found admin profile or -DmgrUi on command line, switching jvm to manager mode" );
			setJvmInManagerMode( true );
			Application.AGENT_NAME_PORT = "admin_8911";
		} else {
			logger.info( "Did not find mgrUi on command line, running in agent mode" );
		}

		try {

			if ( isRunningOnDesktop() ) {
				developmentModeSetup();
			}

			if ( !CSAP_ROOT_DEFINITION_FILE.exists() ) {
				CSAP_ROOT_DEFINITION_FILE = new File( CSAP_ROOT_DEFINITION_FILE.getParentFile(),
					EOL_CLUSTER_CONFIG_JS );
				logger
					.warn( "Use of non default name: {}, it is recommended to switch to: {} after migrating to CSAP 3.8 in all lifecycles",
						CSAP_ROOT_DEFINITION_FILE, CSAP_DEFAULT_DEFINITION_NAME );
			}

			// junit switch for definitino
			if ( isJunit() ) {
				URL junitTestUrl = getClass()
					.getResource( "/org/csap/test/data/DEFAULT_APPLICATION.json" );

				if ( junitTestUrl == null ) {
					// String cp =
					// WordUtils.wrap(System.getProperty("java.class.path").replaceAll(";",
					// " "), 140);
					logger.info( "Unable to locate default junit definition - check classpath for {}: \n {}",
						"/org/csap/test/data/DEFAULT_APPLICATION.json" );
					System.exit( 99 );
				}

				File csapTestApplicationDefinition = new File( junitTestUrl.getPath() );

				if ( csapTestApplicationDefinition.exists() ) {
					CSAP_ROOT_DEFINITION_FILE = csapTestApplicationDefinition;
					logger.warn( "Setting JSON location from classpath for JUnits: " + CSAP_ROOT_DEFINITION_FILE );
				} else {
					logger.error(
						"Did not find junit definition: {}" + csapTestApplicationDefinition.getAbsolutePath() );
				}

			}

			String mailInfo = CSAP.pad( "Mail:" ) + "not initialized";
			if ( springEnvironment != null ) {
				mailInfo = CSAP.pad( "Mail:" ) + springEnvironment.getProperty( "spring.mail.host" ) + ":"
						+ springEnvironment.getProperty( "spring.mail.port" );
			}
			logger
				.warn( "\n\t {}{} \n\t {}{} \n\t {}{} \n\t {}{} \n\t {}{} \n\t {}\n\n",
					CSAP.pad( "Agent Working:" ), processWorkingDirectory,
					CSAP.pad( "PROCESSING:" ), PROCESSING,
					CSAP.pad( "STAGING:" ), STAGING,
					CSAP.pad( "Definition:" ), CSAP_ROOT_DEFINITION_FILE.toString(),
					CSAP.pad( "Host Pattern:" ), getAgentHostUrlPattern( false ),
					mailInfo );

			// final CHECK
			if ( !CSAP_ROOT_DEFINITION_FILE.exists() ) {
				CSAP_ROOT_DEFINITION_FILE = new File( CSAP_ROOT_DEFINITION_FILE.getParentFile(),
					EOL_CLUSTER_CONFIG_JS );
				logger
					.warn( "\n\n\n ***************  Failed to load: {} - Verify working folder is correct  ********************\n\n\n",
						CSAP_ROOT_DEFINITION_FILE.getAbsolutePath() );
				try {
					Thread.sleep( 30000 ); // delay to view message in console
				} catch (InterruptedException e) {
					// nop
				}
			}

			updateApplicationVariables();

		} catch (Exception e) {
			logger.error( "Failed to initialize: {}", CSAP.getCsapFilteredStackTrace( e ) );
		}
		try {
			if ( isAutoReload() )
				updateCache( true );
		} catch (Exception e) {
			logger.error( "Failed to update cache {}", CSAP.getCsapFilteredStackTrace( e ) );
		}

		resetPendingResourceUpdates();
		logger.info( "========== Properties initialized " );
	}

	private void updateApplicationVariables ()
			throws UnknownHostException {

		httpdIntegration.updateConstants();

		WAR_DIR = STAGING + CSAP_SERVICE_PACKAGES;
		BUILD_DIR = STAGING + "/build/";
		// Use relative paths to handle all use cases
		CSAP_DEFINITION_FOLDER_FOR_JUNITs = getDefinitionFile()
			.getParentFile()
			.getAbsolutePath();
		InetAddress addr = InetAddress.getLocalHost();

		// Get hostname
		HOST_NAME = addr.getHostName();
		if ( HOST_NAME.indexOf( "." ) != -1 ) {
			// in case of host.somecompany.com, strip off domain
			HOST_NAME = HOST_NAME.substring( 0, HOST_NAME.indexOf( "." ) );
		}

		// hook for testing on eclipse
		logger.info( "Host name is: {}", HOST_NAME );
		if ( isRunningOnDesktop() ) {
			HOST_NAME = "localhost";
			//
			logger.info( "\n\n Did not find STAGING env var,  forcing test mode - setting host to localhost \n\n" );
		}

		if ( isJvmInManagerMode() ) {
			refreshConfigHandleOnlyOnMgrs = refreshConfigPool.scheduleWithFixedDelay(
				new Runnable() {

					@Override
					public void run () {
						updateCache( false );
					}
				}, 10, 10, TimeUnit.SECONDS );
		}
	}

	private void developmentModeSetup () {

		logger.warn( "\n\n =========== Running in Development mode, stubbed OS output will be used =================\n\n" );

		addPathToJVM( "devData/stubResults" );

		if ( isJunit() ) {
			// addPathToJVM( "devData/stubResults" );
			// special hook for eclipse JUnit
			JUNIT_CLUSTER_PREFIX = "target/junit/" + "JUNIT_CLUSTER_";

		}
		try {

			File fullPath = new File( PROCESSING );
			PROCESSING = fullPath.getCanonicalPath();
			fullPath = new File( STAGING );
			STAGING = fullPath.getCanonicalPath();

		} catch (IOException e) {
			logger.error( "Failed: {}", CSAP.getCsapFilteredStackTrace( e ) );
		}

		CSAP_ROOT_DEFINITION_FILE = new File(
			CsapCoreService.getDefinitionFolder(),
			CSAP_DEFAULT_DEFINITION_NAME );

		try {
			CSAP_ROOT_DEFINITION_FILE = CSAP_ROOT_DEFINITION_FILE.getCanonicalFile();
		} catch (IOException e) {
			logger.error( "Failed to resolve desktop definition" );
		}

		logger.info( "Definition file default location: {}",
			CSAP_ROOT_DEFINITION_FILE.getAbsolutePath() );

		File baseFolder = new File( STAGING );
		if ( !baseFolder.exists() ) {
			logger.warn( "Creating: " + baseFolder.getAbsolutePath() );
			baseFolder.mkdirs();
		}
		baseFolder = new File( PROCESSING );
		if ( !baseFolder.exists() ) {
			baseFolder.mkdirs();
		}
	}

	// should be enum
	public static final String COLLECTION_JMX = "jmx";
	public static final String COLLECTION_SERVICE = "service";
	public static final String COLLECTION_RESOURCE = "resource";

	/**
	 * @return the pendingResourceOperations
	 */
	public ObjectNode getPendingResourceOperations () {
		return pendingResourceOperations;
	}

	/**
	 * @param pendingResourceOperations
	 *            the pendingResourceOperations to set
	 */
	public void setPendingResourceOperations ( ObjectNode pendingResourceOperations ) {
		this.pendingResourceOperations = pendingResourceOperations;
	}

	/**
	 * TOKEN is used so that path on other VMs is relative to local CSAP install
	 * folder
	 *
	 * @return
	 */

	public enum FileToken {
		STAGING( "__staging__" ),
		PROCESSING( "__processing__" ),
		PROPERTY( "__props__" ),
		HOME( "__home__" ),
		ROOT( "__root__" ),
		DOCKER( "__docker__" );

		public String value;

		private FileToken( String value ) {
			this.value = value;
		}
	}

	private static final String SAVED = "saved";

	private static final String CSAP_SCRIPTS_TOKEN = FileToken.STAGING.value + "/" + SAVED + "/" + SCRIPTS_RUN + "/";
	public static final String CSAP_SAVED_TOKEN = FileToken.STAGING.value + "/" + SAVED + "/";

	public static final String CSAP_SERVICE_PACKAGES = "/csap-packages/";
	public static final String CSAP_PACKAGES_TOKEN = FileToken.STAGING.value + CSAP_SERVICE_PACKAGES;

	public static final String MISSING_SERVICE_MESSAGE = "Did not find service. ";
	public static final String ALL_PACKAGES = "All Packages";

	private static File CSAP_ROOT_DEFINITION_FILE = null;
	private static final String EOL_CLUSTER_CONFIG_JS = "clusterConfig.js";
	public static final String CSAP_DEFAULT_DEFINITION_NAME = "Application.json";

	public File getDefinitionFile () {
		return CSAP_ROOT_DEFINITION_FILE;
	}

	public static final String AGENT_ID = CSAP.AGENT_CONTEXT;
	public static String AGENT_NAME_PORT = AGENT_ID + "_" + CSAP.AGENT_PORT;
	//
	public static final String FACTORY = "factory";
	static private String HOST_NAME = "localhost";

	/**
	 * This checks the file system to see if there are any host dirs that have
	 * been updated/added/removed
	 */
	static String instanceConfigContents = "empty";

	private static boolean jvmInManagerMode = false;

	public static String SKIP_TOMCAT_JAR_SCAN = "skipTomcatJarScan";

	public static final String SYS_USER = "System";

	private static String WAR_DIR;

	public static File getProcessingTempFolder () {
		File processingTemp = new File( PROCESSING + "/_pTemp" );

		if ( !processingTemp.exists() ) {
			logger.info( "Creating: {}", processingTemp.getAbsolutePath() );
			if ( !processingTemp.mkdirs() ) {
				logger.error( "\n\n\n ********** Failed creating: {}", processingTemp.getAbsolutePath() );
			}
		}

		return processingTemp;
	}

	public static String getPROCESSING () {
		return PROCESSING;
	}

	public static String stagingPathForParsingOnly () {
		return STAGING;
	}

	private static File stagingFolder;

	public File getStagingFolder () {

		if ( stagingFolder == null ) {

			stagingFolder = new File( STAGING );

			try {
				stagingFolder = stagingFolder.getCanonicalFile();
			} catch (IOException e) {
				logger.error( "Failed to resolve {} ", stagingFolder, e );
			}

		}
		return stagingFolder;
	}

	public String stagingFolderAsString () {
		return STAGING;
	}

	public static File getStagingFile ( String path ) {

		return new File( stagingFolder, path );
	}

	private HttpdIntegration httpdIntegration = new HttpdIntegration( this );

	public HttpdIntegration getHttpdIntegration () {
		return httpdIntegration;
	}

	public String getFromSpringEnvironment ( String variableName, String defaultIfNull ) {

		if ( springEnvironment == null )
			return defaultIfNull;

		return springEnvironment.getProperty( variableName );
	}

	static boolean printPatternWarning = true;

	public String getAgentHostUrlPattern ( boolean checkForInternalOverride ) {

		if ( springEnvironment == null ) {
			if ( printPatternWarning )
				logger.warn( "Spring is NULL, using default" );
			printPatternWarning = false;
			return "http://CSAP_HOST." + CSAP.DEFAULT_DOMAIN + ":" + CSAP.AGENT_PORT + "/" + CSAP.AGENT_CONTEXT;
		}
		String agentUrl = springEnvironment.getProperty( "csap-agent.host-url-pattern" );
		String internalAgentUrl = springEnvironment.getProperty( "csap-agent.host-url-pattern-internal" );

		if ( !checkForInternalOverride || internalAgentUrl == null ) {
			// use for all Browser UI requests, and most of the time when
			// internal/external hosts are the same
			return agentUrl;
		} else {
			// private networking is being used - so local host url is different
			// when
			// agent connectivity is being used.
			return internalAgentUrl;
		}
	}

	// Used to get launch urls for services, using the suffix in yml
	public String getApplicationUrl ( String host, String context ) {
		String appUrl = getAgentHostUrlPattern( true ).replaceAll( "CSAP_HOST", host );

		String agentPortAndName = ":" + CSAP.AGENT_PORT + "/" + AGENT_ID;
		// strip off agent and port context
		if ( appUrl.contains( agentPortAndName ) ) {
			appUrl = appUrl.substring( 0, appUrl.indexOf( agentPortAndName ) );
		}

		appUrl += context;
		// logger.debug("host pattern: {}, appUrl: {}", getAgentHostUrlPattern(
		// true ), appUrl );

		return appUrl;
	}

	public String getAgentUrl ( String host, String target ) {
		return getAgentUrl( host, target, false );
	}

	// Use in templates!!! search strings
	public String getAgentUrl ( String host, String target, boolean checkForInternalOverride ) {


		if ( host.equals( "$host" ) ) {
			host = Application.getHOST_NAME();
		}
		try {
			String pattern = getAgentHostUrlPattern( checkForInternalOverride );
			String url = pattern.replaceAll( "CSAP_HOST", host ) + target;
			// logger.info( "pattern: {} resolved: {}", pattern, url );
			return url;
		} catch (Throwable t) {

			logger.error( "Error in host name: {} for target: {}", host, target, t );
			String url = getAgentHostUrlPattern( checkForInternalOverride ).replaceAll( "CSAP_HOST", "ERROR_IN_HOST_NAME" ) + target;
			return url;
		}
	}

	private static String JUNIT_CLUSTER_PREFIX = "JUNIT_CLUSTER_";

	@Autowired
	private Environment springEnvironment;

	public String getCompanyConfiguration ( String key, String defaultValue ) {

		if ( springEnvironment == null )
			return defaultValue;
		// logger.info("Getting: {}", key) ;
		return springEnvironment.getProperty( key, defaultValue );
	}

	public boolean isCompanyVariableConfigured ( String key ) {
		if ( springEnvironment != null && springEnvironment.getProperty( key ) != null ) {
			return true;
		}
		return false;
	}

	@Autowired
	private HostAlertProcessor hostAlerts;

	@Autowired
	private CsapCoreService csapCoreService;

	public boolean isJunit () {
		if ( springEnvironment == null ) {
			return true;
		}
		return Arrays.asList( springEnvironment.getActiveProfiles() ).contains( "junit" );
	}

	public boolean isDesktopProfileActive () {
		if ( springEnvironment == null ) {
			return true;
		}
		return Arrays.asList( springEnvironment.getActiveProfiles() ).contains( "desktop" );
	}

	@Autowired
	StandardPBEStringEncryptor encryptor;

	public String decode ( String input, String description ) {
		String result = input;

		if ( encryptor == null )
			return result;
		try {
			result = encryptor.decrypt( input );
		} catch (EncryptionOperationNotPossibleException e) {
			logger.warn( "{} is not encrypted.  Use CSAP encrypt to generate",
				description );
		} catch (Exception e) {
			logger.warn( "{} Encryption error: {}",
				description, CSAP.getCsapFilteredStackTrace( e ) );
		}
		return result;
	}

	final public static int CSAP_MODEL_LOAD_ORDER = 1;
	final public static int CSAP_SERVICE_STATE_LOAD_ORDER = 2;

	private boolean isAdminProfileActive () {

		// JUNITS
		if ( springEnvironment == null )
			return false;

		return Arrays.asList( springEnvironment.getActiveProfiles() ).contains( "admin" );
	}

	static List<String> pathsAdded = new ArrayList<>();

	public void addPathToJVM ( String pathToBeAdded ) {

		// junits will repeatedly initialize
		if ( pathsAdded.contains( pathToBeAdded ) )
			return;
		pathsAdded.add( pathToBeAdded );

		try {
			logger.warn( "Adding new path to JVM resources: {}", pathToBeAdded );
			File pathInFile = new File( pathToBeAdded );
			URI uriWithNewPath = pathInFile.toURI();
			URLClassLoader urlClassLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();
			Class<URLClassLoader> urlClass = URLClassLoader.class;
			Method method = urlClass.getDeclaredMethod( "addURL", new Class[] { URL.class } );
			method.setAccessible( true );
			method.invoke( urlClassLoader, new Object[] { uriWithNewPath.toURL() } );
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| MalformedURLException e) {

			logger.error( "Failed to add path: {}", CSAP.getCsapFilteredStackTrace( e ) );
		}
	}

	@Autowired
	ResourceUrlProvider mvcResourceUrlProvider;

	public String versionedUrl ( String path ) {
		return mvcResourceUrlProvider.getForLookupPath( path );
	}

	// requires.js will do relative lookups - only return the name
	public String requiresUrl ( String path ) {
		String requiresJsName = mvcResourceUrlProvider.getForLookupPath( path );
		// try {
		// requiresJsName = requiresJsName.substring(
		// requiresJsName.lastIndexOf( "/" ) );
		// requiresJsName = requiresJsName.substring( 1, requiresJsName.indexOf(
		// "." ) );
		// } catch ( Exception e ) {
		// logger.error("Failed to version path: {}, looked up: {}", path,
		// requiresJsName) ;
		// requiresJsName = "Verify/path/requested/" + path;
		// }
		return requiresJsName;
	}

	public ReleasePackage getRootModel () {
		return _modelParser.getRootModel();
	}

	public List<String> getPackageNames () {
		return getRootModel()
			.getReleasePackageNames()
			.collect( Collectors.toList() );
	}

	public Map<String, String> getPackageNameToFileMap () {

		HashMap<String, String> map = new HashMap<>();

		getReleasePackageStream().forEach( releasePackage -> {
			map.put( releasePackage.getReleasePackageName(), releasePackage.getReleasePackageFileName() );
		} );

		return map;
	}

	public Map<String, String> getHelpMenuMap () {
		return getRootModel()
			.getHelpMenuUrlMap();
	}

	public List<ReleasePackage> getPackageModels () {
		return getRootModel()
			.getReleasePackages()
			.collect( Collectors.toList() );
	}

	public Stream<ReleasePackage> getReleasePackageStream () {
		return getRootModel()
			.getReleasePackages();
	}

	// public Stream<ArrayList<InstanceConfig>>
	// getServiceConfigStreamCurrentLC() {
	// return _globalModel.getSvcToConfigMapCurrentLC().values().stream();
	// }
	public ReleasePackage getActiveModel () {
		return getRootModel()
			.getActiveModel();
	}

	public String getActiveModelName () {
		return getRootModel()
			.getActiveModel()
			.getReleasePackageName();
	}

	public ReleasePackage getModel ( String releasePackage ) {
		if ( releasePackage.equals( "default" ) ) {
			return getRootModel();
		}

		if ( releasePackage.equals( ALL_PACKAGES ) ) {
			return getRootModel()
				.getAllPackagesModel();
		}

		return getRootModel()
			.getReleasePackage( releasePackage );
	}

	/**
	 * Main Data is cached for 5 minutes; war timestamps are cached for 60s
	 */
	public void updateCache ( boolean force ) {

		updateApplication();

		updateServiceTimeStamps( force ); // only occurs every 60s

	}

	DefinitionParser _modelParser = new DefinitionParser( this );

	public DefinitionParser getParser () {
		return _modelParser;
	}

	public StringBuffer getLastTestParseResults () {
		return lastParseResults;
	}

	private StringBuffer lastParseResults = null;

	// Helper method for testing
	public boolean loadDefinitionForJunits ( boolean isTest, File definitionFile )
			throws JsonProcessingException, IOException {

		if ( isJunit() ) {
			logger.debug( "unit tests ----- updating PROCESSING AND STAGING" );
			String now = LocalDateTime.now().format( DateTimeFormatter.ofPattern( "MMM.d-HH.mm.ss" ) );
			PROCESSING = "target/junit/junit-" + now + "/processing";
			STAGING = "target/junit/junit-" + now + "/staging";
		}

		if ( springEnvironment != null ) {
			// setting up for junits running in spring context
			updateApplicationVariables();
		}
		//
		logger.info( "\n\n =========  Unit Testing: {}, PROCESSING: {}, STAGING: {}",
			definitionFile.getName(), PROCESSING, STAGING );

		lastParseResults = _modelParser.parseConfig( isTest, definitionFile );

		int errorIndex = lastParseResults.indexOf( CSAP.CONFIG_PARSE_ERROR );
		if ( errorIndex >= 0 ) {
			logger.warn( "Found errors: {}", lastParseResults.substring( errorIndex ) );
			return false;
		}

		logger.info( "springEnvironment: {}, isJvmInManagerMode: {} ", springEnvironment, isJvmInManagerMode() );

		if ( springEnvironment != null && !isJvmInManagerMode() ) {
			startCollectorsForJunit();
		}

		setBootstrapComplete();
		int warnIndex = lastParseResults.indexOf( CSAP.CONFIG_PARSE_WARN );
		if ( warnIndex >= 0 ) {
			logger.warn( "Found warnings: {}", lastParseResults.substring( warnIndex ) );
			return false;
		}
		// _osManager.checkForProcessStatusUpdate();

		return true;
	}

	public void startCollectorsForJunit () {
		if ( !collectorsStarted ) {
			collectorsStarted = true;
			startResourceCollectors();
		}
	}

	public JsonNode getDefinitionForActivePackage () {
		return getActiveModel()
			.getJsonModelDefinition();
	}

	/**
	 * Mongo chokes on "." in field names. Arguably not a desirable practice
	 * anyway
	 *
	 * @param definitionNode
	 * @param location
	 * @return
	 */
	public ObjectNode getDefinitionForAllPackages () {
		ObjectNode applicationDefinition = jacksonMapper.createObjectNode();

		logger.debug( "Definition Folder: {}", getDefinitionFolder() );
		File[] definitionFiles = getDefinitionFolder().listFiles();

		ArrayNode definitions = applicationDefinition.putArray( "definitions" );

		getReleasePackageStream().forEach( releasePackage -> {
			ObjectNode item = definitions.addObject();
			item.put( "fileName", releasePackage.getReleasePackageFileName() );
			try {
				item.put( "content", releasePackage.getJsonModelDefinition().toString() );
			} catch (Exception e) {
				item.put( "content", "Failed to parse:" + e.getMessage() );
				logger.error( "Failed to create definition object for upload: {}", CSAP.getCsapFilteredStackTrace( e ) );
			}
		} );

		return applicationDefinition;
	}

	public String buildHttpdConfiguration () {
		return httpdIntegration.buildHttpdConfiguration();
	}

	public ObjectNode checkDefinitionForParsingIssues (
														String updatedConfig,
														String releasePackage,
														String outputLocation ) {
		ObjectNode resultNode = jacksonMapper.createObjectNode();

		resultNode.put( releasePackage, releasePackage );
		ArrayNode errorNode = resultNode.putArray( VALIDATION_ERRORS );
		ArrayNode warningsNode = resultNode.putArray( VALIDATION_WARNINGS );

		OutputFileMgr outputManager = null;
		try {
			// Critical hook - need to blow away previous folder since there is
			// no
			// clean
			outputManager = new OutputFileMgr( getProcessingDir(),
				outputLocation );

			// EXCEPTION: file does not exist....
			String selectedConfig = getModel( releasePackage )
				.getReleasePackageFileName();
			// Create a new empty working folder for the uploaded file
			// Working folder is used solely to validate contents, then will be
			// moved to build folder prior to triggering reload
			File workingFolder = new File( getRootModelBuildLocation()
					+ CONFIG_SUFFIX_FOR_UPDATE );

			FileUtils.deleteQuietly( workingFolder );

			// createWorkingFolder using existing live files
			FileUtils.copyDirectory( getDefinitionFolder(), workingFolder,
				FileFilterUtils.suffixFileFilter( ".js" ) );
			FileUtils.copyDirectory( getDefinitionFolder(), workingFolder,
				FileFilterUtils.suffixFileFilter( ".json" ) );
			outputManager.print( "Created working folder: " + workingFolder.getAbsolutePath()
					+ "\n initialized from: " + getDefinitionFolder()
					+ "\n containing: " + Arrays.asList( workingFolder.list() ) );

			// First put the uploaded file into working directory
			File tempConfigFile = new File( workingFolder, selectedConfig );
			File tempGlobalCluster = new File( workingFolder, getRootModel()
				.getReleasePackageFileName() );
			FileUtils.writeStringToFile( tempConfigFile, updatedConfig.replaceAll( "\r", "\n" ) );
			outputManager.print( "Pushed updated config to : " + tempConfigFile.getAbsolutePath() );

			// Now run parser
			// first we run in test mode to verify content
			StringBuffer parsingResultsBuffer = _modelParser.parseConfig( true,
				tempGlobalCluster );
			logger.debug( "parsing results: \n{}", parsingResultsBuffer.toString() );
			if ( (parsingResultsBuffer != null) ) {

				if ( parsingResultsBuffer.indexOf( CSAP.CONFIG_PARSE_WARN ) != -1 ) {

					updateOutputWithLimitedInfo( CSAP.CONFIG_PARSE_WARN, 25, outputManager,
						parsingResultsBuffer, warningsNode );

				}

				if ( parsingResultsBuffer.indexOf( CSAP.CONFIG_PARSE_ERROR ) != -1 ) {

					updateOutputWithLimitedInfo( CSAP.CONFIG_PARSE_ERROR, 25, outputManager,
						parsingResultsBuffer, errorNode );

				}
			}
			if ( (parsingResultsBuffer != null)
					&& parsingResultsBuffer.indexOf( CSAP.CONFIG_PARSE_ERROR ) == -1 ) {
				resultNode.put( "success", true );
			} else {
				resultNode.put( "success", false );
				logger.error( "Failed to parse, look for errors in: {}", parsingResultsBuffer );
				if ( (parsingResultsBuffer != null) ) {
					outputManager.print( "-" );
					outputManager
						.print( "\n\n============= Found Semantic Errors !! ====================\n"
								+ "Filtered output for :"
								+ CSAP.CONFIG_PARSE_ERROR );
				}
			}
		} catch (JsonParseException jp) {
			resultNode.put( "success", false );
			// Parsing exceptions come in here
			// logger.error("Failed to parse config", jp);
			ObjectNode errorItem = errorNode.addObject();
			errorItem.put( "type", "json" );
			errorItem.put( "message", jp.getLocalizedMessage() );
			errorItem.put( "line", jp
				.getLocation()
				.getLineNr() );
			errorItem.put( "column", jp
				.getLocation()
				.getColumnNr() );
			errorItem.put( "offset", jp
				.getLocation()
				.getCharOffset() );
		} catch (IOException e) {
			resultNode.put( "success", false );
			// Parsing exceptions come in here
			logger.error( "Failed to parse config", e );
			ObjectNode errorItem = errorNode.addObject();
			errorItem.put( "type", "semantic" );
			errorItem.put( "message", e.getLocalizedMessage() );
			// outputManager.print(Application.CONFIG_PARSE_ERROR
			// + "Failed to do parse config:\n" +
			// Application.getCustomStackTrace( e ));
		} finally {
			if ( outputManager != null ) {
				outputManager.close();
			}
		}
		return resultNode;
	}

	public static final String VALIDATION_WARNINGS = "warnings";

	public static final String VALIDATION_ERRORS = "errors";

	public void updateOutputWithLimitedInfo (	String filterString, int maxLines, OutputFileMgr outputManager,
												StringBuffer outputContents, ArrayNode alertItemsJson )
			throws IOException {

		int found = outputContents.indexOf( filterString );

		int lineCount = 0;
		String lastMatch = "";
		while (found != -1) {
			if ( (outputContents.indexOf( "\n", found ) != -1) && (lineCount < maxLines) ) {

				String newMatch = outputContents.substring( found,
					outputContents.indexOf( "\n", found ) );

				logger.debug( "newMatch: {}", newMatch );
				if ( !newMatch.equals( lastMatch ) ) {
					lineCount++;
					outputManager.print( newMatch );
					if ( alertItemsJson != null ) {
						alertItemsJson.add( newMatch );
					}
					lastMatch = newMatch;
				}
			}

			found = outputContents.indexOf( filterString, found + 5 );
		}
		if ( lineCount > maxLines ) {
			outputManager.print( "Note: only first " + maxLines + " of " + lineCount
					+ " shown. View output above for others" + "\n" );
		}

	}

	public ObjectNode getAlertsOnLocalAgent ( double alertLevel ) {

		logger.debug( "Health check" );

		ObjectNode alertMessages = jacksonMapper.createObjectNode();
		ArrayNode errorArray = alertMessages.putArray( VALIDATION_ERRORS );
		try {

			alertMessages = hostAlerts.alertsBuilder( alertLevel, Application.getHOST_NAME(),
				(ObjectNode) getOsManager().getHostRuntime() );

		} catch (Exception e) {
			if ( logger.isDebugEnabled() ) {
				logger.error( "Failed checking VM heath", e );
			}
			errorArray.add( Application.getHOST_NAME() + ": " + "Failed HealthCheck reason: " + e.getMessage() );
		}

		return alertMessages;
	}

	public ObjectNode getAlertsOnRemoteAgent ( double alertLevel, String hostName ) {

		logger.debug( "Health check on: {}", hostName );

		ObjectNode alertMessages = jacksonMapper.createObjectNode();
		ArrayNode errorArray = alertMessages.putArray( VALIDATION_ERRORS );

		if ( getHostStatusManager() == null ) {
			// if ( isRunningOnDesktop()) return result ;
			errorArray.add( "Host Status manager is not initialized" );
			return alertMessages;
		}

		ObjectNode hostStatusJson = getHostStatusManager().getResponseFromHost( hostName );

		if ( hostStatusJson == null ) {
			errorArray.add( "No response found" );
			return alertMessages;
		} else {

			try {

				alertMessages = hostAlerts.alertsBuilder( alertLevel, hostName, hostStatusJson );

			} catch (Exception e) {
				if ( logger.isDebugEnabled() ) {
					logger.error( "Failed checking VM heath", e );
				}
				errorArray.add( hostName + ": " + "Failed HealthCheck reason: " + e.getMessage() );
				return alertMessages;
			}
		}

		return alertMessages;
	}

	public ObjectNode getServiceCollection ( String[] hostNames, String service_port ) {

		ObjectNode serviceDataAllHosts = jacksonMapper.createObjectNode();
		ArrayNode dataArray = serviceDataAllHosts.putArray( "data" );
		try {

			for ( String hostName : hostNames ) {
				ObjectNode serviceHostData = serviceDataAllHosts.putObject( hostName );

				ObjectNode hostCollectedNode = null;

				if ( !Application.jvmInManagerMode ) {
					hostCollectedNode = (ObjectNode) _osManager.getHostRuntime();

				} else {

					hostCollectedNode = getHostStatusManager().getResponseFromHost( hostName );

					if ( hostCollectedNode == null ) {
						serviceHostData.put( "error", "No response found for host" );
					}

				}

				if ( hostCollectedNode != null ) {
					ObjectNode servicesNode = (ObjectNode) hostCollectedNode.path( "services" );
					if ( servicesNode == null ) {
						serviceDataAllHosts.put( "error", "No response found for host" );
						return serviceDataAllHosts;
					}

					if ( !servicesNode.has( service_port ) ) {

						serviceDataAllHosts.put( "error", "No response found for service_port" );
						return serviceDataAllHosts;
					}
					ObjectNode serviceHost = (ObjectNode) servicesNode.get( service_port );
					serviceHost.put( "hostName", hostName );
					dataArray.add( serviceHost );
				}
			}

			// return (ObjectNode) servicesNode.get( service_port );
		} catch (Exception e) {

			serviceDataAllHosts.put( "error", "No response found for host" );

		}

		return serviceDataAllHosts;
	}

	/**
	 * invoke from JSP
	 *
	 * @param instance
	 * @return
	 */
	private void scanFileSystemForVersion ( ServiceInstance instance ) {

		StringBuilder version = new StringBuilder();

		if ( instance.isOs() ) {
			version.append( "OS managed" );

		} else if ( instance.isDockerContainer() ) {

			String dockerVersion = "Docker managed";

			if ( isDockerInstalledAndActive()
					&& instance.getDockerVersionCommand() != null ) {

				String versionOutput = dockerHelper.containerCommand(
					instance.getDockerContainerPath(),
					instance.getDockerVersionCommand().split( " " ) );
				if ( versionOutput.length() > 0 ) {
					dockerVersion = versionOutput;
					if ( versionOutput.length() > 20 ) {
						dockerVersion = versionOutput.substring( 0, 19 );
					}
				}
			}
			version.append( dockerVersion );

		} else if ( instance.isWrapper() || instance.isSpringBoot() ) {
			File versionFile = new File( getProcessingDir(), instance.getServiceName_Port() + "/version" );

			if ( !versionFile.exists() ) {
				// fallback to propertyFolder
				versionFile = new File( instance.getPropDirectory() + "/version" );
			}

			logger.debug( "Scanning processing folder for version info: {} ", versionFile.getAbsolutePath() );

			if ( versionFile.exists() ) {
				File[] filesInFolder = versionFile.listFiles();
				if ( filesInFolder.length == 1 ) {
					version.append( filesInFolder[0].getName() );
				} else {
					version.append( "*Too Many Versions*" );
				}
			} else if ( instance.isOsWrapper() ) {
				version.append( "*PartialReImage" );
			} else {
				version.append( "*Not Deployed" );
			}
		} else {
			File targetFile = new File( getProcessingDir(), instance.getServiceName() + "_"
					+ instance.getPort() + "/webapps" );

			logger.debug( "Scanning processing folder for version info: {}", targetFile.getAbsolutePath() );

			if ( targetFile.exists() ) {
				File[] filesInFolder = targetFile.listFiles();
				for ( File itemInFolder : filesInFolder ) {
					if ( itemInFolder.isDirectory() ) {
						if ( itemInFolder
							.getName()
							.contains( "##" ) ) {
							if ( version.length() > 0 ) {
								version.append( "," );
							}
							version.append( itemInFolder.getName() );
						}
					}
				}
			} else {
				version.append( "*Not Deployed" );
			}
		}
		instance.setDeployedArtifacts( version.toString() );
		return;
	}

	private boolean autoReload = true;

	public boolean isAutoReload () {
		return autoReload;
	}

	public void setAutoReload ( boolean autoReload ) {
		this.autoReload = autoReload;
	}

	@Inject
	CsapInformation csapInfo;

	public ObjectNode getCapabilitySummary ( boolean isIncludeHealth ) {
		ObjectNode summaryJson = jacksonMapper.createObjectNode();
		summaryJson.put( "name", getName() );
		summaryJson.put( "lifecycle", Application.getCurrentLifeCycle() );

		if ( csapInfo != null ) {
			summaryJson.put( "version", csapInfo.getVersion() );
		} else {
			summaryJson.put( "version", "not-loaded" );
		}
		summaryJson.put( "projectUrl", lifeCycleSettings().getLbUrl() );

		if ( isIncludeHealth ) {
			try {
				ObjectNode healthJson = summaryJson.putObject( "health" );
				ObjectNode errorNode = buildErrorsForAdminOrAgent( 1.0 );
				if ( errorNode.size() == 0 ) {
					healthJson.put( "Healthy", true );
				} else {

					healthJson.put( "Healthy", false );
					healthJson.set( "issues", errorNode );
				}
			} catch (Exception e) {
				logger.warn( "Failed to get health", e );
			}
		}

		if ( Application.isJvmInManagerMode() ) {
			// ObjectNode packageSummary =
			// summaryJson.putObject("packageSummary");
			ArrayNode arraySummary = summaryJson.putArray( "packages" );

			getRootModel()
				.getReleasePackages()
				.forEach( model -> {

					ObjectNode packageItem = arraySummary.addObject();
					packageItem.put( "package", model.getReleasePackageName() );

					if ( model.getInstanceTotalCountInCurrentLC() > 0 ) {
						// ObjectNode packageItem =
						// packageSummary.putObject(key);
						packageItem.put( "vms",
							model
								.getLifeCycleToHostInfoMap()
								.get( Application.getCurrentLifeCycle() )
								.size() );
						packageItem.put( "services", model
							.serviceInstancesInCurrentLifeByName()
							.size() );
						packageItem.set( "instances", model.getInstanceCountInCurrentLC() );
						packageItem.set( "clusters", getClusters( model ) );
						packageItem.set( "metrics", getMetricsConfiguriation( model ) );
					}

				} );

			ObjectNode packageItem = arraySummary.addObject();
			packageItem.put( "package", "all" );
			// ObjectNode packageItem = packageSummary.putObject("all");
			packageItem.put( "vms", getModel( Application.ALL_PACKAGES )
				.getLifeCycleToHostInfoMap()
				.get( Application.getCurrentLifeCycle() )
				.size() );
			packageItem.put( "services", getModel( Application.ALL_PACKAGES )
				.serviceInstancesInCurrentLifeByName()
				.size() );
			packageItem.put( "instances", getModel( Application.ALL_PACKAGES )
				.getInstanceTotalCountInCurrentLC() );

			summaryJson.set( "serviceAttributes", servicePerformanceLabels() );
		} else {
			summaryJson
				.put( "error",
					"Capability Summary is only available from Deployment Manager service. Vm Name: admin." );
		}

		return summaryJson;
	}

	private ObjectNode getMetricsConfiguriation ( ReleasePackage releasePackage ) {
		ObjectNode configJson = jacksonMapper.createObjectNode();

		// String type = reqType;
		// if (reqType.startsWith("jmx"))
		// type = "jmx";
		for ( String metricType : lifeCycleSettings()
			.getMetricToSecondsMap()
			.keySet() ) {

			ArrayNode samplesArray = configJson.putArray( metricType );
			for ( Integer sampleInterval : lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( metricType ) ) {
				samplesArray.add( sampleInterval );
			}
		}

		return configJson;
	}

	public ObjectNode getClusters ( ReleasePackage model ) {
		ObjectNode clusterJson = jacksonMapper.createObjectNode();

		String vmLifeCycle = Application.getCurrentLifeCycle();

		logger.debug( "csapApp.getActiveModel().getLifeCycleToGroupMap(): {} ", getActiveModel()
			.getLifeCycleToGroupMap() );
		for ( String cluster : model
			.getLifeCycleToGroupMap()
			.get( vmLifeCycle ) ) {

			// logger.info("cluster: " + cluster);
			for ( String version : model
				.getGroupToVersionMap()
				.get( vmLifeCycle + cluster ) ) {
				String label = cluster + "-" + version;
				ArrayNode clusterArray = clusterJson.putArray( label );
				ArrayList<String> hostList = model
					.getLcGroupVerToHostMap()
					.get(
						vmLifeCycle + cluster + version );
				if ( hostList == null ) {
					continue;
				}
				for ( String host : hostList ) {
					clusterArray.add( host );
				}
			}
		}

		ArrayNode allArray = clusterJson.putArray( "all" );
		for ( String host : getHostsForCurrentLifecycle( model ) ) {
			allArray.add( host );
		}

		return clusterJson;
	}

	private HostStatusManager _hostStatusManager = null;

	public void setHostStatusManager ( HostStatusManager hostStatusManager ) {
		this._hostStatusManager = hostStatusManager;
	}

	public HostStatusManager getHostStatusManager () {
		return _hostStatusManager;
	}

	@Inject
	ActiveUsers activeUsers;

	/**
	 * 
	 * Note run on a background threads - but updates in response to either UI
	 * or platform updates.
	 * 
	 */
	synchronized private void updateApplication () {

		long currentTimeStampTotals = addDefinitionFilesLastModified();
		if ( (sumOfDefinitionTimestamps != currentTimeStampTotals) && isAutoReload() ) {
			sumOfDefinitionTimestamps = currentTimeStampTotals;
			reloadApplicationDefinition();

			if ( !isStatefulRestartNeeded() && !isJvmInManagerMode() && !collectorsStarted ) {

				startResourceCollectors();
			}

			// update jobs based on settings
			if ( getOsManager().getInfraRunner() != null ) {
				getOsManager().getInfraRunner().scheduleInfrastructure();
			}

		} else {
			logger.debug( "Timestamp not modified on file: {}", getDefinitionFile().getAbsolutePath() );

		}

	}

	// private DateFormat dateFormatter = DateFormat.getDateInstance(
	// DateFormat.FULL );
	DateFormat dateFormatter = new SimpleDateFormat( "HH:mm:ss MMM-dd-yyy" );

	private void reloadApplicationDefinition () {
		logger.warn( "Updated Definition found, reloading" );

		if ( isJvmInManagerMode() && _hostStatusManager != null ) {
			_hostStatusManager.wipeList();
		}

		try {

			StringBuffer resultsBuf = _modelParser.parseConfig( false, getDefinitionFile() );
			logger.debug( "Parse results: {} ", resultsBuf.toString() );

			configureApiSecurity();

			refreshAgentHttpConnections();

			if ( isJvmInManagerMode() ) {
				updateManagerAgentInstances();
			}

			lastClusterLoadTime = System.currentTimeMillis();

			JsonNode data = latestReloadInfo();
			csapEventClient.publishEvent(
				CsapEventClient.CSAP_SYSTEM_CATEGORY + "/model/reload", "Reloaded Cluster",
				data, null );

			logger.info( "{}", jacksonMapper
				.writerWithDefaultPrettyPrinter()
				.writeValueAsString( data ) );

		} catch (Exception e) {
			logger.error( "Failed to parse {}", CSAP.getCsapFilteredStackTrace( e ) );

			csapEventClient.publishEvent( "CsAgent",
				" parsing: " + getDefinitionFile().getAbsolutePath(), "Parse Failure", e );
		}
	}

	private void configureApiSecurity () {
		if ( csapRestFilter == null || !lifeCycleSettings().isAgentLocalAuth() ) {
			logger.info( "Agent apis using LDAP" );
		} else {
			logger.debug( "Updated Agent credentials" );
			if ( lifeCycleSettings().getAgentPass() == null ) {
				String hash = getRootModel().getName() + Application.getCurrentLifeCycle();
				logger.info( "Generating hash: {}", hash );
				lifeCycleSettings().setAgentPass( encryptor.encrypt( hash ) );
			}
			String localPass = lifeCycleSettings().getAgentPass();
			try {
				localPass = encryptor.decrypt( localPass );
			} catch (Exception e1) {
				logger.warn( "agent api credential should be encrypted using csap encrpter" );
			}
			csapRestFilter.setLocalCredentials(
				lifeCycleSettings().getAgentUser(),
				localPass );
		}
	}

	@Autowired ( required = false )
	CsapSecurityRestFilter csapRestFilter;

	CsapRestTemplateFactory restTemplateFactory = new CsapRestTemplateFactory();
	private RestTemplate agentRestTemplate;

	private int hostRefreshIntervalSeconds = 60;
	private int agentConnectinReadTimeoutSeconds = 60;
	private int previousHostCount = 0;

	private void refreshAgentHttpConnections () {

		int latestHostCount = getAllPackages().getLifeCycleToHostMap().get( getCurrentLifeCycle() ).size();

		if ( latestHostCount > previousHostCount ) {
			// if number of hosts increase - then increase the pool
			restTemplateFactory.closeAllAndReset();
			previousHostCount = latestHostCount;
		} else {
			return;
		}

		setAgentConnectinReadTimeoutSeconds( lifeCycleSettings().getAdminToAgentTimeoutSeconds() );
		if ( !Application.isJvmInManagerMode() ) {
			// increasing timeout for script operations
			setAgentConnectinReadTimeoutSeconds( 65 );
		}

		agentRestTemplate = restTemplateFactory.buildDefaultTemplate(
			"AgentHttpConnections", getCsapCoreService().isDisableSslValidation(),
			5, latestHostCount * 2 + 10,
			lifeCycleSettings().getAdminToAgentTimeoutSeconds(),
			getAgentConnectinReadTimeoutSeconds(),
			getHostRefreshIntervalSeconds() + 30 );
	}

	static public boolean isStatefulRestartNeeded () {
		return System.getProperty( "org.csap.needStatefulRestart" ) != null;
	}

	private void updateManagerAgentInstances () {
		StringBuffer sbuf = new StringBuffer( "Service loaded in lifecycle "
				+ getCurrentLifeCycle() + ":" );

		if ( _hostStatusManager != null ) {
			_hostStatusManager.shutdown();
			_hostStatusManager = null;
		}

		for ( String host : getAllPackages()
			.getLifeCycleToHostMap()
			.get(
				getCurrentLifeCycle() ) ) {
			sbuf.append( "\n" + host + " : " );
			ArrayList<ServiceInstance> hostInstances = getAllPackages()
				.getHostToConfigMap()
				.get( host );
			for ( ServiceInstance instance : hostInstances ) {
				sbuf.append( instance.getServiceName() + "(" + instance.getPort() + ") " );
			}

		}

		logger.debug( "Services loaded: {}", sbuf.toString() );

		ArrayList<String> allHostsInAllPackages = new ArrayList<String>();
		allHostsInAllPackages.addAll( getAllPackages()
			.getLifeCycleToHostMap()
			.get(
				getCurrentLifeCycle() ) );

		_hostStatusManager = new HostStatusManager(
			this,
			lifeCycleSettings().getNumberWorkerThreads(),
			allHostsInAllPackages );
	}

	public JsonNode latestReloadInfo () {
		String lastLoad = dateFormatter.format( new Date( lastClusterLoadTime ) );

		ObjectNode data = jacksonMapper.createObjectNode();
		data.put( "Path: ", getDefinitionFile().getAbsolutePath() );
		data.put( "Services Parsed", getSvcToConfigMap()
			.keySet()
			.size() );
		data.put( "Hosts Parsed", getActiveModel()
			.getHostToAdminMap()
			.keySet()
			.size() );
		data.put( "millis", lastClusterLoadTime );
		data.put( "formated", lastLoad );
		data.put( "sumOfDefinitionTimestamps", sumOfDefinitionTimestamps );
		return data;
	}

	private long addDefinitionFilesLastModified () {
		long configFileLastModTime = 0;
		if ( getDefinitionFile().exists() && getDefinitionFile().canRead() ) {

			long totalTime = 0;

			logger.debug( "Loading definition files in: {}", getDefinitionFolder()
				.getAbsolutePath() );
			@SuppressWarnings ( "unchecked" )
			Iterator<File> jsFileIterator = FileUtils.iterateFiles( getDefinitionFolder(),
				new String[] { "js", "json" }, false );
			StringBuilder builder = null;
			if ( logger.isDebugEnabled() ) {
				builder = new StringBuilder();
			}
			while (jsFileIterator.hasNext()) {
				File jsFile = jsFileIterator.next();

				if ( logger.isDebugEnabled() ) {
					builder.append( "\n\t" + jsFile.getAbsolutePath() );
				}
				totalTime += jsFile.lastModified();
			}

			logger.debug( "Checking filestamps for file(s):  {} ", builder );
			configFileLastModTime = totalTime;
		} else {
			logger.error( "Cannot access config file:" + getDefinitionFile().getAbsolutePath() );
		}
		return configFileLastModTime;
	}

	boolean collectorsStarted = false;

	public boolean isCollectorsStarted () {
		return collectorsStarted;
	}

	private void startResourceCollectors () {

		logger.warn( "=== Kicking off background threads for jobs, logs, metricsToCsap and metricsPublish: \n {}",
			lifeCycleSettings().getMetricToSecondsMap() );

		if ( lifeCycleSettings()
			.getMetricToSecondsMap()
			.get(
				COLLECTION_RESOURCE ) != null ) {
			collectorsStarted = true;
			Collections.sort( lifeCycleSettings()
				.getMetricToSecondsMap()
				.get(
					COLLECTION_RESOURCE ) );

			boolean publishSummary = true;
			for ( Integer time : lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( COLLECTION_RESOURCE ) ) {
				osSharedResourceCollectorMap.put( time,
					new OsSharedResourcesCollector( this, getOsManager(), time,
						publishSummary ) );
				publishSummary = false;
			}
		}

		if ( !collectorsStarted ) {
			logger.info( "No resource collectors configured - Resource collectors are disabled" );
			return;
		}

		if ( lifeCycleSettings()
			.getMetricToSecondsMap()
			.get(
				COLLECTION_SERVICE ) != null ) {
			Collections.sort( lifeCycleSettings()
				.getMetricToSecondsMap()
				.get(
					COLLECTION_SERVICE ) );

			boolean publishSummary = true;
			for ( Integer time : lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( COLLECTION_SERVICE ) ) {
				osProcessCollectorMap
					.put( time, new OsProcessCollector( this, getOsManager(), time,
						publishSummary ) );
				publishSummary = false;
			}
		}

		// Sorting JMX because ONLY the lowest interval will do
		// collections. Other intervals simply use the last
		// collected
		// result to avoid overwhelming connections.
		if ( lifeCycleSettings()
			.getMetricToSecondsMap()
			.get(
				COLLECTION_JMX ) != null ) {
			Collections.sort( lifeCycleSettings()
				.getMetricToSecondsMap()
				.get(
					COLLECTION_JMX ) );

			boolean publishSummary = true;
			for ( Integer time : lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( COLLECTION_JMX ) ) {
				logger.debug( "Jmx Sorted items: {}", time );

				ServiceCollector jmxRunnable = new ServiceCollector( this,
					getOsManager(), time, publishSummary );

				publishSummary = false;
				serviceCollectorMap.put( time, jmxRunnable );
			}
		}

		if ( lifeCycleSettings()
			.isMetricsPublication() ) {
			for ( JsonNode item : lifeCycleSettings()
				.getMetricsPublicationNode() ) {
				publishers.add( new MetricsPublisher( this, (ObjectNode) item ) );
			}
		}

		// Finally - Kick Off the top thread
		getOsManager().startSharedHostResourceCollectors();

	}

	/**
	 * Timestamps shown on ui for war, only checked every 60s to avoid overhead
	 */
	private synchronized void updateServiceTimeStamps ( boolean force ) {

		if ( force | System.currentTimeMillis() - lastSvcVerLoadTimestamp > 1000 * 60 ) {
			lastSvcVerLoadTimestamp = System.currentTimeMillis();

			// get version for currentHost
			ArrayList<ServiceInstance> instanceList = getActiveModel()
				.getHostToConfigMap()
				.get(
					getHOST_NAME() );

			updateServicesWithArtifacts();
		}
	}

	public void updateServicesWithArtifacts () {
		logger.debug( "starting scan" );

		// admins scan for versions for platform score.
		// if ( isJvmInManagerMode() ) {
		// logger.debug( "Skipping scan as managers access instance state
		// through runtime data" );
		// return;
		// }
		Split allServicesSplit = SimonManager.getStopwatch( "application.artifact.scan" ).start();

		getActiveModel()
			.getServicesOnHost( Application.getHOST_NAME() )
			.filter( ServiceInstance::isFileSystemScanRequired )
			.forEach( this::scanServiceFolderForArtifacts );

		allServicesSplit.stop();

	}

	private void scanServiceFolderForArtifacts ( ServiceInstance instanceConfig ) {

		Split serviceScanTimer = SimonManager.getStopwatch( "application.artifact.scan." + instanceConfig.getServiceName() ).start();

		logger.debug( "Updating  timestamps of files in: {}", WAR_DIR );
		scanFileSystemForVersion( instanceConfig );
		scanFileSystemForArtifactDate( instanceConfig );
		scanFileSystemForEolJars( instanceConfig );

		serviceScanTimer.stop();
		instanceConfig.setFileSystemScanRequired( false );
	}

	private void scanFileSystemForEolJars ( ServiceInstance serviceInstance ) {

		Set<String> eolSet = new TreeSet<>();
		lifeCycleSettings()
			.getEolJarPatterns()
			.forEach( eolPattern -> {

				String jarPattern = eolPattern.asText();
				File libDir = getLibraryFolder( serviceInstance );
				logger.debug( "{} looking for {} in {}",
					serviceInstance.getServiceName(), jarPattern, libDir.getAbsolutePath() );
				if ( libDir.exists() && libDir.isDirectory() ) {
					String[] matches = libDir.list( ( File dir, String name ) -> {

						return name.matches( jarPattern );
					} );
					logger.debug( "{} looking for {} in {} found: {}",
						serviceInstance.getServiceName(),
						jarPattern,
						libDir.getAbsolutePath(),
						Arrays.asList( matches ) );
					if ( matches.length > 0 ) {
						eolSet.add( jarPattern );
					}
				}

			} );

		// Keep unique string for multiple matches
		ArrayNode eolJars = jacksonMapper.createArrayNode();
		eolSet.forEach( eolJars::add );

		serviceInstance.setEolJars( eolJars );

	}

	private File getLibraryFolder ( ServiceInstance serviceInstance ) {

		// default to be tomcat based as context is calculated.
		String svcId = serviceInstance.getServiceName_Port();
		File libFolder = new File( getWorkingDirectory( svcId ), serviceInstance.getLibDirectory() );
		if ( serviceInstance.getLibDirectory().length() > 0 && serviceInstance.getLibDirectory().startsWith( "/" ) ) {
			libFolder = new File( serviceInstance.getLibDirectory() );
		}

		if ( serviceInstance.isTomcatPackaging() && serviceInstance.getLibDirectory().length() == 0 ) {
			// tomcat instance have a context folder
			libFolder = new File( getPropertyFolder( svcId ).getParentFile(), "/lib" );

		} else if ( serviceInstance.isSpringBoot() ) {

			if ( !libFolder.exists() && serviceInstance.isSpringBoot() ) {
				// legacy boot lib folder
				libFolder = new File( getWorkingDirectory( svcId ),
					ServiceInstance.getBootFolder( true ) + "lib" );
			}

		}

		return libFolder;
	}

	private void scanFileSystemForArtifactDate ( ServiceInstance serviceInstance ) {
		serviceInstance.setWarDate( "Today" );

		//
		File deployFile = getServiceDeployFile( serviceInstance );

		logger.debug( "Checking deployment file: {}", deployFile.getAbsolutePath() );

		if ( deployFile.exists() ) {
			SimpleDateFormat formatter = new SimpleDateFormat( "MMM.d H:mm" );
			Date date = new Date( deployFile.lastModified() );
			serviceInstance.setWarDate( formatter.format( date ) );
		} else {

			serviceInstance.setWarDate( "DeployFileNotFound" );

		}

		File versionFile = getDeployVersionFile( serviceInstance );

		logger.debug( "Checking versionFile: {}", versionFile.getAbsolutePath() );

		if ( versionFile.canRead() ) {
			String info = Application.getContents( versionFile );

			if ( info.contains( "version" ) ) {
				info = info.substring( info.indexOf( "version" ) - 1 );
			}

			serviceInstance.setScmVersion( info.replaceAll( "<[^>]*>", " " ) );
			// logger.info("Found Version File" ) ;
		} else if ( !serviceInstance.isOs()
				&& !(serviceInstance.isOsWrapper() && serviceInstance.isWrapper()) ) {
			// os versions are loaded from JSON files
			serviceInstance.setScmVersionNotDeployed();
		}
	}

	public static String BUILD_DIR;
	private static String CSAP_DEFINITION_FOLDER_FOR_JUNITs = System.getenv( "STAGING" ) + "/conf";

	public File getDefinitionFolder () {
		return new File( CSAP_DEFINITION_FOLDER_FOR_JUNITs );
	}

	private ObjectNode pendingResourceOperations;

	public ArrayNode getPendingResourceAdds () {
		return (ArrayNode) getPendingResourceOperations().get( "adds" );
	}

	public ArrayNode getPendingResourceDeletes () {
		return (ArrayNode) getPendingResourceOperations().get( "deletes" );
	}

	public void resetPendingResourceUpdates () {

		logger.info( "clearing resource request backlog" );
		FileUtils.deleteQuietly( getResourcesWorkingFolder() );
		setPendingResourceOperations( jacksonMapper.createObjectNode() );
		getPendingResourceOperations().putArray( "adds" );
		getPendingResourceOperations().putArray( "deletes" );
	}

	public File getResourcesFolder ( String name ) {

		return new File( getDefinitionFolder(), "propertyOverride/" + name + "/resources" );
	}

	public File getResourcesWorkingFolder () {

		return new File( getStagingFolder(), "build/propertyOverride/" );

	}

	/**
	 * TOKEN is used so that path on other VMs is relative to local CSAP install
	 * folder
	 *
	 * @return
	 */
	public String getDefinitionToken () {
		return FileToken.STAGING.value + "/conf";
	}

	/**
	 *
	 * Utility function to enable defintion unit testing to occur without
	 * impacting other tests
	 *
	 * @param definitionFile
	 */
	public void configureForDefinitionOperationsTest ( File definitionFile ) {

		File globalBuildLoc = new File( getRootModelBuildLocation() );

		try {
			FileUtils.deleteQuietly( globalBuildLoc );

			// We are going to work using a test folder to avoid impacting other
			// tests.
			// CLUSTER_DIR += "_TEST";
			CSAP_DEFINITION_FOLDER_FOR_JUNITs = JUNIT_CLUSTER_PREFIX + System.currentTimeMillis();

			logger.warn( "Test setup using: " + definitionFile.getAbsolutePath()
					+ "\n Deleting: " + globalBuildLoc.getAbsolutePath()
					+ "\n Deleting: " + getDefinitionFolder()
						.getAbsolutePath()
					+ "\n Copying: " + definitionFile
						.getParentFile()
						.getAbsolutePath()
					+ " to: "
					+ getDefinitionFolder()
						.getAbsolutePath() );

			FileUtils.deleteQuietly( getDefinitionFolder() );

			FileUtils.copyDirectory( definitionFile.getParentFile(), getDefinitionFolder(),
				FileFilterUtils.suffixFileFilter( ".js" ) );
			FileUtils.copyDirectory( definitionFile.getParentFile(), getDefinitionFolder(),
				FileFilterUtils.suffixFileFilter( ".json" ) );

		} catch (IOException e) {
			logger.error( "Failed test setup", e );
		}
	}

	public static String getContents ( File file ) {

		if ( file == null || !file.exists() ) {
			return "Cannot read: " + file;
		}

		try {
			return FileUtils.readFileToString( file );
		} catch (IOException e) {
			LoggerFactory
				.getLogger( Application.class )
				.warn( "Failed to read: " + file
					.toURI()
					.getPath(),
					e );
			return "Failed to read: " + file
				.toURI()
				.getPath()
					+ " due to: " + e.getMessage();
		}

	}

	public static String getHOST_NAME () {
		return HOST_NAME;
	}

	public static boolean isJvmInManagerMode () {
		return jvmInManagerMode;
	}

	public static boolean isRunningOnDesktop () {
		return developerMode;
	}

	public static boolean isDesktopHost () {
		if ( isRunningOnDesktop() && !isJvmInManagerMode() ) {
			return true;
		}
		return false;
	}

	/**
	 * Test for whether ~/.cafEnv contains a flag to disable root commands This
	 * is used for gen 1 csap installs where root is not accessible.
	 *
	 * @return
	 */
	public static boolean isRunningAsRoot () {
		if ( System.getenv( "CSAP_NO_ROOT" ) == null ) {
			return true;
		}
		return false;
	}

	public static void setJvmInManagerMode ( boolean jvmInManagerMode ) {
		logger.info( "jvmInManagerMode: {}", jvmInManagerMode );
		Application.jvmInManagerMode = jvmInManagerMode;
	}

	private String agentStatus = "bootStrapInProgress";

	private boolean bootstrapComplete = false;

	private static String currentLifeCycle = "";

	public static String getCurrentLifeCycle () {
		return currentLifeCycle;
	}

	private ObjectMapper jacksonMapper = new ObjectMapper();

	@Value ( "${svcAgent.jmxAuth:false}" )
	private String jmxAuth = "false"; // spring overwrites,

	private Map<Integer, ServiceCollector> serviceCollectorMap = new HashMap<Integer, ServiceCollector>();

	private long sumOfDefinitionTimestamps = 0;
	private long lastClusterLoadTime = 0;

	public String lastOp = "0::Admin Process Restarted";

	private long lastOpMillis = System.currentTimeMillis();

	private long lastSvcVerLoadTimestamp = 0;

	@Inject
	CsapEventClient csapEventClient;

	private SimpleDateFormat df = new SimpleDateFormat( "E MMM d,  HH:mm" );

	private String motdMessage = "CsAgent restart: " + df.format( new Date() );

	private Map<Integer, OsSharedResourcesCollector> osSharedResourceCollectorMap = new HashMap<Integer, OsSharedResourcesCollector>();

	OsCommandRunner osCommandRunner = new OsCommandRunner( 90, 3, "CapMgr" ); // apachectl
	// should be
	// very fast

	// will match servlet at least.
	@Inject
	private OsManager _osManager;

	public OsManager getOsManager () {
		return _osManager;
	}

	// final OperatingSystemMXBean osStats =
	// ManagementFactory.getOperatingSystemMXBean();
	final com.sun.management.OperatingSystemMXBean osStats = (com.sun.management.OperatingSystemMXBean) ManagementFactory
		.getOperatingSystemMXBean();

	ScheduledFuture<?> refreshConfigHandleOnlyOnMgrs = null;

	BasicThreadFactory schedFactory = new BasicThreadFactory.Builder()
		.namingPattern( "CsapRefreshDefinition-%d" )
		.daemon( true )
		.priority( Thread.NORM_PRIORITY )
		.build();
	ScheduledExecutorService refreshConfigPool = Executors.newScheduledThreadPool( 1, schedFactory );

	@Value ( "${user.dir}" )
	private String processWorkingDirectory = "..";

	// defaults to above

	public TreeMap<String, HashSet<String>> serviceToArtifactMap;

	@Value ( "${security.cookieName:SodcSvcsIrSSO}" )
	public String sodcCookieName = "SodcSvcsIrSSO"; // spring overwrites,

	private Map<Integer, OsProcessCollector> osProcessCollectorMap = new HashMap<Integer, OsProcessCollector>();

	// init for tests from
	// main
	public String getAgentStatus () {
		return agentStatus;
	}

	/**
	 * Lifecycle Name is embedded to prevent cross-lifecycle calls
	 *
	 * @return
	 */
	public String getTomcatAjpKey () {
		return getCurrentLifeCycle() + getRootModel()
			.getCapabilityAjpSecret();
	}

	public String getName () {
		return getRootModel()
			.getName();
		// if (getGlobalModel().getReleaseModelCount() == 1)
		// return getGlobalModel().getName();
		// return getGlobalModel().getName() + " : "
		// + getActiveModel().getReleasePackageName();
	}

	private ObjectNode cachedPlatformScore = null;

	public ObjectNode getPlatformScore ( boolean blocking ) {

		logger.debug( "Blocking: {}", blocking );

		if ( cachedPlatformScore != null && !blocking ) {
			return cachedPlatformScore;
		}
		updatePlatformVersionsFromCsapTools( false );

		ObjectNode updatedScoreCard = jacksonMapper.createObjectNode();

		ArrayList<ServiceInstance> jdkInstances = getLifeCycleServicesByMatch( ".*jdk.*" );
		int jdkUpToDate = 0;
		for ( ServiceInstance instance : jdkInstances ) {

			if ( _hostStatusManager != null ) {
				ObjectNode runResults = getHostStatusManager()
					.getServiceRuntime( instance.getHostName(),
						instance.getServiceName_Port() );

				if ( runResults != null ) {

					logger.debug( "JDK: {} version: {} CsapTools version: {}",
						instance.getHostName(),
						runResults
							.path( "deployedArtifacts" )
							.asText(),
						jdkCachedRelease );

					if ( runResults
						.path( "deployedArtifacts" )
						.asText()
						.compareTo( jdkCachedRelease ) >= 0 ) {
						jdkUpToDate++;
					}
				} else {
					logger.warn( "{} Unable to get runtime results, this is OK during restarts",
						instance.getHostName() );
				}
			} else {
				// Standalone node
				if ( instance
					.getMavenVersion()
					.compareTo( jdkCachedRelease ) >= 0 ) {
					jdkUpToDate++;
				}

				logger.debug( "JDK: {} Version: {}  CsapTools Version: {}",
					instance.getHostName(),
					instance.getMavenVersion(),
					jdkCachedRelease );
			}
		}
		ArrayList<ServiceInstance> redHatInstances = getLifeCycleServicesByMatch( ".*RedHat.*" );
		int rhUpToDate = 0;
		for ( ServiceInstance instance : redHatInstances ) {

			// redhat version is derived, so we need to use hoststatus results
			if ( _hostStatusManager != null ) {
				ObjectNode runResults = getHostStatusManager()
					.getServiceRuntime( instance.getHostName(),
						instance.getServiceName_Port() );

				if ( runResults != null ) {

					logger.debug( "Redhat: {} version: {} CsapTools version: {}",
						instance.getHostName(),
						runResults
							.path( "deployedArtifacts" )
							.asText(),
						linuxCachedRelease );

					if ( runResults
						.path( "deployedArtifacts" )
						.asText()
						.compareTo( linuxCachedRelease ) >= 0 ) {
						rhUpToDate++;
					}
				}
			}
		}

		ArrayList<ServiceInstance> csAgentInstances = getLifeCycleServicesByMatch( ".*CsAgent.*" );
		int agentUpToDate = 0;
		for ( ServiceInstance instance : csAgentInstances ) {
			if ( _hostStatusManager != null ) {
				ObjectNode runResults = getHostStatusManager()
					.getServiceRuntime( instance.getHostName(),
						instance.getServiceName_Port() );

				if ( runResults != null ) {

					logger.debug( "CsAgent: {} version: {} CsapTools version: {}",
						instance.getHostName(),
						runResults
							.path( "deployedArtifacts" )
							.asText(),
						csagentCachedRelease );

					if ( runResults
						.path( "deployedArtifacts" )
						.asText()
						.compareTo( csagentCachedRelease ) >= 0 ) {
						agentUpToDate++;
					}
				}
			} else {
				if ( instance
					.getMavenVersion()
					.compareTo( csagentCachedRelease ) >= 0 ) {
					agentUpToDate++;
				}

				logger.debug( "CsAgent: {} Version: {}  CsapTools Version: {}",
					instance.getHostName(),
					instance.getMavenVersion(),
					csagentCachedRelease );

			}
		}
		updatedScoreCard.put( "CsAgent", agentUpToDate + " of " + csAgentInstances.size() );
		updatedScoreCard.put( "Redhat", rhUpToDate + " of " + redHatInstances.size() );
		updatedScoreCard.put( "JDK", jdkUpToDate + " of " + jdkInstances.size() );
		updatedScoreCard.put( "upToDate", jdkUpToDate + rhUpToDate + agentUpToDate );
		updatedScoreCard.put( "total", jdkInstances.size() + redHatInstances.size() + csAgentInstances.size() );

		cachedPlatformScore = updatedScoreCard;
		return updatedScoreCard;
	}

	private String csagentCachedRelease = "zz";
	private String linuxCachedRelease = "zz";
	private String jdkCachedRelease = "zz";

	private long lastCsapToolsTimeStamp = 0; //
	static final long CSAPTOOLS_REFRESH = 1000 * 60 * 60; // every hour

	public String updatePlatformVersionsFromCsapTools ( boolean forceUpdate ) {

		if ( !lifeCycleSettings().isEventPublishEnabled() ) {
			logger.info( "Stubbing out data for trends - add csap events services" );
			csagentCachedRelease = "6";
			linuxCachedRelease = "6";
			jdkCachedRelease = "6";
			return csagentCachedRelease + ", " + jdkCachedRelease + ", " + linuxCachedRelease;
		}

		if ( !forceUpdate && (System.currentTimeMillis() - lastCsapToolsTimeStamp < CSAPTOOLS_REFRESH) ) {

			logger.debug( "====> Cache Reuse or disabled" );

			return csagentCachedRelease;
		}
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
		// factory.setHttpClient(httpClient);
		// factory.getHttpClient().getConnectionManager().getSchemeRegistry().register(scheme);

		factory.setConnectTimeout( 2000 );
		factory.setReadTimeout( 2000 );

		RestTemplate restTemplate = new RestTemplate( factory );
		restTemplate
			.getMessageConverters()
			.clear();
		restTemplate
			.getMessageConverters()
			.add( new MappingJackson2HttpMessageConverter() );

		String restUrl = lifeCycleSettings().getToolsServer() + "/admin/api/agent/runtime";

		try {

			logger.debug( "Refreshing cache: {}", restUrl );
			ObjectNode csAgentResponse = (ObjectNode) restTemplate.getForObject( restUrl, JsonNode.class );

			csagentCachedRelease = csAgentResponse
				.path( "services" )
				.path( AGENT_NAME_PORT )
				.path( "deployedArtifacts" )
				.asText();

			jdkCachedRelease = csAgentResponse
				.path( "services" )
				.path( "jdk_0" )
				.path( "deployedArtifacts" )
				.asText();
			// linuxCachedRelease = csAgentResponse.path( "services" )
			// .path( "RedHatLinux_0" )
			// .path( "deployedArtifacts" )
			// .asText();

			// hardcoding because 6.4 is reasonable
			linuxCachedRelease = "6.4";

			lastCsapToolsTimeStamp = System.currentTimeMillis();
			logger.debug( "csagentCachedRelease: {}, jdkCachedRelease: {}, linuxCachedRelease: {}",
				csagentCachedRelease, jdkCachedRelease, linuxCachedRelease );
		} catch (Exception e) {
			logger.warn( "Failed getting platform version from:  {}, {} ", restUrl, CSAP.getCsapFilteredStackTrace( e ) );
		}

		String result = csagentCachedRelease + ", " + jdkCachedRelease + ", " + linuxCachedRelease;

		logger.debug( "Result: {}", result );

		return result;
	}

	private ObjectNode cachedApplicationScore = null;

	protected void resetAppScoreCards () {
		cachedApplicationScore = null;
	}

	public ObjectNode getApplicationScore () {

		if ( cachedApplicationScore != null ) {
			// nulled out when app is reloaded
			return cachedApplicationScore;
		}
		ObjectNode applicationScoreJson = jacksonMapper.createObjectNode();

		ReleasePackage allModel = getRootModel()
			.getAllPackagesModel();

		int total = 0;
		int upToDate = 0;

		for ( String svc : allModel
			.getServiceToAllInstancesMap()
			.keySet() ) {

			if ( allModel
				.getServiceToAllInstancesMap()
				.get( svc ) != null ) {
				for ( ServiceInstance instance : allModel
					.getServiceToAllInstancesMap()
					.get( svc ) ) {

					if ( instance
						.getLifecycle()
						.startsWith(
							getCurrentLifeCycle() ) ) {
						if ( instance.isTomcatJarsPresent() || instance.isSpringBoot() ) {
							total++;
							if ( instance.isUpToDate() ) {
								upToDate++;
							}
						}
					}
				}
			}
		}

		applicationScoreJson.put( "upToDate", upToDate );
		applicationScoreJson.put( "total", total );
		return applicationScoreJson;
	}

	public ArrayList<ServiceInstance> getLifeCycleServicesByMatch ( String jvmName ) {
		ReleasePackage allModel = getRootModel()
			.getAllPackagesModel();

		ArrayList<ServiceInstance> filterInstances = new ArrayList<ServiceInstance>();
		for ( String svc : allModel
			.getServiceToAllInstancesMap()
			.keySet() ) {

			if ( !svc.matches( jvmName ) ) {
				continue;
			}

			if ( allModel
				.getServiceToAllInstancesMap()
				.get( svc ) != null ) {
				for ( ServiceInstance instance : allModel
					.getServiceToAllInstancesMap()
					.get( svc ) ) {

					if ( instance
						.getLifecycle()
						.startsWith(
							getCurrentLifeCycle() ) ) {
						filterInstances.add( instance );
					}
				}
			}
		}
		return filterInstances;
	}

	public String getSourceLocation () {
		return getRootModel()
			.getSourceLocation();
	}

	public String getSourceType () {
		return getRootModel()
			.getCapabilityScmType();
	}

	public String getSourceBranch () {
		return getRootModel()
			.getCapabilityScmBranch();
	}

	public String getClusterType ( String platformLifeCycleSubLifeVersion ) {
		return getRootModel()
			.getClusterVersionToTypeMap()
			.get( platformLifeCycleSubLifeVersion );
	}

	public String getCpuCount () {
		return Integer.toString( osStats.getAvailableProcessors() );
	}

	public int getCpuCountAsInt () {
		return osStats.getAvailableProcessors();
	}

	public LifeCycleSettings lifeCycleSettings () {
		if ( getRootModel() == null || getRootModel().getLifeToMetaDataMap() == null ) {
			logger.info( "Unable to get settings: null model" );
			return null;
		}
		return getRootModel()
			.getLifeToMetaDataMap()
			.get( getCurrentLifeCycle() );
	}

	public LifeCycleSettings lifeCycleSettings ( String life ) {
		return getRootModel()
			.getLifeToMetaDataMap()
			.get( life );
	}

	public File getDeployVersionFile ( ServiceInstance serviceInstance ) {

		return new File( getDeploymentStorageFolder(), serviceInstance.getDeployFileName() + ".txt" );
	}

	public File getServiceDeployFile ( ServiceInstance serviceInstance ) {
		return new File( getDeploymentStorageFolder(), serviceInstance.getDeployFileName() );
	}

	private File deployFolder = null;

	public File getDeploymentStorageFolder () {

		if ( deployFolder == null ) {
			deployFolder = new File( getStagingFolder(), CSAP_SERVICE_PACKAGES );
			deployFolder.mkdirs();
		}
		return deployFolder;
	}

	public void move_to_csap_saved_folder ( File folder_to_backup, StringBuilder operation_output )
			throws IOException {

		File csapSavedFolder = getCsapSavedFolder();

		String now = LocalDateTime.now().format( DateTimeFormatter.ofPattern( "MMM.d-HH.mm.ss" ) );

		File backUpFolder = new File( csapSavedFolder, folder_to_backup.getName() + "." + now );

		if ( backUpFolder.exists() ) {
			logger.info( "Warning: agent jobs are not cleaning up: {}", backUpFolder.getAbsolutePath() );
			FileUtils.deleteQuietly( backUpFolder );
			operation_output.append( "\n\n Deleting previous backup: " + backUpFolder.getAbsolutePath() + "\n" );
		}

		if ( folder_to_backup.exists() ) {

			logger.info( "Moving: {} to {}", folder_to_backup.getAbsolutePath(), backUpFolder.getAbsolutePath() );

			operation_output.append( "\n\n Moving : "
					+ folder_to_backup.getAbsolutePath()
					+ " to: "
					+ backUpFolder.getAbsolutePath() + "\n" );

			FileUtils.moveDirectory( folder_to_backup, backUpFolder );
		} else {
			operation_output.append( "Folder does not exist: " + folder_to_backup.getCanonicalPath() );
			logger.warn( "Folder does not exist: {}", folder_to_backup.getCanonicalPath() );

		}
	}

	
	public File getCsapSavedFolder () {
		File csapSavedFolder = getStagingFile( SAVED );
		if ( !csapSavedFolder.exists() ) {
			logger.info( "creating csap saved folder: {}", csapSavedFolder.getAbsolutePath() );
			csapSavedFolder.mkdirs();
		}
		return csapSavedFolder;
	}

	public File getFileOnHost ( String svcName, String logSelect, String propSelect ) {

		if ( propSelect != null ) {
			return new File( getPropertyFolder( svcName ), propSelect );
		}

		File logPath = new File( getLogDir( svcName ), logSelect );

		return logPath;

	}

	public Integer getFirstJmxStatsKey () {
		return lifeCycleSettings()
			.getMetricToSecondsMap()
			.get( COLLECTION_JMX )
			.get( 0 );
	}

	public Integer getFirstVmStatsKey () {
		return lifeCycleSettings()
			.getMetricToSecondsMap()
			.get( COLLECTION_RESOURCE )
			.get( 0 );
	}

	public Integer getFirstSvcStatsKey () {
		return lifeCycleSettings()
			.getMetricToSecondsMap()
			.get( COLLECTION_SERVICE )
			.get( 0 );
	}

	public ObjectNode statusForAdminOrAgent ( double alertLevel ) {

		ObjectNode healthJson = jacksonMapper.createObjectNode();
		ObjectNode errorNode = buildErrorsForAdminOrAgent( alertLevel );

		ObjectNode vmNode = healthJson.putObject( "vm" );

		if ( errorNode.size() == 0 ) {
			healthJson.put( "Healthy", true );
		} else {
			healthJson.put( "Healthy", false );
			healthJson.set( VALIDATION_ERRORS, errorNode );
		}

		ObjectNode serviceToRuntimeNode = getHostLoadCpuAndMore();
		;
		vmNode.put( "cpuCount", Integer.parseInt(
			serviceToRuntimeNode
				.path( "cpuCount" )
				.asText() ) );

		double newKB = Math.round( Double.parseDouble(
			serviceToRuntimeNode
				.path( "cpuLoad" )
				.asText() )
				* 10.0 )
				/ 10.0;

		vmNode.put( "cpuLoad", newKB );
		vmNode.put( "host", Application.getHOST_NAME() );
		vmNode.put( "packageName", getActiveModel().getReleasePackageName() );
		vmNode.put( "capabilityName", getName() );
		vmNode.put( "lifecycle", getCurrentLifeCycle() );

		int totalServicesActive = 0;
		int totalServices = 0;
		for ( ServiceInstance instance : getServicesOnHost() ) {

			if ( !instance.isScript() ) { // Scripts should be ignored
				totalServices++;
				if ( instance.isRunning() ) {
					totalServicesActive++;
				}
			}

		}

		ObjectNode serviceNode = healthJson.putObject( "services" );
		serviceNode.put( "total", totalServices );
		serviceNode.put( "active", totalServicesActive );

		return healthJson;
	}

	public ObjectNode buildErrorsForAdminOrAgent ( double alertLevel ) {
		ObjectNode errorItemsJson = jacksonMapper.createObjectNode();

		if ( Application.isJvmInManagerMode() ) {
			ReleasePackage allModel = getRootModel()
				.getAllPackagesModel();
			for ( String host : allModel
				.getLifeCycleToHostMap()
				.get( getCurrentLifeCycle() ) ) {
				// logger.info("Health check on: " + host) ;

				ObjectNode errorNode = getAlertsOnRemoteAgent( alertLevel, host );
				ArrayNode errors = (ArrayNode) errorNode.get( VALIDATION_ERRORS );
				if ( errors.size() > 0 ) {
					errorItemsJson.set( host, errors );
					// ArrayNode errors = errorItemsJson.putArray(host);
					// for (String error : hostErrors) {
					// errors.add(error);
					// }
				}
			}
		} else {
			// errorItemsJson.put("results", adminConnectionCheck()) ;
			ObjectNode errorNode = getAlertsOnLocalAgent( alertLevel );
			ArrayNode errors = (ArrayNode) errorNode.get( VALIDATION_ERRORS );
			ObjectNode states = (ObjectNode) errorNode.get( "states" );

			if ( errors.size() > 0 ) {
				errorItemsJson.set( Application.getHOST_NAME(), errors );
				errorItemsJson.set( "states", states );
			}

			// if (hostErrors.size() > 0) {
			// ArrayNode errors = errorItemsJson.putArray(
			// Application.getHOST_NAME() );
			// for (String error : hostErrors) {
			// errors.add(error);
			// }
			// }
		}
		return errorItemsJson;
	}

	public ArrayList<String> getHostsForCurrentLifecycle ( ReleasePackage model ) {

		return getHostsForLifecycle( getCurrentLifeCycle(), model );
	}

	/**
	 * This returns a mutable list - hence copies are made
	 *
	 * @param lc
	 * @return
	 */
	public List<String> getMutableHostsInActivePackage ( String lc ) {

		logger.debug( "Getting: {}", lc );

		List<String> hostList = new ArrayList();

		if ( lc != null && lc.equals( ALL_PACKAGES ) ) {
			hostList = new ArrayList<String>( getAllHostsInAllPackagesInCurrentLifecycle() );
		}

		if ( lc != null ) {

			logger.debug( "Getting: {} from available: {}", lc, getClustersForActivePackage().keySet() );
			List testHosts = getClustersForActivePackage().get( lc );

			if ( testHosts != null ) {
				hostList = new ArrayList<String>( testHosts );
			}
		}

		Collections.sort( hostList );
		//
		// return getHostsForLifecycle(lc, getActiveModel());
		// merge all packages together Map<String, List<String>>
		// return getClustersForActiveLifecycle().get( lc );

		return hostList;

	}

	public Map<String, List<String>> getClustersForActivePackage () {
		Map<String, List<String>> clusterToHostListJson = getActiveModel().getClustersToHostMap();

		// now create the all list
		List<String> allHosts = clusterToHostListJson
			.values()
			.stream()
			.flatMap( Collection::stream )
			.distinct()
			.collect( Collectors.toList() );

		clusterToHostListJson.put( activeLifecycleClusterName(),
			allHosts );

		return clusterToHostListJson;
	}

	public String activeLifecycleClusterName () {
		return getCurrentLifeCycle()
				+ "(" + getActiveModel().getReleasePackageName() + ")";
	}

	public Map<String, List<String>> getClustersForActiveLifecycle () {

		Map<String, List<String>> clusterMap = new HashMap();

		getReleasePackageStream().forEach( model -> {
			String packageName = model.getReleasePackageName();
			model
				.getClustersToHostMap()
				.entrySet()
				.forEach( entry -> {
					clusterMap.put(
						entry.getKey() + "@" + packageName,
						entry.getValue() );
				} );
		} );

		// Map<String, List<String>> clusterToHostListJson
		// = getReleasePackageStream()
		// .map( CapabilityDataModel::getClustersToHostMap )
		// .map( Map::entrySet )
		// .flatMap( Collection::stream )
		// .collect(
		// Collectors.toMap(
		// Map.Entry::getKey,
		// Map.Entry::getValue,
		// ( clusterMap1, clusterMap2 ) -> {
		// logger.error( "Duplicate Entry: {}", clusterMap2 );
		// return clusterMap1;
		// }
		// ) );
		// now create the all list
		List<String> allHosts = clusterMap
			.values()
			.stream()
			.flatMap( Collection::stream )
			.distinct()
			.collect( Collectors.toList() );

		clusterMap.put( "all", allHosts );

		return clusterMap;
	}

	public ArrayList<String> getHostsForLifecycle ( String lc, ReleasePackage model ) {

		ArrayList<String> hostList = new ArrayList<String>();
		if ( lc != null && getLifeCycleToHostMap()
			.get( lc ) != null ) {
			hostList = new ArrayList<String>( model
				.getLifeCycleToHostMap()
				.get( lc ) );
			Collections.sort( hostList );
		}
		return hostList;
	}

	Double byteToGb = 1024 * 1024 * 1024D;
	DecimalFormat gbFormat = new DecimalFormat( "#.#GB" );

	String getGb ( long num ) {
		// logger.info( "num: {}" , num );
		return gbFormat.format( num / byteToGb );
	}

	DecimalFormat percentFormat = new DecimalFormat( "#.#%" );

	String getPercent ( double num ) {
		// logger.info( "num: {}", num );
		return percentFormat.format( num );
	}

	public boolean isDockerInstalledAndActive () {

		boolean isActive = false;
		ServiceInstance dockerInstance = findServiceByNameOnCurrentHost( "docker" );

		if ( dockerInstance != null && dockerInstance.isRunning() ) {
			isActive = true;
		}

		return isActive;
	}

	@Inject
	DockerHelper dockerHelper;

	public ObjectNode getHostLoadCpuAndMore () {
		// TreeMap<String, String> loadResultsMap = new TreeMap<String,
		// String>();

		ObjectNode hostStatus = jacksonMapper.createObjectNode();

		try {
			hostStatus.put( "cpuLoad", Double.toString( osStats.getSystemLoadAverage() ) );

			hostStatus.put( "cpuCount", Integer.toString( osStats.getAvailableProcessors() ) );

			hostStatus.put( "cpu", getLatestCpu() );
			hostStatus.put( "cpuIoWait", getLatestIoWait() );
			hostStatus.put( "processCount", _osManager.numberOfProcesses() );
			hostStatus.put( "csapCount", getServicesOnHost().size() );
			hostStatus.put( "diskCount", _osManager.getDiskCount() );

			if ( isDockerInstalledAndActive() ) {
				hostStatus.set( "docker", dockerHelper.buildSummary() );
			}

			ObjectNode memory = hostStatus.putObject( "memory" );
			memory.put( "total", getGb( osStats.getTotalPhysicalMemorySize() ) );
			memory.put( "free", getGb( osStats.getFreePhysicalMemorySize() ) );
			memory.put( "swapTotal", getGb( osStats.getTotalSwapSpaceSize() ) );
			memory.put( "swapFree", getGb( osStats.getFreeSwapSpaceSize() ) );

			memory.put( "free-m", _osManager.getMemoryAvailbleLessCache() );

			hostStatus.set( "users", activeUsers.getActive() );

			hostStatus.put( "lastOp", getLastOpMessage() );

			hostStatus.set( "vmLoggedIn", getOsManager().getVmLoggedIn() );

			hostStatus.put( "motd", getMotdMessage() );

			Format tsFormater = new SimpleDateFormat( "HH:mm:ss" );

			hostStatus.put( "timeStamp", tsFormater.format( new Date() ) );

			hostStatus.put( "lastOpMillis", getLastOpMillis() );
		} catch (Exception e) {
			hostStatus.put( "error", "Reason: " + CSAP.getCsapFilteredStackTrace( e ) );
			logger.info( "Failed build health: {}", CSAP.getCsapFilteredStackTrace( e ) );
		}

		// try {
		// logger.info( "hostStatus: {} , {}",
		// CSAP.jsonPrint( jacksonMapper, hostStatus ),
		// CSAP.getCsapFilteredStackTrace( new Error("ShowStack") ) );
		// } catch (JsonProcessingException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }

		return hostStatus;
	}

	// public TreeMap<String, ArrayList<InstanceConfig>> getHostToConfigMap() {
	// return getActiveModel().getHostToConfigMap();
	// }
	public String getJmxAuth () {
		return jmxAuth;
	}

	public Map<Integer, ServiceCollector> getApplicationCollectors () {
		return serviceCollectorMap;
	}

	public ServiceCollector getServiceCollector ( Integer time ) {
		if ( time.intValue() < 0 ) {
			time = getFirstJmxStatsKey();
		}

		if ( serviceCollectorMap.containsKey( time ) ) {
			return serviceCollectorMap.get( time );
		} else {
			// common query is to specify non existant or 0
			logger.debug( "Requested collector for interval: {} not found, using first", time );
			return serviceCollectorMap.get( getFirstJmxStatsKey() );
		}

	}

	public Integer getLastJmxStatsKey () {
		return lifeCycleSettings()
			.getMetricToSecondsMap()
			.get( COLLECTION_JMX )
			.get( lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( COLLECTION_JMX )
				.size()
					- 1 );
	}

	public Integer getLastVmStatsKey () {
		return lifeCycleSettings()
			.getMetricToSecondsMap()
			.get( COLLECTION_RESOURCE )
			.get( lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( COLLECTION_RESOURCE )
				.size()
					- 1 );
	}

	public String getLastOp () {
		return lastOp;
	}

	public String getLastOpMessage () {
		if ( lastOp.contains( "::" ) ) {
			return lastOp.substring( lastOp.indexOf( ":" ) + 2 );
		}
		return lastOp;
	}

	public long getLastOpMillis () {
		return lastOpMillis;
	}

	public Integer getLastSvcStatsKey () {
		return lifeCycleSettings()
			.getMetricToSecondsMap()
			.get( COLLECTION_SERVICE )
			.get( lifeCycleSettings()
				.getMetricToSecondsMap()
				.get( COLLECTION_SERVICE )
				.size()
					- 1 );
	}

	public ArrayList<String> getLifeCycleHosts () {
		return getLifeCycleToHostMap()
			.get( getCurrentLifeCycle() );
	}

	public ArrayList<String> getLifecycleList () {
		return getRootModel()
			.getLifecycleList();
	}

	public TreeMap<String, ArrayList<HostInfo>> getLifeCycleToHostInfoMap () {
		return getActiveModel()
			.getLifeCycleToHostInfoMap();
	}

	public TreeMap<String, ArrayList<String>> getLifeCycleToHostMap () {
		return getActiveModel()
			.getLifeCycleToHostMap();
	}

	// public ArrayList<String> getClustersInLifecycle() {
	//
	// ArrayList<String> clustersInCurrentLifeCycle = new ArrayList<String>();
	// for (String lc : getActiveModel().getLifecycleList()) {
	// // logger.debug("Lifecycle: " + lc);
	// ArrayList<String> hostList =
	// getActiveModel().getLifeCycleToHostMap().get(lc);
	// if (hostList == null) {
	// continue;
	// }
	//
	// if (lc.startsWith(Application.getCurrentLifeCycle())) {
	// clustersInCurrentLifeCycle.add(lc);
	// }
	// }
	// return clustersInCurrentLifeCycle;
	// }
	public ArrayList<String> getClustersInLifecycle ( String releasePackage ) {

		ArrayList<String> clustersInCurrentLifeCycle = new ArrayList<String>();
		for ( String lc : getModel( releasePackage ).getLifecycleList() ) {
			// logger.debug("Lifecycle: " + lc);
			ArrayList<String> hostList = getModel( releasePackage ).getLifeCycleToHostMap().get( lc );
			if ( hostList == null ) {
				continue;
			}

			if ( lc.startsWith( Application.getCurrentLifeCycle() ) ) {
				clustersInCurrentLifeCycle.add( lc );
			}
		}
		return clustersInCurrentLifeCycle;
	}

	public TreeMap<String, LifeCycleSettings> getLifeToMetaDataMap () {
		return getRootModel()
			.getLifeToMetaDataMap();
	}

	public String getLoadAverage () {
		return Double.toString( osStats.getSystemLoadAverage() );
	}

	public CsapEventClient getEventClient () {
		return csapEventClient;
	}

	public void setEventClient ( CsapEventClient client ) {
		this.csapEventClient = client;
	}

	public String getMotdMessage () {
		return motdMessage;
	}

	public Map<Integer, OsSharedResourcesCollector> getOsSharedResourceCollectorMap () {
		return osSharedResourceCollectorMap;
	}

	public void clearResourceStats () {
		for ( OsSharedResourcesCollector vmStatsRunnable : osSharedResourceCollectorMap.values() ) {
			vmStatsRunnable.clear();
		}
	}

	public OsSharedResourcesCollector getVmSharedCollector ( Integer time ) {
		if ( time.intValue() < 0 ) {
			time = getFirstVmStatsKey();
		}

		if ( osSharedResourceCollectorMap.containsKey( time ) ) {
			return osSharedResourceCollectorMap.get( time );
		} else {
			logger.warn( "Requested key not found, using first" );
			return osSharedResourceCollectorMap.get( getFirstVmStatsKey() );
		}

	}

	/**
	 * Helper method used to provide a script parameter to rebuildAndDeploy.sh;
	 * it identifies the others hosts which require scp the war file.
	 *
	 * @param svcName
	 * @return
	 */
	public Set<String> getAllHostsInAllPackagesInCurrentLifecycle () {

		Set result = new TreeSet<String>( getRootModel()
			.getAllPackagesModel()
			.getLifeCycleToHostMap()
			.get( getCurrentLifeCycle() ) );

		logger.debug( "Other hosts: {}", result.toString() );

		return result;
	}

	public File getProcessingDir () {
		return new File( PROCESSING );
	}

	public File getWorkingDirectory ( String serviceName_port ) {

		if ( isRunningOnDesktop() ) {
			return getProcessingDir();
		}

		return new File( getProcessingDir(), "/" + serviceName_port );

	}

	private File getPropertyFolder ( String serviceName_port ) {

		ServiceInstance instance = getServiceInstanceCurrentHost( serviceName_port );
		File procDir = new File( getWorkingDirectory( serviceName_port ), "/webapps/" );
		File[] filesInFolder = procDir.listFiles();

		File propFile = new File( getWorkingDirectory( serviceName_port ), "/webapps/"
				+ instance.getContext() + "/" + instance.getPropDirectory() );

		File propFileOver = new File( getWorkingDirectory( serviceName_port ), "/"
				+ instance.getPropDirectory() );

		if ( instance.isSpringBoot() && !propFileOver.exists() ) {
			// springboot <= 1.3
			logger.debug( "Did not find: {}", propFileOver.getAbsolutePath() );
			propFileOver = new File( getWorkingDirectory( serviceName_port ),
				ServiceInstance.getBootFolder( true ) );
		}

		if ( propFileOver.exists() ) {
			propFile = propFileOver;
		} else if ( filesInFolder != null ) {
			// handle tomcat parallel deployments - may have multiple contexts
			// so only pick the first one
			for ( File itemInFolder : filesInFolder ) {
				if ( itemInFolder
					.getName()
					.startsWith( instance.getContext() ) ) {
					propFile = itemInFolder;
					File test = new File( itemInFolder, instance.getPropDirectory() );
					if ( test.exists() ) {
						propFile = test;
					}
					break;
				}
			}
		}

		if ( instance
			.getPropDirectory()
			.startsWith( "/" ) ) {
			propFile = new File( instance.getPropDirectory() );
		}

		if ( isRunningOnDesktop() ) {
			propFile = new File( getProcessingDir() + "/../../src/main/resources" );
		}

		logger.debug( "file: {}", propFile.getAbsolutePath() );
		return propFile;
	}

	// varies based on cvs or svn, but gets the working directory
	public String getRootModelBuildLocation () {
		if ( getSourceLocation()
			.startsWith( "http" ) ) {
			File f = new File( getSourceLocation()
				.substring( getSourceLocation()
					.indexOf( "/" ) ) );
			return STAGING + "/build/" + f.getName();
		} else {
			return STAGING + "/build" + getSourceLocation();
		}

	}

	private boolean testMode = false;

	public boolean isTestMode () {
		return testMode;
	}

	public void setTestMode ( boolean b ) {
		testMode = b;
	}

	public ReleasePackage getAllPackages () {
		return getActiveModel().getAllPackagesModel();
	}

	//@formatter:off
	
	

	public int getMaxDeploySecondsForService( String serviceNamePort) {
		
		// in case port is added
		String serviceName = serviceNamePort.split( "_" )[0] ;
		
		
		int largestTimeout = 
				getAllPackages()
					.getServiceInstances( serviceName )
						.mapToInt( ServiceInstance::getDeployTimeOutSeconds )
							.max()
							.getAsInt();
		
		
		return largestTimeout;
		
	}

	//@formatter:on

	/**
	 *
	 * Helper method to get access to instance config data
	 *
	 * @param svcName_port
	 * @return
	 */
	public ServiceInstance getServiceInstanceAnyPackage ( String svcName_port ) {

		if ( !svcName_port.contains( "_" ) ) {
			return null;
		}
		// logger.debug( "svcName_port: {}", svcName_port );
		String[] svc = svcName_port.split( "_" );

		String svcName = svc[0];
		String port = svc[1];
		ArrayList<ServiceInstance> instanceList = getRootModel()
			.getAllPackagesModel()
			.getServiceToAllInstancesMap()
			.get( svcName );

		if ( instanceList != null ) {
			for ( ServiceInstance instanceConfig : instanceList ) {
				if ( instanceConfig
					.getServiceName()
					.equals( svcName )
						&& instanceConfig
							.getPort()
							.equals( port ) ) {
					return instanceConfig;
				}
			}
		}
		return null;
	}

	public ServiceInstance getServiceInstancePackage ( String svcName_port, String releasePackage ) {

		logger.debug( "svcName_port: {}", svcName_port );

		String[] svc = svcName_port.split( "_" );

		if ( !svcName_port.contains( "_" ) ) {
			return null;
		}

		String svcName = svc[0];
		String port = svc[1];
		ArrayList<ServiceInstance> instanceList = getModel( releasePackage )
			.getServiceToAllInstancesMap()
			.get(
				svcName );

		if ( instanceList != null ) {
			for ( ServiceInstance instanceConfig : instanceList ) {
				if ( instanceConfig
					.getServiceName()
					.equals( svcName )
						&& instanceConfig
							.getPort()
							.equals( port ) ) {
					return instanceConfig;
				}
			}
		}
		return null;
	}

	public ServiceInstance getServiceInstanceCurrentHost ( String svcName_port ) {
		return getServiceInstance( svcName_port, getHOST_NAME(), getActiveModelName() );
	}

	public ServiceInstance getServiceInstanceAnyPackage ( String svcName_port, String hostname ) {

		return getServiceInstance( svcName_port, hostname, getAllPackages().getReleasePackageName() );
	}

	public ServiceInstance getServiceInstance ( String svcName_port, String hostname, String modelName ) {

		ReleasePackage targetModel = getModel( modelName );
		// Enabling debug on this file will show many logs
		//
		// logger.debug("svcName_port: " + svcName_port + " hostName: "
		// + hostname);

		String[] svc = svcName_port.split( "_" );

		if ( !svcName_port.contains( "_" ) ) {
			return null;
		}
		String svcName = svc[0];
		String port = svc[1];
		ArrayList<ServiceInstance> instanceList = targetModel
			.getHostToConfigMap()
			.get( hostname );

		if ( instanceList != null ) {
			for ( ServiceInstance instanceConfig : instanceList ) {
				if ( instanceConfig
					.getServiceName()
					.equals( svcName )
						&& instanceConfig
							.getPort()
							.equals( port ) ) {
					return instanceConfig;
				}
			}
		}
		return null;
	}

	public ArrayList<ServiceInstance> getServicesOnHost () {
		// return hostToSvcPathList.get(getHostDir().getName());
		return getActiveModel()
			.getHostToConfigMap()
			.get( getHOST_NAME() );
	}

	public ServiceInstance findServiceByNameOnCurrentHost ( String name ) {
		Optional<ServiceInstance> optionalInstance = getServicesOnHost()
			.stream().filter( instance -> instance.getServiceName().equals( name ) )
			.findFirst();

		if ( optionalInstance.isPresent() )
			return optionalInstance.get();
		logger.debug( "Failed to locate: {}", name );

		return null;
	}

	public ArrayList<ServiceInstance> getServicesOnTargetHost ( String host ) {
		// return hostToSvcPathList.get(getHostDir().getName());
		logger.debug( "Host: {}, Model: {} ", host, getModelForHost( host ) );

		try {
			// if ( host.length() > 2 ) throw new Exception("Peter Testing");
			return getModelForHost( host )
				.getHostToConfigMap()
				.get( host );
		} catch (Exception e) {
			logger.warn( "Failed finding host: {} ", host, e );
		}

		return new ArrayList<ServiceInstance>();
	}

	public String findHostWithLowestAttribute ( List<String> hostNames, String attribute ) {
		return getHostStatusManager()
			.getHostWithLowestAttribute( hostNames, "/hostStats/cpuLoad" );
	}

	public TreeMap<String, HashSet<String>> getServiceToArtifactMap ( ReleasePackage model ) {

		if ( getHostStatusManager() == null ) {
			return null;
		}

		ArrayList<String> lifeCycleHostList = model
			.getLifeCycleToHostMap()
			.get(
				getCurrentLifeCycle() );

		TreeMap<String, HashSet<String>> working_serviceToVersionMap = new TreeMap<String, HashSet<String>>();

		try {
			for ( String host : lifeCycleHostList ) {
				ObjectNode hostRuntime = getHostStatusManager().getResponseFromHost( host );
				if ( hostRuntime != null ) {

					Iterator<String> serviceIter = hostRuntime
						.path( "services" )
						.fieldNames();

					while (serviceIter.hasNext()) {
						String service_port = serviceIter.next();

						String serviceName = hostRuntime
							.path( "services" )
							.path( service_port )
							.path( "serviceName" )
							.asText();

						String artifacts = hostRuntime
							.path( "services" )
							.path( service_port )
							.path( "deployedArtifacts" )
							.asText();

						if ( artifacts.contains( "##" ) ) {
							artifacts = artifacts.substring( artifacts.indexOf( "##" ) + 2 );
						}

						if ( !working_serviceToVersionMap.containsKey( serviceName ) ) {
							working_serviceToVersionMap.put( serviceName, new HashSet<String>() );
						}

						HashSet<String> versionList = working_serviceToVersionMap.get( serviceName );

						if ( artifacts
							.trim()
							.length() != 0 ) {
							versionList.add( artifacts.trim() );
						}
					}
				}
			}
		} catch (Exception e) {
			logger.error( "Failed parsing runtime", e );
		}

		serviceToArtifactMap = working_serviceToVersionMap;

		return serviceToArtifactMap;
	}

	public String getSodcCookieName () {
		return sodcCookieName;
	}

	public static String filePathAllOs ( File path ) {

		String fpath = path.getAbsolutePath();
		try {
			if ( Application.isRunningOnDesktop() ) {
				fpath = path.getCanonicalPath().replaceAll( "\\\\", "/" );
			} else {
				fpath = path.getCanonicalPath();
			}
		} catch (Exception ex) {
			logger.warn( "Failed when checking {}", path.getAbsolutePath(), ex );
		}

		return fpath;
	}
	
	public String getScriptToken() {
		return CSAP_SCRIPTS_TOKEN ;
	}

	public File getScriptDir () {

		File scriptDir = new File( getCsapSavedFolder(), SCRIPTS_RUN );
		if ( testMode ) {
			scriptDir = new File( PROCESSING, SCRIPTS_RUN );
		}

		if ( !scriptDir.exists() ) {
			logger.info( "Did not find {}, so creating", scriptDir.getAbsolutePath() );
			scriptDir.mkdirs();
		}

		return scriptDir;
	}

	public Map<Integer, OsProcessCollector> getOsProcessCollectorMap () {
		return osProcessCollectorMap;
	}

	public OsProcessCollector getVmProcessCollector ( Integer time ) {
		if ( time.intValue() < 0 ) {
			time = getFirstSvcStatsKey();
		}
		if ( osProcessCollectorMap.containsKey( time ) ) {
			return osProcessCollectorMap.get( time );
		} else {
			logger.warn( "Requested key not found, using first" );
			return osProcessCollectorMap.get( getFirstSvcStatsKey() );
		}
	}

	public Map<String, ArrayList<ServiceInstance>> getSvcToConfigMap () {
		return getActiveModel()
			.getServiceToAllInstancesMap();
	}

	/**
	 * Current all service instances matching service name
	 *
	 * @param releasePackage
	 * @param serviceName_port
	 * @return
	 */
	public Stream<ServiceInstance> getServiceInstances ( String releasePackage, String serviceName_port ) {
		return getModel( releasePackage )
			.getServiceInstances( serviceName_port );
	}

	public List<ServiceInstance> serviceInstancesByName (
															String releasePackage,
															String serviceName_noPort ) {

		return getModel( releasePackage )
			.serviceInstancesInCurrentLifeByName()
			.get( serviceName_noPort );

	}

	/**
	 * use the raw definitions because lookup is done in every lifecycle
	 *
	 * @return
	 */
	public ObjectNode servicePerformanceLabels () {

		ObjectNode serviceAttributesMap = jacksonMapper.createObjectNode();

		ReleasePackage allModel = getAllPackages();

		allModel.getServiceNameStream().forEach( serviceName -> {
			ArrayNode meters = lifeCycleSettings().getRealTimeMeters();

			final String packageFilter = IntStream
				.range( 0, meters.size() )
				.mapToObj( meters::get )
				.filter( meter -> meter.has( "id" ) )
				.filter( meter -> {
					MetricCategory performanceCategory = MetricCategory.parse( meter );
					if ( performanceCategory == MetricCategory.application ) {
						if ( serviceName.equals( performanceCategory.serviceName( meter ) ) ) {
							return true;
						}
					}
					// logger.info(serviceName) ;
					return false;
				} )
				.filter( meter -> meter.has( "optionSource" ) )
				.findFirst()
				.map( meter -> meter.get( "optionSource" ).asText() )
				.orElse( ".*" );

			logger.debug( "{} packageFilter: {}", serviceName, packageFilter );

			Optional<JsonNode> serviceDef = allModel
				.getReleasePackages()
				.filter( pkg -> pkg.getReleasePackageName().matches( packageFilter ) )
				.filter( pkg -> !pkg.getServiceDefinition( serviceName ).isMissingNode() )
				.map( pkg -> pkg.getServiceDefinition( serviceName ) )
				.findFirst();
			//

			if ( serviceDef.isPresent() ) {
				JsonNode perf = serviceDef.get().path( ServiceAttributes.performanceApplication.value );
				ObjectNode idToName = jacksonMapper.createObjectNode();
				perf.fieldNames().forEachRemaining( id -> {
					try {

						if ( perf.get( id ).has( "title" ) ) {
							idToName.put( id, perf.get( id ).get( "title" ).asText( "MissingTitle" ) );
						} else {
							idToName.put( id, id + "*" );
						}
					} catch (Exception e) {
						logger.warn( "{} Missing {}, {}", serviceName, ServiceAttributes.performanceApplication.value, id, e );
					}
				} );

				serviceAttributesMap.set( serviceName, idToName );
			} else {
				logger.warn( " Did not find service: {} in instance: {}", serviceName,
					allModel.getServiceDefinition( serviceName ).toString() );
			}
			// getServiceDefinition( serviceName );
		} );

		return serviceAttributesMap;
	}

	public ServiceInstance findFirstServiceInstanceInLifecycle ( String serviceName ) {

		Optional<ServiceInstance> def = getActiveModel()
			.getAllPackagesModel()
			.getServiceInstances( serviceName )
			.findFirst();

		if ( def.isPresent() ) {
			return def.get();
		}
		return null;
	}

	public Stream<ServiceInstance> getActiveServiceInstances ( String releasePackage, String serviceName ) {
		return getModel( releasePackage )
			.getServiceInstances( serviceName )
			.filter( this::filterInactiveHostsUsingStatusManager );
	}

	public boolean filterInactiveHostsUsingStatusManager ( ServiceInstance serviceInstance ) {

		// Filter out the instances that are not running.
		ObjectNode serviceJson = getHostStatusManager().getServiceRuntime(
			serviceInstance.getHostName(),
			serviceInstance.getServiceName_Port() );

		logger.debug( "serviceJson: {}", serviceJson );

		if ( serviceJson == null ) {
			return false;
		}

		return !serviceJson.get( "cpuUtil" ).asText().equals( ServiceInstance.INACTIVE );

	}

	public String getSysCpuLevel () {

		if ( osSharedResourceCollectorMap.size() == 0 ) {
			return "-99";
		}
		return osSharedResourceCollectorMap
			.get( getFirstVmStatsKey() )
			.getSysCpuLevel();
	}

	public String getUsrCpuLevel () {

		if ( osSharedResourceCollectorMap.size() == 0 ) {
			return "-99";
		}
		return osSharedResourceCollectorMap
			.get( getFirstVmStatsKey() )
			.getUsrCpuLevel();
	}

	/**
	 * mxbean can false report
	 * 
	 * @return
	 */
	public int getLatestCpu () {

		if ( osSharedResourceCollectorMap.size() == 0 ) {
			return -1;
		}

		return osSharedResourceCollectorMap
			.get( getFirstVmStatsKey() )
			.getLatestCpu();

	}

	public int getLatestIoWait () {

		if ( osSharedResourceCollectorMap.size() == 0 ) {
			return -1;
		}

		return osSharedResourceCollectorMap
			.get( getFirstVmStatsKey() )
			.getLatestIoWait();

	}

	public ArrayList<String> getVersionList () {
		return getActiveModel()
			.getVersionList();
	}

	public boolean isBootstrapComplete () {
		return bootstrapComplete;
	}

	public void setAgentStatus ( String agentStatus ) {
		this.agentStatus = agentStatus;
	}

	public void setBootstrapComplete () {
		agentStatus = "admin"; //
		bootstrapComplete = true;
	}

	public void setCurrentLifeCycle ( String currentLifeCycle ) {
		Application.currentLifeCycle = currentLifeCycle;
	}

	public void setJmxAuth ( String jmxAuth ) {
		this.jmxAuth = jmxAuth;
	}

	public void setLastOp ( String lastOp ) {
		lastOpMillis = System.currentTimeMillis();
		this.lastOp = lastOp;
	}

	public void setLastOpMillis ( long lastOpMillis ) {
		this.lastOpMillis = lastOpMillis;
	}

	public void setMotdMessage ( String motdMessage ) {
		this.motdMessage = motdMessage;
	}

	public void setSodcCookieName ( String sodcCookieName ) {
		this.sodcCookieName = sodcCookieName;
	}

	/**
	 * Kill off the spawned threads - triggered from ServiceRequests
	 */
	private boolean shutdown = false;

	public boolean isShutdown () {
		return shutdown;
	}

	@EventListener ( { ContextClosedEvent.class } )
	public void onSpringShutdownEvent ( ContextClosedEvent event ) {

		logger.warn( "Receive Spring ContextClosedEvent" );
		// shutdown();

	}

	@PreDestroy
	public void shutdown () {

		logger.info( "Standard out is used because log4j thread will be shutdown" );
		System.out.println( "\n\n\n*************** Shutting Threads down  **********************" );
		System.out.println( "\n*************** Shutting Publishers down" );
		try {
			for ( MetricsPublisher publisher : publishers ) {
				publisher.stop();
			}

			for ( OsSharedResourcesCollector collector : osSharedResourceCollectorMap.values() ) {
				collector.shutdown();
			}

			for ( OsProcessCollector collector : osProcessCollectorMap.values() ) {
				collector.shutdown();
			}
			for ( ServiceCollector collector : serviceCollectorMap.values() ) {
				collector.shutdown();
			}

			getOsManager()
				.shutDown();

		} catch (Exception e1) {
			logger.error( "Errors on shutdown", e1 );
		}

		this.shutdown = true;

		System.out.println( "\n*************** Shutting Collectors down" );
		try {
			for ( OsSharedResourcesCollector collector : osSharedResourceCollectorMap.values() ) {
				collector.uploadMetricsNow();
			}

			for ( OsProcessCollector collector : osProcessCollectorMap.values() ) {
				collector.uploadMetricsNow();
			}
			for ( ServiceCollector collector : serviceCollectorMap.values() ) {
				collector.uploadMetricsNow();
			}

		} catch (Exception e1) {
			logger.error( "Errors on shutdown", e1 );
		}

		if ( refreshConfigPool != null ) {
			refreshConfigPool.shutdownNow();
		}

		if ( _hostStatusManager != null ) {
			System.out.println( "\n*************** Shutting HostManager down" );
			// only admins
			_hostStatusManager.shutdown();
			_hostStatusManager = null;
		}
		// if (osManager != null)
		// osManager.shutDown();

		System.out.println( "\n***************  Flushing Event Cache: all collections will be uploaded to server  **********************" );
		int maxAttempts = 0;
		while (csapEventClient.getBacklogCount() > 0 && maxAttempts++ < 40) {

			try {

				logger.info( "csapEventClient Backlog count: {}", csapEventClient.getBacklogCount() );
				Thread.sleep( 1000 );
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		if ( csapEventClient.getBacklogCount() > 0 ) {
			logger
				.error( "\n\t ======= Max Attempts reached: {} while flushing event queue. {} items will be lost.\n\n\n\n ",
					maxAttempts, csapEventClient.getBacklogCount() );
		} else {
			logger.info( "\n\t ======= Exiting - all event data flushed successfully\n\n\n\n" );
		}

	}

	List<MetricsPublisher> publishers = new ArrayList<MetricsPublisher>();

	@Override
	public String toString () {

		StringBuilder sbuf = new StringBuilder( "\n\n ============== Active Model: "
				+ getActiveModel()
					.getReleasePackageName() );

		getRootModel()
			.getReleasePackages()
			.forEach( model -> {

				sbuf.append( "\n Host Map for " + model.getReleasePackageName() );
				for ( String host : model
					.getHostToConfigMap()
					.keySet() ) {
					sbuf.append( "\n\t" + host + "\n\t\t" );
					ArrayList<ServiceInstance> svcList = model
						.getHostToConfigMap()
						.get( host );
					if ( svcList != null ) {
						int num = 0;
						for ( ServiceInstance serviceDefinition : svcList ) {
							sbuf.append( "     " + serviceDefinition.toSummaryString() );
							if ( ++num % 5 == 0 ) {
								sbuf.append( "\n\t\t" );
							}
						}
					}
				}

				sbuf.append( "\n Service Map for " + model.getReleasePackageName() );
				for ( String service : model
					.getServiceToAllInstancesMap()
					.keySet() ) {
					sbuf.append( "\n\t" + service + "\n\t\t" );
					ArrayList<ServiceInstance> svcList = model
						.getServiceToAllInstancesMap()
						.get( service );
					if ( svcList != null ) {
						for ( ServiceInstance serviceDefinition : svcList ) {
							sbuf.append( "\t" + serviceDefinition.toSummaryString() );
						}
					}
				}
			} );

		return sbuf.toString();
	}

	public class HTMLCharacterEscapes extends CharacterEscapes {

		/**
		 *
		 */
		private static final long serialVersionUID = 1L;
		private final int[] asciiEscapes;

		public HTMLCharacterEscapes( ) {
			// start with set of characters known to require escaping
			// (double-quote, backslash etc)
			int[] esc = CharacterEscapes.standardAsciiEscapesForJSON();
			// and force escaping of a few others:
			esc['<'] = CharacterEscapes.ESCAPE_STANDARD;
			esc['>'] = CharacterEscapes.ESCAPE_STANDARD;
			esc['&'] = CharacterEscapes.ESCAPE_STANDARD;
			esc['\''] = CharacterEscapes.ESCAPE_STANDARD;
			asciiEscapes = esc;
		}

		// this method gets called for character codes 0 - 127
		@Override
		public int[] getEscapeCodesForAscii () {
			return asciiEscapes;
		}

		// and this for others; we don't need anything special here
		@Override
		public SerializableString getEscapeSequence ( int ch ) {
			// no further escaping (beyond ASCII chars) needed:
			return null;
		}
	}

	public ReleasePackage getModel ( String hostName, String serviceName ) {

		ReleasePackage hostModel;
		if ( hostName.equals( "*" ) ) {
			hostModel = getRootModel().getModelForService( serviceName );
		} else {
			hostModel = getModelForHost( hostName );
		}

		if ( serviceName.equals( AGENT_ID ) ) {
			hostModel = getRootModel();
		}

		return hostModel;
	}

	public ReleasePackage getModelForHost ( String hostName ) {

		if ( !Application.isJvmInManagerMode() ) {
			return getActiveModel();
		}
		ReleasePackage releasePackage = getRootModel()
			.getReleasePackageForHost( hostName );

		return releasePackage;
	}

	final static Logger serviceDebugLogger = LoggerFactory.getLogger( "org.csap.serviceDebug" );

	public boolean isServiceDebug ( ServiceInstance serviceInstance ) {

		if ( serviceDebugLogger.isDebugEnabled() ) {
			File serviceDebugFile = new File(
				getProcessingDir().getAbsolutePath() + "/" + serviceInstance.getServiceName_Port() + ".debug" );

			// logger.info( "checking for {}",
			// serviceDebugFile.getAbsolutePath() );
			if ( serviceDebugFile.exists() ) {
				return true;
			}
		}
		return false;
	}

	public File getLogDir ( String serviceName_port ) {

		ServiceInstance instance = getServiceInstanceCurrentHost( serviceName_port );
		if ( instance == null ) {
			logger.warn( "Did not location instance for: () ", serviceName_port );
			return null;
		}

		return instance.getLogWorkingDirectory();
	}

	public String getDefaultLogFileName ( String svcName ) {
		ServiceInstance instance = getServiceInstanceCurrentHost( svcName );
		return instance.getDefaultLogToShow();
	}

	public String getDefaultLogFile ( String svcName ) {
		File logDir = getLogDir( svcName );

		String logFileName = getDefaultLogFileName( svcName );
		File defaultFile = new File( logDir, logFileName );

		// needs to be remote
		if ( !defaultFile.exists() && logDir.exists() ) {
			logger.debug( "Searching now: {} ", logDir.getAbsolutePath() );
			try {
				for ( String name : logDir.list() ) {

					logger.debug( "found: {} ", name );
					logFileName = name;
					if ( name.endsWith( ".log" ) ) {
						break;
					}
				}
			} catch (Exception e) {
				logger.debug( "Error getting files", e );
			}
		}
		return logDir.getName() + "/" + logFileName;
	}

	public File getRequestedFile (	String fromFolder, String svcName,
									boolean isLogFile ) {

		File targetFile = new File( getProcessingDir(), fromFolder );

		if ( isLogFile ) {
			targetFile = new File( getLogDir( svcName ),
				fromFolder );
		} else if ( svcName != null && fromFolder.startsWith( FileToken.PROPERTY.value ) ) {
			targetFile = new File( getPropertyFolder( svcName ),
				fromFolder.substring( 9 ) );

		} else if ( fromFolder.startsWith( FileToken.PROCESSING.value ) ) {
			targetFile = new File( getProcessingDir(),
				fromFolder.substring( FileToken.PROCESSING.value.length() ) );

		} else if ( fromFolder.startsWith( FileToken.STAGING.value ) ) {

			if ( fromFolder.startsWith( FileToken.STAGING.value + "/conf/" ) && isRunningOnDesktop() ) {
				targetFile = new File( getDefinitionFolder(),
					fromFolder.substring( 16 ) );
				logger.info( " Desktop definition: {}", targetFile.getAbsolutePath() );
			} else {
				targetFile = new File( getStagingFolder(),
					fromFolder.substring( FileToken.STAGING.value.length() ) );
			}
		} else if ( fromFolder.startsWith( FileToken.HOME.value ) ) {
			targetFile = new File( System.getProperty( "user.home" ), fromFolder.substring( 8 ) );
			// targetFile = new File( "/home", fromFolder.substring( 8 ) );
			// if ( System.getenv( "HOME" ) != null ) {
			// targetFile = new File( System.getenv( "HOME" ),
			// fromFolder.substring( 8 ) );
			// } else {
			//
			// targetFile = new File( System.getenv( "user.home" ),
			// fromFolder.substring( 8 ) );
			// }
		} else if ( fromFolder.startsWith( FileToken.ROOT.value ) ) {
			targetFile = new File( "/", fromFolder.substring( 8 ) );
			if ( fromFolder.contains( ":" ) ) {
				logger.debug( "Suspected windows path: {} . Will skip past the : for dev purposes.", fromFolder );
				targetFile = new File( "/", fromFolder.substring( fromFolder.lastIndexOf( ":" ) + 1 ) );
			}
		}

		try {
			targetFile = targetFile.getCanonicalFile();
		} catch (IOException e) {
			logger.error( "Failed to resolve file: {}", targetFile );
		}

		if ( isDisplayOnDesktop() ) {
			// targetFile = getProcessingDir();
			logger.info( "Desktop Detected: fromFolder: {}   targetFile: {}", fromFolder, targetFile );
		}
		logger.debug( "fromFolder: {}   targetFile: {}", fromFolder, targetFile );

		return targetFile;
	}

	private int desktopDisplayCount = 0;
	private int MAX_DESKTOP_COUNT = 10;

	public boolean isDisplayOnDesktop () {
		if ( isRunningOnDesktop() ) {
			if ( desktopDisplayCount++ < MAX_DESKTOP_COUNT ) {
				return true;
			}
		}
		return false;
	}

	public static String getBUILD_DIR () {
		return BUILD_DIR;
	}

	public CsapCoreService getCsapCoreService () {
		return csapCoreService;
	}

	public void setCsapCoreService ( CsapCoreService agentService ) {
		this.csapCoreService = agentService;
	}

	public void flushCacheToDisk ( ArrayNode cache, String cacheName ) {
		// TODO Auto-generated method stub

		try {
			File cacheFile = getHostCollectionCacheLocation( cacheName );

			if ( cacheFile.exists() ) {
				logger.error( "Existing file found, should not happen: {}", cacheFile.getCanonicalPath() );
				cacheFile.delete();
			}
			// show during shutdowns - log4j may not be output
			System.out.println( "\n *** Writing cache to disk: {}" + cacheFile.getAbsolutePath() );

			FileUtils.writeStringToFile( cacheFile, jacksonMapper.writeValueAsString( cache ) );
		} catch (Exception e) {
			logger.error( "Failed to store cache {}", CSAP.getCsapFilteredStackTrace( e ) );
		}

	}

	private File getHostCollectionCacheLocation ( String cacheName ) {
		File cacheFile = new File( getCsapSavedFolder(), "collection-cache/" + cacheName + ".json" );
		cacheFile.getParentFile().mkdirs() ;
		return cacheFile;
	}

	public void loadCacheFromDisk ( ArrayNode cache, String cacheName ) {
		try {
			File cacheFile = getHostCollectionCacheLocation( cacheName );
			
			if ( ! cacheFile.exists() ) {
				 cacheFile = new File( getStagingFolder(), cacheName + ".json" );
				logger.info( "Legacy file support: {}", cacheFile.getAbsolutePath() );
			}

			if ( cacheFile.exists() ) {
				logger.warn( "Reading cache disk: {}", cacheFile.getAbsolutePath() );

				String cacheData = FileUtils.readFileToString( cacheFile ) ;
				JsonNode loadedCache = jacksonMapper.readTree( cacheData );
				if ( loadedCache.isArray() ) {
					logger.info( "Loading disk entries: {}", loadedCache.size() );
					cache.addAll( (ArrayNode) loadedCache );
				}

				logger.info( "in memory cache size: {}", cache.size() );
				if ( !cacheFile.delete() ) {
					logger.warn( "Cache read in - but cannot delete" );
				}
				;
			} else {
				logger.error( "Existing cache file not found: {} , should not happen", cacheFile.getCanonicalPath() );
			}

		} catch (Exception e) {
			logger.error( "Failed to load cache {}", CSAP.getCsapFilteredStackTrace( e ) );
		}

	}

	final static int CHUNKING_SIZE = 100 * 1024;

	public RestTemplate getAgentPooledConnection ( long fileSize, int timeoutSeconds ) {
		/**
		 * HttpClient will run OOM if filesize is large. Use the
		 * 
		 */

		if ( agentRestTemplate == null ) {
			logger.error( "Pool not initialized - verify setup" );
		}

		if ( agentRestTemplate == null
				|| (fileSize > CHUNKING_SIZE)
				|| (timeoutSeconds > getAgentConnectinReadTimeoutSeconds()) ) {

			// Pooled connections are NOT used when:
			// Transfer of large objects (100's of mb for large packages)
			// OOM exception will occur
			// Transfer of scripts - possibly long timeouts

			// logger.info( "csapCoreService: {}", csapCoreService );
			if ( getCsapCoreService().isDisableSslValidation() ) {
				HttpComponentsClientHttpRequestFactory factory = getCsapCoreService()
					.csapRestFactory()
					.buildFactoryDisabledSslChecks( "AgentPoolOnDemand", lifeCycleSettings().getAdminToAgentTimeoutSeconds(),
						timeoutSeconds );
				factory.setBufferRequestBody( false );
				return new RestTemplate( factory );
			}

			SimpleClientHttpRequestFactory simpleFactory = new SimpleClientHttpRequestFactory();
			simpleFactory.setReadTimeout( timeoutSeconds * 1000 );
			simpleFactory.setChunkSize( CHUNKING_SIZE ); //
			simpleFactory.setConnectTimeout( lifeCycleSettings().getAdminToAgentTimeoutMs() );
			simpleFactory.setBufferRequestBody( false );

			return new RestTemplate( simpleFactory );
		}

		// return pooled/active connection
		return agentRestTemplate;
	}

	public boolean isHostAuthenticatedMember ( String ipAddress ) {
		try {
			InetAddress addr = InetAddress.getByName( ipAddress );
			String remoteServerName = addr.getHostName();

			if ( remoteServerName.equals( "127.0.0.1" ) ) {
				remoteServerName = "localhost";
			}

			if ( remoteServerName.contains( "." ) ) {
				remoteServerName = remoteServerName.substring( 0, remoteServerName.indexOf( "." ) );
			}

			if ( remoteServerName.equals( "rtp-someDeveloper-8811" ) && getCurrentLifeCycle().equals( "dev" ) ) {

				logger.warn( "DEVELOPER TESTING: Resolved: {} to host: {}", ipAddress, remoteServerName );
				return true;
			}
			logger.debug( "Resolved: {} to host: {}", ipAddress, remoteServerName );

			return getAllHostsInAllPackagesInCurrentLifecycle().contains( remoteServerName );

		} catch (Exception e) {
			logger.error( "Failed to get hostname" );
		}

		return false;
	}

	public int getHostRefreshIntervalSeconds () {
		return hostRefreshIntervalSeconds;
	}

	public void setHostRefreshIntervalSeconds ( int hostRefreshIntervalSeconds ) {
		this.hostRefreshIntervalSeconds = hostRefreshIntervalSeconds;
	}

	public String getAgentRunHome () {
		return agentRunHome;
	}

	public void setAgentRunHome ( String agentRunHome ) {
		this.agentRunHome = agentRunHome;
	}

	public String getAgentRunUser () {
		return agentRunUser;
	}

	public void setAgentRunUser ( String agentRunUser ) {
		this.agentRunUser = agentRunUser;
	}

	public int getAgentConnectinReadTimeoutSeconds () {
		return agentConnectinReadTimeoutSeconds;
	}

	public void setAgentConnectinReadTimeoutSeconds ( int agentConnectinReadTimeout ) {
		this.agentConnectinReadTimeoutSeconds = agentConnectinReadTimeout;
	}

}

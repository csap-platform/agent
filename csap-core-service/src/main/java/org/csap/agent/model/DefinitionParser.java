package org.csap.agent.model;

import static org.csap.agent.model.Application.AGENT_ID;

import static org.csap.agent.model.Application.MISSING_SERVICE_MESSAGE;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.io.FileUtils;
import org.csap.agent.CSAP;

import org.csap.agent.linux.HostInfo;
import org.csap.agent.misc.CsapEventClient;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 *
 *
 *
 * @author someDeveloper
 *
 *
 * @see <a href="doc-files/csagent.jpg"> Click to enlarge
 *      <IMG width=300 SRC="doc-files/csagent.jpg"></a>
 *
 * @see <a href="doc-files/spring.jpg" > Click to enlarge
 *      <IMG width=300 SRC="doc-files/modelDocs.jpg"></a>
 *
 */
public class DefinitionParser {

	private static final String PARSER_CLUSTER_VERSION = "version";
	private static final String PARSER_SERVICE_VERSION = "version";

	final Logger logger = LoggerFactory.getLogger( DefinitionParser.class );

	public static final String SERVER = "server";
	// Only using this in a couple of places. Provides some abstraction to
	// spring object model
	static private LifeCycleSettings _lastLifeCycleConfig = null;
	public static final String PARSER_OS = "os";
	public static final String PARSER_JVMS = "jvms";
	public static final String PARSER_CAPABILITY = "capability";
	public static final String PARSER_CLUSTER_DEFN = "clusterDefinitions";
	private static final String PARSER_PACKAGE_DEFN = "packageDefinition";
	private static final String PARSER_RELEASE_PACKAGE_NAME = "name";
	public static final String PARSER_RELEASE_PACKAGES = "releasePackages";
	public static final String PARSER_SETTINGS = "settings";
	public static final String DEFINITION_MONITORS = "monitors";
	public static String ERROR_INVALID_CHARACTERS = "Servie name contains invalid characters. Only Alphanumeric and '-' is permitted";
	public static String ERROR_DUPLICATE_HOST_PORT = "found duplicate host / port combination";
	public static final String CLUSTER_OS_SERVICES = "osProcessesList";
	public static final String CLUSTER_JAVA_SERVICES = "jvmPorts";
	public static final String OS_PROCESSES = "osProcesses";

	public static LifeCycleSettings getModelConfiguration () {
		return _lastLifeCycleConfig;
	}

	static public String buildServicePtr ( String serviceName, boolean isJvm ) {
		if ( isJvm ) {
			return "/" + PARSER_JVMS + "/" + serviceName;
		}
		return "/" + OS_PROCESSES + "/" + serviceName;
	}

	static public String buildLifePtr ( String lifecycle ) {

		return "/" + PARSER_CLUSTER_DEFN + "/" + lifecycle;
	}

	static public String buildClusterPtr () {

		return "/" + PARSER_CLUSTER_DEFN;
	}

	private ObjectMapper jacksonMapper = new ObjectMapper();

	Application csapApplication = null;

	// Container for both global definition, and any children
	volatile private ReleasePackage _rootModel = null;
	private TreeMap<String, String> clusterVersionToTypeMap = null;

	public DefinitionParser( Application app ) {
		this.csapApplication = app;
	}

	public ReleasePackage getRootModel () {
		return _rootModel;
	}

	public void setRootModel ( ReleasePackage updatedModel ) {
		_rootModel = updatedModel;
		_lastLifeCycleConfig = updatedModel.getLifeToMetaDataMap().get( Application.getCurrentLifeCycle() );
		return;
	}

	/**
	 *
	 * CSAP Models optionally support 0 or more release packages - for very
	 * large deployments it offers separation of management/deployment
	 *
	 * @param applicationDefinitionFile
	 * @param parsingResults
	 * @param testRootModel
	 * @param fileNameMap
	 * @param platformLifeCycle
	 * @param lifeMetaData
	 * @throws IOException
	 * @throws JsonProcessingException
	 * @throws JsonParseException
	 * @throws JsonMappingException
	 */
	private void loadReleasePackagesInLifecycle (	File applicationDefinitionFile, StringBuffer parsingResults,
													ReleasePackage testRootModel, TreeMap<String, String> fileNameMap,
													String platformLifeCycle, LifeCycleSettings lifeMetaData ) {

		ObjectNode testGlobalJson = (ObjectNode) testRootModel.getJsonModelDefinition();
		ArrayNode releasePackages = (ArrayNode) testGlobalJson.path( PARSER_CAPABILITY )
			.get( PARSER_RELEASE_PACKAGES );

		// JsonNode is not yet streamable
		StreamSupport
			.stream(
				releasePackages.spliterator(), false )
			.map( JsonNode::asText )
			// gets fileNames in definition.json array

			.forEach(
				releasePackageFileName -> {
					try {

						loadReleasePackage(
							releasePackageFileName,
							applicationDefinitionFile,
							parsingResults,
							testRootModel, fileNameMap,
							platformLifeCycle, lifeMetaData );

					} catch (Exception e) {
						logger.info( "Got error parsing release package: " + releasePackageFileName + " info: "
								+ e.getMessage() );
						throw new RuntimeException( e );
					}

				} );

	}

	public File getPackageTemplate () {

		// Loading resources has some corner cases, using spring to manage toURI
		// location
		File result = null;
		ClassPathResource cp = new ClassPathResource( "/releasePackageTemplate.json" );
		try {
			result = cp.getFile();
		} catch (IOException e) {
			logger.error( "Failed to find template", e );
		}

		return result;
	}

	private void loadReleasePackage (
										String releasePackageFileName, File applicationDefinitionFile,
										StringBuffer parsingResults,
										ReleasePackage testRootModel, TreeMap<String, String> fileNameMap,
										String platformLifeCycle, LifeCycleSettings lifeMetaData )
			throws IOException {

		if ( !fileNameMap.containsKey( releasePackageFileName ) ) {

			// Use path relative to parent to determin child
			File releasePackageFile = new File( applicationDefinitionFile.getAbsoluteFile().getParent(),
				releasePackageFileName );

			if ( !releasePackageFile.exists() || !releasePackageFile.isFile()
					|| !releasePackageFile.isFile() ) {
				logger.warn( "Creating new release package as file does not exist: {}",
					releasePackageFile.getAbsolutePath() );
				parsingResults.append( "\n Assuming new package was added:"
						+ releasePackageFile.getAbsolutePath() );
				parsingResults.append( "\n Creating using template:"
						+ releasePackageFile.getAbsolutePath() + "\n" );
				FileUtils.copyFile( getPackageTemplate(), releasePackageFile );
			}

			ObjectNode supportingJson = loadReleasePackageDefinition( releasePackageFile );
			ReleasePackage releaseModel = new ReleasePackage( supportingJson );

			releaseModel.setReleaseModelMap( testRootModel );
			releaseModel.setDefaultMavenRepo( testRootModel.getDefaultMavenRepo() );

			boolean isModelUpdated = updateModelWithPackageSection(
				releaseModel, supportingJson, releasePackageFile );
			if ( !isModelUpdated ) {
				logger.error( "Did not find " + PARSER_PACKAGE_DEFN + " in package "
						+ releasePackageFile.getAbsolutePath() );
				return;
			}

			fileNameMap.put( releasePackageFileName, releaseModel.getReleasePackageName() );

			testRootModel.putReleasePackage( releaseModel.getReleasePackageName(),
				releaseModel );

		}
		ReleasePackage releasePackage = testRootModel.getReleasePackage(
			fileNameMap.get( releasePackageFileName ) );

		if ( releasePackage.getLifeCycleToGroupMap().get( platformLifeCycle ) == null ) {
			ArrayList<String> packageGroupList = new ArrayList<String>();
			releasePackage.getLifeCycleToGroupMap().put( platformLifeCycle, packageGroupList );
		}

		ArrayList<String> packageGroupList = releasePackage.getLifeCycleToGroupMap().get(
			platformLifeCycle );

		releasePackage.getLifeCycleToHostMap().put( platformLifeCycle, new ArrayList<String>() );
		releasePackage.getLifecycleList().add( platformLifeCycle );

		for ( Iterator<String> supportingClusterIterator = releasePackage.getJsonModelDefinition()
			.path( PARSER_CLUSTER_DEFN ).path( platformLifeCycle ).fieldNames(); supportingClusterIterator
				.hasNext(); ) {

			String clusterName = supportingClusterIterator.next().trim();

			logger.debug( "clusterName: {} , platformLifeCycle: {}", clusterName, platformLifeCycle );

			JsonNode supportingClusterJson = releasePackage.getJsonModelDefinition()
				.path( PARSER_CLUSTER_DEFN ).path( platformLifeCycle ).path( clusterName );

			// logger.info("Supporting Definition"
			// + supportingClusterJson);
			generateLifecycleServiceInstances(
				releasePackage,
				platformLifeCycle, parsingResults,
				lifeMetaData, packageGroupList, clusterName, supportingClusterJson, testRootModel );

			// logger.info(fileName
			// + " SubService found:"
			// + serviceModel.getSvcToConfigMap().keySet()
			// .size());
		}

		// If we find the current host name in release, then we
		// override
		if ( releasePackage.getHostToConfigMap().keySet().contains( Application.getHOST_NAME() ) ) {
			testRootModel.setActiveModel( releasePackage );
		}

	}

	private void validateModelAndAddAgent ( StringBuffer resultsBuf, ReleasePackage testRootModel ) {

		List<JsonNode> eolAttributes = testRootModel.getJsonModelDefinition().findValues( "maxLoad" );

		if ( eolAttributes.size() > 0 ) {
			resultsBuf.append( CSAP.CONFIG_PARSE_WARN + "Found " + eolAttributes.size()
					+ " instances of maxLoad in definition. Replace with: maxHostCpuLoad.\n" );
		}

		eolAttributes = testRootModel.getJsonModelDefinition().findValues( "useSmartLogger" );

		if ( eolAttributes.size() > 0 ) {
			resultsBuf.append( CSAP.CONFIG_PARSE_WARN + "Found " + eolAttributes.size()
					+ " instances of useSmartLogger in definition. No longer supported - remove\n" );
		}

		eolAttributes = testRootModel.getJsonModelDefinition().findValues( "max_numTomcatConns" );

		if ( eolAttributes.size() > 0 ) {
			resultsBuf.append( CSAP.CONFIG_PARSE_WARN + "Found " + eolAttributes.size()
					+ " instances of max_numTomcatConns in definition. Use: max_tomcatConnections\n" );
		}

		// End to End check for factories. Must ensure that unique suffix is
		// present as the suffix is used for
		// both DB SID names and modjk worker keys/cookies
		StringBuffer checkForDuplicateSuffix = new StringBuffer( "" );
		for ( String host : testRootModel.getHostToConfigMap().keySet() ) {
			// sbuf.append("\n\t" + host + "\n\t\t");

			ArrayList<ServiceInstance> svcList = testRootModel.getHostToConfigMap().get( host );

			// check for Duplicate ports
			HashMap<String, String> testList = new HashMap<String, String>();
			for ( ServiceInstance instance : svcList ) {
				if ( testList.containsKey( instance.getPort() ) ) {
					String message = CSAP.CONFIG_PARSE_WARN
							+ instance.getLifecycle()
							+ ":"
							+ instance.getServiceName()
							+ " on host "
							+ instance.getHostName()
							+ " contains a duplicate port entry: "
							+ instance.getPort()
							+ ". Port should be changed vi the UI to ensure it is unique on each host. The other instance with this port:"
							+ testList.get( instance.getPort() ) + "\n";
					logger.warn( message );
					resultsBuf.append( message );
				} else if ( !instance.isWrapper() && !instance.getPort().equals( "0" ) ) {
					testList.put( instance.getPort(),
						instance.getLifecycle() + ":" + instance.getServiceName() );
				}
			}

			// All services on host will have the same image
			boolean isFactory = svcList.get( 0 ).isConfigureAsSingleVmPartition();
			if ( isFactory ) {
				String hostSuffix = getFactorySuffix( host, resultsBuf );
				if ( hostSuffix != null ) {
					if ( checkForDuplicateSuffix.indexOf( "," + hostSuffix + "," ) != -1 ) {
						resultsBuf
							.append( CSAP.CONFIG_PARSE_ERROR
									+ "Duplicate singleVmPartion host suffix found:"
									+ host
									+ " singleVmPartion host names must be in the form x-y where y is used for oracle instance name and modjk routing, and must be unique" );
					} else {
						checkForDuplicateSuffix.append( "," + hostSuffix + "," );
					}
				}
			}
		}
		updateModelWithCsAgentAndMetadata( testRootModel, resultsBuf );
	}

	private String getFactorySuffix ( String host, StringBuffer resultsBuf ) {
		String factorySuffix = null;
		String[] hostNameArray = host.split( "-" );
		if ( hostNameArray.length != 2 && hostNameArray.length != 3 && !host.equals( "localhost" ) ) {
			resultsBuf
				.append( CSAP.CONFIG_PARSE_ERROR
						+ "Invalid singleVmPartion host name format, found is:"
						+ host
						+ " singleVmPartion host names must be in the form x-y where y is used for oracle instance name and modjk routing, and must be unique" );

		} else {
			if ( hostNameArray.length == 2 ) {
				factorySuffix = hostNameArray[1];
			} else if ( hostNameArray.length == 3 ) {
				factorySuffix = hostNameArray[1] + hostNameArray[2];
			}

		}

		return factorySuffix;
	}

	@Autowired
	StandardPBEStringEncryptor encryptor;

	private StringBuffer activateModel ( ReleasePackage newReleasePackage ) {
		// Wire in CsAgent on All hosts on All lifecycles
		// Figure out which lifecycle current host is in
		// updateModelWithCsAgentAndMetadata(testGlobalModel,
		// resultsBuf);

		StringBuffer resultsBuf = new StringBuffer();
		ObjectNode testGlobalJson = (ObjectNode) newReleasePackage.getJsonModelDefinition();

		// updateModelWithCsAgentAndMetadata( newReleasePackage, resultsBuf );
		JsonNode appName = testGlobalJson.path( PARSER_CAPABILITY ).get( "name" );

		if ( appName != null && appName.isTextual() ) {
			newReleasePackage.setCapabilityName( appName.textValue().trim() );
		}

		JsonNode scm = testGlobalJson.path( PARSER_CAPABILITY ).get( "scm" );
		if ( scm != null && scm.isTextual() ) {
			newReleasePackage.setCapabilityScm( scm.textValue().trim() );
		}
		JsonNode scmType = testGlobalJson.path( PARSER_CAPABILITY ).get( "scmType" );
		if ( scmType != null && scmType.isTextual() ) {
			newReleasePackage.setCapabilityScmType( scmType.textValue().trim() );
		}
		JsonNode scmBranch = testGlobalJson.path( PARSER_CAPABILITY ).get( "scmBranch" );
		if ( scmBranch != null && scmBranch.isTextual() ) {
			newReleasePackage.setCapabilityScmBranch( scmBranch.textValue().trim() );
		}

		JsonNode ajp = testGlobalJson.path( PARSER_CAPABILITY ).get( "ajpSecret" );
		if ( ajp != null && ajp.isTextual() ) {
			// try decode
			String ajpSecret = ajp.textValue().trim();
			try {
				ajpSecret = encryptor.decrypt( ajpSecret );
			} catch (Exception e) {
				logger.warn( "ajpSecret is not encrypted.  Use CSAP encrypt to generate" );
			}

			newReleasePackage.setCapabilityAjpSecret( ajpSecret );
		}

		JsonNode infra = testGlobalJson.path( PARSER_CAPABILITY ).get( "infrastructure" );
		if ( infra != null && infra.isObject() ) {
			newReleasePackage.setInfrastructure( (ObjectNode) infra );
		}

		JsonNode helpItems = testGlobalJson.path( PARSER_CAPABILITY ).get( "helpMenuItems" );
		if ( helpItems != null && helpItems.isObject() ) {
			for ( Iterator<String> helpIter = helpItems.fieldNames(); helpIter.hasNext(); ) {

				String menuName = helpIter.next();
				String menuUrl = testGlobalJson.path( PARSER_CAPABILITY ).path( "helpMenuItems" )
					.path( menuName ).textValue();

				logger.debug( "*** Menu Item: {} ,  target: ", menuName, menuUrl );

				newReleasePackage.getHelpMenuUrlMap().put( menuName, menuUrl );
			}
		}

		// since we found sub packages, create an all package
		// generate "all" package
		if ( newReleasePackage.getReleaseModelCount() > 1 ) {
			newReleasePackage.generateAllPackageModel( csapApplication.getDefinitionFile().getName() );
		}
		// if (isJvmInManagerMode() &&
		// testGlobalModel.getReleaseModelCount() > 1) {
		// generateAllPackageModel(testGlobalModel);
		// }

		Collections.sort( newReleasePackage.getVersionList() );

		setRootModel( newReleasePackage );

		StringBuilder modelActivationResults = new StringBuilder(
			"\n\n Application Definition Reloaded:" );

		modelActivationResults.append(
			"\n\t Name: " + newReleasePackage.getName()
					+ "   Service Count: " + newReleasePackage.getServiceToAllInstancesMap().size()
					+ "   Host Count: " + newReleasePackage.getHostToConfigMap().size()
					+ "   Current Lifecycle: " + Application.getCurrentLifeCycle() );

		modelActivationResults.append( "\n\t Active Model: "
				+ newReleasePackage.getActiveModel().getReleasePackageName() );

		modelActivationResults.append( csapApplication.lifeCycleSettings().summarySettings() );

		modelActivationResults.append( "\n\t Agent url: "
				+ csapApplication.getAgentHostUrlPattern( false )
				+ " internal: " + csapApplication.getAgentHostUrlPattern( true ) );

		String allModelsDescription = newReleasePackage
			.getReleasePackages()
			.map( model -> {
				StringBuilder modelInfo = new StringBuilder();
				modelInfo.append( "\n\t Release Package: " );
				modelInfo.append( "  " + CSAP.pad( model.getReleasePackageName() ) );
				// modelInfo.append( ":" + model.getEmailNotifications() );
				modelInfo.append( "   Current Lifecycle Services: "
						+ model.serviceInstancesInCurrentLifeByName().size() + ", Instances: "
						+ model.getInstanceTotalCountInCurrentLC() );

				if ( model.getInstanceTotalCountInCurrentLC() > 0 ) {
					modelInfo.append( "    Hosts : "
							+ model.getLifeCycleToHostInfoMap().get( Application.getCurrentLifeCycle() )
								.size()
							+ ", All:" + model.getHostToConfigMap().size() );
				}
				return modelInfo.toString();
			} )
			.reduce( "", ( a, b ) -> a + b );

		modelActivationResults.append( allModelsDescription );

		modelActivationResults.append( "\n\n" );

		logger.warn( modelActivationResults.toString() );

		resultsBuf.append( modelActivationResults );
		// New definition means new processes need to be scanned.
		if ( csapApplication.getOsManager() == null ) {
			logger.warn( "Application manager has a null OsManger - OK for testing only" );
			return resultsBuf;
		}

		csapApplication.getOsManager().resetAllCaches();

		csapApplication.getEventClient().initialize( csapApplication.lifeCycleSettings(), csapApplication.getActiveModelName() );

		// get the current artifacts off of disk
		csapApplication.updateServicesWithArtifacts();

		// Trigger the httpdWorkers for load balancing
		if ( newReleasePackage.getServiceToAllInstancesMap().isEmpty() ) {
			logger.error( "\n\n ====================== ERROR PARSING capability =====================\n\n" );
		} else if ( !Application.isJvmInManagerMode() ) {
			// generate HTTP mappings
			ArrayList<ServiceInstance> hostInstances = csapApplication.getServicesOnHost();

			// Not efficient, but infrequent so iterate
			for ( ServiceInstance svcInstance : hostInstances ) {
				if ( svcInstance.isGenerateWebMappings() ) {
					csapApplication.buildHttpdConfiguration();
					break;
				}
			}
		}

		// Finally update httpd instances with test urls
		newReleasePackage.getHttpdTestUrls().clear();

		if ( csapApplication.getActiveModel()
			.getServiceInstances( "httpd" ).count() == 0 ) {
			newReleasePackage.getHttpdTestUrls().add( "http://noHttpdConfigured" );
		} else {
			csapApplication.getActiveModel()
				.getServiceInstances( "httpd" )
				.forEach(
					serviceInstance -> {
						newReleasePackage.getHttpdTestUrls().add(
							serviceInstance.getHostName() + ":" + serviceInstance.getPort() );
					} );
		}

		// trigger application score calculation
		csapApplication.resetAppScoreCards();

		// Update the cluster types used in csap summary ui
		newReleasePackage.setClusterVersionToTypeMap( clusterVersionToTypeMap );
		return resultsBuf;
	}

	private String checkForInvalidMongoKeys ( JsonNode definitionNode, String location ) {
		// TODO Auto-generated method stub
		StringBuffer resultsBuf = new StringBuffer( "" );

		Iterator<String> names = definitionNode.fieldNames();
		while (names.hasNext()) {
			String name = names.next();

			// if ( logger.isDebugEnabled() )
			// logger.debug("key: " + name + " location: " + location);
			JsonNode fieldValue = definitionNode.get( name );
			if ( fieldValue.isObject() ) {
				resultsBuf.append( checkForInvalidMongoKeys( fieldValue, location + "," + name ) );
			} else if ( name.contains( "." ) ) {
				resultsBuf.append( CSAP.CONFIG_PARSE_WARN + " - \".\"  should not appear in: \"" + name
						+ "\" in definition file: " + location + "\n" );
			}

		}
		// resultsBuf.append(CONFIG_PARSE_WARN +
		// " - invalid key in definition file: " + fileName + "\n");
		return resultsBuf.toString();
	}

	private void buildServiceParseError ( String serviceName, String packageName, String message )
			throws IOException {
		throw new IOException( " Service: " + serviceName + "(" + packageName + ") - " + message );
	}

	private void updateParseResults (
										String messageType, StringBuffer resultsBuf,
										String serviceName, String packageName, String message ) {
		ServiceBaseParser.updateServiceParseResults(
			resultsBuf,
			messageType,
			" Service: " + serviceName + "(" + packageName + ") - " + message );
	}

	volatile private boolean isTest_FOR_LOGS_ONLY = false;

	public synchronized StringBuffer parseConfig ( boolean isTest, File applicationDefintionFile )
			throws JsonProcessingException, IOException {

		isTest_FOR_LOGS_ONLY = isTest;

		clusterVersionToTypeMap = new TreeMap<String, String>();

		StringBuffer parsingResultsBuffer = new StringBuffer( "\n\n ============ Results from parsing: "
				+ applicationDefintionFile.getAbsolutePath() + "\n\n" );

		ObjectNode testGlobalJson = loadReleasePackageDefinition( applicationDefintionFile );

		parsingResultsBuffer.append( checkForInvalidMongoKeys( testGlobalJson, applicationDefintionFile.getAbsolutePath() ) );

		if ( !testGlobalJson.has( PARSER_CAPABILITY ) ) {
			throw new JsonParseException( "Did not find: " + PARSER_CAPABILITY, null );
		}

		//
		// Build root model
		//
		ReleasePackage testRootModel = new ReleasePackage( testGlobalJson );
		boolean isModelUpdated = updateModelWithPackageSection( testRootModel, testGlobalJson, applicationDefintionFile );
		if ( !isModelUpdated ) {
			logger.warn( "{}  is missing: {}", applicationDefintionFile.getAbsolutePath(), PARSER_PACKAGE_DEFN );
		}

		testRootModel.putReleasePackage( testRootModel.getReleasePackageName(),
			testRootModel );

		testRootModel.setDefaultMavenRepo( testGlobalJson.path( PARSER_CAPABILITY ).path( "repoUrl" )
			.textValue().trim() );

		TreeMap<String, String> fileNameMap = new TreeMap<String, String>();
		for ( Iterator<String> lifeCycleIterator = testGlobalJson.path( PARSER_CLUSTER_DEFN ).fieldNames(); lifeCycleIterator
			.hasNext(); ) {

			String platformLifeCycle = lifeCycleIterator.next().trim();

			testRootModel.getLifeCycleToHostMap().put( platformLifeCycle, new ArrayList<String>() );
			parsingResultsBuffer.append( "\n\n ===========================================================\n" );
			parsingResultsBuffer.append( "\n Processing lifecycle: " + platformLifeCycle.toString() + "\n" );

			testRootModel.getLifecycleList().add( platformLifeCycle );

			LifeCycleSettings lifeMetaData = new LifeCycleSettings();

			lifeMetaData.loadSettings( testGlobalJson, parsingResultsBuffer, platformLifeCycle, csapApplication );

			logger.debug( "platformLifeCycle: {} , Settings: {}", platformLifeCycle, lifeMetaData.toString() );

			testRootModel.getLifeToMetaDataMap().put( platformLifeCycle, lifeMetaData );

			ArrayList<String> globalGroupList = new ArrayList<String>();
			testRootModel.getLifeCycleToGroupMap().put( platformLifeCycle, globalGroupList );

			for ( Iterator<String> clusterIterator = testGlobalJson.path( PARSER_CLUSTER_DEFN )
				.path( platformLifeCycle ).fieldNames(); clusterIterator.hasNext(); ) {

				String clusterName = clusterIterator.next().trim();

				// logger.info("platformSubLife:" + platformSubLife );
				JsonNode clusterJson = testGlobalJson.path( PARSER_CLUSTER_DEFN )
					.path( platformLifeCycle ).path( clusterName );

				// the Settings MetaData is a child of life cycle. Here we
				// exclude from adding
				// to groups
				if ( getClusterPartionNode( clusterJson ).isMissingNode() ) {
					logger.debug( "Assuming current node is a settings item" );
					continue;
				}

				generateLifecycleServiceInstances(
					testRootModel,
					platformLifeCycle, parsingResultsBuffer,
					lifeMetaData, globalGroupList, clusterName, clusterJson, testRootModel );
			}
			if ( testGlobalJson.path( PARSER_CAPABILITY ).has( PARSER_RELEASE_PACKAGES ) ) {

				loadReleasePackagesInLifecycle( applicationDefintionFile, parsingResultsBuffer, testRootModel, fileNameMap,
					platformLifeCycle, lifeMetaData );
			}

			if ( parsingResultsBuffer.indexOf( CSAP.CONFIG_PARSE_ERROR ) >= 0 ) {

				logger.error( "Found Errors in parsing: "
						+ parsingResultsBuffer.substring( parsingResultsBuffer.indexOf( CSAP.CONFIG_PARSE_ERROR ) ) );

				return parsingResultsBuffer; // no point in continuing
			}
			// logger.info("Pushing " + platformLifeCycle + " with groups size "
			// + groupList.size()) ;

		}

		parsingResultsBuffer.append( "\n\n Model validation and Agent Configuration" );
		validateModelAndAddAgent( parsingResultsBuffer, testRootModel );
		loadReleaseFilesIfTheyExist( parsingResultsBuffer, testRootModel, applicationDefintionFile );
		configureRemoteCollections( parsingResultsBuffer, testRootModel, applicationDefintionFile );

		// logger.info("Parsed: " + sb);
		if ( isTest ) {
			// Hook for switch from default empty cluster, to actually loading
			// from svn
			testGlobalJson.path( PARSER_CAPABILITY ).path( "name" ).textValue().trim();
			testGlobalJson.path( PARSER_CAPABILITY ).path( "scm" ).textValue().trim();
			// testGlobalJson.path( PARSER_CAPABILITY ).path( "vdc"
			// ).textValue().trim();

		} else {

			parsingResultsBuffer.append( activateModel( testRootModel ) );

			if ( Application.isJvmInManagerMode() ) {

				csapApplication.getEventClient().publishEvent( CsapEventClient.CSAP_SYSTEM_CATEGORY + "/model/summary",
					"Cluster Summary", null,
					csapApplication.getCapabilitySummary( false ) );

				csapApplication.getEventClient().publishEvent( CsapEventClient.CSAP_SYSTEM_CATEGORY + "/model/definition",
					"Cluster Defintion", null,
					csapApplication.getDefinitionForAllPackages() );
			}

		}

		return parsingResultsBuffer;

	}

	private void configureRemoteCollections (
												StringBuffer parsingResults,
												ReleasePackage testRootModel,
												File applicationDefinitionFile ) {

		StringBuilder summary = new StringBuilder();

		testRootModel
			.getServiceConfigStreamInCurrentLC()
			.filter( entriesWithRemoteCollections() )
			.forEach( configureRemoteCollection( summary, parsingResults ) );

		logger.info( "Remote Collection Services: \n{}", summary );

		// On agent only?
	}

	public static Consumer<Map.Entry<String, List<ServiceInstance>>> configureRemoteCollection (
																									StringBuilder summary,
																									StringBuffer parsingResults ) {
		return serviceEntry -> {
			List<ServiceInstance> serviceInstances = serviceEntry.getValue();
			ServiceInstance firstInstance = serviceInstances.get( 0 );
			JsonNode remoteCollections = serviceInstances.get( 0 ).getAttributeAsJson( ServiceAttributes.remoteCollections );

			summary.append( "\n\n name:" );
			summary.append( serviceEntry.getKey() );
			summary.append( "\t remoteCollection: " );
			summary.append( remoteCollections );

			if ( serviceInstances.size() != remoteCollections.size() ) {
				String msg = firstInstance.getErrorHeader()
						+ "Invalid format for " + ServiceAttributes.remoteCollections
						+ " expected: " + serviceInstances.size() + " items, but found: "
						+ remoteCollections.size();
				firstInstance.updateServiceParseResults( parsingResults, CSAP.CONFIG_PARSE_WARN, msg );

			} else if ( !remoteCollections.isArray() ) {
				firstInstance.updateServiceParseResults(
					parsingResults, CSAP.CONFIG_PARSE_WARN,
					firstInstance.getErrorHeader()
							+ "Invalid format for " + ServiceAttributes.remoteCollections
							+ " expected: array, found: " + remoteCollections.toString() );

			} else {
				int remoteIndex = 0;
				for ( ServiceInstance instance : serviceInstances ) {
					instance.configureRemoteCollection( remoteIndex, parsingResults );
					remoteIndex++;
				}
			}
		};
	}

	public static Predicate<Map.Entry<String, List<ServiceInstance>>> entriesWithRemoteCollections () {
		return serviceEntry -> {
			List<ServiceInstance> serviceInstances = serviceEntry.getValue();
			if ( serviceInstances != null && serviceInstances.size() > 0 ) {
				JsonNode remoteCollections = serviceInstances.get( 0 ).getAttributeAsJson( ServiceAttributes.remoteCollections );
				if ( remoteCollections != null ) {
					return true;
				}
			}
			return false;
		};
	}

	private void loadReleaseFilesIfTheyExist (
												StringBuffer parsingResults,
												ReleasePackage testRootModel,
												File applicationDefinitionFile ) {

		StringBuilder releaseLoadResults = new StringBuilder();

		// Go throught all the models updating version where needed.
		testRootModel
			.getReleasePackages()
			.filter( model -> !model.getReleasePackageName().equals( ReleasePackage.GLOBAL_PACKAGE ) )
			.forEach( model -> {

				// Use path relative to parent to determin child
				File releasePackageFile = model.getReleaseFile( applicationDefinitionFile );
				logger.debug( "model: {} releaseFile: {}", model.getReleasePackageName(), releasePackageFile );

				// logger.info( "Call path: {}",
				// Application.getCsapFilteredStackTrace( new Exception(
				// "calltree" ), "." ) );
				logger.debug( "{} Check for release file: {}",
					model.getReleasePackageName(), releasePackageFile.getAbsolutePath() );

				if ( releasePackageFile.exists() ) {
					if ( releaseLoadResults.length() == 0 ) {
						releaseLoadResults.append( "\n\n ============ Release Files Found ==========" );
					}
					releaseLoadResults.append( "\n"
							+ model.getReleasePackageName() + " Release File: " + releasePackageFile.getAbsolutePath() );

					try {
						loadReleaseFileForCurrentLifecycle( releaseLoadResults, releasePackageFile, model );

					} catch (Exception e) {
						releaseLoadResults
							.append( "\n" + CSAP.CONFIG_PARSE_WARN + releasePackageFile.getName() + " failed to parse due to: "
									+ e.getClass().getName() );
						logger.error( "Failed to parse: {}", releasePackageFile.getAbsolutePath(), e );
					}

				} else {
					logger.debug( "Release file not found" );
				}
			} );

		if ( releaseLoadResults.length() == 0 ) {
			releaseLoadResults.append( "\n\n No release files found\n" );
		}
		releaseLoadResults.append( "\n" );

		logger.info( releaseLoadResults.toString() );
		parsingResults.append( releaseLoadResults );

	}

	private void loadReleaseFileForCurrentLifecycle (
														StringBuilder releaseLoadResults, File releasePackageFile, ReleasePackage model )
			throws IOException {

		ObjectNode releaseNode = loadReleasePackageDefinition( releasePackageFile );

		releaseNode.fields().forEachRemaining( serviceEntry -> {
			String serviceName = serviceEntry.getKey();
			if ( model
				.getServiceNamesInLifecycle()
				.contains( serviceName ) ) {

				ObjectNode versionNode = (ObjectNode) serviceEntry.getValue();
				if ( versionNode.has( Application.getCurrentLifeCycle() ) ) {
					String newVersion = versionNode.get( Application.getCurrentLifeCycle() ).asText();
					if ( newVersion.split( ":" ).length != 4 ) {

						releaseLoadResults.append( "\n\t" + CSAP.CONFIG_PARSE_WARN
								+ releasePackageFile.getName()
								+ " Invalid artifact format: " + newVersion );
					} else {
						releaseLoadResults.append( "\n\t Updating: " + serviceEntry.getKey() + " to version: " + newVersion );
						model.getServiceInstances( serviceName )
							.forEach( serviceInstance -> {
								if ( serviceInstance.isIsAllowReleaseFileToOverride() ) {
									serviceInstance.setMavenId( newVersion );
								}
							} );
					}
				}

			} else {
				releaseLoadResults.append( "\n\t" + CSAP.CONFIG_PARSE_WARN
						+ releasePackageFile.getName()
						+ " Did not find: " + serviceEntry.getKey() );
			}
		} );
	}

	private boolean updateModelWithPackageSection (
													ReleasePackage testRootModel,
													ObjectNode testGlobalJson,
													File packageFile ) {

		JsonNode packageDefnJson = testGlobalJson.path( PARSER_PACKAGE_DEFN );
		if ( packageDefnJson.isMissingNode() ) {
			return false;
		}
		testRootModel.setReleaseInfo(
			packageDefnJson.get( PARSER_RELEASE_PACKAGE_NAME )
				.asText(),
			packageFile.getName() );

		if ( packageDefnJson.has( "architect" ) ) {
			testRootModel.setArchitect( packageDefnJson.path( "architect" ).asText() );
		}

		if ( packageDefnJson.has( "emailNotifications" ) ) {
			testRootModel.setEmailNotifications( packageDefnJson.path( "emailNotifications" ).asText() );
		}

		if ( packageDefnJson.has( "description" ) ) {
			testRootModel.setDescription( packageDefnJson.path( "description" ).asText() );
		}

		return true;
	}

	public ObjectNode loadReleasePackageDefinition ( File definitionFile )
			throws JsonProcessingException,
			IOException {

		ObjectNode resultNode = null;
		if ( definitionFile.exists() ) {
			String configJson = FileUtils.readFileToString( definitionFile );

			jacksonMapper.getFactory().enable( JsonParser.Feature.ALLOW_COMMENTS );
			jacksonMapper.getFactory().enable( JsonParser.Feature.ALLOW_SINGLE_QUOTES );
			jacksonMapper.getFactory().enable(
				JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER );
			jacksonMapper.getFactory().enable( JsonParser.Feature.AUTO_CLOSE_SOURCE );

			jacksonMapper.getFactory().setCharacterEscapes( new HTMLCharacterEscapes() );
			resultNode = (ObjectNode) jacksonMapper.readTree( configJson );

			logger.debug( "Parsed Cluster from file: {} Contains: \n{}", definitionFile.getAbsolutePath(),
				resultNode.toString() );

			String packageName = resultNode.path( PARSER_CAPABILITY ).path( "name" ).asText( definitionFile.getName() );
			if ( resultNode.has( PARSER_PACKAGE_DEFN ) ) {
				packageName = resultNode.path( PARSER_PACKAGE_DEFN ).path( PARSER_RELEASE_PACKAGE_NAME )
					.asText();
			}

			// Most basic test
			logger.info( "\n\n ===> Parsing package: {} using: {} \t testing: {}"
					+ "\n\t {}, \t Size: {}\n\n",
				packageName, definitionFile.getName(), isTest_FOR_LOGS_ONLY,
				definitionFile.getParentFile().getAbsolutePath(),
				definitionFile.length() );

		} else {
			logger.error( "Did not find JSON cluster file: " + definitionFile.getAbsolutePath() );
			throw new IOException( "File does not exist: " + definitionFile.getAbsolutePath() );
		}
		return resultNode;
	}

	private ServiceInstance configureJavaService (	StringBuffer resultsBuf, ReleasePackage testModel,
													String platformLifeCycle, String jvmName, JsonNode jvmDefinition,
													String platformVersion,
													JsonNode versionNode, String subLife, String hostName, String port,
													ClusterPartition partitionType ) {

		logger.debug(
			"jvmName: {}, subLife: {}, platformLifeCycle: {}, platformVersion: {} \njvmNode: {},\n versionNode: {},\n ",
			jvmName, subLife, platformLifeCycle, platformVersion, jvmDefinition, versionNode );

		String origVersion = platformVersion;

		JsonNode serviceLifecycleSettings = null;
		if ( jvmDefinition.path( PARSER_SERVICE_VERSION ).has( platformVersion ) ) {
			serviceLifecycleSettings = jvmDefinition.path( PARSER_SERVICE_VERSION ).path( platformVersion );
		} else if ( versionNode != null && versionNode.has( "baseVersion" ) ) {
			// Base Version is a hook to avoid having to code specific configs
			// in each JVM
			platformVersion = versionNode.get( "baseVersion" ).textValue().trim();
			// resultsBuf.append("Checking for baseVersion: " +
			// platformVersion);
			if ( jvmDefinition.path( PARSER_SERVICE_VERSION ).has( platformVersion ) ) {
				serviceLifecycleSettings = jvmDefinition.path( PARSER_SERVICE_VERSION ).path( platformVersion );
			}
		} else if ( jvmName.contains( AGENT_ID ) ) {
			String firstVersion = jvmDefinition.path( PARSER_SERVICE_VERSION ).fieldNames().next();
			serviceLifecycleSettings = jvmDefinition.path( PARSER_SERVICE_VERSION ).path( firstVersion );
			resultsBuf
				.append( "\n" + CSAP.CONFIG_PARSE_WARN
						+ " CsAgent platform version not found: " + platformVersion
						+ ",  defaulting to use: " + serviceLifecycleSettings );
		}

		if ( !port.endsWith( "x" ) ) {
			updateParseResults(
				CSAP.CONFIG_PARSE_WARN, resultsBuf,
				jvmName, testModel.getReleasePackageFileName(),
				"port does not end with 'x'. Java based instances require xxx1 for http, xxx2 for tomcat admin, xxx6/7/8 for JMX "
						+ ".  Reference found in lifecycle: " + platformLifeCycle
						+ ", cluster: " + subLife );
		}

		ServiceInstance serviceInstance = new ServiceInstance();
		serviceInstance.setServiceName( jvmName );

		if ( serviceLifecycleSettings == null ) {
			if ( !jvmName.contains( AGENT_ID ) ) {

				logger.warn( CSAP.CONFIG_PARSE_WARN + "Did not find platform version: "
						+ platformVersion + " for jvm: " + jvmName + " == Skipping \n" );

				// CsAgent is ALWAYS on a host
				return serviceInstance;
			} else {

				resultsBuf.append( CSAP.CONFIG_PARSE_WARN
						+ "Did not find platform version or base versions for CsAgent: "
						+ platformVersion + " for jvm: " + jvmName + " == Using jvm globals" );
			}
		}

		serviceInstance.setPort( port ); // Mostly used for wrappers
		if ( port.endsWith( "x" ) ) {
			serviceInstance.setPort( port.substring( 0, 3 ) + "1" ); // most
			// CSSP
		} // jvms
		serviceInstance.setJmxPort( port.substring( 0, 3 ) + "6" );
		if ( jvmDefinition.has( ServiceAttributes.javaJmxPort.value ) ) {
			serviceInstance.setJmxPort( jvmDefinition.path( ServiceAttributes.javaJmxPort.value ).asText().trim() );
		}
		serviceInstance.setHostName( hostName );
		serviceInstance.setPartitionType( partitionType );
		serviceInstance.setPlatformVersion( origVersion );
		serviceInstance.setLifecycle( subLife );
		serviceInstance.setMavenRepo( testModel.getDefaultMavenRepo() );

		/**
		 *
		 * Adds the common settings with optional overrides
		 *
		 */
		serviceInstance.parseDefinition( testModel.getReleasePackageFileName(), jvmDefinition, resultsBuf );

		if ( serviceInstance.getContext().length() == 0 ) {
			if ( (!serviceInstance.isSpringBoot())
					|| (serviceInstance.isSpringBoot() && serviceInstance.isApacheWebIntegration()) ) {
				serviceInstance.setContext( jvmName );
			}
		}
		//
		//
		//
		// Forcing agent to spring boot - to handle migration from cssp3
		if ( jvmName.equals( Application.AGENT_ID ) || jvmName.equals( "admin" ) ) {
			// logger.info( "Forcing BOOT on agent on host {}" , hostName );
			serviceInstance.setServerType( ServiceInstance.SPRING_BOOT );

		}
		if ( jvmName.equals( Application.AGENT_ID ) ) {
			serviceInstance.setContext( Application.AGENT_ID );
		}

		if ( serviceLifecycleSettings != null ) {
			JsonNode lifecycleNode = serviceLifecycleSettings.get( platformLifeCycle );
			if ( lifecycleNode != null ) {
				serviceInstance.parseDefinition( testModel.getReleasePackageFileName(), lifecycleNode, null );

			}
		}

		// Update Globals
		if ( serviceInstance.getDefaultBranch() == null ) {
			serviceInstance.setDefaultBranch( "HEAD" );
		}

		if ( serviceInstance.isConfigureAsSingleVmPartition() ) {
			String hostSuffix = getFactorySuffix( hostName, resultsBuf );
			if ( hostSuffix != null ) {
				if ( serviceInstance.getContext().length() != 0
						&& !serviceInstance.getContext().startsWith( "http:" ) ) {
					serviceInstance.setContext( serviceInstance.getContext() + "-" + hostSuffix );
				}
			}

		} else if ( serviceInstance.isConfigureAsMultiVmPartition() ) {
			serviceInstance.setContext( serviceInstance.getContext() + "-"
					+ serviceInstance.getPlatformVersion() );

		} else if ( !jvmName.contains( AGENT_ID ) ) {
			// Validate JVM name is unique when multiVM is in use on BOTH
			// releasePackages

			String currentModelName = testModel.getReleasePackageName();

			testModel
				.getReleasePackages()
				.forEach( model -> {
					if ( model.getReleasePackageName().equals( currentModelName ) ) {
						return;
					}

					if ( model.getServiceToAllInstancesMap().containsKey( jvmName ) ) {
						for ( ServiceInstance checkInstance : model.getServiceToAllInstancesMap().get( jvmName ) ) {

							if ( checkInstance.isConfigureAsEnterprise() ) {

								updateParseResults(
									CSAP.CONFIG_PARSE_ERROR, resultsBuf,
									jvmName, testModel.getReleasePackageFileName(),
									" service found in multiple release packages, it must be unique"
											+ ".  Reference found in: " + model.getReleasePackageFileName()
											+ ", cluster: " + subLife );
								return;
							}
						}
					}
				} );
		}

		// logger.info( "{}: host: {}, context: {} , url: {}",
		// serviceInstance.paddedId(), serviceInstance.getHostName(),
		// serviceInstance.getContext(), serviceInstance.getUrl() );
		// default url using context
		if ( serviceInstance.getContext().startsWith( "http" ) ) {
			serviceInstance.setUrl( serviceInstance.getContext() );
		} else {
			String url = csapApplication
				.getApplicationUrl(
					serviceInstance.getHostName(),
					":" + serviceInstance.getPort() + "/" + serviceInstance.getContext() );

			serviceInstance.setUrl( url );
		}

		// override if launchUrl specified
		if ( jvmDefinition.has( "launchUrl" ) ) {
			String launchUrl = jvmDefinition.path( "launchUrl" ).asText();

			if ( !launchUrl.startsWith( "http" ) ) {
				launchUrl = csapApplication.getApplicationUrl(
					serviceInstance.getHostName(),
					":" + serviceInstance.getPort() + "/" + launchUrl );
			}

			serviceInstance.setUrl( launchUrl );
		}

		// logger.info( "{}: host: {}, context: {} , url: {}",
		// serviceInstance.paddedId(), serviceInstance.getHostName(),
		// serviceInstance.getContext(), serviceInstance.getUrl() );

		if ( !jvmDefinition.has( ServiceAttributes.cookieName.value ) ) {
			// hook for factories
			if ( serviceInstance.isConfigureAsSingleVmPartition()
					|| serviceInstance.isConfigureAsMultiVmPartition() ) {

				serviceInstance.setCookieName( "JSESSIONID_" + serviceInstance.getContext() );
			}
		}

		if ( !jvmDefinition.has( ServiceAttributes.cookiePath.value ) ) {
			// hook for factories
			if ( serviceInstance.isConfigureAsSingleVmPartition()
					|| serviceInstance.isConfigureAsMultiVmPartition() ) {

				serviceInstance.setCookiePath( "/" );
			}
		}

		// resultsBuf.append(instanceConfig.toString() + "\n");
		// Now fill in the data
		// structures
		// First the AdminUrls for host
		// queries.
		if ( serviceInstance.getServiceName().equalsIgnoreCase( AGENT_ID ) ) {

			testModel.getLifeCycleToHostMap().get( platformLifeCycle ).add( hostName );

			String restUrl = csapApplication.getAgentUrl( hostName, "/api/" );

			// hostList.add(hostName);
			testModel
				.getLifeCycleToHostInfoMap()
				.get( platformLifeCycle )
				.add(
					new HostInfo( hostName, restUrl ) );

			testModel.getHostToAdminMap().put( serviceInstance.getHostName(), serviceInstance );

		}
		ArrayList<ServiceInstance> hostToConfigList = testModel.getHostToConfigMap().get(
			serviceInstance.getHostName() );

		if ( hostToConfigList == null ) {
			hostToConfigList = new ArrayList<ServiceInstance>();
			testModel.getHostToConfigMap().put( serviceInstance.getHostName(), hostToConfigList );
		}
		hostToConfigList.add( serviceInstance );

		ArrayList<ServiceInstance> svcToConfigList = testModel.getServiceToAllInstancesMap().get(
			serviceInstance.getServiceName() );
		if ( svcToConfigList == null ) {
			svcToConfigList = new ArrayList<ServiceInstance>();
			testModel.getServiceToAllInstancesMap().put( serviceInstance.getServiceName(), svcToConfigList );
		}
		svcToConfigList.add( serviceInstance );

		return serviceInstance;
	}

	private void generateLifecycleServiceInstances (
														ReleasePackage releasePackage,
														String platformLifeCycle, StringBuffer resultsBuf, LifeCycleSettings lifeMetaData,
														ArrayList<String> groupList, String platformSubLife, JsonNode subLifeNode,
														ReleasePackage testRootModel
														)
			throws IOException, JsonParseException, JsonMappingException {

		logger.debug( "Checking: {} ", releasePackage.getReleasePackageFileName() );

		if ( subLifeNode.has( DEFINITION_MONITORS ) ) {
			// this is a hook for cluster level settings that overwrite
			// the defaults
			List<JsonNode> nodes = subLifeNode.findValues( "hosts" );

			for ( JsonNode node : nodes ) {
				ArrayNode nodeArray = (ArrayNode) node;
				for ( JsonNode hostNameNode : nodeArray ) {
					String host = hostNameNode.asText().replaceAll( "\\$host", Application.getHOST_NAME() );
					lifeMetaData
						.addHostMonitor( host, subLifeNode.path( DEFINITION_MONITORS ) );
				}
				// logger.warn("_node: " +
				// jacksonMapper.writeValueAsString( node));
			}
		}
		groupList.add( platformSubLife );

		// Any logic/semantic errors are pushed via
		// Application.CONFIG_PARSE_ERROR
		resultsBuf.append( "\n \t " + releasePackage.getReleasePackageFileName() + "\t - \t" + platformSubLife );
		
		
		configureAllJavaServices( resultsBuf, releasePackage, platformLifeCycle, platformSubLife, subLifeNode );

		configureAllOsProcesses( resultsBuf, releasePackage, platformLifeCycle, platformSubLife, subLifeNode, testRootModel );

		generateMapsForConfigScreen( releasePackage, platformLifeCycle, platformSubLife, subLifeNode );

	}

	private JsonNode getClusterPartionNode ( JsonNode node ) {
		JsonNode result = node.path( ClusterPartition.getPartitionType( node ).getJson() );

		// backwards compatible, can be deleted in 3.1 or later
		if ( result.isMissingNode() ) {
			return node.path( PARSER_CLUSTER_VERSION );
		}

		return result;
	}

	private void configureAllJavaServices (	StringBuffer resultsBuf, ReleasePackage model,
											String platformLifeCycle, String platformSubLife, JsonNode subLifeNode )
			throws IOException, JsonParseException, JsonMappingException {

		JsonNode modelJson = model.getJsonModelDefinition();
		if ( !subLifeNode.has( CLUSTER_JAVA_SERVICES ) ) {
			logger.debug( "Did not find an JVM element" );
			return;
		}

		Map<String, ArrayList<String>> jvmToPortListMap = null;
		try {
			jvmToPortListMap = jacksonMapper.readValue( subLifeNode.path( CLUSTER_JAVA_SERVICES ).traverse(),
				new TypeReference<Map<String, ArrayList<String>>>() {
				} );
		} catch (Exception Exception) {

			buildServiceParseError( "multiple in " + CLUSTER_JAVA_SERVICES, model.getReleasePackageFileName(),
				"Invalid jvm port definition" + ".  Reference found in lifecycle: " + platformLifeCycle
						+ ", cluster: " + platformSubLife );

		}

		// svcList.add("CsAgent"); // Must be everywhere
		// sb.append("\t\t\t Sub Lifecycle services found: "
		// + svcList.toString() + "\n");
		ArrayList<String> portCheckList = new ArrayList<String>();

		Pattern p = Pattern.compile( "[\\p{Alnum}-]*" );

		for ( String jvmName : jvmToPortListMap.keySet() ) {

			// core semantic checks
			JsonNode serviceDefinition = modelJson.path( PARSER_JVMS ).path( jvmName );
			validateService( resultsBuf, jvmName, serviceDefinition, model, platformLifeCycle, platformSubLife );

			if ( jvmToPortListMap.get( jvmName ).size() == 0 ) {
				updateParseResults( CSAP.CONFIG_PARSE_ERROR, resultsBuf,
					jvmName, model.getReleasePackageFileName(),
					"Did not find port(s) for JVM" + ".  Reference found in lifecycle: " + platformLifeCycle
							+ ", cluster: " + platformSubLife );
				continue;
			}

			for ( Iterator<String> versionIter = getClusterPartionNode( subLifeNode ).fieldNames(); versionIter
				.hasNext(); ) {
				String platformVersion = versionIter.next().trim();

				if ( !model.getVersionList().contains( platformVersion ) ) {
					model.getVersionList().add( platformVersion );
				}
				String subLife = platformLifeCycle + "-" + platformSubLife + "-" + platformVersion;

				if ( !model.getLifecycleList().contains( subLife ) ) {
					model.getLifecycleList().add( subLife );
					// resultsBuf.append( "\t\t Sub Lifecycle found: " + subLife
					// + "\n" );
				}

				JsonNode versionNode = getClusterPartionNode( subLifeNode ).path( platformVersion );
				ArrayList<String> hostList = jacksonMapper.readValue( versionNode.path( "hosts" )
					.traverse(),
					new TypeReference<ArrayList<String>>() {
					} );

				updateHostVariables( hostList );
				if ( hostList.size() == 0 ) {
					throw new IOException( "Did not find any hosts configured for cluster: "
							+ platformLifeCycle + "." + platformSubLife );
				}

				ClusterPartition partitionType = ClusterPartition.getPartitionType( subLifeNode );

				hostList = hackForDefaultDefintionFile( hostList );

				// Collections.sort( hostList );
				model.getLifeCycleToHostMap().put( subLife, hostList );

				for ( String hostName : hostList ) {

					// if (hostName.startsWith("$")) {
					// hostName = System.getenv(hostName.substring(1));
					// logger.warn("Pulled a host from the env vars:"
					// + hostName.substring(1) + " is: " + hostName);
					// }
					for ( String port : jvmToPortListMap.get( jvmName ) ) {

						if ( portCheckList.contains( hostName + port ) ) {

							updateParseResults( CSAP.CONFIG_PARSE_ERROR, resultsBuf,
								jvmName, model.getReleasePackageFileName(),
								ERROR_DUPLICATE_HOST_PORT + " " + hostName + ":" + port
										+ ".  Reference found in lifecycle: " + platformLifeCycle
										+ ", cluster: " + platformSubLife );
							continue;
						} else {
							portCheckList.add( hostName + port );
						}
						ServiceInstance instance = configureJavaService(
							resultsBuf, model, platformLifeCycle, jvmName,
							serviceDefinition, platformVersion, versionNode, subLife, hostName, port,
							partitionType );

						if ( jvmToPortListMap.get( jvmName ).size() > 1 ) {
							instance.setMultiplePortsOnHost( true );
						}
					}
				}
			}

		}
	}

	private void updateHostVariables ( ArrayList<String> hostList ) {

		if ( hostList.contains( "$host" ) ) {
			hostList.remove( "$host" );
			hostList.add( Application.getHOST_NAME() );
		}
		// hostNameNode.asText().replaceAll( "\\$host", getHOST_NAME() )

	}

	/**
	 * hack for single nodes and Desktop using vars
	 *
	 * @param hostList
	 * @return
	 */
	private ArrayList<String> hackForDefaultDefintionFile ( ArrayList<String> hostList ) {
		if ( hostList.size() == 1 ) {
			String currHost = hostList.get( 0 );
			if ( currHost.startsWith( "$" ) ) {
				// currHost = System.getenv(currHost.substring(1));
				// HOST_NAME = currHost;
				hostList = new ArrayList<String>();
				hostList.add( Application.getHOST_NAME() );
			}
		}
		return hostList;
	}

	Pattern serviceNameCheck = Pattern.compile( "[\\p{Alnum}-]*" );

	private void validateService (	StringBuffer resultsBuf,
									String serviceName,
									JsonNode serviceNode,
									ReleasePackage model,
									String platformLifeCycle,
									String platformSubLife )
			throws IOException {

		if ( !serviceNameCheck.matcher( serviceName ).matches() ) {

			buildServiceParseError( serviceName, model.getReleasePackageFileName(),
				ERROR_INVALID_CHARACTERS + ".  Reference found in lifecycle: " + platformLifeCycle
						+ ", cluster: " + platformSubLife );
		}

		if ( serviceNode.isMissingNode() ) {

			buildServiceParseError( serviceName, model.getReleasePackageFileName(),
				MISSING_SERVICE_MESSAGE + ".  Reference found in lifecycle: " + platformLifeCycle
						+ ", cluster: " + platformSubLife );
		} else {

			boolean isOs = serviceNode.has( SERVER ) && serviceNode.path( SERVER ).asText().equals( "os" );

			if ( ! isOs ) {
				JsonNode serviceVersionObjects = serviceNode.path( PARSER_SERVICE_VERSION );
				if ( serviceVersionObjects.isMissingNode() || !serviceVersionObjects.fieldNames().hasNext() ) {
					updateParseResults(
						CSAP.CONFIG_PARSE_WARN, resultsBuf,
						serviceName, model.getReleasePackageFileName(),
						"Missing cluster version" + ".  Reference found in lifecycle: " + platformLifeCycle
								+ ", cluster: " + platformSubLife );

				}
			}
		}

	}

	private void configureAllOsProcesses (	StringBuffer resultsBuf, ReleasePackage model,
											String platformLifeCycle, String platformSubLife, JsonNode subLifeNode,
											ReleasePackage testRootModel)
			throws IOException, JsonParseException, JsonMappingException {

		JsonNode modelDefinition = model.getJsonModelDefinition();

		if ( !subLifeNode.has( CLUSTER_OS_SERVICES ) ) {
			logger.debug( "Did not find an OS element" );
			return;
		}

		// resultsBuf.append( "\nLooking for OS Instances" );
		ArrayList<String> osProcessList = jacksonMapper.readValue( subLifeNode.path( CLUSTER_OS_SERVICES ).traverse(),
			new TypeReference<ArrayList<String>>() {
			} );

		for ( String osProcessName : osProcessList ) {

			JsonNode serviceDefinition = modelDefinition.path( OS_PROCESSES ).path( osProcessName );
			
			if ( serviceDefinition.isMissingNode() ) {
				// Support for overloading from root model
				logger.info( "Looking in global model: {} for {}", testRootModel.getName(), osProcessName  );
				serviceDefinition = testRootModel.getJsonModelDefinition().path( OS_PROCESSES ).path( osProcessName );
				
			}
			

			// core semantic checks
			validateService( resultsBuf, osProcessName, serviceDefinition, model, platformLifeCycle, platformSubLife );

			for ( Iterator<String> versionIter = getClusterPartionNode( subLifeNode ).fieldNames(); versionIter
				.hasNext(); ) {
				String platformVersion = versionIter.next().trim();
				String origPlatformVersion = platformVersion;
				if ( !model.getVersionList().contains( platformVersion ) ) {
					model.getVersionList().add( platformVersion );
				}

				String subLife = platformLifeCycle + "-" + platformSubLife + "-" + platformVersion;

				if ( !model.getLifecycleList().contains( subLife ) ) {
					model.getLifecycleList().add( subLife );
					// resultsBuf.append( "\t\t Sub Lifecycle found: " + subLife
					// + "\n" );
				}

				JsonNode versionNode = getClusterPartionNode( subLifeNode ).path( platformVersion );
				ArrayList<String> hostList = jacksonMapper.readValue( versionNode.path( "hosts" )
					.traverse(),
					new TypeReference<ArrayList<String>>() {
					} );

				updateHostVariables( hostList );
				ClusterPartition partitionType = ClusterPartition.getPartitionType( subLifeNode );

				hostList = hackForDefaultDefintionFile( hostList );

				model.getLifeCycleToHostMap().put( subLife, hostList );

				for ( String hostName : hostList ) {

					configureOsService( hostName, osProcessName, partitionType, serviceDefinition, resultsBuf, model, origPlatformVersion,
						subLife, platformVersion, versionNode, platformLifeCycle );
				}
			}

		}
	}

	private void configureOsService (
										String hostName, String osProcessName, ClusterPartition partitionType,
										JsonNode osDefinition, StringBuffer resultsBuf, ReleasePackage testModel,
										String origPlatformVersion, String subLife, String platformVersion,
										JsonNode versionNode, String platformLifeCycle ) {

		ServiceInstance serviceInstance = new ServiceInstance();
		serviceInstance.setHostName( hostName.trim() );
		serviceInstance.setServiceName( osProcessName );
		serviceInstance.setPartitionType( partitionType );

		// defaults for wrappers
		serviceInstance.setMavenRepo( testModel.getDefaultMavenRepo() );
		serviceInstance.setContext( osProcessName );
		serviceInstance.setPlatformVersion( origPlatformVersion );

		if ( !osDefinition.has( "port" ) ) {
			serviceInstance.setPort( "0" );
		} else {
			// default launch for OS services
			serviceInstance.setPort( osDefinition.path( "port" ).asText().trim() );

			if ( !serviceInstance.getPort().equals( "0" ) ) {
				String launchUrl = csapApplication.getApplicationUrl(
					serviceInstance.getHostName(),
					":" + serviceInstance.getPort() + "/" );

				serviceInstance.setUrl( launchUrl );
			}

		}

		if ( serviceInstance.getPort().length() >= 4 ) {
			serviceInstance.setJmxPort( serviceInstance.getPort().substring( 0, 3 ) + "6" );
		}

		if ( osDefinition.has( "user" ) ) {
			serviceInstance.setUser( osDefinition.path( "user" ).asText().trim() );
		}

		if ( osDefinition.has( "scmVersion" ) ) {
			serviceInstance.setScmVersion( osDefinition.path( "scmVersion" ).asText().trim() );
		}

		serviceInstance.setLifecycle( subLife );
		/**
		 *
		 * Adds the common settings with optional overrides
		 *
		 */
		serviceInstance.parseDefinition( testModel.getReleasePackageFileName(), osDefinition, resultsBuf );

		JsonNode serviceLifecycleSettings = null;

		if ( osDefinition.path( PARSER_SERVICE_VERSION ).has( platformVersion ) ) {
			serviceLifecycleSettings = osDefinition.path( PARSER_SERVICE_VERSION ).path( platformVersion );

		} else if ( versionNode.has( "baseVersion" ) ) {
			// Base Version is a hook to avoid having to code
			// specific configs
			// in each JVM
			platformVersion = versionNode.get( "baseVersion" ).textValue().trim();
			// resultsBuf.append("Checking for baseVersion: " +
			// platformVersion);
			if ( osDefinition.path( PARSER_SERVICE_VERSION ).has( platformVersion ) ) {
				serviceLifecycleSettings = osDefinition.path( PARSER_SERVICE_VERSION ).path( platformVersion );
			}

		}
		if ( serviceLifecycleSettings != null ) {
			JsonNode lifecycleNode = serviceLifecycleSettings.get( platformLifeCycle );
			if ( lifecycleNode != null ) {
				serviceInstance.parseDefinition( testModel.getReleasePackageFileName(), lifecycleNode, null );

			}
		}

		ArrayList<ServiceInstance> hostToConfigList = testModel.getHostToConfigMap().get(
			serviceInstance.getHostName() );
		if ( hostToConfigList == null ) {
			hostToConfigList = new ArrayList<ServiceInstance>();
			testModel.getHostToConfigMap().put( serviceInstance.getHostName(),
				hostToConfigList );
		}
		hostToConfigList.add( serviceInstance );

		ArrayList<ServiceInstance> svcToConfigList = testModel.getServiceToAllInstancesMap().get(
			serviceInstance.getServiceName() );
		if ( svcToConfigList == null ) {
			svcToConfigList = new ArrayList<ServiceInstance>();
			testModel.getServiceToAllInstancesMap().put( serviceInstance.getServiceName(),
				svcToConfigList );
		}

		svcToConfigList.add( serviceInstance );

		// resultsBuf.append(instanceConfig.toString() + "\n");
	}

	private void generateMapsForConfigScreen (	ReleasePackage model, String platformLifeCycle,
												String platformSubLife, JsonNode subLifeNode )
			throws IOException, JsonParseException,
			JsonMappingException {

		logger.debug( "Generating metadata for browsing" );
		ArrayList<String> groupVersionList = new ArrayList<String>();

		// subLifeNode.path("version")
		for ( Iterator<String> versionIter = getClusterPartionNode( subLifeNode ).fieldNames(); versionIter
			.hasNext(); ) {
			String platformVersion = versionIter.next().trim();

			groupVersionList.add( platformVersion );
			JsonNode versionNode = getClusterPartionNode( subLifeNode ).path( platformVersion );

			String baseVersion = "";
			if ( versionNode.has( "baseVersion" ) ) {
				// Base Version is a hook to avoid having to code
				// specific configs
				// in each JVM
				baseVersion = versionNode.get( "baseVersion" ).textValue().trim();
			}
			// logger.info("+++baseVersion " + baseVersion + " platformVersion "
			// + platformVersion) ;

			ArrayList<String> hostList = jacksonMapper.readValue( versionNode.path( "hosts" )
				.traverse(),
				new TypeReference<ArrayList<String>>() {
				} );

			updateHostVariables( hostList );

			model.getLcGroupVerToHostMap().put(
				platformLifeCycle + platformSubLife + platformVersion, hostList );

			ClusterPartition clusterType = ClusterPartition.ENTERPRISE;
			if ( subLifeNode.has( CLUSTER_JAVA_SERVICES ) ) {
				Map<String, ArrayList<String>> jvmToPortListMap = jacksonMapper.readValue(
					subLifeNode.path( CLUSTER_JAVA_SERVICES ).traverse(),
					new TypeReference<Map<String, ArrayList<String>>>() {
					} );

				TreeMap<String, ArrayList<String>> filteredMap = new TreeMap<String, ArrayList<String>>();
				// hook for CsAgent on every host
				filteredMap.put( AGENT_ID, new ArrayList<String>() );
				filteredMap.get( AGENT_ID ).add( CSAP.AGENT_PORT );

				for ( String jvm : jvmToPortListMap.keySet() ) {
					ArrayList<ServiceInstance> jvmInstances = model.getServiceToAllInstancesMap().get( jvm );

					if ( jvmInstances == null ) {
						continue;
					}

					for ( ServiceInstance instance : jvmInstances ) {
						if ( instance.getPlatformVersion().equals( platformVersion )
								|| instance.getPlatformVersion().equals( baseVersion ) ) {

							if ( instance.isConfigureAsSingleVmPartition() ) {
								clusterType = ClusterPartition.SHARED_NOTHING;
							}

							if ( instance.isConfigureAsMultiVmPartition() ) {
								clusterType = ClusterPartition.MULTI_SHARED_NOTHING;
							}

							filteredMap.put( jvm, jvmToPortListMap.get( jvm ) );
							continue;
						}
					}

				}
				model.getLcGroupVerToJvmMap().put(
					platformLifeCycle + platformSubLife + platformVersion, filteredMap );
			}

			if ( subLifeNode.has( CLUSTER_OS_SERVICES ) ) {
				ArrayList<String> osProcessList = jacksonMapper.readValue( subLifeNode.path( CLUSTER_OS_SERVICES ).traverse(),
					new TypeReference<ArrayList<String>>() {
					} );
				model.getLcGroupVerToOsMap().put(
					platformLifeCycle + platformSubLife + platformVersion, osProcessList );
			}

			clusterVersionToTypeMap.put( platformLifeCycle + platformSubLife + platformVersion,
				clusterType.getJson() );

		}

		model.getGroupToVersionMap().put( platformLifeCycle + platformSubLife, groupVersionList );
	}

	private void addCsAgentInstances (	String lc, ReleasePackage testRootModel, StringBuffer resultsBuf,
										ReleasePackage model ) {
		ArrayList<String> lifeCycleHostList = model.getLifeCycleToHostMap().get( lc );

		// logger.info("___ lifeCycleHostList: " + lc +
		// " hosts: "
		// + lifeCycleHostList);
		// if (lifeCycleHostList == null) continue ;
		if ( !lc.contains( "-" ) ) {
			return; // platformlc hosts are added in loop
		} // below

		String plaformLc = lc.substring( 0, lc.indexOf( "-" ) );

		if ( !model.getLifeCycleToHostInfoMap().containsKey( plaformLc ) ) {
			ArrayList<HostInfo> hostInfoList = new ArrayList<HostInfo>();
			model.getLifeCycleToHostInfoMap().put( plaformLc, hostInfoList );
		}

		for ( String host : lifeCycleHostList ) {

			// logger.info("=========== Adding Csagent to: " +
			// host) ;
			if ( !model.getHostToAdminMap().containsKey( host ) ) {

				// Inherit host type based on first services
				ClusterPartition partitionTypeForFirstService = ClusterPartition.ENTERPRISE;
				String defaultVersion = "1";
				if ( model.getHostToConfigMap().containsKey( host )
						&& model.getHostToConfigMap().get( host ).size() != 0 ) {
					partitionTypeForFirstService = model.getHostToConfigMap().get( host )
						.get( 0 )
						.getPartitionType();

					defaultVersion = model.getHostToConfigMap().get( host ).get( 0 )
						.getPlatformVersion();
				}

				JsonNode agentDefinition = model.getJsonModelDefinition().path( PARSER_JVMS )
					.path( AGENT_ID );

				// CsAgent is NOT needed in subpackages, so we
				// can use
				// it from there
				if ( agentDefinition.isMissingNode() ) {
					agentDefinition = testRootModel.getJsonModelDefinition().path( PARSER_JVMS )
						.path( AGENT_ID );
				}

				configureJavaService( resultsBuf, model, plaformLc, AGENT_ID, agentDefinition,
					defaultVersion, null, plaformLc, host, "801x",
					partitionTypeForFirstService );

				HostInfo dummyForCheck = new HostInfo( Application.getHOST_NAME(), "" );
				// logger.info("___
				// working_lifeCycleToHostInfoMap.get(platformLifeCycle): "
				// +
				// working_lifeCycleToHostInfoMap.get(plaformLc)
				// ) ;
				if ( model.getLifeCycleToHostInfoMap().get( plaformLc )
					.contains( dummyForCheck ) ) {
					csapApplication.setCurrentLifeCycle( plaformLc );
					logger.debug( "___ Setting lifecycle {} as host found in cluster.js: ", plaformLc );
				}

			}
		}

	}

	/**
	 *
	 * CsAgent is added to every host, and host metadata is generated. lifecycle
	 * of current host is set
	 *
	 * @param configNode
	 * @param resultsBuf
	 * @param defaultMavenRepo
	 * @param model
	 */
	private void updateModelWithCsAgentAndMetadata (	ReleasePackage testRootModel,
														StringBuffer resultsBuf ) {

		logger.debug( "testRootModel: {}" + testRootModel.getHostsCurrentLc() );
		testRootModel
			.getReleasePackages()
			.forEach( model -> {
				model.getLifecycleList()
					.forEach( lifeCycle -> {
						addCsAgentInstances( lifeCycle, testRootModel, resultsBuf, model );
					} );

			} );

		testRootModel
			.getReleasePackages()
			.forEach( model -> {
				model.getServiceNameStream()
					.forEach( serviceName -> {
						buildServiceToHostList( serviceName, model );
					} );

			} );

		// Ensure host is only contained in a single release package
		TreeMap<String, String> hostDuplicateCheck = new TreeMap<String, String>();

		testRootModel
			.getReleasePackages()
			.forEach( model -> {

				model.getAdminHostNameStream()
					.forEach( adminHostName -> {
						ensureUniqueHost( adminHostName, resultsBuf, hostDuplicateCheck, model );
					} );

			} );

	}

	public void ensureUniqueHost (	String adminHostName, StringBuffer resultsBuf,
									TreeMap<String, String> hostDuplicateCheck,
									ReleasePackage model ) {

		logger.debug( "checking {} in \n\t {}", adminHostName, hostDuplicateCheck );

		if ( hostDuplicateCheck.containsKey( adminHostName ) ) {

			String message = CSAP.CONFIG_PARSE_ERROR
					+ " Host: "
					+ adminHostName
					+ " was found in multiple release packages: "
					+ hostDuplicateCheck.get( adminHostName )
					+ " and "
					+ model.getReleasePackageFileName()
					+ ". To ensure package isolation, a host can only appear in 1 package\n";
			resultsBuf.append( message );
			logger.warn( message );
		}
		hostDuplicateCheck.put( adminHostName, model.getReleasePackageFileName() );

	}

	private void buildServiceToHostList ( String service, ReleasePackage model ) {
		logger.debug( "Lifecycle: {} All Hosts: {}", Application.getCurrentLifeCycle(), model.getHostsCurrentLc() );

		List<ServiceInstance> instanceListLC = model.getServiceInstancesInAllLifecycles( service )
			.filter( serviceInstance -> model.getHostsCurrentLc().contains( serviceInstance.getHostName() ) )
			.collect( Collectors.toList() );

		model.serviceInstancesInCurrentLifeByName().put( service, instanceListLC );

		logger.debug( "{}  Instances: {}", service, instanceListLC );

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

}

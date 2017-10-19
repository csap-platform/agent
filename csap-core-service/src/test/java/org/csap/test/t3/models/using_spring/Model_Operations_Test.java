package org.csap.test.t3.models.using_spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;

import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.csap.agent.CSAP;
import org.csap.agent.CsapCoreService;
import org.csap.agent.input.http.ui.rest.DefinitionRequests;
import org.csap.agent.model.Application;
import org.csap.agent.services.SourceControlManager;
import org.csap.test.InitializeLogging;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

@RunWith ( SpringRunner.class )
@SpringBootTest ( classes = CsapCoreService.class )
@ActiveProfiles ( profiles = { "junit", "company" } )
public class Model_Operations_Test {

	final static private Logger logger = LoggerFactory.getLogger( Model_Operations_Test.class );

	@Autowired
	private WebApplicationContext wac;

	private MockMvc mockMvc;

	@BeforeClass
	public static void setUpBeforeClass ()
			throws Exception {
		InitializeLogging.printTestHeader( logger.getName() );
	}

	@AfterClass
	public static void tearDownAfterClass ()
			throws Exception {
	}

	@Before
	public void setUp ()
			throws Exception {

		logger.info( "Deleting: {}", csapApp.getHttpdIntegration().getHttpdWorkersFile().getParentFile().getAbsolutePath() );
		FileUtils.deleteQuietly( csapApp.getHttpdIntegration().getHttpdWorkersFile().getParentFile() );

		Application.setJvmInManagerMode( false );
		csapApp.setAutoReload( false );

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/test_application_model.json" ).getPath() );

		logger.info( "Loading test configuration: {}", csapApplicationDefinition );

		assertThat( csapApp.loadDefinitionForJunits( false, csapApplicationDefinition ) ).as( "No Errors or warnings" ).isTrue();

		this.mockMvc = MockMvcBuilders.webAppContextSetup( this.wac ).build();
		csapApp.setTestMode( true );

	}

	@After
	public void tearDown ()
			throws Exception {
	}

	ObjectMapper jacksonMapper = new ObjectMapper();

	/**
	 *
	 * Scenario: CS_AP UI supports encrypting property files
	 *
	 * Verify: REST API gets values
	 *
	 * @throws Exception
	 */
	@Test
	public void getSecurePropertiesFile ()
			throws Exception {

		String message = "Hitting controller to get convert property..... returns JSON";
		logger.info( InitializeLogging.TC_HEAD + message );

		String contents = FileUtils.readFileToString( (new ClassPathResource(
			"csapSecurity.properties" )).getFile() );
		logger.info( contents );
		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.ENCODE_FULL_URL )
				.param( "propertyFileContents", contents )
				.accept( MediaType.APPLICATION_JSON ) );

		//
		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
					responseJsonNode ) );

		// Mock validates the existence. But we can get very explicit using the
		// result
		// Assert properties read from secureSample.properties file. password is
		// scrambled enc(....) in file, but decrypted by property loader in
		// SecureProperties.java
		assertTrue( message, responseJsonNode.get( "converted" ) != null );

		ArrayNode convertedArray = (ArrayNode) responseJsonNode.get( "converted" );
		assertTrue( message, convertedArray.size() > 1 );

		assertFalse( message, convertedArray.get( 0 ).get( "key" ).asText().equals( "" ) );

	}

	/**
	 *
	 * Scenario: CS_AP UI supports encrypting a single value
	 *
	 * Verify: REST API gets values
	 *
	 * @throws Exception
	 */
	@Test
	public void getSecureSingleValue ()
			throws Exception {

		String message = "Hitting controller to get convert property..... returns JSON";
		logger.info( InitializeLogging.TC_HEAD + message );

		String contents = "testValue";
		logger.info( contents );
		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.ENCODE_FULL_URL )
				.param( "propertyFileContents", contents )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
					responseJsonNode ) );

		// Assert properties read from secureSample.properties file. password is
		// scrambled enc(....) in file, but decrypted by property loader in
		// SecureProperties.java
		assertTrue( message, responseJsonNode.get( "converted" ) != null );

		ArrayNode convertedArray = (ArrayNode) responseJsonNode.get( "converted" );
		assertTrue( message, convertedArray.size() == 1 );

		assertFalse( message, convertedArray.get( 0 ).get( "key" ).asText().equals( "" ) );

	}

	@Inject
	Application csapApp;

	@Test
	public void validate_application_model ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/test_application_model.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config : \n" + csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/validateDefinition" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
					responseJsonNode ) );

		assertTrue( "Found Success", responseJsonNode.get( "success" ).asBoolean() );
		assertEquals( "Error Count", 0, responseJsonNode.get( "errors" ).size() );
		assertEquals( "Warning Count", 0, responseJsonNode.get( "warnings" ).size() );

		//
		//
		File workingFolder = new File( csapApp.getRootModelBuildLocation()
				+ SourceControlManager.CONFIG_SUFFIX_FOR_UPDATE );

		File copiedPackage = new File( workingFolder, csapApplicationDefinition.getName() );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );
		assertTrue( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	/**
	 *
	 * Most basic operation: apply a basic definition file
	 *
	 * @throws Exception
	 */
	@Test
	public void validateDefinitionWithWarnings ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/application_with_warnings.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config : \n" + csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/validateDefinition" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
					responseJsonNode ) );

		assertThat( responseJsonNode.get( "success" ).asBoolean() )
			.as( "Validatation Success" )
			.isTrue();

		assertEquals( "Error Count", 0, responseJsonNode.get( "errors" ).size() );
		assertEquals( "Warning Count", 1, responseJsonNode.get( "warnings" ).size() );

		File workingFolder = new File( csapApp.getRootModelBuildLocation()
				+ SourceControlManager.CONFIG_SUFFIX_FOR_UPDATE );

		File copiedPackage = new File( workingFolder, "test_application_model.json" );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );
		assertTrue( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	/**
	 *
	 * Run Validator with a broken file
	 *
	 * @throws Exception
	 */
	@Test
	public void validateDefinitionWithJsonErrors ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/application_with_invalid_format.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config : \n" + csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/validateDefinition" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
					responseJsonNode ) );

		assertFalse( "Found Success", responseJsonNode.get( "success" ).asBoolean() );
		assertEquals( "Error Count", 1, responseJsonNode.get( "errors" ).size() );

		File workingFolder = new File( csapApp.getRootModelBuildLocation()
				+ SourceControlManager.CONFIG_SUFFIX_FOR_UPDATE );

		File copiedPackage = new File( workingFolder, "test_application_model.json" );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath() + " length: "
				+ csapApplicationDefinition.length()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() + " length: " + copiedPackage.length() );

		assertTrue( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	/**
	 *
	 * Run Validator with a broken file
	 *
	 * @throws Exception
	 */
	@Test
	public void validate_application_with_missing_jvm_fails_to_load ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/application_with_jvm_missing_error.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config : \n" + csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/validateDefinition" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ jacksonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
					responseJsonNode ) );

		assertFalse( "Found Success", responseJsonNode.get( "success" ).asBoolean() );
		assertEquals( "Error Count", 1, responseJsonNode.get( "errors" ).size() );

		File workingFolder = new File( csapApp.getRootModelBuildLocation()
				+ SourceControlManager.CONFIG_SUFFIX_FOR_UPDATE );

		File copiedPackage = new File( workingFolder, "test_application_model.json" );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );

		assertTrue( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	@Test
	public void applySimpleDefinition ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/test_application_model.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config : \n" + csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content()
					.contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertThat( responseJsonNode.get( "result" ).asText() )
			.as( "Result Message has no errors" )
			.doesNotContain( CSAP.CONFIG_PARSE_ERROR )
			.contains( DefinitionRequests.EMAIL_DISABLED );

		File copiedPackage = new File( csapApp.getDefinitionFolder(), "test_application_model.json" );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );
		assertTrue( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	@Test
	public void applyDefinitionWithErrors ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/application_with_invalid_format.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD
				+ "Applying a definition with a new package : \n" + csapApplicationDefinition;
		logger.info( message );

		csapApp.configureForDefinitionOperationsTest( csapApplicationDefinition );

		// Hit the endpoint
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertTrue( message, responseJsonNode.get( "result" ).asText().indexOf( CSAP.CONFIG_PARSE_ERROR ) >= 0 );

		// On junits, cluster reloads are placed in test folder, and packages
		// are
		// not reloaded.
		// We just confirm files exist
		File copiedPackage = new File( csapApp.getDefinitionFolder(), "test_application_model.json" );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );
		assertFalse( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	/**
	 *
	 * Scenario - load a definition with multiple sub packages
	 *
	 * Verify - that sub package definitions are loaded
	 *
	 * @throws Exception
	 */
	@Test
	public void applyDefinitionWithSubPackages ()
			throws Exception {

		File configFile = new File(
			getClass().getResource( "/org/csap/test/data/clusterConfigMultiple.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD
				+ "Applying a definition with a new package : \n" + configFile.getAbsolutePath();
		logger.info( message );

		csapApp.configureForDefinitionOperationsTest( configFile );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig", FileUtils.readFileToString( configFile ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertTrue( message,
			responseJsonNode.get( "result" ).asText().indexOf( CSAP.CONFIG_PARSE_ERROR ) == -1 );

		// On junits, cluster reloads are placed in test folder, and packages
		// are
		// not reloaded.
		// We just confirm files exist

		File originalPackage = new File(
			getClass().getResource( "/org/csap/test/data/test_application_model.json" ).getPath() );

		File copiedPackage = new File( csapApp.getDefinitionFolder(),
			"clusterConfigMultipleA.json" );

		logger.info( "original path: " + originalPackage.getAbsolutePath()
				+ "\n cluster File path: " + configFile.getAbsolutePath() );

		assertNotEquals( "package file size matches", originalPackage.length(), configFile.length() );

		copiedPackage = new File( csapApp.getDefinitionFolder(), "test_application_model.json" );

		logger.info( "original path: " + configFile.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );

		assertEquals( "main file size matches", configFile.length(),
			copiedPackage.length() );

	}

	/**
	 *
	 * Release Package scenario: 1) load the manger with a definition that
	 * includes subpackages 2) Update one of the sub packages 3) Verify that the
	 * sub package gets updated.
	 *
	 * @throws Exception
	 */
	@Test
	public void modify_child_release_package_and_apply ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/clusterConfigMultiple.json" ).getPath() );

		logger.info( InitializeLogging.TC_HEAD + "Loading a working definition with multiple supporting services: {}",
			csapApplicationDefinition.getAbsolutePath() );

		assertThat( csapApp.loadDefinitionForJunits( false, csapApplicationDefinition ) ).as( "No Errors or warnings" ).isTrue();

		// Now we update a sub package

		File updatedReleasePackage = new File( getClass().getResource( "application-test-modify.json" ).getPath() );
		logger.info( "Applying a definition with a new sub package: {}", updatedReleasePackage.getAbsolutePath() );
		csapApp.configureForDefinitionOperationsTest( csapApplicationDefinition );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "Supporting Sample A" )
				.param( "updatedConfig",
					FileUtils.readFileToString( updatedReleasePackage ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertThat( responseJsonNode.get( "result" ).asText() )
			.as( "no errors found" )
			.doesNotContain( CSAP.CONFIG_PARSE_ERROR );

		// On junits, cluster reloads are placed in test folder, and packages
		// are
		// not reloaded.
		// We just confirm files exist
		File originalPackage = new File(
			getClass().getResource( "/org/csap/test/data/clusterConfigMultipleA.json" ).getPath() );

		File copiedPackage = new File( csapApp.getDefinitionFolder(),
			"clusterConfigMultipleA.json" );

		logger.info( "original path: " + originalPackage.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );

		assertThat( originalPackage.length() )
			.as( "package size is different after test" )
			.isNotEqualTo( copiedPackage.length() );

		copiedPackage = new File( csapApp.getDefinitionFolder(),
			"clusterConfigMultiple.json" );

		logger.info( "original path: {}, copied path: {}",
			csapApplicationDefinition.getAbsolutePath(), copiedPackage.getAbsolutePath() );

		assertThat( csapApplicationDefinition.length() )
			.as( "package size is different after test" )
			.isEqualTo( copiedPackage.length() );

	}

	private String user = "someDeveloper";
	private String pass = "FIXME";

	@Test
	public void checkin_simple_definition ()
			throws Exception {

		if ( pass.equals( "FIXME" ) ) {
			logger.warn( "Skipping Test as password is not set" );
			Thread.sleep( 2000 );
			return;
		}

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/test_application_model.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config : \n" + csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityCheckIn" )
				.param( "applyButNoCheckin", "false" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", pass )
				.param( "scmUserid", user )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertThat( responseJsonNode.get( "result" ).asText() )
			.as( "Result Message has no errors" )
			.doesNotContain( CSAP.CONFIG_PARSE_ERROR );

		assertThat( responseJsonNode.get( "result" ).asText() )
			.as( "Ensure skip message is present" )
			.contains( "Skipping checkin on Desktop" );

		// assertTrue(message,
		// responseJsonNode.get("result").asText().indexOf(Application.CONFIG_PARSE_ERROR)
		// == -1);

		File copiedPackage = new File( csapApp.getDefinitionFolder(), "test_application_model.json" );

		logger.info( "original path: " + csapApplicationDefinition.getAbsolutePath()
				+ "\n cluster File path: " + copiedPackage.getAbsolutePath() );
		assertTrue( "main file size matches",
			csapApplicationDefinition.length() == copiedPackage.length() );

	}

	@Test
	public void reload_simple_definition ()
			throws Exception {

		if ( pass.equals( "FIXME" ) ) {
			logger.warn( "Skipping Test as password is not set" );
			Thread.sleep( 2000 );
			return;
		}

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/DEFAULT_APPLICATION.json" ).getPath() );

		assertThat( csapApp.loadDefinitionForJunits( false, csapApplicationDefinition ) )
			.as( "No Errors or warnings" )
			.isTrue();

		logger.info( "{} \n\n source location: {}", InitializeLogging.TC_HEAD, csapApp.getSourceLocation() );

		assertThat( csapApp.getSourceLocation() )
			.as( "shared definition location set correctly" )
			.contains( "csap_shared_definitions" );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabiltyReload" )
				.param( "applyButNoCheckin", "false" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", pass )
				.param( "scmUserid", user )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.accept( MediaType.TEXT_PLAIN_VALUE ) );

		String result = resultActions
			.andExpect( status().isOk() )
			.andExpect( content().contentTypeCompatibleWith( MediaType.TEXT_PLAIN_VALUE ) )
			.andReturn()
			.getResponse()
			.getContentAsString();

		logger.info( "result:\n {}", result );

		assertThat( result )
			.as( "Result Message has no errors" )
			.doesNotContain( CSAP.CONFIG_PARSE_ERROR );

		assertThat( result )
			.as( "Reload success messages" )
			.contains( "Copying build location", "to live location", "definitions updated, reloads will occur within 60 seconds" );

		File clusterFileName = new File( csapApp.getRootModelBuildLocation() );
		logger.info( "clusterFileName: {}", clusterFileName.getCanonicalPath() );
		assertThat( clusterFileName )
			.as( "clusterFile exists" )
			.exists();

		File clusterBackupFileName = new File( clusterFileName.getCanonicalPath() + ".old" );
		logger.info( "clusterBackupFileName: {}", clusterBackupFileName.getCanonicalPath() );
		assertThat( clusterBackupFileName )
			.as( "clusterBackupFileName exists" )
			.exists();

	}

	/**
	 *
	 * Scenario - load a definition with release package that does not exist
	 *
	 * Verify - that sub package definitions are loaded using the new package
	 * template
	 *
	 * @throws Exception
	 */
	@Test
	public void definition_with_new_package_creates_an_empty_one ()
			throws Exception {

//		File csapApplicationDefinition = new File(
//			getClass().getResource( "/org/csap/test/data/application_with_new_package.json" ).getPath() );

		File csapApplicationDefinition = InitializeLogging.getTestDefinition( "data/application_with_new_package.json" ) ;
		String message = InitializeLogging.TC_HEAD
				+ "Applying a definition with a new package : \n" + csapApplicationDefinition;
		logger.info( message );

		csapApp.configureForDefinitionOperationsTest( csapApplicationDefinition );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertThat( responseJsonNode.get( "result" ).asText() )
			.as( "Definition does not contain errors" )
			.doesNotContain( CSAP.CONFIG_PARSE_ERROR );

		File newPackageTemplatePath = new File( csapApp.getDefinitionFolder(),
			"newReleaseFile.js" );

		assertTrue( "new release file created from template", newPackageTemplatePath.exists() );

		logger.info( "templateFile path: "
				+ csapApp.getParser().getPackageTemplate().getAbsolutePath() + " File path: "
				+ newPackageTemplatePath.getAbsolutePath() );

		assertEquals( "package file size matches", csapApp.getParser().getPackageTemplate().length(),
			newPackageTemplatePath.length() );

	}

	@Test
	public void applyDefinitionWithParsingErrors ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/application_with_invalid_format.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config with errors: \n"
				+ csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertTrue( message,
			responseJsonNode.get( "result" ).asText().indexOf( CSAP.CONFIG_PARSE_ERROR ) != -1 );

	}

	@Test
	public void apply_definition_with_missing_jvm_has_error_message ()
			throws Exception {

		File csapApplicationDefinition = new File(
			getClass().getResource( "/org/csap/test/data/application_with_jvm_missing_error.json" ).getPath() );

		String message = InitializeLogging.TC_HEAD + "Validating a  config with errors: \n"
				+ csapApplicationDefinition;
		logger.info( message );

		// mock does much validation.....
		ResultActions resultActions = mockMvc.perform(
			post( CsapCoreService.DEFINITION_URL + "/CapabilityApply" )
				.param( "applyButNoCheckin", "true" )
				.param( "hostName", "localhost" )
				.param( "scmBranch", "trunk" )
				.param( "scmPass", "" )
				.param( "scmUserid", "peterUser" )
				.param( "serviceName", "HostCommand" )
				.param( "releasePackage", "SampleDefaultPackage" )
				.param( "updatedConfig",
					FileUtils.readFileToString( csapApplicationDefinition ) )
				.accept( MediaType.APPLICATION_JSON ) );

		JsonNode responseJsonNode = jacksonMapper.readTree(
			resultActions
				.andExpect( status().isOk() )
				.andExpect( content().contentTypeCompatibleWith( MediaType.APPLICATION_JSON ) )
				.andReturn()
				.getResponse()
				.getContentAsString() );

		logger.info( "result:\n"
				+ responseJsonNode.get( "result" ).asText() );

		assertTrue( message,
			responseJsonNode.get( "result" ).asText().indexOf( Application.MISSING_SERVICE_MESSAGE ) != -1 );

	}
}

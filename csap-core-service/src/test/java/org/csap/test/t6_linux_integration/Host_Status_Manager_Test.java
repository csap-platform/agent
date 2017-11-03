package org.csap.test.t6_linux_integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import javax.inject.Inject;

import org.csap.agent.CSAP;
import org.csap.agent.CsapCoreService;
import org.csap.agent.linux.HostStatusManager;
import org.csap.agent.misc.CsapEventClient;
import org.csap.agent.model.Application;
import org.csap.test.InitializeLogging;
import org.csap.test.t1_container.Boot_Container_Test;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@RunWith ( SpringRunner.class )
@SpringBootTest ( classes = CsapCoreService.class )
@ConfigurationProperties ( prefix = "test.variables" )
@ActiveProfiles ( profiles = { "junit", "company" } )
public class Host_Status_Manager_Test {

	final static private Logger logger = LoggerFactory.getLogger( Host_Status_Manager_Test.class );

	final static public File csapApplicationDefinition = new File(
		Host_Status_Manager_Test.class.getResource( "simpleApp.json" ).getPath() );


	public void setTestAdminHost1 ( String testAdminHost1 ) {
		this.testAdminHost1 = testAdminHost1;
	}

	public void setTestAdminHost2 ( String testAdminHost2 ) {
		this.testAdminHost2 = testAdminHost2;
	}

	private String testAdminHost1 = null;
	private String testAdminHost2 = null;
	
	@Test
	public void verify_setup () {

		assertThat( isSetupOk() ).as( "setup ok, ~home/csap/application-company.yml loaded" ).isTrue();

//		assertThat( csapApplication.isCollectorsStarted() )
//			.as( "collectionStarted" ).isTrue();

	}

	private boolean isSetupOk () {

		if ( testAdminHost2 == null || testAdminHost1 == null )
			return false;

		logger.info( "testAdminHost2: {}, testAdminHost1: {}", testAdminHost2, testAdminHost1 );

		return true;
	}
	@BeforeClass
	public static void setUpBeforeClass ()
			throws Exception {

		Boot_Container_Test.printTestHeader( logger.getName() );

	}

	@Inject
	Application csapApplication;

	@AfterClass
	public static void tearDownAfterClass ()
			throws Exception {
	}

	@Before
	public void setUp ()
			throws Exception {
		Application.setJvmInManagerMode( true );
		
		assertThat(
			csapApplication.loadDefinitionForJunits( false, csapApplicationDefinition ) )
				.as( "No Errors or warnings" )
				.isTrue();
	}

	@After
	public void tearDown ()
			throws Exception {
	}

	ObjectMapper jacksonMapper = new ObjectMapper();

	@Test
	public void collect_status_from_remote_hosts ()
			throws Exception {

		
		logger.info( InitializeLogging.TC_HEAD );

		if ( !isSetupOk() )
			return;

		ArrayList<String> hostList = new ArrayList<>( Arrays.asList( testAdminHost1, testAdminHost1 ) );
		
		HostStatusManager hostStatusManager = new HostStatusManager( csapApplication, 2, hostList );

		CSAP.setLogToDebug( HostStatusManager.class.getName() );

		hostStatusManager.refreshAndWaitForComplete( null );
		

		ObjectNode hostJson = hostStatusManager.getHostAsJson( testAdminHost1 );

		logger.debug( "Host Status: {}", CSAP.jsonPrint( jacksonMapper, hostJson ) );

		assertThat( hostJson.at( "/hostStats/cpuCount" ).asInt() )
			.as( "cpuCount" )
			.isGreaterThanOrEqualTo( 1 );

	}

	@Test
	public void find_host_with_lowest_load ()
			throws Exception {

		
		logger.info( InitializeLogging.TC_HEAD );

		if ( !isSetupOk() )
			return;

		ArrayList<String> hostList = new ArrayList<>( Arrays.asList( testAdminHost1, testAdminHost1 ) );
		
		HostStatusManager testStatus = new HostStatusManager( csapApplication, 2, hostList );

		testStatus.refreshAndWaitForComplete( null );
		CSAP.setLogToDebug( HostStatusManager.class.getName());
		String vmWithLowestCpu = testStatus.getHostWithLowestAttribute( hostList, "/hostStats/cpuLoad" );
		logger.info( "vmWithLowestCpu: " + vmWithLowestCpu );

		assertTrue( "Found a vm with low cpu", hostList.contains( vmWithLowestCpu ) );

	}

	@Test
	public void find_host_with_lowest_cpu ()
			throws Exception {

		
		logger.info( InitializeLogging.TC_HEAD );

		if ( !isSetupOk() )
			return;

		ArrayList<String> hostList = new ArrayList<>( Arrays.asList( testAdminHost1, testAdminHost1 ) );
		
		HostStatusManager testStatus = new HostStatusManager( csapApplication, 2, hostList );

		testStatus.refreshAndWaitForComplete( null );

		CSAP.setLogToDebug( HostStatusManager.class.getName() );
		String vmWithLowestCpu = testStatus.getHostWithLowestAttribute( hostList, "/hostStats/cpu" );
		logger.info( "vmWithLowestCpu: " + vmWithLowestCpu );

		assertTrue( "Found a vm with low cpu", hostList.contains( vmWithLowestCpu ) );

	}

}

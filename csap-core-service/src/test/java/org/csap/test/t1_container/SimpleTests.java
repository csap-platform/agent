package org.csap.test.t1_container;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.csap.agent.CSAP;
import org.csap.helpers.CsapRestTemplateFactory;
import org.csap.test.InitializeLogging;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 
 * Simple tests to validate specific configuration of Spring LDAP Template.
 * 
 * Similar to sql - LDAP has a DSL for interacting with provider, which in turn
 * is abstracted somewhat by Java nameing apis. Spring Ldap makes this much more
 * developer friendly.
 * 
 * Prior to jumping to code, it is highly recommended to make use of a desktop
 * LDAP browser to browse LDAP tree to familiarize your self with syntax and
 * available attributes.
 * 
 * Softerra ldap browser is nice way to approach
 * 
 * 
 * @author someDeveloper
 *
 * 
 * @see <a href=
 *      "http://docs.spring.io/spring-ldap/docs/1.3.2.RELEASE/reference/htmlsingle/#introduction-overview">
 *      Spring LDAP lookup </a>
 *
 */

// TO RUN - update UID and password
// @Ignore
public class SimpleTests {

	final static private Logger logger = LoggerFactory.getLogger( SimpleTests.class );

	@Autowired
	private Environment environment;

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
	}

	@After
	public void tearDown ()
			throws Exception {
	}

	// needed for property placedholder context
	@SpringBootApplication
	static class TestConfiguration {
	}

	@PostConstruct
	void printVals () {
	}

	ObjectMapper jacksonMapper = new ObjectMapper();

	@Test
	public void verifyDiskParsing ()
			throws Exception {

		String disk = "7942M";

		logger.info( "disk: {}, parsed: {}", disk, Integer.parseInt( disk.replaceAll( "[\\D]", "" ) ) );

	}

	@Test
	public void verifyJsonClone ()
			throws Exception {

		ArrayNode testArray = jacksonMapper.createArrayNode();

		testArray.add( "a" );
		testArray.add( "b" );
		testArray.add( "c" );

		ArrayNode cloneArray = testArray.deepCopy();

		cloneArray.remove( 1 );

		logger.info( "testArray: {}, cloneArray{}", testArray, cloneArray );

		// logger.info( "Missing item: {}",
		// jacksonMapper.createObjectNode().get( "missing" ).asText("") );
	}

	@Test
	public void verifyJsonFileName ()
			throws Exception {

		ObjectNode fileNode = jacksonMapper.createObjectNode();

		Files.newDirectoryStream(
			Paths.get(
				System.getProperty( "user.dir" ) + "/src/main/resources" ),
			path -> path.toString().endsWith( ".yml" ) )
			.forEach( path -> {
				logger.info( "path: {}", path );
				String content;
				try {
					content = new String( Files.readAllBytes( path ), Charset.forName( "UTF-8" ) );
					fileNode.put( path.toFile().getName(), content );
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} );

		logger.info( "fileNode: {}", CSAP.jsonPrint( jacksonMapper, fileNode ) );

		// logger.info( "Missing item: {}",
		// jacksonMapper.createObjectNode().get( "missing" ).asText("") );
	}

	@Ignore
	@Test
	public void verifyInsecureRest ()
			throws Exception {

		CsapRestTemplateFactory factory = new CsapRestTemplateFactory();

		RestTemplate sslTestTemplate = factory.buildDefaultTemplate( "junit", false, 10, 10, 5, 60, 300 );

		String sslUrl = "https://csap-secure.yourcompany.com/admin/api/application/health";

		String sslResponse = sslTestTemplate.getForObject( sslUrl, String.class );

		logger.info( "url: {} \n\t response: {}", sslUrl, sslResponse );

		// RestTemplate restTemplate = factory.buildDefaultTemplate( "junit",
		// false, 10, 10, 5, 60, 300 );
		//
		// String url =
		// "http://testhost.yourcompany.com:8011/CsAgent/api/agent/health" ;
		// String response = restTemplate.getForObject( url , String.class ) ;
		//
		// logger.info( "url: {} \n\t response: {}", url,response );

		// RestTemplate sslErrorsIgnoreTemplate = new RestTemplate(
		// factory.buildFactoryDisabledSslChecks( "junit", 10, 10 )) ;
		// url = "https://10.127.41.116:8011/CsAgent/api/agent/health" ;
		// response = sslErrorsIgnoreTemplate.getForObject( url , String.class )
		// ;
		//
		// logger.info( "url: {} \n\t response: {}", url,response );

	}

	@Test
	public void verifyFileMatching ()
			throws Exception {
		try (Stream<Path> pathStream = Files.list( new File( "/java" ).toPath() )) {
			Optional<Path> deploymentCheck = pathStream
				.filter( path -> path.getFileName().toString().endsWith( ".bat" ) )
				.findFirst();

			// logger.info( "object: {} : value: {}", deploymentCheck,
			// deploymentCheck.get() );
		}

	}

	final com.sun.management.OperatingSystemMXBean osStats = (com.sun.management.OperatingSystemMXBean) ManagementFactory
		.getOperatingSystemMXBean();

	Double byteToGb = 1024 * 1024 * 1024D;
	DecimalFormat gbFormat = new DecimalFormat( "#.#Gb" );

	String getGb ( long num ) {
		logger.info( "num: {}", num );
		return gbFormat.format( num / byteToGb );
	}

	DecimalFormat percentFormat = new DecimalFormat( "#.#%" );

	String getPercent ( double num ) {
		logger.info( "num: {}", num );
		return percentFormat.format( num );
	}

	@Test
	public void verifyGB ()
			throws Exception {

		logger.info( "memory: {} , sys: {}, process: {}",
			getGb( osStats.getTotalPhysicalMemorySize() ),
			getPercent( osStats.getSystemCpuLoad() ),
			getPercent( osStats.getProcessCpuLoad() ) );

	}

	@Test
	public void verifyDoubleParse ()
			throws Exception {

		ObjectNode test = jacksonMapper.createObjectNode();
		ObjectNode nested = test.putObject( "test" );

		nested.put( "$numberLong", "17" );

		logger.info( "object: {} : value: {}", test.toString(), test.asDouble() );

		double d = 5500 / 1000d;

		logger.info( "d: {} ", d );

	}

	@Test
	public void verifyStringRegex ()
			throws Exception {

		String params = "serviceName=[ServletSample_8091], javaOpts=[-DcsapJava8 -Xms128M -Xmx128M ], runtime=[tomcat8.x], scmUserid=[someDeveloper],"
				+ " scmPass=[ws0zIy4CVuhWgkjKwebi0/y69yQMiJTr], scmBranch=[trunk], hotDeploy=[null], targetScpHosts=[], scmCommand=[-X -Dmaven.test.skip=true clean package, "
				+ "-X -Dmaven.test.skip=true clean package], mavenDeployArtifact=[null]";

		logger.info( "params: \n{}\n\n{}", params, params.replaceAll( "\\bscmPass[^\\s]*", "scmPass[*MASKED*]," ) );

		logger.info( "alphanumeric only: \n{}\n\n{}", params, params.replaceAll( "[^A-Za-z0-9]", "_" ) );

	}

	@Test
	public void lifeReplace ()
			throws Exception {

		String params = "JAVA_OPTS=$life -Djava.rmi.server.hostname=localhost -Dcom.sun.management.jmxremote.port=8046 -Dcom.sun.management.jmxremote.rmi.port=8046 -Dcom.sun.management.jmxremote.local.only=false -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false  -DcsapProcessId=ServletSample_8041  -Djava.security.egd=file:/dev/./urandom -DcsapDockerJava";

		logger.info( "params: \n{}\n\n{}\n{}", params,
			params.replaceAll( Matcher.quoteReplacement( "$life" ), Matcher.quoteReplacement( "$peter" ) ) );

		// String result = params.trim().replaceAll( "\\" +
		// CSAP.SERVICE_PARAMETERS, "-D test.life=$dev".replaceAll( "$", "\\$" )
		// );
		//
		// logger.info( "CSAP.SERVICE_PARAMETERS: {}", result);

	}

	@Test
	public void verifyMatches () {

		String prod = "prod";

		logger.info( "Matched: {}", prod.matches( "prod" ) );
		logger.info( "Matched: {}", prod.matches( "p(?!rod)" ) );

		String dev = "dev";

		logger.info( "Matched: {}", dev.matches( "p(?!rod)" ) );
		logger.info( "Matched: {}", dev.matches( "p(?!rod)" ) );
		logger.info( "^d.* Matched: {}", dev.matches( "^sd.*" ) );

		String buildResponse = "peter \nDiffEntry[MODIFY Services/testFileForGitJunits.txt]";
		
		Pattern searchWithNewLinesPattern = Pattern.compile(
			".*" + Pattern.quote( "DiffEntry[MODIFY" ) + ".*testFileForGitJunits.txt.*"
			, Pattern.DOTALL);
		
		logger.info( "buildResponse: {} contains",
			searchWithNewLinesPattern.matcher( buildResponse ).find() ) ;
//			buildResponse.matches(
//				".*" + Pattern.quote( "DiffEntry[MODIFY" ) + ".*testFileForGitJunits.txt.*") );
	}

	@Test
	public void verifyFileRegex ()
			throws Exception {

		File f = new File( "/aTemp/alertify.js" );

		logger.info( "Absolute: {}, cannonical: {}", f.getAbsolutePath(), f.getCanonicalPath() );

		Pattern p = Pattern.compile( ".*aTemp.*ale.*" );
		Matcher m = p.matcher( Pattern.quote( f.getCanonicalPath() ) );

		logger.info( "Pattern quoted: {} Matches: {}", p.toString(), m.matches() );

		p = Pattern.compile( ".*aTemp.*ale.*" );
		m = p.matcher( f.getCanonicalPath() );

		logger.info( "Pattern raw: {} Matches: {}", p.toString(), m.matches() );

		p = Pattern.compile( ".*\\\\aTemp.*ale.*" );
		m = p.matcher( Pattern.quote( f.getCanonicalPath() ) );
		logger.info( "Pattern: {} Matches: {}", p.toString(), m.matches() );

		p = Pattern.compile( ".*\\\\aTemp.*ale.*" );
		m = p.matcher( f.getCanonicalPath() );
		logger.info( "Pattern Raw: {} Matches: {}", p.toString(), m.matches() );

		String ctl = "├─puppet.service";

		p = Pattern.compile( "[^\\-].*\\.service" );
		m = p.matcher( ctl );
		logger.info( "Pattern Raw: {} Matches: {}, then {}", p.toString(), m.matches(), m.group() );

	}

	@Test
	public void verifyStringList () {
		List<String> list = Arrays.asList( "Bob", "Steve", "Jim", "Arbby" );

		list.stream().forEach( String::toUpperCase );

		logger.info( "list: {}", list );

		for ( int i = 0; i < list.size(); i++ ) {
			list.set( i, list.get( i ).toUpperCase() );
		}

		logger.info( "list: {}", list );

	}
	
	@Test
	public void buildDateList() {
		
		LocalDate today = LocalDate.now() ;
		logger.info( "now: {} ", today.format(DateTimeFormatter.ofPattern( "yyyy-MM-dd" )  ) );
		
		List<String> past10days= LongStream
			.rangeClosed(1, 10)
			.mapToObj( day ->  today.minusDays( day ) )
			.map( offsetDate ->  offsetDate.format(DateTimeFormatter.ofPattern( "yyyy-MM-dd" ) ) )
			.collect( Collectors.toList() );
		
		List<String> past10daysReverse = LongStream.iterate(10, e -> e - 1)
	     	.limit(10)
	     	.mapToObj( day ->  today.minusDays( day ) )
			.map( offsetDate ->  offsetDate.format(DateTimeFormatter.ofPattern( "yyyy-MM-dd" ) ) )
			.collect( Collectors.toList() );

		logger.info( "past10days: {} \n reverse: {}", past10days, past10daysReverse );
		
	}
	
}

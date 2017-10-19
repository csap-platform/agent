package org.csap.test;

import java.io.File;
import java.net.URL;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitializeLogging {

	final static private Logger logger = LoggerFactory.getLogger( InitializeLogging.class );

	public static String TC_HEAD = "\n\n ========================= UNIT TEST =========================== \n\n";

	public static String SETUP_HEAD = "\n\n ========================= SETUP     =========================== \n\n";
	
	private static boolean isJvmInfoPrinted = false;
	
	public static File DEFINITION_DEFAULT = new File(
		InitializeLogging.class.getResource( "/org/csap/test/data/DEFAULT_APPLICATION.json" ).getPath() );
	

	public static File DEFINITION_WITH_PUBLISH = new File(
		InitializeLogging.class.getResource( "/org/csap/test/data/application-publish-enabled.json" ).getPath() );
	
	public static File getTestDefinition(String path) {
		return new File(
			InitializeLogging.class.getResource( path ).getPath() );
	}
	

	public static void printTestHeader ( String description ) {

		if ( !isJvmInfoPrinted ) {
			isJvmInfoPrinted = true;
			System.out.println( "Working Directory = " +
					System.getProperty( "user.dir" ) );
			StringBuffer sbuf = new StringBuffer();
			// Dump log4j first - if it does not work, nothing will
			String resource = "log4j2-junit.yml";
			URL configFile = InitializeLogging.class.getResource( resource );

			if ( configFile == null ) {
				System.out.println( "ERROR: Failed to find log configuration file in classpath: " + resource );

				System.exit( 99 );
			}
			try {
				sbuf.append( "\n\n ** " + resource + " found in: " + configFile.toURI().getPath() );
				
				LoggerContext context = (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
				// this will force a reconfiguration
				context.setConfigLocation(configFile.toURI());
			} catch (Exception e) {
				
				System.out.println( "ERROR: Failed to resolve path: " + resource );
				System.exit( 99 );
			}

			// Now dump nicely formatted classpath.
			sbuf.append( "\n\n ====== JVM Classpath is: \n"
					+ WordUtils.wrap( System.getProperty( "java.class.path" ).replaceAll( ";", " " ), 140 ) );
			System.out.println( sbuf );
			
			String testDir = System.getProperty( "csapTest" ) ;
			if ( testDir == null) {
				testDir = System.getProperty( "user.home" ) + "/csap" ;
			}
			logger.info( "External configuration: " + testDir );
			File externalConfiguration = new File(testDir) ;
				
			logger.info( "Adding System property: spring.config.location, {}" ,  externalConfiguration.toPath().toUri()  );
			System.setProperty( "spring.config.location", externalConfiguration.toPath().toUri().toString() ) ;
			
			File testFolder = new File( "target/junit" );
			logger.info( "Deleting: {}", testFolder.getAbsolutePath() );
			FileUtils.deleteQuietly( testFolder );

		}

		// https://logging.apache.org/log4j/2.0/faq.html
		logger.info( "\n\n *********************  {}  ***********************\n\n", description );
	}
	
	
	@Test
	public void testLog() {
		
		printTestHeader( logger.getName() ) ;
		
		logger.info( "Got here" );
	}

}

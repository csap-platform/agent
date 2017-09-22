p={
	"packageDefinition" : {
		"name" : "changeToYourPackageName" ,
		"architect" : "updateArchUserid",
		"description" : "Update with a meaningful description, include links to documents.",
		"emailNotifications" : "support@notConfigured.com"
	}, 
		
	"clusterDefinitions": {
		"dev": {
			"changeToYourCluster": {
				"jvmPorts": {
					"spring-PetClinic": [
						"814x"
					]
				},
				"osProcessesList": [
					"jdk", "sspJdkCerts", "RedHatLinux"
				],
				"version": {
					"1": {
						"hosts": [
							"changeToYourHost-extension"
						],
						"vdcImage": "factory-64bit-1.0"
					}
				}
			}
		}
	},
	
	"jvms": {

		"spring-PetClinic": {
			"description": "Spring reference App - PetClinic",
			"docUrl": "https://github.com/spring-projects/spring-petclinic",
			"java": "-Xms128M -Xmx128M -XX:MaxPermSize=128m",
			"autoStart": "99",
			"server": "tomcat7.x",
			"source": {
				"scm": "git",
				"path": "https://github.com/spring-projects/spring-petclinic.git",
				"branch": "HEAD"
			},
			"maven": {
				"dependency": "com.your.group:spring-PetClinic:1.0.0:war"
			},
			"version": {
				"1": {
					"dev": {},
					"stage": {
						"java": "-Xms192M -Xmx192M -XX:MaxPermSize=128m"
					},
					"lt": {},
					"prod": {}
				}
			}
		}

	},

	"osProcesses": {
		"jdk": {
			"description": "Oracle JDK",
			"docUrl": "http://www.oracle.com/technetwork/java/javase/downloads/jdk7-downloads-1880260.html",
			"autoStart": "02",
			"url": "http://www.oracle.com/technetwork/java/javase/downloads/jdk7-downloads-1880260.html",
			"port": "7999",
			"scmVersion": "none",
			"user": "ssadmin",
			"disk": "/home/ssadmin",
			"server": "wrapper",
			"metaData": "isScript",
			"logDirectory": "/opt/java",
			"propDirectory": "/opt/java",
			"maven": {
				"dependency": "com.yourcompany.csap:JavaDevKitPackage:7u25.3:zip"
			},
			"source": {
				"scm": "svn",
				"path": "http://yourSvnOrGit/svn/csap/trunk/JavaDevKitPackage",
				"branch": "HEAD"
			},
			"version": {
				"1": {}
			},
			"jmxPort": "-1"
		},
		"sspJdkCerts": {
			"description": " root cert for jdk. Click help to find more information.",
			"docUrl": "https://github.com/csap-platform/csap-core/wiki",
			"autoStart": "03",
			"url": "https://github.com/csap-platform/csap-core/wiki",
			"port": "7998",
			"scmVersion": "none",
			"user": "ssadmin",
			"disk": "/home/ssadmin",
			"server": "wrapper",
			"metaData": "isScript",
			"logDirectory": "/opt/java",
			"propDirectory": "/opt/java",
			"maven": {
				"dependency": "com.yourcompany.test1:SSP_JdkCerts:1.0.3:zip"
			},
			"source": {
				"scm": "svn",
				"path": "http://yourSvnOrGit/svn/smartservices/coreservices/trunk/SSP_JdkCerts",
				"branch": "HEAD"
			},
			"version": {
				"1": {}
			},
			"jmxPort": "-1"
		},
		"RedHatLinux": {
			"description": "RedHat Linux",
			"docUrl": "http://www.redhat.com/products/enterprise-linux/server/",
			"autoStart": "01",
			"url": "http://$host.yourcompany.com:8011/CsAgent/ui/getStats",
			"port": "7997",
			"scmVersion": "none",
			"user": "ssadmin",
			"disk": "/home/ssadmin",
			"server": "wrapper",
			"metaData": "isScript",
			"logDirectory": "/opt/java",
			"propDirectory": "/opt/java",
			"maven": {
				"dependency": "com.yourcompany.csap:RedHatLinux:1.0.0:zip"
			},
			"source": {
				"scm": "svn",
				"path": "http://yourSvnOrGit/svn/csap/trunk/RedHatLinux",
				"branch": "HEAD"
			},
			"version": {
				"1": {}
			},
			"jmxPort": "-1"
		}
	}
}
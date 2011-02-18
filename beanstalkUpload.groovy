import org.apache.commons.logging.LogFactory

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.PropertiesCredentials

import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.Bucket
import com.amazonaws.services.s3.model.StorageClass
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectResult
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.ProgressEvent
import com.amazonaws.services.s3.model.ProgressListener
import com.amazonaws.services.s3.transfer.Upload
import com.amazonaws.services.s3.transfer.TransferManager

import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.elasticbeanstalk.model.S3Location
import com.amazonaws.services.elasticbeanstalk.model.CreateApplicationVersionRequest
import com.amazonaws.services.elasticbeanstalk.model.ApplicationVersionDescription

void upload(appName, appVersion, fileToUpload) {
	
	def fileSize = fileToUpload.size()
	def config = new Properties()
	
	def credentialsFile = new File("credentials.properties")
	if (!credentialsFile.exists()) {
		log(appName, "File '${credentialsFile}' does not exist, you have to have one in the same")
		log(appName, "directory from where you're executing this script. It should have two keys")
		log(appName, "with names 'accessKey' and 'secretKey' with respective content.")
		System.exit(1)
	} else {
		log(appName, "Loading 'credentials.propeties' file")
	}
	
	def credentials = new PropertiesCredentials(credentialsFile)
	log(appName, "Loaded AWS credentials")
	
	def s3client = new AmazonS3Client(credentials) 
	def bucketName = "${appName}-${UUID.randomUUID()}"
    
	log(appName, "Creating s3 bucket '${bucketName}' to hold application file")
	def bucket = s3client.createBucket(bucketName)
	
	def objectKey = "${new Date().format('yyyyMMddHHmmss')}-${fileToUpload.name}"
	
	def metadata = new ObjectMetadata()
	metadata.setContentLength(fileSize) 
	metadata.addUserMetadata("app-name", appName)
	metadata.addUserMetadata("app-version", appVersion)	
	
	def transferManager = new TransferManager(credentials)
	fileToUpload.withInputStream { fileToUploadInputStream -> 
		
		def decimalFormat = new java.text.DecimalFormat("##.#")
		def warUpload = transferManager.upload(bucketName, objectKey, fileToUploadInputStream, metadata)
		
		while (!warUpload.done) {
			
			def percent = (warUpload.progress.bytesTransfered * 100) / warUpload.progress.totalBytesToTransfer
			def percentToShow = decimalFormat.format(percent)
			log(true, appName, "${warUpload.description}: [${warUpload.state}] - ${warUpload.progress.bytesTransfered} of ${warUpload.progress.totalBytesToTransfer} (${percentToShow}%) ")
			Thread.sleep(1000)
		}	
	}
			
	def beanstalk = new AWSElasticBeanstalkClient(credentials)
	def applicationVersionRequest = new CreateApplicationVersionRequest(appName, appVersion)
	applicationVersionRequest.setAutoCreateApplication(true)
	applicationVersionRequest.setSourceBundle(new S3Location(bucketName, objectKey))
	
	println ""
	log(appName, "Creating application version...")
	def applicationVersionResult = beanstalk.createApplicationVersion(applicationVersionRequest)
	def applicationVersionDescription = applicationVersionResult.applicationVersion
    
	log(appName, "Done!")
	log(appName, "App: ${applicationVersionDescription.applicationName}")
	log(appName, "Version: ${applicationVersionDescription.versionLabel}")
	log(appName, "S3 Bucket: ${bucketName}")
	log(appName, "War file:  ${objectKey}")
	log(appName, "Version created at: ${applicationVersionDescription.dateCreated.format('yyyy/MM/dd HH:mm:ss')}")
	
	transferManager.shutdownNow()
}

void log(message) {
	println "[${new Date().format('yyyy/MM/dd HH:mm:ss')}] ${message}"
}

void log(returnLine = false, appName, message) {
	
	def msg = "[${new Date().format('yyyy/MM/dd HH:mm:ss')}] [${appName}] ${message}"
	if (returnLine)
		print "\r${msg}"
	else 
		println msg
}

//disable output messages for aws sdk
def logAttribute = "org.apache.commons.logging.Log"
def logValue = "org.apache.commons.logging.impl.NoOpLog"
LogFactory.getFactory().setAttribute(logAttribute, logValue)

//script
if (args.size() != 3) {
	log("Usage: groovy beanstalkUpload.groovy <path_to_war> <application_name> <application_version_label>")
	System.exit(1)
}

def fileToUpload = new File(args[0])
def appName = args[1]
def appVersion = args[2]

if (!fileToUpload.exists()) {
	log("File '${fileToUpload}' does not exist")
	System.exit(1)
}

upload(appName, appVersion, fileToUpload)
# DevStack

DevStack is intended for the DevOps engineer who finds her/him self constantly jumping between different DevOps enterprise tools to deliver new solutions.

DevStack gives you simple to reuse groovy classes for spinning up new test environments in a local or remote docker engine and automates the basic setup of the individual tool.

## What does it really do?

DevStack creates local or remote docker containers, sets up docker level networking, spins up the container with sensible settings and configures the basic settings needed inside the tool to get it up and running.

Example: [JsmAndBitbucketH2Deployment.groovy](src%2Fmain%2Fgroovy%2Fcom%2Feficode%2Fdevstack%2Fdeployment%2Fimpl%2FJsmAndBitbucketH2Deployment.groovy)

This class will help you spin up JIRA and Bitbucket containers in the same docker network, configure sensible JAVA environment variables, setup DBs for both applications, install JIRA apps and create an Application Link between them.

## Pros/Cons

### Pro
* Facilitate testing of complex setups
  * Got a complex flow involving Jira, Bitbucket, Jenkins, Harbor and now you are afraid of upgrading?
    * Integrate DevStack in your Spock test, configure it to spin up a mirror of your environment and the flow. Tweak the version used and run your test.
* Takes over after Docker has done its thing.
  * Docker is great for starting an application like JIRA but then it´s up to you (or DevStack) to setup, DB, Apps, Admin accounts, licenses.
* Move away from Snowflake test environments, test your changes in fresh environments every time.
* DevStack can use local or remote Docker engines, making running the tests locally on your machine easy while still offloading the heavy lifting to a cloud Docker Engine. 

### Cons
* DevStack is not intended for production use ever, it should only be used for short-lived test environments with nothing to loose.
* Not intended for existing environments, presumes DevStack was used to setup up the environment. 

# Main building blocks and concepts

## Containers vs Deployments

The two main building blocks of DevStack are Containers and Deployments. A container is essentially just a representation of a normal Docker container, ie a pretty much a Docker Image that has been started with some added basic network and env-vars added. This is where Deployment then takes over and installs licenses, sets up database, admin accounts etc as many steps as possible to give you an environment ready for use.

## SubDeployments
SubDeployments are simply a collection of deployments used by a more complex deployment. [JenkinsAndHarborDeployment.groovy](src%2Fmain%2Fgroovy%2Fcom%2Feficode%2Fdevstack%2Fdeployment%2Fimpl%2FJenkinsAndHarborDeployment.groovy) for example uses the Jenkins and Harbor deployments as SubDeployments. 

## Utils

These are classes mainly intended to be used by Container/Deployment-classes when massaging of the containers are needed for example. 
Currently, [ImageBuilder.groovy](src%2Fmain%2Fgroovy%2Fcom%2Feficode%2Fdevstack%2Futil%2FImageBuilder.groovy) dynamically builds Atlassian images on the fly.
[TimeMachine.groovy](src%2Fmain%2Fgroovy%2Fcom%2Feficode%2Fdevstack%2Futil%2FTimeMachine.groovy) changes the apparent time for all
containers sharing a Docker Engine, intended for testing date changes.

# Setup Docker Engine in AWS

DevStack defaults to trying to connect to a local Docker engine, but a terraform project is supplied for setting up a remote EC2 with docker engine. The docker engine will be configured to only accept HTTPS from your IP.  

### Requirements:
 * An AWS account with no vital data
   * With corresponding Access and Secret key: [Check guide](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html#Using_CreateAccessKey)
 * CA, Server and Client certs 
   * This script can be used for generating the Certs: [createCerts](https://gist.github.com/farthinder/9df3019e2e01cdd167dd02abf7d5f903)
 * SSH Keys

1. Setup Docker Engine certs in ~/.docker/
    * ca.pem: A ca cert that signed the other certs
    * server-cert.pem & server-key.pem: Public and private keys to be used by docker server. By convention DevStack uses: docker.domain.se, but anything is fine aslong as you can put it in your hosts file.
    * ~/.docker/ is the default place but can be changed in [variables.tf](Environments%2FAWS%2Fvariables.tf)

2. Setup Docker Client certs in ~/.docker/
   * ca.pem: A ca cert that signed the other certs (same as in step 1)
   * cert.pem & key.pem: Public and private keys to be used by docker client
   * The ~/.docker/ location is arbitrary and ultimately determined by you when you instantiate DevStack classes.  

3. Set up an AWS account with high (admin) privileges in an ISOLATED test account *WITH NO VITAL DATA*
    * Get Access Key and Secret Key for the account
    * Update *aws_credentials* in Environments/AWS/variables.tf

4. Make sure you have a valid public SSH key in ~/.ssh/id_rsa.pub
    * Or update *ssh-public-key-local-path* in Environments/AWS/variables.tf

5. Update *trusted-external-ips* in Environments/AWS/variables.tf
    * This should normally be set to your external ip, check for example: https://whatismyipaddress.com

6. Go to Environments/Terraform and run terraform apply
    * Note the "Hosts-record" output, this needs to be added to your /etc/hosts
    * This host record should be considered a suggestion as it depends on things such as the base URLs you select for your deployments
7. Presuming everything was successful you should now be able to SSH to the new EC2 server and run commands such as "docker ps"
   * If you have setup you hosts file according to the previous step, you should be able to ssh to docker.domain.se
8. Update your code, the Deployment and Container classes should all accept a docker host and cert path parameter
```groovy
String dockerRemoteHost = "https://docker.domain.se:2376"
String dockerCertPath = "~/.docker/"


//JsmH2Deployment jsmDep = new JsmH2Deployment(jiraBaseUrl) //If using a local docker engine
JsmH2Deployment jsmDep = new JsmH2Deployment(jiraBaseUrl, dockerRemoteHost, dockerCertPath) //If using a remote docker Engine

```


## Using DevStack in your project

A note on versions, DevStack is available in two version a "normal" one and a standalone edition with shaded dependencies.

The standalone edition should alleviate dependency issues but is also larger

Examples:
 * com.eficode:devstack:2.3.9-SNAPSHOT
   * DevStack version 2.3.9-SNAPSHOT
 * com.eficode:devstack-standalone:2.3.9-SNAPSHOT
   * DevStack version 2.3.9-SNAPSHOT, standalone edition.

To find the latest version, check the "packages" branch: https://github.com/eficode/devStack/tree/packages/repository/com/eficode/devstack


### Maven install

```bash

mvn dependency:get -Dartifact=com.eficode:devstack:2.3.9-SNAPSHOT -DremoteRepositories=https://github.com/eficode/DevStack/raw/packages/repository/


mvn dependency:get -Dartifact=com.eficode:devstack-standalone:2.3.9-SNAPSHOT -DremoteRepositories=https://github.com/eficode/DevStack/raw/packages/repository/

```


### POM dependency 

```xml
 ..
 .... 
<dependencies>
   <dependency>
         <groupId>com.eficode</groupId>
         <artifactId>devstack</artifactId>
         <version>2.3.9-SNAPSHOT</version>
         <!--Optional standalone version-->
        <!--artifactId>devstack-standalone</artifactId-->
     </dependency>
</dependencies>

..
....
<repositories>
   <repository>
      <id>eficode-github-DevStack</id>
      <url>https://github.com/eficode/DevStack/raw/packages/repository/</url>
   </repository>
</repositories>
..
....
```

### Grape Dependency
```groovy
@GrabResolver(name = "devstack-github", root = "https://github.com/eficode/devstack/raw/packages/repository/")
@Grab(group = "com.eficode" , module = "devstack-standalone", version = "2.3.9-SNAPSHOT")
```


# Breaking Changes

* 2.3.9
  * From now on two artifacts will be generated, devstack and devstack-standalone and the classifier standalone is deprecated

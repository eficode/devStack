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


## Using DevStack in your project

A note on versions, DevStack is cross-compiled for both Groovy 3 and 2.5, these editions are also available in a standalone edition with shaded dependencies.

The standalone edition should alleviate dependency issues but is also larger

Examples:
 * com.eficode:devstack:2.0.0-SNAPSHOT-groovy-2.5
   * DevStack version 2.0.0, compiled for groovy 2.5
 * devstack:2.0.0-SNAPSHOT-groovy-3.0:jar:standalone
   * DevStack version 2.0.0, compiled for groovy 3, standalone edition.

To find the latest version, check the "packages" branch: https://github.com/eficode/devStack/tree/packages/repository/com/eficode/devstack


### Maven install

```bash

mvn dependency:get -Dartifact=com.eficode:devstack:2.0.0-SNAPSHOT-groovy-2.5 -DremoteRepositories=https://github.com/eficode/DevStack/raw/packages/repository/


mvn dependency:get -Dartifact=com.eficode:devstack:2.0.0-SNAPSHOT-groovy-2.5:jar:standalone -DremoteRepositories=https://github.com/eficode/DevStack/raw/packages/repository/

```


### POM dependency 

```xml
 ..
 .... 
<dependencies>
   <dependency>
         <groupId>com.eficode</groupId>
         <artifactId>devstack</artifactId>
         <version>2.0.0-SNAPSHOT-groovy-3.0</version>
         <!--Optional standalone classifier-->
         <!--classifier>standalone</classifier-->
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


## Setup of test Environment

//TODO add info about license files

1. Setup certs in resources/dockerCert
    * ca.pem: A ca cert that signed the other certs
    * cert.pem & key.pem: Public and private keys to be used by docker client
    * server-cert.pem & server-key.pem: Public and private keys to be used by docker server. Should be setup for domain name: docker.domain.se

2. Set up an AWS account with high (admin) privileges in an ISOLATED test account *WITH NO VITAL SETUPS*
    * Get Access Key and Secret Key for the account
    * Update *aws_credentials* in Environments/Terraform/variables.tf

3. Make sure you have a valid public SSH key in ~/.ssh/id_rsa.pub
    * Or update *ssh-public-key-local-path* in Environments/Terraform/variables.tf

4. Update *trusted-external-ips* in Environments/Terraform/variables.tf
    * This should normally be set to your external ip, check for example: https://whatismyipaddress.com

5. Go to Environments/Terraform and run terraform apply
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
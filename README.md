

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
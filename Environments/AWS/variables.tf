variable "tags" {
  description = "Tags to set on resources."
  type        = map(string)
  default = {
    owner : "User"
    testing : "True"
    okToRemove : "True"
    useCase : "DevStack"
  }
}

variable "aws_credentials" {

  type = map(string)

  default = {
    "access_key" = ""
    "secret_key" = ""
    "account" = ""
  }
  
}


variable "dockerServerCert" {
  type = map(string)
  default = {
    "tlscacert" = "~/.docker/ca.pem"
    "tlscert"   = "~/.docker/server-cert.pem"
    "tlskey"    = "~/.docker/server-key.pem"
  }
  
}

variable "ssh-public-key-local-path" {
  type    = string
  default = "~/.ssh/id_rsa.pub"
}


variable "trusted-external-ips" {
  description = "These IPs will have access to the exposed ports"
  type = set(string)
  default = ["1.2.3.4/32"]
  
}


variable "ec2-username" {
  type    = string
  default = "ubuntu"
}

variable "ec2-instance-type" {
  type = string
  default = "t4g.xlarge" //t4g.xlarge (ARM)
  //default = "t3.xlarge" //"t3.xlarge" (x64)
}

variable "ingress_rules_from_trusted" {
    description = "This will expose the corresponding ports to the internet, but limited to trusted-external-ips"
    type = list(object({
      port   = number
      protocol    = string
      description = string
    }))
    default     = [
        {
          port   = 22
          protocol    = "tcp"
          description = "ssh access"
        },
        {
          port   =  80
          protocol    = "tcp"
          description = "HTTP port 80"
        },
        {
          port   =  8080
          protocol    = "tcp"
          description = "HTTP port 8080"
        },
        {
          port   =  8082
          protocol    = "tcp"
          description = "HTTP port 8082"
        },
        {
          port   =  2376
          protocol    = "tcp"
          description = "Docker port"
        },
         {
          port   =  7990
          protocol    = "tcp"
          description = "Bitbucket port"
        },
        {
          port   =  7992
          protocol    = "tcp"
          description = "Bitbucket second instance port"
        }
    ]
}


variable "tags" {
  description = "Tags to set on resources."
  type        = map(string)
  default = {
    owner : "Lantz"
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
    "tlscacert" = "../../resources/dockerCert/ca.pem"
    "tlscert" = "../../resources/dockerCert/server-cert.pem"
    "tlskey" = "../../resources/dockerCert/server-key.pem"
  }
  
}

variable "ssh-public-key-local-path" {
  type    = string
  default = "~/.ssh/id_rsa.pub"
}


variable "trusted-external-ips" {
  description = "These IPs will have acces to the exposed ports"
  type = set(string)
  default = ["1.2.3.4/32"]
  
}


variable "ec2-username" {
  type    = string
  default = "ubuntu"
}

variable "ingress_rules_from_trusted" {
    description = "This will expose the coresponding ports to the internet, but limited to trusted-external-ips"
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
          port   =  2376
          protocol    = "tcp"
          description = "Docker port"
        }
    ]
}


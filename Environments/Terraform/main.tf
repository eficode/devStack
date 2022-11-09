terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "4.34.0"
    }
  }
}

provider "aws" {

  region              = "eu-central-1"
  access_key          = var.aws_credentials.access_key
  secret_key          = var.aws_credentials.secret_key
  allowed_account_ids = [var.aws_credentials.account]

  skip_get_ec2_platforms      = true
  skip_metadata_api_check     = true
  skip_region_validation      = true
  skip_credentials_validation = true

  default_tags {
    tags = var.tags
  }

}


resource "aws_key_pair" "ec2-ssh-key" {

  key_name = "${var.tags.useCase}-${var.tags.owner}-key"
  public_key = file(var.ssh-public-key-local-path)
}

/*
Get the NIC of the LB in the public net
*/
data "aws_network_interface" "lb_nic" {


  filter {
    name   = "description"
    values = ["ELB ${aws_lb.load-balancer.arn_suffix}"]
  }
  
 
  
  filter {
    name   = "subnet-id"
    values = [aws_subnet.base-stack-public-subnet.id]
  }
  
}


data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}


//Get the latest AmazoneLinux2 AMI
data "aws_ami" "latest_amazon_linux_2" {
  most_recent = true
  filter {
    name   = "name"
    values = ["*amzn2-ami-hvm*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
  owners = ["amazon"]
}

//Get the latest Ubuntu 22.04 AMI
data "aws_ami" "ubuntuAMI" {
  most_recent = true

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  owners = ["099720109477"] # Canonical
}

resource "aws_vpc" "base-stack" {

  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-vpc"
  }


}

resource "aws_internet_gateway" "base-stack" {

  vpc_id = aws_vpc.base-stack.id


  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-gw"
  }

}
resource "aws_eip" "nat_eip" {

  vpc        = true
  depends_on = [aws_internet_gateway.base-stack]
  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-eip"
  }

}


resource "aws_subnet" "base-stack-private-subnet" {


  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-private-subnet"
  }

  availability_zone       = data.aws_availability_zones.available.names[0]
  cidr_block              = "10.0.66.0/24"
  vpc_id                  = aws_vpc.base-stack.id
  map_public_ip_on_launch = false


}

resource "aws_subnet" "base-stack-public-subnet" {


  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-public-subnet"
  }

  availability_zone = data.aws_availability_zones.available.names[0]
  cidr_block        = "10.0.99.0/24"
  vpc_id            = aws_vpc.base-stack.id
  

}

resource "aws_nat_gateway" "outbound-nat" {

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-outbount-nat"
  }
  allocation_id = aws_eip.nat_eip.id
  subnet_id     = aws_subnet.base-stack-public-subnet.id


}
resource "aws_route_table" "private-route-tb" {

  vpc_id = aws_vpc.base-stack.id

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-private-routetable"
  }

}

resource "aws_route_table" "public-route-tb" {

  vpc_id = aws_vpc.base-stack.id

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-private-routetable"
  }

}

resource "aws_route" "route-to-world" {

  route_table_id         = aws_route_table.public-route-tb.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.base-stack.id

}

resource "aws_route" "route-to-nat" {

  route_table_id         = aws_route_table.private-route-tb.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_nat_gateway.outbound-nat.id

}

resource "aws_route_table_association" "route-public" {

  subnet_id      = aws_subnet.base-stack-public-subnet.id
  route_table_id = aws_route_table.public-route-tb.id


}

resource "aws_route_table_association" "route-private" {

  subnet_id      = aws_subnet.base-stack-private-subnet.id
  route_table_id = aws_route_table.private-route-tb.id


}



resource "aws_security_group_rule" "ingress_rules" {
  count = length(var.ingress_rules_from_trusted)

  type              = "ingress"
  from_port         = var.ingress_rules_from_trusted[count.index].port
  to_port           = var.ingress_rules_from_trusted[count.index].port
  protocol          = upper(var.ingress_rules_from_trusted[count.index].protocol)
  cidr_blocks       = setunion(var.trusted-external-ips, ["${data.aws_network_interface.lb_nic.private_ip}/32"])
  description       = var.ingress_rules_from_trusted[count.index].description
  security_group_id = aws_security_group.private-subnet-sg.id
}


resource "aws_security_group" "private-subnet-sg" {

  name   = "${var.tags.useCase}-${var.tags.owner}-sg"
  vpc_id = aws_vpc.base-stack.id


  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

}



resource "aws_instance" "ec2-node" {

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-ec2"
  }

  ami                    = data.aws_ami.ubuntuAMI.id
  instance_type          = "t3.xlarge"
  subnet_id              = aws_subnet.base-stack-private-subnet.id
  key_name               = aws_key_pair.ec2-ssh-key.key_name
  vpc_security_group_ids = [aws_security_group.private-subnet-sg.id]
  availability_zone      = data.aws_availability_zones.available.names[0]
  iam_instance_profile   = aws_iam_instance_profile.default_profile.name

  root_block_device {
    volume_size = 24
    tags = var.tags
  }

  user_data = templatefile("ubuntu_user_data.sh", {
    awsRegion : data.aws_region.current.name
    tlscacert : file(var.dockerServerCert.tlscacert)
    tlscert : file(var.dockerServerCert.tlscert)
    tlskey : file(var.dockerServerCert.tlskey)
    }
  )


}



resource "aws_lb" "load-balancer" {

  name               = "${var.tags.useCase}-${var.tags.owner}-lb"
  internal           = false
  load_balancer_type = "application"
  subnets            = [aws_subnet.base-stack-public-subnet.id]
  enable_deletion_protection = false

}

resource "aws_lb_listener" "lb-listener" {
  count = length(var.ingress_rules_from_trusted)
  load_balancer_arn = aws_lb.load-balancer.arn
  port = var.ingress_rules_from_trusted[count.index].port
  protocol = upper(var.ingress_rules_from_trusted[count.index].protocol)
  

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.target-group[count.index].arn
  }

}

resource "aws_lb_target_group" "target-group" {
  count = length(var.ingress_rules_from_trusted)

  name        = "${var.tags.useCase}-${var.tags.owner}-port-${var.ingress_rules_from_trusted[count.index].port}"
  port        = var.ingress_rules_from_trusted[count.index].port
  protocol    = upper(var.ingress_rules_from_trusted[count.index].protocol)
  target_type = "instance"
  vpc_id      = aws_vpc.base-stack.id


}


resource "aws_lb_target_group_attachment" "ssh-target-hosts" {

  count = length(var.ingress_rules_from_trusted)

  target_group_arn = aws_lb_target_group.target-group[count.index].arn
  target_id        = aws_instance.ec2-node.id
  port             = var.ingress_rules_from_trusted[count.index].port

}


output "SSH-TO-Node" {
  value = "ssh -v ${var.ec2-username}@${aws_lb.load-balancer.dns_name} -p 22 -o StrictHostKeyChecking=no"
}

output "Hosts-record" {
  value = "${data.aws_network_interface.lb_nic.association[0].public_ip} jira.test.com docker.domain.se bitbucket.domain.se jira.domain.se jira2.domain.se bitbucket2.domain.se jira.auga.se bitbucket.auga.se jenkins.domain.se harbor.domain.se jenkins-agent.domain.se"
  
}


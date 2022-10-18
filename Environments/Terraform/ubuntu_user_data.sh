#!/bin/bash
echo "Start of user data"
apt-get update
apt install -y unzip
echo "Installing latest AWS CLI"
rm -rf /bin/aws
curl -s "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip -q awscliv2.zip
./aws/install -i /usr/local/aws -b /bin
sudo -u ubuntu aws configure set default.region ${awsRegion}

echo "export PATH=/usr/bin/:$PATH" >> ~/.bash_profile
echo "complete -C '/usr/bin/aws_completer'" aws >> ~/.bash_profile

echo "AWS CLI Installed"


echo "Setting up docker"

mkdir -p /etc/docker/
cat <<EOT >> /etc/docker/daemon.json
{
    "hosts": ["tcp://0.0.0.0:2376" , "fd://"],
    "tlsverify": true,
    "tlscacert": "/etc/docker/ca.pem",
    "tlscert": "/etc/docker/cert.pem",
    "tlskey": "/etc/docker/key.pem"
}
EOT

echo "${tlscacert}" >> /etc/docker/ca.pem
echo "${tlscert}" >> /etc/docker/cert.pem
echo "${tlskey}" >> /etc/docker/key.pem


cat <<EOT >> /etc/docker/ca2.pem
${tlscacert}
EOT




apt upgrade -y && apt-get install -y locales htop nano inetutils-ping net-tools && localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8

apt install -y ca-certificates curl gnupg lsb-release

mkdir -p /etc/apt/keyrings && curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
echo \
"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
$(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null

apt-get update && apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

sed -i 's/-H fd:\/\///' /lib/systemd/system/docker.service
systemctl daemon-reload
systemctl restart docker

echo "Finished setting up docker"

echo "Tweaking sshd"

echo "ClientAliveInterval 60" >> /etc/ssh/sshd_config 
echo "ClientAliveCountMax 100" >> /etc/ssh/sshd_config 
systemctl restart sshd

echo "Finished tweaking sshd"



echo End of user data
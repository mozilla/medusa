cd $(cd -P -- "$(dirname -- "$1")" && pwd -P)

echo "Install dependencies"
sudo apt-get -qq update
sudo DEBIAN_FRONTEND=noninteractive apt-get -qq -y install openjdk-7-jdk 

echo "Setting up leiningen"
if [ ! -f /usr/local/bin/lein ];
then
	sudo wget -P /usr/local/bin https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein
	sudo chmod +x /usr/local/bin/lein
fi
export PATH=$PWD:$PATH

lein daemon start medusa

Medusa
======

Medusa is a tracking, alerting, and visualization service for regressions in [Telemetry](http://telemetry.mozilla.org/). It aims to provide a unified frontend for managing regression alerts.

Medusa does not perform detection of regressions by itself; it exposes an API that **detectors** - services that detect regressions - can post to. For example, [Cerberus](https://github.com/mozilla/cerberus#readme) is a detector for histogram regressions.

Some examples of [Cerberus](https://github.com/mozilla/cerberus#readme), an automatic histogram regression detector.

Medusa is written in [Clojure/Clojurescript](http://clojure.org/), and uses [Ring](https://github.com/ring-clojure/ring#readme) with [React/Om](https://github.com/omcljs/om#readme) for the web interface.

## Development and deployment

First, make sure you have [Vagrant](https://www.vagrantup.com/) and [Ansible](http://www.ansible.com/home).

To start hacking on your local machine:
```bash
vagrant up
vagrant ssh
```

To provision resources and deploy medusa on AWS, first edit `ansible/provision.yml`, replacing the vaue of `key_name` with your own key, then:
```bash
ansible-playbook ansible/provision.yml -i ansible/inventory
```

## Usage

To launch the server simply yield `lein run`. Note that you must have a recent version of leiningen installed (> 2); some distributions ship with earlier versions.

# medusa

Medusa is a client/server system written in Clojure and Clojurescript that allows to track, visualize and keep developers notified of various regressions detected in Telemetry's data. As there are different kind of regressions that can be tracked, like distributional changes of histograms and ranking fluctuations of main-thread IO, an indipendent aggregator avoids duplicating the effort of re-implementing the same logic for the different regression detectors.

## Usage

To launch the server simply yield `lein daemon start medusa`. Note that you must have a recent version of leiningen installed (> 2); some distributions ship with earlier versions.

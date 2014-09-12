# medusa

Medusa is a client/server system written in Clojure and Clojurescript that allows to track, visualize and keep developers notified of various regressions detected in Telemetry's data. As there are different kind of regressions that can be tracked, like distrubtional changes in histograms and ranking fluctuations of main-thread IO, an indipendent aggregator avoids duplicating the effort of re-implementing the same logic for the different regression detectors.

## Usage


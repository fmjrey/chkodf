# chkodf
CLI tool to check URLs in ODF documents, with special handling of wikipedia URLs.

The idea is to help maintain documents containing a list of hyperlinks,
checking they're still working, and for wikipedia links checking they're
pointing to the page in the language of the document.

Here is the list of checks done for each URL found in the document:
  - uses https instead of http
  - checks URL is still valid with a HEAD request
  - if pointing to a wikipedia page:
    - warn if pointing to a page in another language than the document's
      - if so tries to determine the wikipedia page in the right language

The tool takes one or two parameters, for input and output files respectively.

# Build and use

To build and use this tool perform the following initial steps:
- install the clojure CLI: https://clojure.org/guides/install_clojure
- clone this repository and cd into the project dir
- run the `deploy-bin` build task with this command:
  `clojure -X:build/task deploy-bin`

That last command creates `~/.local/bin/chkodf` as an executable file.

# Development

This project uses the Practicalli minimal project template, see
https://practical.li/clojure/clojure-cli/projects/templates/practicalli/

The main advantage of using that template is the tooling it brings for
development. In particular it provides the reloaded workflow and the portal
window for viewing generated events. See `dev/user.clj` and load it into a
REPL. Using emacs the necessary alias should be loaded automatically.
Otherwise use the following command line to start a REPL with these features:
`clojure -M:repl/reloaded`


Initial code taken from https://gist.github.com/a1e9755e9b2e7f638620.git

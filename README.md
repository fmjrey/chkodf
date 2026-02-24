# chkodf
CLI tool to check URLs in ODF documents, with special handling of wikipedia URLs.

The idea is to help maintain documents containing a list of hyperlinks,
checking they're still working, and for wikipedia links, checking they're
pointing to the page in the language of the document.

Here is the list of checks done for each URL found in the document:
  - uses https instead of http
  - checks URL is still valid with a HEAD request
  - if pointing to a wikipedia page:
    - warn if pointing to a page in another language than the document's
      - if so tries to determine the wikipedia page in the right language

`chkodf` takes one or two parameters, for input and output files respectively.

# Build and use

Prerequisite:

- install the clojure CLI: https://clojure.org/guides/install_clojure
- clone this repository

Then you can run the tool directly from source with the following command:

`clojure -M:run/app test1.odt`

This will run `chkodf` on the test file provided with this project.

To build and install an executable jar run the following command:

`clojure -X:build/task deploy-bin`

That last command creates `~/.local/bin/chkodf` as an executable file.
If you need to change the destination directory, use an optional options map
with these keys (shown with their defaults values):

  `{:uberjar-file target/chkodf.jar ;; the uberjar to generate
  ` :dest-dir \"~/.local/bin\" ;; where to copy the uberjar`
  ` :filename \"chkodf\" ;; the name of the executable file`
  ` :chmod-permissions \"u=rwx,g=rx,o=rx\"} ;; the permissions for executable`

For example this command create the executable in the project `target` dir:

`clojure -X:build/task deploy-bin '{:dest-dir "target"}'``

# Development

This project was created using the
[Practicalli minimal project template](https://practical.li/clojure/clojure-cli/projects/templates/practicalli/)
which, combined with the
[clojure CLI config](https://github.com/practicalli/clojure-cli-config)
of the same author, provide useful tooling for development.
Of particular interest are the
[REPL reloaded workflow](https://practical.li/clojure/clojure-cli/repl-reloaded/)
and the [portal UI](https://github.com/djblue/portal)
for viewing generated [mulog](https://github.com/BrunoBonacci/mulog) events.

Both require the `repl/reloaded` alias defined in 
[`deps.edn` from the clojure CLI config](https://github.com/practicalli/clojure-cli-config/blob/main/deps.edn). With that alias you can then launch the reloaded REPL.
With emacs the `reloaded` alias is loaded automatically as per `.dir-locals.el`.
Otherwise use the following command line to start a REPL with these features:

`clojure -M:repl/reloaded`

Finally loading `dev/user.clj` in the reloaded REPL should automatically display
the Portal UI which will show the `mulog` events generated in the app.

# Credits

- [Practicalli](https://practical.li/) for the great tooling and templates that
  helped create this project
- Initial code taken from https://gist.github.com/a1e9755e9b2e7f638620.git

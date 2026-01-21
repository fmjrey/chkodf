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

Initial code taken from https://gist.github.com/a1e9755e9b2e7f638620.git

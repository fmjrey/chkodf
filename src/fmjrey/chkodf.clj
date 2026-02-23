;; ---------------------------------------------------------
;; ChkODF
;;
;; CLI tool to check URLs in ODF documents, with special
;; handling of wikipedia URLs.
;; The idea is to help maintain documents containing a list of
;; hyperlinks, checking they're still working, and for wikipedia
;; links checking they're pointing to page in the language of
;; the document.
;; ---------------------------------------------------------

(ns fmjrey.chkodf
  (:import [java.util Locale]
           [org.odftoolkit.odfdom.dom.element.text TextAElement]
           [org.odftoolkit.odfdom.doc OdfDocument$UnicodeGroup OdfDocument]
           [java.util.concurrent Future])
  (:require [app :refer [app-info app-name app-home app-cli
                         major-version minor-version version-string]]
            [clj-http.client :as http]
            [clj-http.conn-mgr :as httpcm]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [missionary.core :as m]
            [com.brunobonacci.mulog :as mu])
  (:gen-class))

;; ---------------------------------------------------------
;; URL Result handling

(defn ->result
  "Given some http response, or a url result keyword and additional elements,
  return a result map with the following keys:
    - `:url` the given url the result is for
    - `:result` `:success`  `true` if the URL has been processed successfully
                `:redirect` if the url redirects to another location
                `:replace`  if the url should be changed to another location
                `:failure`  if the url was not proved to be correct (e.g. 404)
                `:ignore`   if the url cannot be processed (e.g. not http(s))
    - `:location` the alternate location for the URL
    - `:status` http code as an `int`
    - `:reason-phrase` explanation for the status code
    - `:previous` a vector of previous results that led to this one
    - `:msgs` a vector of string messages explaining the result"
  ([url result-keyword msg]
   {:url url :result result-keyword :msgs [msg]})
  ([url result-keyword k v]
   {:url url :result result-keyword k v})
  ([url result-keyword k v & kvs]
   (let [result (->result url result-keyword k v)]
     (if kvs
       (apply assoc result kvs)
       result)))
  ([response]
   (let [{:keys [status reason-phrase trace-redirects]} response
         redirects? (seq trace-redirects)
         result (cond
                  (= 200 status) :success
                  (and redirects? (< 199 status 400)) :redirect
                  :else :failure)
         msgs (cond-> [(str status " " reason-phrase)]
                redirects? (conj (str "Redirects: " trace-redirects)))]
     (cond-> (->result (-> response :request :url) result
                       :status status
                       :msgs msgs
                       :reason-phrase reason-phrase)
       redirects? (assoc :location (last trace-redirects))))))

(defn add-msg
  "Add a message to the given result."
  [result msg]
  (update result :msgs (fnil conj []) msg))

(defn add-previous
  "Add a previous result to the given result for reference."
  [result previous]
  (update result :previous (fnil conj []) previous))

(defn add-warning
  "Add a warning to the given result."
  [result warning]
  (update result :warnings (fnil conj []) warning))

;; ---------------------------------------------------------
;; Result printing and summarizing

(def bold  "\u001b[1m")
(def black  "\u001b[30m")
(def yellow "\u001b[33m")
(def on-red  "\u001b[41m")
(def on-yellow "\u001b[43m")
(def on-blue  "\u001b[44m")
(def reset  "\u001b[0m")

(defn str-result
  [result-keyword]
  (let [rs (-> result-keyword name str/upper-case)]
    (case result-keyword
      :failure  (str bold black  on-red    rs reset)
      :replace  (str bold black  on-yellow rs reset)
      :redirect (str bold yellow on-blue   rs reset)
      rs)))

(defn print-url-result
  "Prints the result for a url"
  [url {:keys [result reason-phrase location msgs]}]
  (println "URL" url)
  (case result
    (:replace :redirect)  (println " ->" location)
    nil)
  (println "  >" (str-result result))
  (when reason-phrase (println "  " reason-phrase))
  (doseq [msg msgs]   (println "   -" msg)))

(defn check-results
  "Some sanity check on the results to verify good logic.
  Return the given state as-is.
  TODO: this should become the basis for test cases."
  [{:keys [dfv-created dfv-assigned unassigned-urls] :as state}]
  (when-not (= dfv-created dfv-assigned)
    (println "ERROR: not all results were assigned")
    (println "  dfv created : " dfv-created)
    (println "  dfv assigned: " dfv-assigned)
    (println "  unassigned    :" unassigned-urls))
  state)

(defn print-summary
  "Print a summary of the result and return the state as-is."
  [{:keys [input-name language input-urls urls
           urls-by-result] :as state}]
  (println "Summary for" input-name)
  (println "Language" language)
  (println "Input URLs    :" (count input-urls))
  (println "Processed URLs:" (count urls))
  (doseq [[result urls] urls-by-result]
    (println (name result) ": " (count urls)))
  state)

;; ---------------------------------------------------------
;; Application state

(defn init-state
  ([] (init-state {}))
  ([state]
   (merge {:input-urls [] ;; urls given for processing
           :urls [] ;; urls actually processed (more than input)
           :dfv-by-url {} ;; {url dfv, url dfv,...}
           :results-by-url {} ;; {url result, url result,...}
           :unassigned-urls #{}
           :dfv-created 0
           :dfv-assigned 0
           :urls-by-result {} ;; {:success [urls...] :failure []...}
           :connection-manager
           (httpcm/make-reusable-async-conn-manager
             {:timeout 10 ;; Time in seconds that connections are left open
              :threads 2  ;; Threads managing the connection pool
              :default-per-route 3 ;; Max nb of simultaneous conn per host
              :insecure? true      ;; http allowed
              :io-config {}        ;; not changing default values for now
              ;;
              })}
          state)))

(defn tear-down
  [state]
  (mu/log ::tear-down)
  (when-let [cm (:connection-manager state)]
    (httpcm/shutdown-manager cm))
  (dissoc state :connection-manager))

(defn url-dfv
  "Return the missionary dataflow variable for the given URL."
  [state url]
  (get-in state [:dfv-by-url url]))

(defn url-in?
  "Return true if url has been added to the state"
  [state url]
  (url-dfv state url))

(defn url-result
  "Return the result value for a given url or nil if not yet processed."
  [state url]
  (get-in state [:results-by-url url]))

(defmacro await-url-result
  "Return the result value for a given url, blocking thread if not yet ready."
  [state url]
  `(m/? (url-dfv ~state ~url))
  ;;`(do
  ;;   (mu/log ::await-url-result :state ~state :url ~url)
  ;;   (m/? (url-dfv ~state ~url)))
  ;;
)

(defn assign
  "Assign a result for a given URL in the state.
  Returns the updated state."
  [state url result]
  (let [result (assoc result :url url)]
    (if (url-result state url)
      state
      (do
        (mu/log ::assign :url url :result result)
        (print-url-result url result)
        ((url-dfv state url) result)
        (-> state
            (update :dfv-assigned inc)
            (update :unassigned-urls disj url)
            (assoc-in [:results-by-url url] result)
            (update-in [:urls-by-result (:result result)] conj url))))))

(defn add-url
  "Add a URL to the state, optionally with its processing result, and
  return an updated state, or the same one if url was already added."
  ([state url]
   (let [in? (url-in? state url)]
     (if in?
       state
       (do (mu/log ::add-url :url url)
           (-> state
               (update :urls conj url)
               (update :dfv-by-url assoc url (m/dfv))
               (update :unassigned-urls conj url)
               (update :dfv-created inc))))))
  ([state url result]
   (-> state
       (add-url url)
       (assign url result))))

(defn add-input-url
  "Add an input URL to the state and return an updated state. Duplicates
  can be added as no attempt is made to check previous input."
  [state url]
  (-> state
      (update :input-urls conj url)))

;; ---------------------------------------------------------
;; User Agent header needed by Wikipedia otherwise it blocks

(def user-agent (str app-name "/" major-version "." minor-version
                     " (" app-home ")"))
(def headers {:headers {"User-Agent" user-agent}})

;; ---------------------------------------------------------
;; HTTP tasks using clj-http with missionary

;; Counter incremented for each request. Useful to track creation
;; order and filter logs for a specific request.
(defonce request-counter (atom 0))

(defn http-request
  "Build a `clj-http` async request task with the given request map.
  The task returns the response object."
  [& {:keys [] :as request}]
  ;; No use of missionary API, just conforming to the task spec
  (mu/with-context {:request-id (swap! request-counter inc)}
    (let [request (assoc request :async? true)
          ctx (mu/local-context) ;; capture thread-local context
          log (fn
                ([e]     (mu/with-context ctx (mu/log e)))
                ([e k v] (mu/with-context ctx (mu/log e k v))))]
      (log ::http-request-creation :request request)
      (fn [success-fn failure-fn]
        (log ::http-request-execution)
        (let [^Future response-fut
              (http/request
                (assoc request :oncancel
                       (fn []
                         (log ::http-request-cancelled)
                         (failure-fn (ex-info "Request cancelled"
                                              {:request request}))))
                (fn [r]
                  (log ::http-request-completed :response r)
                  (success-fn r))
                (fn [e]
                  (mu/with-context ctx
                    (log ::http-request-failed :exception e))
                  (failure-fn e)))]
          (fn []
            (log ::http-request-cancellation)
            (.cancel response-fut true)))))))

(defn url-status
  "Return a task that retrieves a URL HTTP status with a HEAD request.
  An optional options map will be merged with the request map.
  The task returns a map as per `->result`."
  ([state url] (url-status state url nil))
  ([state url options]
   (m/sp
     (let [r (try
               (let [cm (:connection-manager state)]
                 (-> (merge options {:connection-manager cm
                                     :method :head
                                     :url url
                                     :throw-exceptions false
                                     :cookie-policy :none
                                     :save-request? true})
                     http-request
                     m/?
                     ->result))
               (catch Throwable t
                 (->result url :failure (.toString t))))]
       ;;(mu/log ::url-status :result r)
       r))))

;; ---------------------------------------------------------
;; URL manipulation

(defn ->https
  "Return the https version of a URL, which may be identical to input,
  or nil if the scheme isn't http(s)."
  [url]
  (when-let [[_ scheme] (re-matches #"^(https?)://.*" url)]
    (condp = scheme
      "https" url
      "http"  (str/replace-first url #"^http://" "https://"))))

;; ---------------------------------------------------------
;; Wikipedia URL handling

(defn get-translated-page-url
  "Return a task to get the URL of a wikipedia page in the given target
  language using the wikipedia API. The task returns the URL or:
    - :not-found if the API recognized the page as valid, but no
                 translation to the target language was found.
    - :invalid if the provided page points to a non-existent page."
  [state source-language target-language page]
  (m/sp
    (let [cm (:connection-manager state)
          url (str "https://" source-language
                   ".wikipedia.org/w/api.php?action=query&prop=langlinks&titles="
                   page "&lllang=" target-language "&format=json")
          response (->> (merge headers {:connection-manager cm
                                        :method :get
                                        :url url
                                        :throw-exceptions false
                                        :cookie-policy :none})
                        http-request
                        m/?)
          body (json/read-str (:body response) :key-fn keyword)
          pages (get-in body [:query :pages])
          page-id (first (keys pages))
          lang-links (get-in pages [page-id :langlinks])
          tourl (when (seq lang-links)
                  (let [translated-page (-> lang-links first :*)]
                    (str "https://" target-language ".wikipedia.org/wiki/"
                         translated-page)))]
      (mu/log ::translation :page page :body body :tourl tourl)
      (cond
        (str/starts-with? (name page-id) "-") :invalid
        (nil? tourl) :not-found
        :else tourl))))

;; Here is a curl command for the langlinks query followed by its EDN body
;; curl -H "User-Agent: ChkODF/0.1 (https://github.com/fmjrey/chkodf)" "https://en.wikipedia.org/w/api.php?action=query&titles=Lorem_ipsum&lllang=fr&prop=langlinks&format=json" | jq .
(comment
  {:batchcomplete "",
   :query
   {:normalized [{:from "Lorem_ipsum", :to "Lorem ipsum"}],
    :pages
    {:190246
     {:pageid 190246,
      :ns 0,
      :title "Lorem ipsum",
      :langlinks [{:lang "fr", :* "Lorem ipsum"}]}}}})
;; Here is a request for an non-existent page
;; curl -H "User-Agent: ChkODF/0.1 (https://github.com/fmjrey/chkodf)" "https://en.wikipedia.org/w/api.php?action=query&titles=Lorem_Hipsum&lllang=fr&prop=langlinks&format=json" | jq .
(comment
  {:batchcomplete "",
   :query
   {:normalized [{:from "Lorem_Hipsum", :to "Lorem Hipsum"}],
    :pages {:-1 {:ns 0, :title "Lorem Hipsum", :missing ""}}}})

(defn check-wikipedia-url
  "Return a task to check if `url` points to a wikipedia page in the same
  language as the document language, and suggesting the corresponding
  page in the document language if it exists.
  The task returns a vector in the form `[state url result]` where:
    - `state`: the state
    - `url`: input URL, or URL of the corresponding wikipedia page that
             matches the document language
    - `result`: nil if the URL fails to register as a wikipedia page,
                or a result map for the input url as per ->result."
  [state url]
  (m/sp
    (let [target-lang (state :language)
          [_ source-lang page fragment]
          (re-matches #"^https?://(\w*)\.wikipedia\.org/wiki/([^#]*)#?([^#]*)$"
                      url)]
      (cond
        (nil? page) ;; not a wikipedia page
        [state url nil]
        (= source-lang target-lang) ;; page lang matches doc lang
        (let [result (m/? (url-status state url headers))]
          [state url (add-msg result "URL language matches document language")])
        :else ;; search for translation into doc lang
        (let [tourl (m/? (get-translated-page-url state source-lang
                                                  target-lang page))
              result (case tourl
                       :not-found
                       (->result url :success
                                 (str "No translation to " target-lang))
                       :invalid
                       (->result url :failure "Not a valid wikipedia page")
                       ;; a translation was found
                       (cond-> (->result url :replace {:location tourl})
                         true
                         (add-msg (str "Found translation: " tourl))
                         (not (str/blank? fragment))
                         (add-warning (str "Cannot carry fragment #" fragment
                                           "over to " target-lang))))]
          [state (if (not (keyword? tourl)) tourl url) result])))))

;; ---------------------------------------------------------
;; URL handling

(defn process-http-based-on-https
  "Returns a task to process one http url given the result of processing its
  https equivalent. An optional result map for the http url can be passed.
  It will be used in case the https url is not validated, and will be augmented
  with the given https-url-result (using `add-previous`).
  The task returns the updated state.
  Use `url-result` to retrieve the result of processing the url."
  ([state url https-url https-url-result]
   (process-http-based-on-https state url https-url https-url-result nil))
  ([state url https-url https-url-result result-when-https-failed]
   (m/sp
     (mu/log ::http-https :url url :https-url-result https-url-result
             :http-url-result-when-https-failed result-when-https-failed)
     (let [https-url-result (or https-url-result
                                (url-result state https-url)
                                (m/? (url-status state url)))
           http-url-result
           (or (url-result state url)
               (-> (case (:result https-url-result)
                     (:redirect :replace)
                     (->result url :replace
                               :location (:location https-url-result))
                     :success
                     (->result url :replace :location https-url)
                     ;; https version isn't validated, only http matters now
                     (or result-when-https-failed
                         (-> (m/? (url-status state url))
                             (add-msg "HTTPS wasn't resolved satisfactorily"))))
                   (add-previous https-url-result)))]
       (assign state url http-url-result)))))

(defn process-url
  "Returns a task to process one url. The task returns the updated state.
  Use `url-result` to retrieve the result of processing the url."
  [state url]
  (m/sp
    (let [state (add-input-url state url)]
      (if (url-in? state url)
        ;; 1/ url was already encountered before, just return the state
        state
        ;; 2/ url is new, add it to state and check http/https
        (let [state (add-url state url)
              https-url (->https url)
              http-url? (and https-url (not= url https-url))
              https-url? (and https-url (= url https-url))
              https-url-result (and http-url? ;; cant' wait on oneself
                                    (url-in? state https-url)
                                    (await-url-result state https-url))]
          (if https-url
            ;; 3/ url is http or https
            (if (and http-url? https-url-result)
              ;; 4/ url is http and new but https version was seen before
              (m/? (process-http-based-on-https state url https-url
                                                https-url-result))
              ;; url can't be https and seen before, this is caught by 1/
              ;; 5/ new http or https url, if http then https is also new
              (let [[state wp-url wp-url-result]
                    (m/? (check-wikipedia-url state https-url))
                    wp-page? wp-url-result
                    translation-found? (and wp-url-result
                                            (not= https-url wp-url))]
                (if wp-page?
                  ;; url looks like a wikipedia page
                  (cond-> state
                    (and https-url? (not translation-found?))
                    (assign url wp-url-result)
                    (and https-url? translation-found?)
                    (assign https-url
                            (-> (->result https-url :replace :location wp-url)
                                (add-msg (str "Translation for " https-url))
                                (add-previous wp-url-result)))
                    translation-found?
                    (add-url wp-url
                             (-> (->result wp-url :success
                                           (str "Translation for: " url))
                                 (add-previous wp-url-result)))
                    (and http-url? translation-found?)
                    (add-url https-url
                             (-> (->result https-url :replace :location wp-url)
                                 (add-msg (str "Translation for " https-url))
                                 (add-previous wp-url-result)))
                    (and http-url? (not translation-found?))
                    (-> (process-http-based-on-https
                         url https-url https-url-result
                         (->result url :failure "Not a valid wikipedia page."))
                        m/?))
                  ;; url is http or https but not a wikipedia page
                  (let [https-url-result (or https-url-result
                                             (and http-url? ;; cant' wait on oneself
                                                  (url-in? state https-url)
                                                  (await-url-result
                                                   state https-url))
                                             (m/? (url-status state https-url)))]
                    (if https-url?
                      (assign state url https-url-result)
                      (m/? (process-http-based-on-https state url https-url
                                                        https-url-result)))))))
            ;; url is not http or https, just ignore
            (assign state url (->result url :ignored "Not an HTTP(S) URL"))))))))

(defn process-urls
  "Return a flow of tasks processing each URL of a given sequence.
  Each tasks is built with `process-url` and return an updated state.
  An optional parameter defines the number of concurrent URL processing,
  which by default are processed sequentially."
  ([state-or-language urls]
   (let [state (if (string? state-or-language)
                 {:language state-or-language}
                 state-or-language)]
     (mu/log ::process-urls :state state :urls urls)
     (m/ap
       (loop [state (init-state state-or-language)
              urls urls]
         (if (seq urls)
           (let [state (m/? (process-url state (first urls)))]
             (m/amb state (recur state (next urls))))
           (m/amb))))))
  ;; TODO: make state an atom and use m/join instead of the loop above
  ;; until then the below will still process URLs sequentially
  ([par state-or-language urls]
   (m/ap (m/?> par (process-urls state-or-language urls)))))

;; ---------------------------------------------------------
;; Parse ODF document and generate a sequence of URL strings

(defn node->href
  [^TextAElement node]
  (-> node .getXlinkHrefAttribute))

(defn node-list-seq [^org.w3c.dom.NodeList node-list]
  (map (fn [index] (.item node-list index))
       (range (.getLength node-list))))

(defn odf->hrefs [^OdfDocument doc]
  (->> doc
       .getContentRoot
       (tree-seq (fn [node] (.hasChildNodes node))
                 (fn [node] (-> node
                                .getChildNodes
                                node-list-seq)))
       (filter #(instance? TextAElement %))
       (map node->href)))

(defn init-app-state
  ([filename]
   (let [doc (OdfDocument/loadDocument filename)
         locale (.getLocale doc OdfDocument$UnicodeGroup/WESTERN)
         language (.getLanguage locale)]
     {:input-name filename
      :hrefs (odf->hrefs doc)
      :language language}))
  ([language hrefs]
   {:input-name (str (count hrefs) " hrefs")
    :hrefs hrefs
    :language language}))

(defn process-app-state
  "Parse the given app state and return an updated state resulting from
  processing the URLs it contains."
  [{:keys [input-name language] :as state}]
  (println "Processing" input-name)
  (println "Language" language)
  (let [final-state (try
                      (some->> state
                               :hrefs
                               (process-urls state)
                               ;; the following does not process URLs in //
                               ;; See TODO comment in process-urls
                               ;;(process-urls 10 state)
                               (m/reduce {} state)
                               m/?)
                      (finally (tear-down state)))]
    (mu/log ::final-state :state final-state)
    (some-> final-state
            check-results
            print-summary)))

;; ---------------------------------------------------------
;; Application setup

(defn usage [options-summary]
  (str "Usage: " app-cli " [options] <input file> [<output-file>]\n"
       "Options:\n"
       options-summary))

(def cli-options
  [["-h" "--help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n"
       (str/join "\n" errors)))

(defn exit
  ([status msg]
   (println msg)
   (System/exit status))
  ([status msg options-summary]
   (println msg)
   (usage options-summary)
   (System/exit status)))

(defn greet
  "Return a greeting message, options map may contain :username and :println?
   to respectively include the user name and call println."
  ([] (greet {:println? true}))
  ([options]
   (let [{:keys [username println?]} options
         msg (cond-> ""
               true     (str app-name " - version " version-string)
               username (str ", run by " username)
               true     (str "\nUser-Agent: " user-agent))]
     (when println? (println msg))
     msg)))

(defn run
  "Main processing logic"
  ([inputfile] (run inputfile nil))
  ([inputfile outputfile]
   (let [state (-> inputfile
                   init-app-state
                   process-app-state)]
     (when outputfile
       (println "Saving to" outputfile)
       (.save (:doc state) (io/file outputfile))))))

(defn -main
  "Entry point into the application via clojure.main -M"
  [& args]
  (try
    (let [{:keys [options arguments errors summary]}
          (parse-opts args cli-options)]
      (mu/set-global-context! app-info)
      (mu/log ::application-startup :arguments args)
      (greet)
      (cond
        (:help options) (exit 0 (usage summary))
        errors (exit 1 (error-msg errors))
        (not (#{1 2} (count arguments)))
        (exit 1 "Please provide exactly one or two filenames.")
        :else (let [[inputfile outputfile] arguments]
                (run inputfile outputfile)))
      (exit 0 (str app-name " completed successfully.")))
    (catch Throwable t
      (mu/log ::failure :cause (str t) :exception t)
      (exit 1 (str app-name " completed.")))))

;; ---------------------------------------------------------


;; ---------------------------------------------------------
;; Rich Comment
(comment

  (-main "test1.odt")

  (def test1-urls
    (-> (init-app-state "test1.odt")
        :hrefs))

  (def test1-urls
    ["https://en.wiktionary.org/wiki/aliquam"
     "http://en.wikipedia.org/wiki/Lorem_ipsum"
     "https://en.wikipedia.org/wiki/Lorem_ipsum"
     "https://en.wikipedia.org/wiki/JDK"
     "https://en.wikipedia.org/wik/Lorem_ipsum"
     "https://en.wikipedia.org/wiki/Voluptuary"
     "http://en.wikipedia.org/wiki/Labore"
     "https://owasp.org/badurl"
     "https://fr.wikipedia.org/wiki/NotAPage"
     "http://dymmy.web.site/url"
     "https://en.wikipedia.org/wiki/Kōta_Takai"
     "mailto:dymmy@email.dot.tld"
     "https://en.wikipedia.org/wiki/Kōta_Takai"
     "https://en.wikipedia.org/wiki/Dolores_O'Riordan"
     "https://fr.wikipedia.org/wiki/Dolores_O'Riordan"
     "http://owasp.org/badurl"
     "http://kyoto-keihoku.jp/en/howto/see/1714/"
     "https://github.com/takimata"
     "https://fr.wiktionary.org/wiki/dolor"
     "https://www.wordhippo.com/what-is/the-meaning-of/latin-word-03528c2f93ecdfb462a61d01138006b711511285.html"])

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

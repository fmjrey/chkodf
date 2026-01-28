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
           [org.w3c.dom Node NodeList]
           [java.net URI URL])
  (:require [app :refer [app-info app-name app-home app-cli
                         major-version minor-version version-string]]
            [com.brunobonacci.mulog :as mu]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io])
  (:gen-class))

;; ---------------------------------------------------------
;; User Agent header needed by Wikipedia otherwise it blocks

(def user-agent (str app-name "/" major-version "." minor-version
                     " (" app-home ")"))
(def headers {:headers {"User-Agent" user-agent}})

;; ---------------------------------------------------------
;; Wikipedia URL handling

(defn get-translated-page-url [source-language target-language page]
  (let [url (str "https://" source-language ".wikipedia.org/w/api.php?action=query&prop=langlinks&titles=" page "&lllang=" target-language "&format=json")
        response (client/get url headers)
        body (json/read-str (:body response) :key-fn keyword)
        pages (get-in body [:query :pages])
        page-id (first (keys pages))
        page-data (get pages page-id)
        lang-links (get page-data :langlinks)]
    (when (seq lang-links)
      (let [translated-page (-> lang-links first :*)]
        (str "https://" target-language ".wikipedia.org/wiki/" translated-page)))))

(defn check-wikipedia-language [state url]
  (if-let [[_ source-language page fragment] (re-matches #"^https?://(\w*)\.wikipedia\.org/wiki/([^#]*)#?([^#]*)$" url)]
    (if (= source-language (state :language))
      [state url]
      (if-let [tourl (get-translated-page-url source-language (state :language) page)]
        (do
          (println  "    Found translation: "    tourl)
          (if (str/blank? fragment)
            [state tourl]
            (do
              (println "    but can't translate fragment #" fragment)
              [state url])))
        (do
          (println "    No translation found")
          [state url])))
    [state url]))

;; ---------------------------------------------------------
;; General URL handling

(defn valid-page? [url]
  (try
    (let [response (client/head url (merge headers {:throw-exceptions false
                                               :cookie-policy :none}))
          code (:status response)]
      (println (str code " " url))
      (= 200 (:status response)))
    (catch Exception e
      false)))

(defn url-status [url]
  (try
    (let [response (client/head url (merge headers {:throw-exceptions false
                                               :cookie-policy :standard}))
          code (:status response)]
      ;(println (str "    -> " code))
      code)
    (catch Exception e
      (println "    ERROR: fetching throws " e))))

;; ---------------------------------------------------------
;; ODF document handling
(defn process-hyperlink [state ^TextAElement n]
  (let [href (.getXlinkHrefAttribute n)]
    (println "--- Checking" href)
    (if (re-matches #"^https?://.*" href)
      (let [href-code (url-status href)
            fixed (str/replace-first href #"^http://" "https://")
            [state fixed] (check-wikipedia-language state fixed)]
        (when-not (= 200 href-code)
          (println "    WARNING: " href-code " " href))
        (if (= href fixed)
          state
          (let [fixed-code (url-status fixed)]
            (if (= 200 fixed-code)
              (do
                (println "    Updating to" fixed)
                (.setXlinkHrefAttribute n fixed)
                (update-in state [:modified-elements] conj n))
              (do
                (println "    WARNING: " fixed-code " " fixed)
                state)))))
      state)))

(defn node-list-seq [^org.w3c.dom.NodeList node-list]
  (map (fn [index] (.item node-list index))
       (range (.getLength node-list))))

(defn process-odf [state]
  (->> state
       :doc
       .getContentRoot
       (tree-seq (fn [node] (.hasChildNodes node))
                 (fn [node] (-> node .getChildNodes node-list-seq)))
       (filter (fn [node] (instance? TextAElement node)))
       (reduce process-hyperlink state)))


;; ---------------------------------------------------------
;; Application

(defn init-state [filename]
  (let [doc (OdfDocument/loadDocument filename)
        locale (.getLocale doc OdfDocument$UnicodeGroup/WESTERN)]
    {:filename filename
     :doc ^OdfDocument doc
     :locale ^Locale locale
     :language (.getLanguage locale)
     :modified-elements #{}}))

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
  "Return a greeting message"
  ([] (greet nil))
  ([options]
   (let [{:keys [username]} options]
     (cond-> ""
       true     (str app-name " - version " version-string)
       username (str ", run by " username)
       true     (str "\nUser-Agent: " user-agent)
       true     println))))

(defn -main
  "Entry point into the application via clojure.main -M"
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (mu/set-global-context! app-info)
    (mu/log ::application-startup :arguments args)
    (greet nil)
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors))
      (not (#{1 2} (count arguments))) (exit 1 "Please provide exactly one or two filenames.")
      :else (let [[inputfile outputfile] arguments]
              (println "Processing" inputfile)
              (let [state (init-state inputfile)]
                (println "Language" (state :language))
                (let [state (process-odf state)]
                  (when (-> state :modified-elements seq)
                    (println (-> state :modified-elements count) "changes")
                    (when outputfile
                      (println "Saving to" outputfile)
                      (.save (:doc state) (io/file outputfile))))))))
    (println (str app-name "completed."))))

;; ---------------------------------------------------------


;; ---------------------------------------------------------
;; Rich Comment
(comment
  (->
    (init-state "test.odt")
    (process-odf)
    )

  (-main)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------

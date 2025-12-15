(ns chkodf.core
  (:import [java.util Locale]
           [org.odftoolkit.odfdom.doc OdfDocument$UnicodeGroup OdfDocument]
           [org.odftoolkit.odfdom.dom.element.text TextAElement]
           [org.w3c.dom Node NodeList]
           [java.net URI URL])
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io])
  (:gen-class))

(def ua {:headers {"User-Agent" "ChkODF/0.1 (https://github.com/fmjrey/chkodf)"}})

(defn get-translated-page-url [source-language target-language page]
  (let [url (str "https://" source-language ".wikipedia.org/w/api.php?action=query&prop=langlinks&titles=" page "&lllang=" target-language "&format=json")
        response (client/get url ua)
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

(defn valid-page? [url]
  (try
    (let [response (client/head url (merge ua {:throw-exceptions false
                                               :cookie-policy :none}))
          code (:status response)]
      (println (str code " " url))
      (= 200 (:status response)))
    (catch Exception e
      false)))

(defn url-status [url]
  (try
    (let [response (client/head url (merge ua {:throw-exceptions false
                                               :cookie-policy :standard}))
          code (:status response)]
      ;(println (str "    -> " code))
      code)
    (catch Exception e
      (println "    ERROR: fetching throws " e))))

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

(defn process-node-list [state ^NodeList nl]
  (let [l (.getLength  nl)]
    (if (zero? l)
      state
      (loop [i 0
             state state]
        (if (< i l)
          (let [n (.item nl i)]
            (if (instance? TextAElement n)
              (recur (inc i) (process-hyperlink state n))
              (recur (inc i)
                     (->> n .getChildNodes (process-node-list state)))))
          state)))))

(defn process-odt [state]
  (->> state :doc .getContentRoot .getChildNodes (process-node-list state)))

(defn init-state [filename]
  (let [doc (OdfDocument/loadDocument filename)
        locale (.getLocale doc OdfDocument$UnicodeGroup/WESTERN)]
    {:filename filename
     :doc ^OdfDocument doc
     :locale ^Locale locale
     :language (.getLanguage locale)
     :modified-elements #{}}))

(defn usage [options-summary]
  (str "Usage: chkodf [options] <input file> [<output-file>]\n"
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

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors))
      (not (#{1 2} (count arguments))) (exit 1 "Please provide exactly one or two filenames.")
      :else (let [[inputfile outputfile] arguments]
              (println "Processing" inputfile)
              (let [state (init-state inputfile)]
                (println "Language" (state :language))
                (let [state (process-odt state)]
                  (when (-> state :modified-elements seq)
                    (println (-> state :modified-elements count) "changes")
                    (when outputfile
                      (println "Saving to" outputfile)
                      (.save (:doc state) (io/file outputfile)))))))))
  (println "Program completed."))

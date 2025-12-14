(defproject chkodf "0.1.0-SNAPSHOT"
  :description "Check hyperlinks status and wikipedia translations in ODF files."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.odftoolkit/odfdom-java "0.12.0"]
                 [clj-http "3.13.0"]
                 [org.clojure/data.json "2.5.1"]
                 [clojurewerkz/urly "1.0.0"]
                 [org.clojure/tools.cli "1.1.230"]]
  :main chkodf.core
  :aot :all)
